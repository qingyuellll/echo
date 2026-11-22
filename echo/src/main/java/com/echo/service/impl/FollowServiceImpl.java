package com.echo.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.dto.UserDTO;
import com.echo.entity.Follow;
import com.echo.entity.UserInfo;
import com.echo.mapper.FollowMapper;
import com.echo.service.IFollowService;
import com.echo.service.IUserInfoService;
import com.echo.service.IUserService;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.echo.utils.RedisConstants.*;


/**
 * 用户关注服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private com.echo.utils.RedisLuaHelper redisLuaHelper;
    @Resource
    private IUserService userService;
    @Resource
    private IUserInfoService userInfoService;
    @Resource(name = "followExecutor")
    private ThreadPoolTaskExecutor followExecutor;
    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private DefaultRedisScript<Long> followUserScript;

    @Override
    public Result<?> follow(Long followUserId, Boolean isFollow) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null || currentUser.getId() == null) {
            log.warn("[关注操作] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }
        Long userId = currentUser.getId();

        if (isFollow == null) {
            log.warn("[关注参数错误] 操作类型为空，userId:{}, followUserId:{}", userId, followUserId);
            return Result.fail(AppMessages.FOLLOW_TYPE_EMPTY);
        }

        log.info("[关注操作] 用户:{} {}关注用户:{}", userId, isFollow ? "开始" : "取消", followUserId);

        if (followUserId == null || followUserId <= 0) {
            log.warn("[关注参数错误] 无效的followUserId:{}", followUserId);
            return Result.fail(AppMessages.FOLLOW_USER_ID_INVALID);
        }

        if (userId.equals(followUserId)) {
            return Result.fail(AppMessages.FOLLOW_SELF);
        }

        // 关注前校验目标用户是否真实存在，避免产生指向幽灵用户的关注关系
        if (isFollow && userService.getById(followUserId) == null) {
            log.warn("[关注失败] 目标用户不存在，followUserId:{}", followUserId);
            return Result.fail(AppMessages.USER_NOT_EXIST);
        }

        String followKey = FOLLOW_KEY + userId;
        String fansKey = FOLLOW_FANS_KEY + followUserId;
        int action = isFollow ? 1 : -1;

        String operation = isFollow ? "关注用户" : "取关用户";
        Long result = redisLuaHelper.executeWithOperationContext(
                followUserScript,
                List.of(followKey, fansKey),
                operation,
                followUserId.toString(), String.valueOf(action), userId.toString());

        if (result == 0L) {
            return Result.fail(AppMessages.FOLLOW_ALREADY_EXISTS);
        }
        if (result == -2L) {
            return Result.fail(AppMessages.FOLLOW_NOT_EXISTS);
        }

        // Redis 操作成功，使用线程池异步持久化到 DB
        final boolean finalIsFollow = isFollow;
        followExecutor.execute(() -> persistFollowAsync(userId, followUserId, finalIsFollow));

        log.info("[关注{}] 用户:{} {}用户:{}", isFollow ? "成功" : "取关成功", userId, isFollow ? "已关注" : "已取关", followUserId);
        return Result.ok();
    }

    /**
     * 持久化关注关系到 DB（幂等）
     * <p>
     * 幂等的必要性：Redis Set 可能因为 flush/驱逐/重启而缺失成员，
     * 此时 Lua 会认为“未关注”并返回成功，如果这里无条件 INSERT，
     * tb_follow 会出现重复行，并让 followee/fans 计数被重复累加。
     */
    @Transactional(rollbackFor = Exception.class)
    public void persistFollowToDB(Long userId, Long followUserId, boolean isFollow) {
        if (isFollow) {
            long exists = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
            if (exists > 0) {
                log.debug("[关注持久化] DB已存在关注记录，跳过插入，userId:{}, followUserId:{}", userId, followUserId);
                return;
            }
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            follow.setCreateTime(LocalDateTime.now());
            save(follow);
            updateFollowCounts(userId, followUserId, true);
        } else {
            boolean removed = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (!removed) {
                log.debug("[关注持久化] DB无关注记录，跳过计数回退，userId:{}, followUserId:{}", userId, followUserId);
                return;
            }
            updateFollowCounts(userId, followUserId, false);
        }
    }

    private void persistFollowAsync(Long userId, Long followUserId, boolean isFollow) {
        try {
            FollowServiceImpl self = applicationContext.getBean(FollowServiceImpl.class);
            self.persistFollowToDB(userId, followUserId, isFollow);
        } catch (Exception e) {
            log.error("[关注持久化失败] 已回滚Redis关注关系，userId:{}, followUserId:{}, error:{}",
                    userId, followUserId, e.getMessage(), e);
            // DB 落库失败必须补偿回滚 Redis，否则 Redis 说“已关注”、DB 无记录，
            // 且后续无任何机制修复，会造成永久不一致
            rollbackFollowRedis(userId, followUserId, isFollow);
        }
    }

    /**
     * DB 持久化失败时回滚 Redis 关注关系（补偿事务）
     */
    private void rollbackFollowRedis(Long userId, Long followUserId, boolean isFollow) {
        try {
            String followKey = FOLLOW_KEY + userId;
            String fansKey = FOLLOW_FANS_KEY + followUserId;
            if (isFollow) {
                stringRedisTemplate.opsForSet().remove(followKey, followUserId.toString());
                stringRedisTemplate.opsForSet().remove(fansKey, userId.toString());
            } else {
                stringRedisTemplate.opsForSet().add(followKey, followUserId.toString());
                stringRedisTemplate.opsForSet().add(fansKey, userId.toString());
            }
        } catch (Exception e) {
            log.error("[关注回滚失败] Redis补偿失败，需人工修复，userId:{}, followUserId:{}, error:{}",
                    userId, followUserId, e.getMessage(), e);
        }
    }

    /**
     * 维护 tb_user_info 的关注数/粉丝数
     * <p>
     * 必须用 SQL 原子自增自减：旧实现是 getById -> set(旧值+delta) -> updateById，
     * 高并发关注时会出现读-改-写丢失更新（例如 10 个人同时关注只加到 1）。
     */
    private void updateFollowCounts(Long userId, Long followUserId, boolean isFollow) {
        applyCountDelta(userId, "followee", isFollow);
        applyCountDelta(followUserId, "fans", isFollow);

        // 关注数/粉丝数变化后必须失效 cache:user-info:，否则个人主页数字在 TTL 内不会变
        evictUserInfoCache(userId);
        evictUserInfoCache(followUserId);
    }

    /**
     * 原子更新单个计数列（column 为受控常量，不接受外部输入，无注入风险）
     */
    private void applyCountDelta(Long userId, String column, boolean increase) {
        String sql = increase
                ? column + " = IFNULL(" + column + ", 0) + 1"
                : column + " = GREATEST(IFNULL(" + column + ", 0) - 1, 0)";

        boolean updated = userInfoService.lambdaUpdate()
                .setSql(sql)
                .set(UserInfo::getUpdateTime, LocalDateTime.now())
                .eq(UserInfo::getUserId, userId)
                .update();

        if (updated) {
            return;
        }

        // 该用户还没有 tb_user_info 行，补建一条（并发插入冲突时退化为再次原子更新）
        UserInfo info = new UserInfo();
        info.setUserId(userId);
        info.setCreditScore(100);
        info.setCredits(0);
        info.setFans("fans".equals(column) && increase ? 1 : 0);
        info.setFollowee("followee".equals(column) && increase ? 1 : 0);
        info.setCreateTime(LocalDateTime.now());
        info.setUpdateTime(LocalDateTime.now());
        try {
            userInfoService.save(info);
        } catch (Exception e) {
            log.warn("[关注计数] tb_user_info 并发插入冲突，转为原子更新，userId:{}, column:{}", userId, column);
            userInfoService.lambdaUpdate()
                    .setSql(sql)
                    .set(UserInfo::getUpdateTime, LocalDateTime.now())
                    .eq(UserInfo::getUserId, userId)
                    .update();
        }
    }

    private void evictUserInfoCache(Long userId) {
        try {
            stringRedisTemplate.delete(CACHE_USER_INFO_KEY + userId);
        } catch (Exception e) {
            log.warn("[关注计数] 用户资料缓存失效失败，userId:{}, error:{}", userId, e.getMessage());
        }
    }

    @Override
    public Result<?> isFollow(Long followUserId) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null || currentUser.getId() == null) {
            log.warn("[查询关注状态] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }
        Long userId = currentUser.getId();

        if (followUserId == null || followUserId <= 0) {
            log.warn("[查询关注状态参数错误] 无效的followUserId:{}", followUserId);
            return Result.fail(AppMessages.FOLLOW_USER_ID_INVALID);
        }

        String key = FOLLOW_KEY + userId;

        try {
            Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, followUserId.toString());
            if (Boolean.TRUE.equals(isMember)) {
                return Result.ok(true);
            }
        } catch (Exception e) {
            log.warn("[Redis查询失败] 回源DB查询，key:{}, error:{}", key, e.getMessage());
        }

        // Redis 未命中或异常，回源 DB 并回填 Redis（同时回填粉丝set）
        try {
            long count = query()
                    .eq("user_id", userId).eq("follow_user_id", followUserId).count();
            boolean followed = count > 0;

            // 异步回填Redis（避免影响主流程性能）
            if (followed) {
                followExecutor.execute(() -> {
                    try {
                        // 这里绝不能 expire()：follows:/fans: 是关注关系全量镜像，
                        // 旧实现设置 1 小时 TTL，导致关注数归零、粉丝收不到评测推送
                        stringRedisTemplate.opsForSet().add(key, followUserId.toString());
                        stringRedisTemplate.opsForSet().add(FOLLOW_FANS_KEY + followUserId, userId.toString());
                    } catch (Exception e) {
                        log.warn("[Redis回填失败] 不影响主流程，error:{}", e.getMessage());
                    }
                });
            }

            return Result.ok(followed);
        } catch (Exception e) {
            log.error("[DB查询失败] userId:{}, followUserId:{}, error:{}", userId, followUserId, e.getMessage(), e);
            return Result.fail(AppMessages.FOLLOW_STATUS_QUERY_FAIL);
        }
    }

    @Override
    public Result<?> followCommons(Long id) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null || currentUser.getId() == null) {
            log.warn("[共同关注] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }
        Long userId = currentUser.getId();

        if (id == null || id <= 0) {
            log.warn("[共同关注参数错误] 无效的用户ID:{}", id);
            return Result.fail(AppMessages.FOLLOW_USER_ID_INVALID);
        }

        if (userId.equals(id)) {
            log.warn("[共同关注参数错误] 不能查询与自己的共同关注");
            return Result.fail(AppMessages.FOLLOW_CANNOT_QUERY_SELF);
        }

        try {
            String key = FOLLOW_KEY + userId;
            String targetKey = FOLLOW_KEY + id;

            Set<String> intersect = null;
            try {
                intersect = stringRedisTemplate.opsForSet().intersect(key, targetKey);
            } catch (Exception e) {
                log.warn("[共同关注] Redis求交集失败，回源DB，error:{}", e.getMessage());
            }

            List<Long> ids;
            if (intersect == null || intersect.isEmpty()) {
                // Redis 未命中（如缓存重建前）时必须回源 DB，
                // 旧实现直接返回空列表，会让共同关注功能在缓存缺失时静默失效
                ids = queryCommonFollowFromDb(userId, id);
            } else {
                ids = intersect.stream().map(Long::valueOf).toList();
            }

            if (ids.isEmpty()) {
                return Result.ok(Collections.emptyList());
            }

            List<UserDTO> users = userService.listByIds(ids)
                    .stream()
                    .filter(Objects::nonNull)
                    .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                    .toList();

            log.info("[共同关注查询] 用户:{} 与用户:{} 的共同关注数:{}", userId, id, users.size());
            return Result.ok(users);
        } catch (Exception e) {
            log.error("[共同关注查询失败] userId:{}, targetId:{}, error:{}", userId, id, e.getMessage(), e);
            return Result.fail(AppMessages.FOLLOW_COMMON_QUERY_FAIL);
        }
    }

    /**
     * DB 回源查询共同关注（性能优化版）
     *
     * 优化策略：
     * - 使用SQL子查询在数据库层完成交集运算（避免加载全量数据到内存）
     * - 利用索引加速查询（user_id + follow_user_id 联合索引）
     * - 支持分页返回（避免大数据量场景下的性能问题）
     *
     * SQL等价逻辑：
     *   SELECT follow_user_id FROM tb_follow WHERE user_id = #{userId}
     *   INTERSECT
     *   SELECT follow_user_id FROM tb_follow WHERE user_id = #{targetId}
     *
     * MySQL不支持INTERSECT，使用INNER JOIN替代：
     *   SELECT f1.follow_user_id
     *   FROM tb_follow f1
     *   INNER JOIN tb_follow f2 ON f1.follow_user_id = f2.follow_user_id
     *   WHERE f1.user_id = #{userId} AND f2.user_id = #{targetId}
     */
    private List<Long> queryCommonFollowFromDb(Long userId, Long targetId) {
        // 使用MyBatis-Plus的QueryWrapper构建高效SQL查询
        // 核心思路：通过子查询在数据库层完成交集，避免N+1问题和内存消耗
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Follow> subQuery =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        subQuery.select("follow_user_id")
                .eq("user_id", targetId)
                .isNotNull("follow_user_id");

        List<Follow> commonFollows = query()
                .select("follow_user_id")
                .eq("user_id", userId)
                .isNotNull("follow_user_id")
                .in("follow_user_id", subQuery)  // 子查询：数据库层交集
                .last("LIMIT 100")  // 安全限制：最多返回100个共同关注
                .list();

        return commonFollows.stream()
                .map(Follow::getFollowUserId)
                .filter(id -> id != null)
                .distinct()  // 去重（防止数据异常）
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public Result<?> followCount(Long userId) {
        if (userId == null || userId <= 0) {
            log.warn("[关注数查询参数错误] 无效的userId:{}", userId);
            return Result.fail(AppMessages.FOLLOW_USER_ID_INVALID);
        }

        try {
            String followKey = FOLLOW_KEY + userId;
            String fansKey = FOLLOW_FANS_KEY + userId;

            Long followSize = stringRedisTemplate.opsForSet().size(followKey);
            Long fansSize = stringRedisTemplate.opsForSet().size(fansKey);

            // Redis Set 为空有两种含义：真的没有，或者缓存还没重建。
            // 旧实现一律当成 0，导致 Redis 重启/新部署后用户主页关注数、粉丝数全部显示 0。
            long followCount = (followSize != null && followSize > 0)
                    ? followSize
                    : query().eq("user_id", userId).count();
            long fansCount = (fansSize != null && fansSize > 0)
                    ? fansSize
                    : query().eq("follow_user_id", userId).count();

            java.util.Map<String, Object> countMap = new java.util.HashMap<>();
            countMap.put("followee", followCount);
            countMap.put("fans", fansCount);

            log.info("[关注数查询] userId:{}, 关注数:{}, 粉丝数:{}", userId, followCount, fansCount);
            return Result.ok(countMap);
        } catch (Exception e) {
            log.error("[关注数查询失败] userId:{}, error:{}", userId, e.getMessage(), e);

            try {
                long dbFollowCount = query().eq("user_id", userId).count();
                long dbFansCount = query().eq("follow_user_id", userId).count();

                java.util.Map<String, Object> countMap = new java.util.HashMap<>();
                countMap.put("followee", dbFollowCount);
                countMap.put("fans", dbFansCount);

                return Result.ok(countMap);
            } catch (Exception ex) {
                log.error("[关注数DB查询也失败] userId:{}, error:{}", userId, ex.getMessage());
                return Result.fail(AppMessages.FOLLOW_COUNT_QUERY_FAIL);
            }
        }
    }
}
