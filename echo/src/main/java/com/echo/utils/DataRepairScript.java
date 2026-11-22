package com.echo.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echo.entity.Show;
import com.echo.entity.Ticket;
import com.echo.entity.TicketOrder;
import com.echo.service.IShowService;
import com.echo.service.ITicketOrderService;
import com.echo.service.ITicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 数据修复脚本(修复已售数量统计字段)
 *
 * @author tulip
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "echo.data-repair.auto-run", havingValue = "true")
public class DataRepairScript implements CommandLineRunner {

    /** 自动执行开关（从配置文件读取） */
    @Value("${echo.data-repair.auto-run:false}")
    private boolean autoRunEnabled;

    /** 订单服务 */
    private final ITicketOrderService ticketOrderService;

    /** 演出服务 */
    private final IShowService showService;

    /** 票种服务 */
    private final ITicketService ticketService;

    /** Redis字符串操作模板（用于分布式锁） */
    private final StringRedisTemplate stringRedisTemplate;

    /** 订单状态：已支付 */
    private static final int ORDER_STATUS_PAID = 2;

    /** 订单状态：已核销 */
    private static final int ORDER_STATUS_USED = 3;

    /** 默认已售数量（用于null值处理） */
    private static final int DEFAULT_SOLD_COUNT = 0;

    /** 分布式锁Key（防止多实例并发执行） */
    private static final String REPAIR_LOCK_KEY = "data:repair:lock";

    /** 分布式锁超时时间（秒）- 足够完成修复操作 */
    private static final long LOCK_TIMEOUT_SECONDS = 300L;

    /**
     * CommandLineRunner启动入口（应用启动时自动执行）
     * <p>
     * 执行流程：
     * 1. 检查自动执行开关
     * 2. 获取分布式锁（多实例部署安全）
     * 3. 执行演出和票种的已售数量修复
     * 4. 输出修复结果日志
     * 5. 释放分布式锁
     *
     * @param args 命令行参数（未使用）
     */
    @Override
    public void run(String... args) {
        if (!autoRunEnabled) {
            log.info("[数据修复] 自动执行已禁用（可通过API手动触发）");
            return;
        }

        RepairResult result = new RepairResult();

        try {
            if (!acquireRepairLock()) {
                log.info("[数据修复] 未获取到分布式锁，跳过（另一实例可能正在执行）");
                return;
            }

            log.info("[数据修复] 开始自动执行...");
            long startTime = System.currentTimeMillis();

            result.showRepaired = repairShowSoldCount();
            result.ticketRepaired = repairTicketSoldCount();
            result.costTimeMs = System.currentTimeMillis() - startTime;

            logRepairResult(result);

        } catch (Exception e) {
            log.error("[数据修复] 脚本执行失败", e);
        } finally {
            releaseRepairLock();
        }
    }

    /**
     * 获取分布式锁
     *
     * @return 是否成功获取锁
     */
    private boolean acquireRepairLock() {
        try {
            Boolean locked = stringRedisTemplate.opsForValue()
                    .setIfAbsent(REPAIR_LOCK_KEY,
                            String.valueOf(System.currentTimeMillis()),
                            LOCK_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS);
            return Boolean.TRUE.equals(locked);
        } catch (Exception e) {
            log.warn("[数据修复] 获取分布式锁异常，继续执行 - error:{}", e.getMessage());
            return true;
        }
    }

    /**
     * 释放分布式锁
     */
    private void releaseRepairLock() {
        try {
            stringRedisTemplate.delete(REPAIR_LOCK_KEY);
        } catch (Exception ignored) {
        }
    }

    /**
     * 记录修复结果日志
     *
     * @param result 修复结果
     */
    private void logRepairResult(RepairResult result) {
        if (result.hasRepairs()) {
            log.info("[数据修复] 完成 - 修复演出:{}个, 修复票种:{}个, 耗时:{}ms",
                    result.showRepaired, result.ticketRepaired, result.costTimeMs);
        } else {
            log.info("[数据修复] 无需修复, 耗时:{}ms", result.costTimeMs);
        }
    }

