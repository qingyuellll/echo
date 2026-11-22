package com.echo.event.listener;

import com.echo.event.OrderStatusChangeEvent;
import com.echo.message.CacheInvalidateMessage;
import com.echo.utils.MqConstants;
import com.echo.utils.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;

/**
 * 缓存失效监听器 - 订单状态变更时主动清除相关缓存
 * <p>
 * 业务场景：
 * - 当订单支付成功、退款、取消、超时时，演出详情和票种的"已售数量"会发生变化
 * - 需要立即失效 Redis 缓存和各节点的 Caffeine 本地缓存，确保数据一致性
 * - 采用 Cache-Aside 模式：先删缓存，下次查询时从数据库加载最新数据
 * <p>
 * 技术实现：
 * - 监听 OrderStatusChangeEvent 领域事件（Spring Event 发布-订阅模式）
 * - 异步处理（@Async）避免阻塞订单主流程
 * - 通过 RocketMQ Broadcasting 广播通知分布式集群的所有节点清除本地缓存
 *
 * @see OrderStatusChangeEvent 订单状态变更领域事件
 * @see CacheInvalidateMessage 缓存失效消息体
 * @author echo
 * @version 3.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketSoldCountUpdater {

    /** Redis客户端 */
    private final StringRedisTemplate stringRedisTemplate;

    /** RocketMQ消息模板 */
    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 处理订单状态变更事件,执行缓存失效
     *
     * @param event 订单状态变更事件对象
     */
    @Async
    @EventListener
    public void onOrderStatusChange(OrderStatusChangeEvent event) {
        String eventType = resolveEventTypeDescription(event.getEventType());
        handleCacheInvalidate(event, eventType);
    }

    /**
     * 根据事件类型代码转换为中文描述
     * <p>
     * 用于日志记录，便于排查问题
     *
     * @param eventType 事件类型代码（PAY/REFUND/CANCEL/TIMEOUT等）
     * @return 中文描述字符串
     */
    private String resolveEventTypeDescription(String eventType) {
        if (eventType == null) {
            return "未知事件";
        }
        return switch (eventType) {
            case "PAY" -> "支付成功";
            case "REFUND" -> "退款成功";
            case "CANCEL" -> "订单取消";
            case "TIMEOUT" -> "超时取消";
            default -> "其他事件(" + eventType + ")";
        };
    }

    /**
     * 执行缓存失效的核心逻辑
     * <p>
     * 处理流程：
     * 1. 校验 showId 参数有效性
     * 2. 批量删除 Redis 缓存（演出详情 + 票种信息）
     * 3. 发送 RocketMQ 广播消息，通知集群其他节点清除 Caffeine 本地缓存
     *
     * @param event      订单状态变更事件
     * @param eventType 事件类型的中文描述（用于日志）
     */
    private void handleCacheInvalidate(OrderStatusChangeEvent event, String eventType) {
        try {
            Long showId = event.getShowId();
            String orderId = event.getOrderId();

            // 参数校验：showId 必须为有效正整数
            if (showId == null || showId <= 0) {
                log.warn("[缓存失效-跳过] {}事件缺少有效showId，orderId:{}", eventType, orderId);
                return;
            }

            log.info("[缓存失效-开始] 事件类型:{}, orderId:{}, showId:{}", eventType, orderId, showId);

            // 批量删除 Redis 缓存（减少网络往返次数）
            invalidateRedisCache(showId);

            // 发送 MQ 广播通知其他节点清除本地缓存
            broadcastCacheInvalidation(showId);

            log.info("[缓存失效-完成] 事件类型:{}, orderId:{}, showId:{}", eventType, orderId, showId);
        } catch (Exception e) {
            log.error("[缓存失效-异常] 事件类型:{}, orderId:{}, error:{}", eventType, event.getOrderId(), e.getMessage(), e);
        }
    }

    /**
     * 删除 Redis 中的相关缓存数据
     * <p>
     * 使用批量删除接口减少网络 RTT，提升性能。
     * 需要清理的缓存：
     * - 演出详情缓存（cache:show:{showId}）
     * - 票种列表缓存（cache:ticket:show:{showId}）
     *
     * @param showId 演出ID
     */
    private void invalidateRedisCache(Long showId) {
        // 构造需要删除的缓存 key 列表
        var cacheKeys = Arrays.asList(
                RedisConstants.CACHE_SHOW_KEY + showId,
                RedisConstants.CACHE_TICKET_KEY + showId
        );

        // 批量删除（一次网络请求）
        stringRedisTemplate.delete(cacheKeys);

        log.debug("[缓存失效] Redis批量清理完成 - keys: cache:show:{}, cache:ticket:show:{}", showId, showId);
    }

    /**
     * 通过 RocketMQ 发送广播消息，通知集群节点清除本地缓存
     * <p>
     * 使用 Broadcasting 模式确保同一 ConsumerGroup 下的所有节点都能收到消息，
     * 从而清除各自进程内的 Caffeine 本地缓存，保证分布式环境下的缓存一致性。
     *
     * @param showId 演出ID
     */
    private void broadcastCacheInvalidation(Long showId) {
        CacheInvalidateMessage message = CacheInvalidateMessage.of(RedisConstants.CACHE_SHOW_KEY, showId);
        rocketMQTemplate.syncSend(MqConstants.CACHE_INVALIDATE_TOPIC, message);

        log.debug("[缓存失效] RocketMQ广播已发送，通知所有节点删除Caffeine本地缓存 - showId:{}", showId);
    }
}
