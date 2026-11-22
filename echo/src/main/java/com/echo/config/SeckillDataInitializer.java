package com.echo.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.echo.entity.SeckillTicket;
import com.echo.entity.TicketOrder;
import com.echo.service.ISeckillTicketService;
import com.echo.service.ITicketOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.echo.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.echo.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Component
public class SeckillDataInitializer implements CommandLineRunner {

    @Value("${seckill.init-on-startup:true}")
    private boolean initOnStartup;

    @Resource
    private ISeckillTicketService seckillTicketService;

    @Resource
    private ITicketOrderService ticketOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(String... args) {
        if (!initOnStartup) {
            log.info("[秒杀数据初始化] 已禁用，跳过（可通过 seckill.init-on-startup=true 启用）");
            return;
        }

        log.info("[秒杀数据初始化] 开始同步Redis秒杀数据（V3.0性能优化版）...");
        try {
            List<SeckillTicket> seckillTickets = seckillTicketService.list();
            if (seckillTickets.isEmpty()) {
                log.info("[秒杀数据初始化] 无秒杀票档数据，跳过");
                return;
            }

            List<Long> ticketIds = seckillTickets.stream()
                    .map(SeckillTicket::getTicketId)
                    .toList();

            Map<Long, Long> paidOrderCountMap = getPaidOrderCountMap(ticketIds);
            Map<Long, List<TicketOrder>> validOrderMap = getValidOrderMap(ticketIds);

            int successCount = 0;
            int skipCount = 0;
            int errorCount = 0;

            for (SeckillTicket st : seckillTickets) {
                Long ticketId = st.getTicketId();
                String stockKey = SECKILL_STOCK_KEY + ticketId;
                String orderKey = SECKILL_ORDER_KEY + ticketId;

                try {
                    Boolean hasStock = stringRedisTemplate.hasKey(stockKey);

                    if (Boolean.TRUE.equals(hasStock)) {
                        String currentRedisStock = stringRedisTemplate.opsForValue().get(stockKey);
                        log.debug("[秒杀数据初始化] Redis库存已存在，跳过重建 - key:{}, 当前库存:{}",
                                stockKey, currentRedisStock);
                        skipCount++;
                        continue;
                    }

                    Integer totalStockInMySQL = st.getStock();
                    long paidOrderCount = paidOrderCountMap.getOrDefault(ticketId, 0L);
                    int calculatedStock = Math.max(0, totalStockInMySQL - (int) paidOrderCount);

                    // SETNX原子写入：与预热守卫、Lua扣减并发时保持单一写入路径，
                    // 已扣减计数被迟到的启动初始化覆盖的风险由此消除
                    stringRedisTemplate.opsForValue().setIfAbsent(
                            stockKey, String.valueOf(calculatedStock));

                    Boolean hasOrderSet = stringRedisTemplate.hasKey(orderKey);
                    if (!Boolean.TRUE.equals(hasOrderSet)) {
                        List<TicketOrder> validOrders = validOrderMap.getOrDefault(ticketId, List.of());

                        if (!validOrders.isEmpty()) {
                            List<String> userIds = validOrders.stream()
                                    .map(o -> o.getUserId().toString())
                                    .toList();
                            stringRedisTemplate.opsForSet().add(orderKey, userIds.toArray(new String[0]));
                            log.debug("[秒杀数据初始化] 初始化订单集合: key={}, count={}", orderKey, validOrders.size());
                        }
                    }

                    successCount++;
                    log.info("[秒杀数据初始化] 库存已计算并同步 - ticketId:{}, MySQL总库存:{}, 已消费:{}, Redis实际库存:{}",
                            ticketId, totalStockInMySQL, paidOrderCount, calculatedStock);

                } catch (Exception e) {
                    errorCount++;
                    log.error("[秒杀数据初始化] 处理失败 - ticketId:{}, error:{}", ticketId, e.getMessage(), e);
                }
            }

            log.info("[秒杀数据初始化] 同步完成 - 成功:{}, 跳过:{}, 失败:{}, 总计:{}",
                    successCount, skipCount, errorCount, seckillTickets.size());

        } catch (Exception e) {
            log.error("[秒杀数据初始化] 同步异常", e);
        }
    }

    private Map<Long, Long> getPaidOrderCountMap(List<Long> ticketIds) {
        List<TicketOrder> paidOrders = ticketOrderService.list(
                new LambdaQueryWrapper<TicketOrder>()
                        .in(TicketOrder::getTicketId, ticketIds)
                        .in(TicketOrder::getStatus, 2, 3)
                        .select(TicketOrder::getTicketId)
        );

        return paidOrders.stream()
                .collect(Collectors.groupingBy(
                        TicketOrder::getTicketId,
                        Collectors.counting()
                ));
    }

    private Map<Long, List<TicketOrder>> getValidOrderMap(List<Long> ticketIds) {
        List<TicketOrder> validOrders = ticketOrderService.list(
                new LambdaQueryWrapper<TicketOrder>()
                        .in(TicketOrder::getTicketId, ticketIds)
                        .in(TicketOrder::getStatus, 2, 3, 6)
        );

        return validOrders.stream()
                .collect(Collectors.groupingBy(TicketOrder::getTicketId));
    }
}
