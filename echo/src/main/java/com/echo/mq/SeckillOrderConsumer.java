package com.echo.mq;

import com.echo.entity.PreOrder;
import com.echo.enums.PreOrderStatus;
import com.echo.message.SeckillOrderMessage;
import com.echo.monitor.OrderMetricsService;
import com.echo.service.IPreOrderService;
import com.echo.utils.MqConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 秒杀预订单消费者(RocketMQ Clustering模式)
 * <p>
 * 轻量级预处理组件：接收秒杀成功消息，仅做状态确认和日志记录，
 * 真正的建单+扣库存由payOrder()在支付时同步完成
 *
 * @author tulip
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rocketmq.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = MqConstants.SECKILL_ORDER_TOPIC,
        consumerGroup = MqConstants.SECKILL_ORDER_CONSUMER_GROUP
)
public class SeckillOrderConsumer implements RocketMQListener<SeckillOrderMessage>,
        RocketMQPushConsumerLifecycleListener {

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        // 消费失败最多重试3次（RocketMQ默认16次），耗尽后进入死信队列 %DLQ%+组名，由DlqAlertMonitor告警
        consumer.setMaxReconsumeTimes(3);
    }

    /** 预订单服务(查询预订单状态) */
    @Resource
    private IPreOrderService preOrderService;

    /** 订单指标监控服务(可选，用于记录秒杀消费指标) */
    @Resource
    private OrderMetricsService orderMetricsService;

    /**
     * 处理秒杀预订单消息
     * <p>
     * 核心职责：参数校验 → 查询PreOrder → 状态确认 → 记录日志
     * <p>
     * 设计原则：轻量级、无副作用、可观察
     *
     * @param message 秒杀预订单消息(强类型)
     */
    @Override
    public void onMessage(SeckillOrderMessage message) {
        long startTime = System.currentTimeMillis();

        log.info("[秒杀消费] 收到预订单消息 - tempId:{}, userId:{}, ticketId:{}",
                message.getTempId(), message.getUserId(), message.getTicketId());

        if (!validateMessage(message)) {
            return;
        }

        try {
            PreOrder preOrder = queryAndValidatePreOrder(message.getTempId());
            if (preOrder == null) {
                recordSuccessMetrics(startTime, message.getTempId(), "订单不存在");
                return;
            }

            logInfoForValidOrder(message, preOrder);
            recordSuccessMetrics(startTime, message.getTempId(), "确认有效");

        } catch (Exception e) {
            log.error("[秒杀消费] 处理失败（不影响主流程） - tempId:{}, error:{}",
                    message.getTempId(), e.getMessage(), e);
            recordFailMetrics(message.getTempId(), e.getMessage(), startTime);
        }
    }

    /**
     * 校验消息参数有效性
     *
     * @param message 秒杀预订单消息
     * @return true=有效, false=无效
     */
    private boolean validateMessage(SeckillOrderMessage message) {
        if (message == null || message.getTempId() == null || message.getTempId().isEmpty()) {
            log.error("[秒杀消费] 消息缺少tempId字段或消息为空，忽略");
            recordFailMetrics("unknown", "缺少tempId", System.currentTimeMillis());
            return false;
        }
        return true;
    }

    /**
     * 查询预订单并验证状态
     * <p>
     * 两道防线：
     * 1. 存在性校验（PreOrder是否已被删除）
     * 2. 状态校验（是否已被取消）
     *
     * @param tempId 预订单ID
     * @return 预订单实体(如果有效), null(如果无效)
     */
    private PreOrder queryAndValidatePreOrder(String tempId) {
        PreOrder preOrder = preOrderService.getById(tempId);

        if (preOrder == null) {
            log.warn("[秒杀消费] 预订单不存在（可能已支付并清理），tempId:{}", tempId);
            return null;
        }

        if (PreOrderStatus.CANCELLED.getCode() == preOrder.getStatus()) {
            log.info("[秒杀消费] 预订单已取消，无需处理 - tempId:{}", tempId);
            return null;
        }

        return preOrder;
    }

    /**
     * 记录有效预订单的详细信息日志
     *
     * @param message 秒杀预订单消息
     * @param preOrder 预订单实体
     */
    private void logInfoForValidOrder(SeckillOrderMessage message, PreOrder preOrder) {
        log.info("[秒杀消费] 预订单确认有效，等待用户支付 - tempId:{}, userId:{}, ticketId:{}, 数量:{}, 状态:{}",
                message.getTempId(),
                preOrder.getUserId(),
                preOrder.getTicketId(),
                preOrder.getQuantity(),
                PreOrderStatus.descriptionOf(preOrder.getStatus()));
    }

    /**
     * 记录成功处理的监控指标
     *
     * @param startTime 开始时间戳
     * @param tempId 预订单ID
     * @param result 结果描述
     */
    private void recordSuccessMetrics(long startTime, String tempId, String result) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            orderMetricsService.recordSeckillOrderSuccess(tempId, result, duration);
        } catch (Exception ignored) {
        }
    }

    /**
     * 记录失败处理的监控指标
     *
     * @param tempId 预订单ID
     * @param errorMessage 错误信息
     * @param startTime 开始时间戳
     */
    private void recordFailMetrics(String tempId, String errorMessage, long startTime) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            orderMetricsService.recordSeckillOrderFail(tempId, errorMessage, duration);
        } catch (Exception ignored) {
        }
    }
}
