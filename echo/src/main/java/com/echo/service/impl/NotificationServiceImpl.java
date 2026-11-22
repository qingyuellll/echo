package com.echo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.dto.UserDTO;
import com.echo.entity.Notification;
import com.echo.enums.ReadStatus;
import com.echo.mapper.NotificationMapper;
import com.echo.service.INotificationService;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.echo.utils.RedisConstants.CACHE_NOTIFICATION_UNREAD_KEY;
import static com.echo.utils.RedisConstants.CACHE_NOTIFICATION_UNREAD_TTL;


/**
 * 通知服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class NotificationServiceImpl extends ServiceImpl<NotificationMapper, Notification> implements INotificationService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource(name = "followExecutor")
    private ThreadPoolTaskExecutor executor;

    @Override
    public Result<?> queryMyNotifications(Integer current) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("[通知列表] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        if (current == null || current < 1) {
            log.warn("[通知列表] 参数错误，current:{}", current);
            current = 1;
        }

        try {
            QueryWrapper<Notification> wrapper = new QueryWrapper<>();
            wrapper.eq("user_id", user.getId())
                  .orderByDesc("create_time");
            Page<Notification> page = page(new Page<>(current, 20), wrapper);

            // 异步缓存未读数量（避免阻塞主流程）
            final Long userId = user.getId();
            executor.execute(() -> {
                try {
                    cacheUnreadCount(userId);
                } catch (Exception e) {
                    log.error("[通知列表] 异步缓存未读数失败，userId:{}，error:{}", userId, e.getMessage(), e);
                }
            });

            log.info("[通知列表] 查询成功，userId:{}, current:{}, total:{}", user.getId(), current, page.getTotal());
            return Result.ok(page);
        } catch (Exception e) {
            log.error("[通知列表] 查询失败，userId:{}, error:{}", user.getId(), e.getMessage(), e);
            return Result.fail(AppMessages.NOTIFICATION_LIST_QUERY_FAIL);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> markAsRead(Long id) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("[标记已读] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        if (id == null || id <= 0) {
            log.warn("[标记已读] 参数错误，id:{}", id);
            return Result.fail(AppMessages.NOTIFICATION_ID_INVALID);
        }

        try {
            Notification notification = getById(id);
            if (notification == null) {
                log.warn("[标记已读] 通知不存在，id:{}", id);
                return Result.fail(AppMessages.NOTIFICATION_NOT_FOUND);
            }
            if (!notification.getUserId().equals(user.getId())) {
                log.warn("[标记已读] 无权操作，不属于当前用户，notificationId:{}, userId:{}", id, user.getId());
                return Result.fail(AppMessages.NOTIFICATION_NOT_BELONG);
            }

            notification.setIsRead(ReadStatus.READ);
            updateById(notification);

            // 删除未读数缓存（下次访问时重新计算）
            invalidateUnreadCache(user.getId());

            log.info("[标记已读] 成功，notificationId:{}, userId:{}", id, user.getId());
            return Result.ok();
        } catch (Exception e) {
            log.error("[标记已读] 失败，id:{}, error:{}", id, e.getMessage(), e);
            return Result.fail(AppMessages.NOTIFICATION_MARK_READ_FAIL);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> markAllAsRead() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("[全部标记已读] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        try {
            boolean success = lambdaUpdate()
                    .eq(Notification::getUserId, user.getId())
                    .eq(Notification::getIsRead, 0)
                    .set(Notification::getIsRead, 1)
                    .update();

            if (success) {
                invalidateUnreadCache(user.getId());
                log.info("[全部标记已读] 成功，userId:{}", user.getId());
            } else {
                log.warn("[全部标记已读] 无需更新，可能没有未读通知，userId:{}", user.getId());
            }

            return Result.ok();
        } catch (Exception e) {
            log.error("[全部标记已读] 失败，userId:{}, error:{}", user.getId(), e.getMessage(), e);
            return Result.fail(AppMessages.NOTIFICATION_MARK_ALL_READ_FAIL);
        }
    }

    /**
     * 缓存未读数量到Redis
     */
    private void cacheUnreadCount(Long userId) {
        try {
            long unreadCount = lambdaQuery()
                    .eq(Notification::getUserId, userId)
                    .eq(Notification::getIsRead, 0)
                    .count();

            String cacheKey = CACHE_NOTIFICATION_UNREAD_KEY + userId;

            if (unreadCount > 0) {
                stringRedisTemplate.opsForValue().set(cacheKey,
                        String.valueOf(unreadCount),
                        CACHE_NOTIFICATION_UNREAD_TTL,
                        TimeUnit.SECONDS);
            } else {
                // 空值缓存，防止缓存穿透
                stringRedisTemplate.opsForValue().set(cacheKey,
                        "0",
                        5,
                        TimeUnit.MINUTES);
            }

            log.debug("[缓存未读数] userId:{}, count:{}", userId, unreadCount);
        } catch (Exception e) {
            log.warn("[缓存未读数] 失败，不影响主流程，userId:{}, error:{}", userId, e.getMessage());
        }
    }

    /**
     * 使未读数缓存失效
     */
    private void invalidateUnreadCache(Long userId) {
        try {
            String cacheKey = CACHE_NOTIFICATION_UNREAD_KEY + userId;
            stringRedisTemplate.delete(cacheKey);
            log.debug("[清除缓存] 未读数缓存已删除，userId:{}", userId);
        } catch (Exception e) {
            log.warn("[清除缓存] 失败，不影响主流程，userId:{}, error:{}", userId, e.getMessage());
        }
    }
}
