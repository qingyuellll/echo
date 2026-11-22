package com.echo.mq;

import com.echo.entity.Show;
import com.echo.message.CacheInvalidateMessage;
import com.echo.monitor.OrderMetricsService;
import com.echo.utils.MqConstants;
import com.echo.utils.RedisConstants;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 缓存失效消息监听器(RocketMQ Broadcasting模式)
 * <p>
 * 监听缓存失效消息并删除本地Caffeine缓存，保证Redis与Caffeine两级缓存一致性
 *
 * @author tulip
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rocketmq.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = MqConstants.CACHE_INVALIDATE_TOPIC,
        consumerGroup = MqConstants.CACHE_INVALIDATE_CONSUMER_GROUP,
        messageModel = MessageModel.BROADCASTING
)
public class CacheInvalidateListener implements RocketMQListener<CacheInvalidateMessage> {

    /** Caffeine演出本地缓存 */
    @Resource
    private Cache<String, Show> showLocalCache;

    /** Redis操作（用于删除二级缓存中的脏数据） */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 订单指标监控服务(可选，用于记录缓存失效指标) */
    @Resource
    private OrderMetricsService orderMetricsService;

    /**
     * 处理缓存失效消息
     *
     * @param message 缓存失效消息
     */
    @Override
    public void onMessage(CacheInvalidateMessage message) {
        long startTime = System.currentTimeMillis();

        try {
            if (!isValidMessage(message)) {
                return;
            }

            String localKey = buildLocalKey(message);
            log.info("[缓存失效] 收到 RocketMQ 广播，key: {}", localKey);

            processInvalidation(message, localKey);
            recordSuccessMetrics(message.getKeyPrefix(), startTime);

        } catch (Exception e) {
            log.error("[缓存失效] 处理消息异常 - keyPrefix:{}, error:{}",
                    message != null ? message.getKeyPrefix() : "null",
                    e.getMessage(), e);
            recordFailMetrics(e.getMessage(), startTime);
        }
    }

    /**
     * 校验消息有效性
     *
     * @param message 缓存失效消息
     * @return true=有效, false=无效
     */
    private boolean isValidMessage(CacheInvalidateMessage message) {
        if (message == null || message.getKeyPrefix() == null) {
            log.warn("[缓存失效] 收到无效消息，跳过");
            return false;
        }
        return true;
    }

    /**
     * 构建本地缓存Key
     *
     * @param message 缓存失效消息
     * @return 本地缓存Key
     */
    private String buildLocalKey(CacheInvalidateMessage message) {
        return message.getKeyPrefix() + message.getId();
    }

    /**
     * 执行缓存失效操作
     *
     * @param message 缓存失效消息
     * @param localKey 本地缓存Key
     */
    private void processInvalidation(CacheInvalidateMessage message, String localKey) {
        if (RedisConstants.CACHE_SHOW_KEY.equals(message.getKeyPrefix())) {
            showLocalCache.invalidate(localKey);
            // 同步删除Redis缓存：延迟双删-④的延时消息依赖本监听器在延时窗口后清理
            // "从库读到旧值回填"产生的Redis脏数据；立即消息(-③)触发同样的删除，幂等无害
            stringRedisTemplate.delete(localKey);
            log.debug("[缓存失效] Caffeine与Redis缓存已删除，key: {}", localKey);
        } else {
            log.debug("[缓存失效] 非演出缓存前缀({})，Caffeine 无需处理",
                    message.getKeyPrefix());
        }
    }

    /**
     * 记录成功处理的监控指标
     *
     * @param keyPrefix 缓存Key前缀
     * @param startTime 开始时间戳
     */
    private void recordSuccessMetrics(String keyPrefix, long startTime) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            orderMetricsService.recordCacheInvalidationTime(duration);
        } catch (Exception ignored) {
        }
    }

    /**
     * 记录失败处理的监控指标
     *
     * @param errorMessage 错误信息
     * @param startTime 开始时间戳
     */
    private void recordFailMetrics(String errorMessage, long startTime) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            orderMetricsService.recordCacheInvalidationFail(errorMessage, duration);
        } catch (Exception ignored) {
        }
    }
}
