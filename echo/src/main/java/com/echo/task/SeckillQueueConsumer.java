package com.echo.task;

import com.echo.service.ITicketOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 秒杀排队队列消费者
 *
 * 排队模式：所有校验通过的秒杀请求进入Redis ZSET队列（seckill:queue:{ticketId}），
 * 本任务每100ms调度一次，每批最多消费50单 → 消费速率500单/秒（≈DB写入能力，按压测调整）。
 * 消费结果写入 seckill:queue:result:{ticketId}:{userId}，前端轮询展示排队进度；
 * 售罄时向全部排队用户广播"已售罄"结果。
 * 多实例部署时由票种级分布式锁（TTL 30s + 看门狗续期）保证同一队列不被并发消费。
 *
 * @author tulip
 */
@Slf4j
@Component
public class SeckillQueueConsumer {

    @Resource
    private ITicketOrderService ticketOrderService;

    /**
     * 定时消费秒杀排队队列（fixedDelay=100ms，单批50单 → 500单/秒）
     */
    @Scheduled(fixedDelay = 100)
    public void consumeSeckillQueue() {
        ticketOrderService.consumeSeckillQueueBatch();
    }
}
