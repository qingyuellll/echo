package com.echo.mq;

import lombok.extern.slf4j.Slf4j;
import com.echo.utils.MqConstants;
import org.apache.rocketmq.client.consumer.DefaultLitePullConsumer;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 死信队列（DLQ）告警监控
 *
 * 背景：3个集群消费器的消费失败重试上限为3次（maxReconsumeTimes=3，见各Consumer.prepareStart），
 * 重试耗尽后消息自动进入死信主题 %DLQ%+consumerGroup。默认无人订阅，会静默堆积。
 *
 * 行为：每5分钟扫描一次各DLQ主题（采样式，不提交消费位点），发现死信立即输出ERROR日志，
 * 配合日志告警平台实现报警；未提交位点意味着告警每轮重复，直至死信被人工处理。
 *
 * 注意：广播模式消费者（CacheInvalidateListener）失败不重试、不进DLQ，无需监控。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
public class DlqAlertMonitor {

    @Value("${rocketmq.name-server}")
    private String nameServer;

    /** 与各@RocketMQMessageListener的consumerGroup一致（仅集群模式消费者有DLQ） */
    private static final List<String> CLUSTER_CONSUMER_GROUPS = List.of(
            MqConstants.SECKILL_ORDER_CONSUMER_GROUP,
            MqConstants.ORDER_TIMEOUT_CONSUMER_GROUP,
            MqConstants.NOTIFICATION_CONSUMER_GROUP);

    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void scanDeadLetterQueues() {
        for (String group : CLUSTER_CONSUMER_GROUPS) {
            scanOneGroup(group);
        }
    }

    private void scanOneGroup(String group) {
        String dlqTopic = "%DLQ%" + group;
        DefaultLitePullConsumer consumer = new DefaultLitePullConsumer("dlq-monitor-" + group);
        try {
            consumer.setNamesrvAddr(nameServer);
            consumer.setInstanceName("dlq-monitor-" + group + "-" + System.currentTimeMillis());
            consumer.setAutoCommit(false);
            consumer.subscribe(dlqTopic, "*");
            consumer.start();
            List<MessageExt> messages = consumer.poll(1000L);
            if (!messages.isEmpty()) {
                MessageExt first = messages.get(0);
                log.error("[DLQ告警] 死信队列存在未处理消息！topic:{}, 采样{}条, 首条msgId:{}, " +
                                "重试次数:{}, 进入死信时间:{}",
                        dlqTopic, messages.size(), first.getMsgId(),
                        first.getReconsumeTimes(), new java.util.Date(first.getStoreTimestamp()));
            }
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).contains("TOPIC_NOT_EXIST")) {
                // DLQ主题尚未创建（从未产生死信），属正常情况
                log.debug("[DLQ扫描] 死信主题不存在（无死信，正常）- topic:{}", dlqTopic);
            } else {
                log.warn("[DLQ扫描] 扫描异常 - topic:{}, error:{}", dlqTopic, e.getMessage());
            }
        } finally {
            consumer.shutdown();
        }
    }
}