    /**
     * 修复演出已售数量（sold字段）
     * <p>
     * 核心原理：
     * 1. 查询所有有效订单（status IN (2,3)）
     * 2. 按showId分组聚合quantity求和
     * 3. 与当前db_show.sold比较，不一致则更新
     * <p>
     * 已售口径定义：
     * <ul>
     *     <li>status=2（ORDER_STATUS_PAID）：已支付（含未核销的票）</li>
     *     <li>status=3（ORDER_STATUS_USED）：已核销（已入场验票）</li>
     * </ul>
     *
     * @return 实际修复的演出数量（仅统计成功更新的记录数）
     */
    public int repairShowSoldCount() {
        try {
            List<TicketOrder> validOrders = queryValidOrders();

            if (validOrders.isEmpty()) {
                return 0;
            }

            Map<Long, Integer> showSoldMap = groupOrdersByShowId(validOrders);
            return repairShowsFromMap(showSoldMap);

        } catch (Exception e) {
            log.error("[修复演出已售] 异常", e);
            return 0;
        }
    }

    /**
     * 查询所有有效订单（已支付+已核销）
     *
     * @return 有效订单列表
     */
    private List<TicketOrder> queryValidOrders() {
        QueryWrapper<TicketOrder> orderQuery = new QueryWrapper<>();
        orderQuery.in("status", ORDER_STATUS_PAID, ORDER_STATUS_USED);
        return ticketOrderService.list(orderQuery);
    }

    /**
     * 按演出ID分组统计销量
     *
     * @param orders 有效订单列表
     * @return Map<演出ID, 正确的已售数量>
     */
    private Map<Long, Integer> groupOrdersByShowId(List<TicketOrder> orders) {
        return orders.stream()
                .filter(order -> order.getShowId() != null && order.getQuantity() != null)
                .collect(Collectors.groupingBy(
                        TicketOrder::getShowId,
                        Collectors.summingInt(TicketOrder::getQuantity)
                ));
    }

    /**
     * 根据统计Map批量修复演出已售数量
     *
     * @param showSoldMap 演出ID→正确销量的映射
     * @return 实际修复的数量
     */
    private int repairShowsFromMap(Map<Long, Integer> showSoldMap) {
        int repairedCount = 0;

        for (Map.Entry<Long, Integer> entry : showSoldMap.entrySet()) {
            Long showId = entry.getKey();
            Integer correctSold = entry.getValue();

            Show show = showService.getById(showId);
            if (show == null) {
                continue;
            }

            if (shouldRepairShowSold(show, correctSold)) {
                boolean success = updateShowSold(showId, correctSold);
                if (success) {
                    repairedCount++;
                    logShowRepaired(showId, show.getSold(), correctSold);
                }
            }
        }

        return repairedCount;
    }

    /**
     * 判断演出是否需要修复
     *
     * @param show        演出对象
     * @param correctSold 正确的已售数量
     * @return true=需要修复, false=无需修复
     */
    private boolean shouldRepairShowSold(Show show, Integer correctSold) {
        Integer currentSold = show.getSold() != null ? show.getSold() : DEFAULT_SOLD_COUNT;
        return !currentSold.equals(correctSold);
    }

    /**
     * 更新演出的已售数量
     *
     * @param showId      演出ID
     * @param correctSold 正确的已售数量
     * @return 是否更新成功
     */
    private boolean updateShowSold(Long showId, Integer correctSold) {
        Show updateShow = new Show();
        updateShow.setId(showId);
        updateShow.setSold(correctSold);
        return showService.updateById(updateShow);
    }

    /**
     * 记录单个演出修复日志
     *
     * @param showId      演出ID
     * @param oldSold     旧值
     * @param newSold     新值
     */
    private void logShowRepaired(Long showId, Integer oldSold, Integer newSold) {
        log.debug("[修复演出已售] showId:{}, {} → {}", showId, oldSold, newSold);
    }

