package com.echo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.dto.UserDTO;
import com.echo.dto.UserInfoUpdateDTO;
import com.echo.entity.User;
import com.echo.entity.UserInfo;
import com.echo.mapper.UserInfoMapper;
import com.echo.service.IUserInfoService;
import com.echo.service.IUserService;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.echo.utils.RedisConstants.*;


/**
 * 用户信息服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

    /**
     * 个人简介最大长度（与 tb_user_info.introduce 字段长度保持一致）
     */
    private static final int INTRODUCE_MAX_LENGTH = 128;

    /**
     * 城市名称最大长度
     */
    private static final int CITY_MAX_LENGTH = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Lazy  // 使用延迟加载打破循环依赖
    @Resource
    private IUserService userService;

    @Override
    public Result<?> getUserInfoById(Long userId) {
        log.debug("[查询用户详情] 查询用户资料，userId:{}", userId);

        if (userId == null || userId <= 0) {
            log.warn("[查询用户详情] 参数非法，userId:{}", userId);
            return Result.fail(AppMessages.PARAM_ERROR);
        }

        String cacheKey = CACHE_USER_INFO_KEY + userId;

        // Redis 异常不能让整个接口 500，降级直连 DB
        try {
            String json = stringRedisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                if (json.isEmpty()) {
                    return Result.ok();
                }
                UserInfo cached = cn.hutool.json.JSONUtil.toBean(json, UserInfo.class);
                return Result.ok(cached);
            }
        } catch (Exception e) {
            log.warn("[查询用户详情] Redis 读取失败，降级查库，userId:{}, error:{}", userId, e.getMessage());
        }

        UserInfo info = getById(userId);
        if (info == null) {
            try {
                stringRedisTemplate.opsForValue().set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[查询用户详情] 空值缓存写入失败，userId:{}, error:{}", userId, e.getMessage());
            }
            return Result.ok();
        }

        info.setCreateTime(null);
        info.setUpdateTime(null);

        try {
            long ttl = randomExpireTime(CACHE_USER_INFO_TTL, CACHE_USER_INFO_RANDOM_TTL);
            stringRedisTemplate.opsForValue().set(cacheKey, cn.hutool.json.JSONUtil.toJsonStr(info), ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[查询用户详情] 缓存写入失败，userId:{}, error:{}", userId, e.getMessage());
        }

        log.info("[查询成功] 用户详情获取完成，userId:{}, city:{}", userId, info.getCity());
        return Result.ok(info);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> updateUserInfo(UserInfoUpdateDTO updateDTO) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        if (updateDTO == null) {
            return Result.fail(AppMessages.PARAM_ERROR);
        }

        Long userId = user.getId();

        // ==================== 0. 审计日志记录 (v4.0) ====================
        String sourceChannel = updateDTO.getSourceChannel() != null ? updateDTO.getSourceChannel() : "unknown";
        String remark = updateDTO.getRemark() != null ? updateDTO.getRemark() : "";
        log.info("[更新资料] [审计] userId:{}, sourceChannel:{}, remark:{}",
                userId, sourceChannel, remark);

        // ==================== 1. 参数校验（防止超长字符串导致 SQL 异常/静默截断） ====================
        if (updateDTO.getIntroduce() != null && updateDTO.getIntroduce().length() > INTRODUCE_MAX_LENGTH) {
            log.warn("[更新资料] 个人简介过长({}字符)，userId:{}", updateDTO.getIntroduce().length(), userId);
            return Result.fail(AppMessages.PARAM_ERROR);
        }
        if (updateDTO.getCity() != null && updateDTO.getCity().length() > CITY_MAX_LENGTH) {
            log.warn("[更新资料] 城市名称过长({}字符)，userId:{}", updateDTO.getCity().length(), userId);
            return Result.fail(AppMessages.PARAM_ERROR);
        }
        if (updateDTO.getBirthday() != null && updateDTO.getBirthday().isAfter(LocalDate.now())) {
            log.warn("[更新资料] 生日不能是未来日期，userId:{}, birthday:{}", userId, updateDTO.getBirthday());
            return Result.fail(AppMessages.PARAM_ERROR);
        }

        // ==================== 2. 更新用户头像（存储在 tb_user 表） ====================
        boolean iconChanged = false;
        if (updateDTO.getIcon() != null && !updateDTO.getIcon().isEmpty()) {
            User userEntity = userService.getById(userId);
            if (userEntity != null) {
                // 只更新 icon 字段，避免把读取时的旧 nickName/points 等一并写回造成丢失更新
                User iconUpdate = new User();
                iconUpdate.setId(userId);
                iconUpdate.setIcon(updateDTO.getIcon());
                userService.updateById(iconUpdate);
                iconChanged = true;
                log.info("[更新资料] 用户{}更新头像，icon:{}", userId, updateDTO.getIcon());

                // 更新Redis缓存中的用户信息（让当前会话立即生效）
                String token = UserHolder.getToken();
                if (token != null) {
                    String redisKey = com.echo.utils.RedisConstants.LOGIN_USER_KEY + token;
                    stringRedisTemplate.opsForHash().put(redisKey, "icon", updateDTO.getIcon());
                    log.info("[更新资料] 已同步更新Redis缓存，key:{}, icon:{}", redisKey, updateDTO.getIcon());
                }
            }
        }

        // ==================== 3. 更新用户资料（tb_user_info） ====================
        // 关键：只写入本次真正变更的字段。
        // 旧实现是 getById() 读出整个实体 -> 改字段 -> saveOrUpdate() 写回，
        // 会把读取瞬间的 credits / creditScore / fans / followee 一起写回，
        // 与积分服务、风控扣分、关注计数并发时会发生丢失更新（积分/信誉分被改回旧值）。
        UserInfo existing = getById(userId);

        UserInfo toUpdate = new UserInfo();
        toUpdate.setUserId(userId);
        if (updateDTO.getCity() != null) {
            toUpdate.setCity(updateDTO.getCity());
        }
        if (updateDTO.getIntroduce() != null) {
            toUpdate.setIntroduce(updateDTO.getIntroduce());
        }
        if (updateDTO.getGender() != null) {
            toUpdate.setGender(updateDTO.getGender());
        }
        if (updateDTO.getBirthday() != null) {
            toUpdate.setBirthday(updateDTO.getBirthday());
        }
        toUpdate.setUpdateTime(LocalDateTime.now());

        if (existing == null) {
            // 首次创建，补齐默认值
            toUpdate.setCreditScore(100);
            toUpdate.setCredits(0);
            toUpdate.setFans(0);
            toUpdate.setFollowee(0);
            toUpdate.setCreateTime(LocalDateTime.now());
            try {
                save(toUpdate);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发场景：关注计数/积分服务可能已经插入了该行，降级为更新
                log.warn("[更新资料] tb_user_info 并发插入冲突，转为更新，userId:{}", userId);
                toUpdate.setCreditScore(null);
                toUpdate.setCredits(null);
                toUpdate.setFans(null);
                toUpdate.setFollowee(null);
                toUpdate.setCreateTime(null);
                updateById(toUpdate);
            }
        } else {
            updateById(toUpdate);
        }

        // ==================== 4. 失效缓存 ====================
        // cache:user-info: 自不必说；cache:user: 也必须删，
        // 否则 queryUserById 会在 TTL(20~40分钟)内继续返回旧头像/旧昵称（脏读）
        // 先删一次，再在事务提交后补删一次（延时双删），
        // 避免“事务未提交时删缓存 -> 其他线程读到旧数据又回填缓存”导致的长期脏读
        evictUserCaches(userId, iconChanged);
        registerAfterCommitEviction(userId, iconChanged);

        UserInfo latest = getById(userId);
        if (latest == null) {
            latest = toUpdate;
        }
        latest.setCreateTime(null);
        latest.setUpdateTime(null);

        log.info("[更新资料] 用户{}更新个人资料，gender:{}, city:{}, birthday:{}",
                userId, updateDTO.getGender(), updateDTO.getCity(), updateDTO.getBirthday());
        return Result.ok(latest);
    }

    /**
     * 失效用户相关缓存（Redis 异常不影响主流程）
     *
     * @param userId      用户ID
     * @param iconChanged 是否修改了头像（头像存于 tb_user，需要额外清 cache:user:）
     */
    private void evictUserCaches(Long userId, boolean iconChanged) {
        try {
            stringRedisTemplate.delete(CACHE_USER_INFO_KEY + userId);
            if (iconChanged) {
                stringRedisTemplate.delete(CACHE_USER_KEY + userId);
            }
        } catch (Exception e) {
            log.warn("[更新资料] 缓存失效失败，userId:{}, error:{}", userId, e.getMessage());
        }
    }

    /**
     * 事务提交后再删除一次缓存，保证 DB 已可见后缓存一定是空的
     */
    private void registerAfterCommitEviction(Long userId, boolean iconChanged) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictUserCaches(userId, iconChanged);
            }
        });
    }
}
