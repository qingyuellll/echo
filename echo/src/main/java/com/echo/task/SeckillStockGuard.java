package com.echo.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.echo.entity.SeckillTicket;
import com.echo.service.ISeckillTicketService;
import com.echo.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀库存key周期守卫
 * <p>
 * 职责：活动进行中但库存key缺失时，按固定节奏完成SETNX原子重建，让秒杀入口保持对MySQL零访问。
 * 覆盖三种key缺失场景：Redis重启清空、库存key TTL意外过期、活动发布后管理端预热遗漏。
 * 入口对key缺失的请求返回"活动准备中"，重建完成后下一次请求恢复正常，最迟一个守卫周期（20秒）。
 * <p>
 * 并发安全：preloadSeckillStock内部为SETNX原子写入，多实例同时触发时也只有一个实例成功建key。
 * 调度隔离：本任务运行在spring.task.scheduling调度线程池上（pool.size=4），与秒杀队列消费、
 * 预订单超时补偿、秒杀对账各自独占线程，互不阻塞。
 *
 * @author tulip
 */
@Slf4j
@Component
public class SeckillStockGuard {

    /** 守卫间隔（毫秒）：Redis重启后最迟一个周期完成库存key重建 */
    private static final long GUARD_INTERVAL_MS = 20000;

    @Resource
    private ISeckillTicketService seckillTicketService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 扫描进行中的秒杀票，对缺失库存key的票种执行SETNX预热
     */
    @Scheduled(fixedDelay = GUARD_INTERVAL_MS)
    public void guardSeckillStockKeys() {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<SeckillTicket> activeTickets = seckillTicketService.list(
                new LambdaQueryWrapper<SeckillTicket>()
                    .le(SeckillTicket::getBeginTime, now)
                    .and(wrapper -> wrapper
                        .ge(SeckillTicket::getEndTime, now)
                        .or()
                        .isNull(SeckillTicket::getEndTime))
                    .gt(SeckillTicket::getStock, 0)
            );

            if (activeTickets.isEmpty()) {
                return;
            }

            int rebuiltCount = 0;
            for (SeckillTicket ticket : activeTickets) {
                Long ticketId = ticket.getTicketId();
                String stockKey = RedisConstants.SECKILL_STOCK_KEY + ticketId;
                Boolean hasKey = stringRedisTemplate.hasKey(stockKey);
                if (Boolean.TRUE.equals(hasKey)) {
                    continue;
                }

                // SETNX原子重建：与Lua扣减并发时，迟到的写入会被拒绝，已扣减计数保持原值
                seckillTicketService.preloadSeckillStock(ticketId);
                rebuiltCount++;
                log.warn("[秒杀库存守卫] 活动进行中且库存key缺失，已执行原子重建 - ticketId:{}, DB库存:{}",
                        ticketId, ticket.getStock());
            }

            if (rebuiltCount > 0) {
                log.warn("[秒杀库存守卫] 本轮重建完成 - 活动中票种:{}, 重建:{}, 下一轮:{}ms后",
                        activeTickets.size(), rebuiltCount, GUARD_INTERVAL_MS);
            }
        } catch (Exception e) {
            log.error("[秒杀库存守卫] 执行异常，下一轮继续 - error:{}", e.getMessage(), e);
        }
    }
}