    /**
     * 修复票种已售数量（soldCount字段）
     * <p>
     * 核心原理：
     * 1. 查询所有有效订单（status IN (2,3)）
     * 2. 按ticketId分组聚合quantity求和
     * 3. 与当前ticket.soldCount比较，不一致则更新
     * <p>
     * 已售口径定义：
     * <ul>
     *     <li>status=2（ORDER_STATUS_PAID）：已支付（含未核销的票）</li>
     *     <li>status=3（ORDER_STATUS_USED）：已核销（已入场验票）</li>
     * </ul>
     *
     * @return 实际修复的票种数量（仅统计成功更新的记录数）
     */
    public int repairTicketSoldCount() {
        try {
            List<TicketOrder> validOrders = queryValidOrders();

            if (validOrders.isEmpty()) {
                return 0;
            }

            Map<Long, Integer> ticketSoldMap = groupOrdersByTicketId(validOrders);
            return repairTicketsFromMap(ticketSoldMap);

        } catch (Exception e) {
            log.error("[修复票种已售] 异常", e);
            return 0;
        }
    }

    /**
     * 按票种ID分组统计销量
     *
     * @param orders 有效订单列表
     * @return Map<票种ID, 正确的已售数量>
     */
    private Map<Long, Integer> groupOrdersByTicketId(List<TicketOrder> orders) {
        return orders.stream()
                .filter(order -> order.getTicketId() != null && order.getQuantity() != null)
                .collect(Collectors.groupingBy(
                        TicketOrder::getTicketId,
                        Collectors.summingInt(TicketOrder::getQuantity)
                ));
    }

    /**
     * 根据统计Map批量修复票种已售数量
     *
     * @param ticketSoldMap 票种ID→正确销量的映射
     * @return 实际修复的数量
     */
    private int repairTicketsFromMap(Map<Long, Integer> ticketSoldMap) {
        int repairedCount = 0;

        for (Map.Entry<Long, Integer> entry : ticketSoldMap.entrySet()) {
            Long ticketId = entry.getKey();
            Integer correctSold = entry.getValue();

            Ticket ticket = ticketService.getById(ticketId);
            if (ticket == null) {
                continue;
            }

            if (shouldRepairTicketSold(ticket, correctSold)) {
                boolean success = updateTicketSold(ticketId, correctSold);
                if (success) {
                    repairedCount++;
                    logTicketRepaired(ticketId, ticket.getSoldCount(), correctSold);
                }
            }
        }

        return repairedCount;
    }

    /**
     * 判断票种是否需要修复
     *
     * @param ticket      票种对象
     * @param correctSold 正确的已售数量
     * @return true=需要修复, false=无需修复
     */
    private boolean shouldRepairTicketSold(Ticket ticket, Integer correctSold) {
        Integer currentSold = ticket.getSoldCount() != null ? ticket.getSoldCount() : DEFAULT_SOLD_COUNT;
        return !currentSold.equals(correctSold);
    }

    /**
     * 更新票种的已售数量（使用LambdaUpdate精准更新）
     *
     * @param ticketId    票种ID
     * @param correctSold 正确的已售数量
     * @return 是否更新成功
     */
    private boolean updateTicketSold(Long ticketId, Integer correctSold) {
        return ticketService.update(
                Wrappers.<Ticket>lambdaUpdate()
                        .set(Ticket::getSoldCount, correctSold)
                        .eq(Ticket::getId, ticketId)
        );
    }

    /**
     * 记录单个票种修复日志
     *
     * @param ticketId 票种ID
     * @param oldSold  旧值
     * @param newSold  新值
     */
    private void logTicketRepaired(Long ticketId, Integer oldSold, Integer newSold) {
        log.debug("[修复票种已售] ticketId:{}, {} → {}", ticketId, oldSold, newSold);
    }

    /**
     * 修复结果统计信息（内部使用）
     */
    private static class RepairResult {
        int showRepaired = 0;
        int ticketRepaired = 0;
        long costTimeMs = 0;

        boolean hasRepairs() {
            return showRepaired > 0 || ticketRepaired > 0;
        }
    }
}
