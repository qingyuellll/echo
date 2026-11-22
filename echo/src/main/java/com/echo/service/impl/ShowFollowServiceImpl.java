package com.echo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.dto.UserDTO;
import com.echo.entity.Show;
import com.echo.entity.ShowFollow;
import com.echo.mapper.ShowFollowMapper;
import com.echo.service.IShowFollowService;
import com.echo.service.IShowService;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.echo.utils.RedisConstants.*;


/**
 * 演出关注服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class ShowFollowServiceImpl extends ServiceImpl<ShowFollowMapper, ShowFollow> implements IShowFollowService {

    /**
     * 关注列表每页条数
     */
    private static final int FOLLOW_PAGE_SIZE = 10;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private com.echo.utils.RedisLuaHelper redisLuaHelper;
    @Resource
    private IShowService showService;
    @Resource(name = "followExecutor")
    private ThreadPoolTaskExecutor executor;

    @Resource
    private DefaultRedisScript<Long> followShowScript;

    /**
     * 关注演出（含参数校验+线程池异步持久化）
     */
    @Override
    public Result<?> followShow(Long showId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            log.warn("[演出关注] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        if (showId == null || showId <= 0) {
            log.warn("[演出关注] 参数错误，showId:{}", showId);
            return Result.fail(AppMessages.SHOW_ID_INVALID);
        }

        try {
            Show show = showService.getById(showId);
            if (show == null) {
                log.warn("[演出关注] 演出不存在，showId:{}", showId);
                return Result.fail(AppMessages.SHOW_NOT_EXIST);
            }

            String followKey = SHOW_FOLLOW_KEY + user.getId();
            String followersKey = SHOW_FOLLOWERS_KEY + showId;

            Long result = redisLuaHelper.executeWithOperationContext(
                    followShowScript,
                    List.of(followKey, followersKey),
                    "关注演出",
                    showId.toString(), "1", user.getId().toString());

            if (result == 0L) {
                log.warn("[演出关注] 已存在，showId:{}, userId:{}", showId, user.getId());
                return Result.fail(AppMessages.SHOW_FOLLOW_ALREADY_EXISTS);
            }

            // 使用线程池异步持久化到DB（避免直接new Thread导致OOM）
            executor.execute(() -> persistFollowAsync(user.getId(), showId, true));

            log.info("[演出关注] 成功，userId:{} 关注了演出:{}({})", user.getId(), show.getName(), showId);
            return Result.ok();
        } catch (Exception e) {
            log.error("[演出关注] 异常，showId:{}, error:{}", showId, e.getMessage(), e);
            return Result.fail(AppMessages.format(AppMessages.SHOW_FOLLOW_FAIL, e.getMessage()));
        }
    }

    /**
     * 取消关注演出（含参数校验+线程池异步持久化）
     */
    @Override
    public Result<?> unfollowShow(Long showId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            log.warn("[演出取关] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        if (showId == null || showId <= 0) {
            log.warn("[演出取关] 参数错误，showId:{}", showId);
            return Result.fail(AppMessages.SHOW_ID_INVALID);
        }

        try {
            String followKey = SHOW_FOLLOW_KEY + user.getId();
            String followersKey = SHOW_FOLLOWERS_KEY + showId;

            Long result = stringRedisTemplate.execute(
                    followShowScript,
                    List.of(followKey, followersKey),
                    showId.toString(), "-1", user.getId().toString());

            if (result == null) {
                log.error("[演出取关] Redis脚本返回空，showId:{}, userId:{}", showId, user.getId());
                return Result.fail(AppMessages.format(AppMessages.SHOW_UNFOLLOW_FAIL, "缓存异常"));
            }

            if (result == -2L) {
                log.warn("[演出取关] 不存在，showId:{}, userId:{}", showId, user.getId());
                return Result.fail(AppMessages.SHOW_FOLLOW_NOT_EXISTS);
            }

            // 使用线程池异步删除DB记录
            executor.execute(() -> persistFollowAsync(user.getId(), showId, false));

            log.info("[演出取关] 成功，userId:{} 取消关注演出, showId:{}", user.getId(), showId);
            return Result.ok();
        } catch (Exception e) {
            log.error("[演出取关] 异常，showId:{}, error:{}", showId, e.getMessage(), e);
            return Result.fail(AppMessages.format(AppMessages.SHOW_UNFOLLOW_FAIL, e.getMessage()));
        }
    }

    /**
     * 检查是否已关注（含参数校验+缓存回填）
     */
    @Override
    public Result<?> isFollowed(Long showId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            log.warn("[检查关注状态] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        if (showId == null || showId <= 0) {
            log.warn("[检查关注状态] 参数错误，showId:{}", showId);
            return Result.fail(AppMessages.SHOW_ID_INVALID);
        }

        try {
            String key = SHOW_FOLLOW_KEY + user.getId();
            Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, showId.toString());

            if (Boolean.TRUE.equals(isMember)) {
                log.debug("[检查关注状态] 命中缓存，已关注，showId:{}, userId:{}", showId, user.getId());
                return Result.ok(true);
            }

            // Redis 未命中，回源 DB 并回填缓存
            long count = lambdaQuery()
                    .eq(ShowFollow::getUserId, user.getId())
                    .eq(ShowFollow::getShowId, showId)
                    .count();
            boolean followed = count > 0;

            if (followed) {
                try {
                    stringRedisTemplate.opsForSet().add(key, showId.toString());
                    stringRedisTemplate.opsForSet().add(SHOW_FOLLOWERS_KEY + showId, user.getId().toString());
                    log.debug("[检查关注状态] 回填缓存，showId:{}, userId:{}", showId, user.getId());
                } catch (Exception e) {
                    log.warn("[检查关注状态] 回填缓存失败，不影响主流程，error:{}", e.getMessage());
                }
            }

            log.debug("[检查关注状态] 结果：{}，showId:{}, userId:{}", followed ? "已关注" : "未关注", showId, user.getId());
            return Result.ok(followed);
        } catch (Exception e) {
            log.error("[检查关注状态] 异常，showId:{}, error:{}", showId, e.getMessage(), e);
            return Result.fail(AppMessages.FOLLOW_STATUS_QUERY_FAIL);
        }
    }

    /**
     * 查询我的关注列表（分页）
     * <p>
     * 旧实现的问题：
     * 1. 命中 Redis Set 时返回“全部”关注，未命中时才分页，同一接口两套行为，分页完全失效；
     * 2. Redis Set 无序，返回顺序随机，且与 DB 分支的“按关注时间倒序”不一致；
     * 3. 回源时只回填第 1 页的 10 个 showId 并给整个 Set 打 TTL，
     *    过期后 Set 被截断成 10 个成员，导致关注状态判断错误；
     * 4. 逐个 getById 造成 N+1 查询。
     * <p>
     * 现在统一以 DB 分页为准（顺序、总数都正确），Redis Set 仅用于关注状态判断，
     * 并在这里做一次全量回填（不设 TTL）。
     */
    @Override
    public Result<?> queryMyFollowedShows(Integer current) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            log.warn("[查询关注列表] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        if (current == null || current < 1) {
            log.warn("[查询关注列表] 参数错误，current:{}", current);
            current = 1;
        }

        try {
            List<ShowFollow> follows = lambdaQuery()
                    .eq(ShowFollow::getUserId, user.getId())
                    .orderByDesc(ShowFollow::getCreateTime)
                    .orderByDesc(ShowFollow::getId)
                    .list();

            // Redis Set 全量回填（不设 TTL：它是关注关系镜像，被关注状态判断/通知推送依赖）
            backfillFollowSetAsync(user.getId(), follows);

            long total = follows.size();
            int fromIndex = (current - 1) * FOLLOW_PAGE_SIZE;
            if (fromIndex >= total) {
                log.info("[查询关注列表] 页码超出范围，userId:{}, current:{}, total:{}", user.getId(), current, total);
                return Result.ok(Collections.emptyList(), total);
            }
            int toIndex = (int) Math.min(fromIndex + FOLLOW_PAGE_SIZE, total);

            List<Long> pageShowIds = follows.subList(fromIndex, toIndex).stream()
                    .map(ShowFollow::getShowId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();

            List<Show> shows = listShowsInOrder(pageShowIds);

            log.info("[查询关注列表] 成功，userId:{}, current:{}, count:{}, total:{}",
                    user.getId(), current, shows.size(), total);
            return Result.ok(shows, total);
        } catch (Exception e) {
            log.error("[查询关注列表] 异常，userId:{}, error:{}", user.getId(), e.getMessage(), e);
            return Result.fail(AppMessages.SHOW_FOLLOW_LIST_QUERY_FAIL);
        }
    }

    /**
     * 批量查询演出并保持传入的 ID 顺序（替代逐个 getById 的 N+1 查询）
     */
    private List<Show> listShowsInOrder(List<Long> showIds) {
        if (showIds.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Show> showMap = showService.listByIds(showIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Show::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        List<Show> ordered = new ArrayList<>(showIds.size());
        for (Long id : showIds) {
            Show show = showMap.get(id);
            if (show != null) {
                ordered.add(show);
            } else {
                log.warn("[查询关注列表] 关注的演出已不存在，showId:{}", id);
            }
        }
        return ordered;
    }

    /**
     * 异步把 DB 中的关注关系全量回填到 Redis Set（不设 TTL）
     */
    private void backfillFollowSetAsync(Long userId, List<ShowFollow> follows) {
        if (follows.isEmpty()) {
            return;
        }
        String[] showIds = follows.stream()
                .map(ShowFollow::getShowId)
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .distinct()
                .toArray(String[]::new);
        if (showIds.length == 0) {
            return;
        }
        executor.execute(() -> {
            try {
                stringRedisTemplate.opsForSet().add(SHOW_FOLLOW_KEY + userId, showIds);
            } catch (Exception e) {
                log.warn("[查询关注列表] Redis回填失败，不影响主流程，userId:{}, error:{}", userId, e.getMessage());
            }
        });
    }

    /**
     * 持久化关注/取关操作到数据库（幂等）
     * <p>
     * 幂等的必要性：Redis Set 可能因为驱逐/重启而缺失成员，此时 Lua 会判定“未关注”并返回成功，
     * 若这里无条件 INSERT，tb_show_follow 就会出现重复关注记录。
     *
     * @param userId 用户ID
     * @param showId 演出ID
     * @param isFollow true=关注，false=取消关注
     */
    private void persistFollowToDB(Long userId, Long showId, boolean isFollow) {
        if (isFollow) {
            long exists = lambdaQuery()
                    .eq(ShowFollow::getUserId, userId)
                    .eq(ShowFollow::getShowId, showId)
                    .count();
            if (exists > 0) {
                log.debug("[DB持久化] 关注记录已存在，跳过插入，userId:{}, showId:{}", userId, showId);
                return;
            }
            ShowFollow follow = new ShowFollow();
            follow.setUserId(userId);
            follow.setShowId(showId);
            follow.setCreateTime(LocalDateTime.now());
            save(follow);
            log.debug("[DB持久化] 新增关注记录，userId:{}, showId:{}", userId, showId);
        } else {
            lambdaUpdate()
                    .eq(ShowFollow::getUserId, userId)
                    .eq(ShowFollow::getShowId, showId)
                    .remove();
            log.debug("[DB持久化] 删除关注记录，userId:{}, showId:{}", userId, showId);
        }
    }

    private void persistFollowAsync(Long userId, Long showId, boolean isFollow) {
        try {
            persistFollowToDB(userId, showId, isFollow);
        } catch (Exception e) {
            log.error("[关注DB持久化失败] 已回滚Redis关注关系，userId:{}, showId:{}, error:{}",
                    userId, showId, e.getMessage(), e);
            // DB 落库失败必须补偿回滚 Redis，否则缓存说“已关注”而 DB 无记录，永久不一致
            rollbackFollowRedis(userId, showId, isFollow);
        }
    }

    /**
     * DB 持久化失败时回滚 Redis 关注关系（补偿事务）
     */
    private void rollbackFollowRedis(Long userId, Long showId, boolean isFollow) {
        try {
            String followKey = SHOW_FOLLOW_KEY + userId;
            String followersKey = SHOW_FOLLOWERS_KEY + showId;
            if (isFollow) {
                stringRedisTemplate.opsForSet().remove(followKey, showId.toString());
                stringRedisTemplate.opsForSet().remove(followersKey, userId.toString());
            } else {
                stringRedisTemplate.opsForSet().add(followKey, showId.toString());
                stringRedisTemplate.opsForSet().add(followersKey, userId.toString());
            }
        } catch (Exception e) {
            log.error("[关注回滚失败] Redis补偿失败，需人工修复，userId:{}, showId:{}, error:{}",
                    userId, showId, e.getMessage(), e);
        }
    }
}
