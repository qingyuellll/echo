package com.echo.mq;

import com.echo.entity.Notification;
import com.echo.enums.ReadStatus;
import com.echo.message.NotificationMessage;
import com.echo.monitor.OrderMetricsService;
import com.echo.service.INotificationService;
import com.echo.utils.MqConstants;
import com.echo.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 用户通知消息消费者(RocketMQ Clustering模式)
 * <p>
 * 消费通知消息并持久化到数据库，通过Redis幂等去重保证消息不重复处理
 *
 * @author tulip
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rocketmq.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = MqConstants.NOTIFICATION_TOPIC,
        consumerGroup = MqConstants.NOTIFICATION_CONSUMER_GROUP
)
public class NotificationConsumer implements RocketMQListener<NotificationMessage>,
        RocketMQPushConsumerLifecycleListener {

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        // 消费失败最多重试3次（RocketMQ默认16次），耗尽后进入死信队列 %DLQ%+组名，由DlqAlertMonitor告警
        consumer.setMaxReconsumeTimes(3);
    }

    /** 通知业务服务(数据库CRUD操作) */
    @Resource
    private INotificationService notificationService;

    /** Redis字符串操作模板(幂等去重+缓存管理) */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 订单指标监控服务(可选，用于记录通知处理指标) */
    @Resource
    private OrderMetricsService orderMetricsService;

    /** 幂等去重Key过期时间(24小时) */
    private static final Duration DEDUP_KEY_TTL = Duration.ofHours(24);

    /**
     * 处理通知消息
     *
     * @param message 通知消息
     */
    @Override
    public void onMessage(NotificationMessage message) {
        long startTime = System.currentTimeMillis();

        try {
            log.debug("[通知] 收到通知消息，userId:{}, type:{}, title:{}",
                    message.getUserId(), message.getType(), message.getTitle());

            if (!performDeduplication(message)) {
                return;
            }

            Notification notification = saveNotification(message);
            invalidateUnreadCache(message.getUserId());
            recordSuccessMetrics(message.getType(), startTime, notification.getId());

        } catch (Exception e) {
            log.error("[通知] 处理消息异常 - userId:{}, type:{}, error:{}",
                    message != null ? message.getUserId() : "null",
                    message != null ? message.getType() : "null",
                    e.getMessage(), e);
            // 必须回滚幂等key：SETNX发生在落库之前，若落库失败后不删除，
            // 后续MQ重投会被去重挡掉，通知永久丢失
            releaseDedupKey(message);
            recordFailMetrics(message, e.getMessage(), startTime);
            throw new RuntimeException("通知处理失败", e);
        }
    }

    /**
     * 释放幂等去重Key（业务落库失败时调用）
     *
     * @param message 通知消息
     */
    private void releaseDedupKey(NotificationMessage message) {
        if (message == null) {
            return;
        }
        try {
            String dedupKeyRaw = buildDedupKey(message);
            stringRedisTemplate.delete(buildRedisDedupKey(dedupKeyRaw));
            log.info("[通知] 已回滚幂等Key，等待重投 - dedupKey:{}", dedupKeyRaw);
        } catch (Exception ex) {
            log.error("[通知] 回滚幂等Key失败", ex);
        }
    }

    /**
     * 执行幂等去重检查
     *
     * @param message 通知消息
     * @return true=首次处理可继续, false=已处理过需跳过
     */
    private boolean performDeduplication(NotificationMessage message) {
        String dedupKeyRaw = buildDedupKey(message);
        String msgIdKey = buildRedisDedupKey(dedupKeyRaw);

        Boolean isFirstTime = stringRedisTemplate.opsForValue()
                .setIfAbsent(msgIdKey, "1", DEDUP_KEY_TTL);

        if (!Boolean.TRUE.equals(isFirstTime)) {
            log.info("[通知] 幂等去重：消息已处理过，跳过 - dedupKey:{}, userId:{}, title:{}",
                    dedupKeyRaw, message.getUserId(), message.getTitle());
            return false;
        }

        log.debug("[通知] 幂等去重通过 - dedupKey:{}", dedupKeyRaw);
        return true;
    }

    /**
     * 构建去重原始Key(双策略)
     *
     * @param message 通知消息
     * @return 去重Key字符串
     */
    private String buildDedupKey(NotificationMessage message) {
        if (message.getRelatedId() != null && !message.getRelatedId().toString().isEmpty()) {
            // 必须携带 type：同一订单会产生多种事件通知（支付成功/退款中/退款成功等），
            // key 缺 type 时第二条通知会被 24h 幂等误判为重复投递而丢弃。
            // 幂等语义：同订单+同用户+同类型仅处理一次（恰好挡住MQ重复投递）。
            String key = "related:" + message.getRelatedId() + ":" + message.getUserId()
                    + ":" + message.getType();
            log.debug("[通知] 使用relatedId+type去重 - relatedId:{}, userId:{}, type:{}",
                    message.getRelatedId(), message.getUserId(), message.getType());
            return key;
        } else {
            String createTimeStr = (message.getCreateTime() != null)
                    ? message.getCreateTime().toString()
                    : String.valueOf(System.currentTimeMillis());
            String key = message.getUserId() + ":" + message.getType() + ":" + createTimeStr;
            log.warn("[通知] 使用兜底去重策略(建议生产者设置relatedId) - userId:{}, type:{}, createTime:{}",
                    message.getUserId(), message.getType(), createTimeStr);
            return key;
        }
    }

    /**
     * 构建Redis去重Key(直接使用原始字符串避免hashCode碰撞)
     *
     * @param dedupKeyRaw 原始去重Key
     * @return Redis Key
     */
    private String buildRedisDedupKey(String dedupKeyRaw) {
        return RedisConstants.CACHE_NOTIFICATION_UNREAD_KEY + "msg:" + dedupKeyRaw;
    }

    /**
     * 保存通知到数据库
     *
     * @param message 通知消息
     * @return 持久化后的通知实体
     */
    private Notification saveNotification(NotificationMessage message) {
        Notification notification = new Notification();
        notification.setUserId(message.getUserId());
        notification.setType(message.getType());
        notification.setTitle(message.getTitle());
        notification.setContent(message.getContent());
        notification.setRelatedId(message.getRelatedId());
        notification.setIsRead(ReadStatus.UNREAD);
        notification.setCreateTime(LocalDateTime.now());

        notificationService.save(notification);
        log.info("[通知] 通知已入库 - id:{}, userId:{}, type:{}",
                notification.getId(), message.getUserId(), message.getType());

        return notification;
    }

    /**
     * 失效用户未读数缓存(Cache-Aside模式)
     *
     * @param userId 用户ID
     */
    private void invalidateUnreadCache(Long userId) {
        String cacheKey = RedisConstants.CACHE_NOTIFICATION_UNREAD_KEY + userId;
        stringRedisTemplate.delete(cacheKey);
        log.debug("[通知] 未读缓存已清除 - userId:{}", userId);
    }

    /**
     * 记录成功处理的监控指标
     *
     * @param type 通知类型
     * @param startTime 开始时间戳
     * @param notificationId 通知ID
     */
    private void recordSuccessMetrics(Object type, long startTime, Long notificationId) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            orderMetricsService.recordNotificationSuccess(type, duration, notificationId);
        } catch (Exception ignored) {
        }
    }

    /**
     * 记录失败处理的监控指标
     *
     * @param message 通知消息
     * @param errorMessage 错误信息
     * @param startTime 开始时间戳
     */
    private void recordFailMetrics(NotificationMessage message, String errorMessage, long startTime) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            Object type = (message != null) ? message.getType() : "unknown";
            orderMetricsService.recordNotificationFail(type, errorMessage, duration);
        } catch (Exception ignored) {
        }
    }
}
