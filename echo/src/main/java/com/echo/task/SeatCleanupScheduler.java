package com.echo.task;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.echo.entity.Seat;
import com.echo.monitor.OrderMetricsService;
import com.echo.service.ISeatService;
import com.echo.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 座位超时清理调度器
 *
 * @author tulip
 */
@Slf4j
@Component
public class SeatCleanupScheduler {

    /** 座位服务 */
    @Resource
    private ISeatService seatService;

    /** Redis字符串操作模板 */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 订单监控指标服务 */
    @Resource
    private OrderMetricsService orderMetricsService;

    /** 定时任务执行间隔(毫秒) - 每2分钟 */
    private static final long SCHEDULE_INTERVAL_MS = 120000L;

    /** 单次处理的最大座位数量(防止长事务阻塞) */
    private static final int MAX_BATCH_SIZE = 100;

    /** 座位状态：已锁定 */
    private static final int SEAT_STATUS_LOCKED = 1;

    /** 座位状态：空闲 */
    private static final int SEAT_STATUS_FREE = 0;

    /** 分布式锁Key(防止多实例并发) */
    private static final String SCHEDULER_LOCK_KEY = "seat:cleanup:scheduler:lock";

    /** 分布式锁超时时间(秒) - 大于执行间隔，防止死锁 */
    private static final long LOCK_TIMEOUT_SECONDS = 150L;

    /** 清理过期的锁定座位(定时任务入口) */
    @Scheduled(fixedRate = SCHEDULE_INTERVAL_MS)
    public void cleanupExpiredLockedSeats() {
        long startTime = System.currentTimeMillis();
        CleanupStatistics stats = new CleanupStatistics();

        try {
            if (!acquireSchedulerLock()) {
                log.debug("[座位清理] 未获取到分布式锁，跳过本轮");
                return;
            }

            log.debug("[座位清理] 开始扫描需要清理的锁定座位...");

            List<Seat> lockedSeats = findLockedSeatsWithoutOrders();

            if (lockedSeats.isEmpty()) {
                return;
            }

            log.info("[座位清理] 发现{}个待检查的锁定座位（status={}且order_id为空），开始验证Redis锁状态...",
                    lockedSeats.size(), SEAT_STATUS_LOCKED);

            processLockedSeats(lockedSeats, stats);

        } catch (Exception e) {
            log.error("[座位清理] 调度任务执行异常", e);
        } finally {
            releaseSchedulerLock();
            recordMetricsAndLog(stats, startTime);
        }
    }

    /**
     * 获取调度器分布式锁
     *
     * @return 是否成功获取锁
     */
    private boolean acquireSchedulerLock() {
        try {
            Boolean lockAcquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(SCHEDULER_LOCK_KEY,
                            String.valueOf(System.currentTimeMillis()),
                            LOCK_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS);

            return Boolean.TRUE.equals(lockAcquired);
        } catch (Exception e) {
            log.warn("[座位清理] 获取分布式锁异常，继续执行 - error:{}", e.getMessage());
            return true;
        }
    }

    /**
     * 释放调度器分布式锁
     */
    private void releaseSchedulerLock() {
        try {
            stringRedisTemplate.delete(SCHEDULER_LOCK_KEY);
        } catch (Exception ignored) {
        }
    }

    /**
     * 处理所有候选的锁定座位
     *
     * @param lockedSeats 候选座位列表
     * @param stats       统计信息
     */
    private void processLockedSeats(List<Seat> lockedSeats, CleanupStatistics stats) {
        for (Seat seat : lockedSeats) {
            try {
                processSingleSeat(seat, stats);
            } catch (Exception e) {
                stats.failCount++;
                log.error("[座位清理] 处理单个座位异常 - seatId:{}, ticketId:{}, error:{}",
                        seat.getId(), seat.getTicketId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 处理单个锁定座位
     *
     * @param seat  座位对象
     * @param stats 统计信息
     */
    private void processSingleSeat(Seat seat, CleanupStatistics stats) {
        String lockKey = buildLockKey(seat);

        if (isRedisLockStillValid(lockKey)) {
            handleValidLock(seat, lockKey, stats);
        } else {
            handleExpiredLock(seat, stats);
        }
    }

    /**
     * 构建Redis锁Key
     *
     * @param seat 座位对象
     * @return Redis锁Key
     */
    private String buildLockKey(Seat seat) {
        return RedisConstants.LOCK_SEAT_KEY + seat.getTicketId() + ":" + seat.getId();
    }

    /**
     * 检查Redis锁是否仍然有效
     *
     * @param lockKey Redis锁Key
     * @return true=锁仍有效(不应释放), false=锁已过期(应该释放)
     */
    private boolean isRedisLockStillValid(String lockKey) {
        try {
            Boolean hasLock = stringRedisTemplate.hasKey(lockKey);
            return Boolean.TRUE.equals(hasLock);
        } catch (Exception e) {
            log.warn("[座位清理] 检查Redis锁异常，假设锁已失效 - lockKey:{}, error:{}", lockKey, e.getMessage());
            return false;
        }
    }

    /**
     * 处理Redis锁仍有效的情况(跳过该座位)
     *
     * @param seat    座位对象
     * @param lockKey Redis锁Key
     * @param stats   统计信息
     */
    private void handleValidLock(Seat seat, String lockKey, CleanupStatistics stats) {
        stats.skipCount++;
        Long remainingTtl = getRemainingTTL(lockKey);
        log.debug("[座位清理] 跳过 - Redis锁仍有效 - seatId:{}, ticketId:{}, 剩余TTL:{}秒",
                seat.getId(), seat.getTicketId(), remainingTtl != null ? remainingTtl : "未知");
    }

    /**
     * 获取Redis锁剩余TTL
     *
     * @param lockKey Redis锁Key
     * @return 剩余秒数，查询失败返回null
     */
    private Long getRemainingTTL(String lockKey) {
        try {
            return stringRedisTemplate.getExpire(lockKey, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("[座位清理] 获取TTL失败 - lockKey:{}, error:{}", lockKey, e.getMessage());
            return null;
        }
    }

    /**
     * 处理Redis锁已过期的情况(尝试释放座位)
     *
     * @param seat  座位对象
     * @param stats 统计信息
     */
    private void handleExpiredLock(Seat seat, CleanupStatistics stats) {
        boolean released = releaseExpiredSeat(seat);
        if (released) {
            stats.successCount++;
            logReleaseSuccess(seat);
        } else {
            stats.failCount++;
        }
    }

    /**
     * 记录座位释放成功的日志
     *
     * @param seat 座位对象
     */
    private void logReleaseSuccess(Seat seat) {
        log.info("[座位清理] 座位释放成功 - seatId:{}, ticketId:{}, 区域:{}, {}排{}座",
                seat.getId(), seat.getTicketId(), seat.getArea(),
                seat.getRowNum(), seat.getColNum());
    }

    /**
     * 查找所有已锁定但未关联订单的座位
     * <p>
     * 查询条件：
     * - status = 1（已锁定）
     * - order_id IS NULL（未建单/未支付）
     * - LIMIT {MAX_BATCH_SIZE}（防止单次处理过多）
     *
     * @return 候选座位列表(可能为空)
     */
    private List<Seat> findLockedSeatsWithoutOrders() {
        var queryWrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Seat>();
        queryWrapper.eq("status", SEAT_STATUS_LOCKED)
                .isNull("order_id")
                .last("LIMIT " + MAX_BATCH_SIZE);

        return seatService.list(queryWrapper);
    }

    /**
     * 原子性释放超时座位(CAS乐观锁操作)
     * <p>
     * 使用乐观锁机制确保并发安全：
     * - WHERE条件：id=? AND status=1 AND order_id IS NULL
     * - SET操作：status=0, order_id=NULL, pre_order_id=NULL
     * <p>
     * CAS保证：
     * - 如果其他线程已将座位改为status=2(已售)，则updated=false
     * - 如果其他线程已释放(status=0)，则updated=false
     * - 只有真正的"DB锁定但Redis锁过期"的座位才会被释放
     *
     * @param seat 待释放的座位对象
     * @return true=释放成功, false=CAS校验未通过或异常
     */
    private boolean releaseExpiredSeat(Seat seat) {
        try {
            UpdateWrapper<Seat> updateWrapper = buildCasUpdateWrapper(seat);
            boolean updated = seatService.update(updateWrapper);

            if (!updated) {
                logCasFailure(seat);
            }

            return updated;
        } catch (Exception e) {
            logReleaseException(seat, e);
            return false;
        }
    }

    /**
     * 构建CAS更新条件
     *
     * @param seat 座位对象
     * @return UpdateWrapper
     */
    private UpdateWrapper<Seat> buildCasUpdateWrapper(Seat seat) {
        UpdateWrapper<Seat> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", seat.getId())
                .eq("status", SEAT_STATUS_LOCKED)
                .isNull("order_id")
                .set("status", SEAT_STATUS_FREE)
                .set("order_id", null)
                .set("pre_order_id", null);
        return updateWrapper;
    }

    /**
     * 记录CAS更新失败的日志
     *
     * @param seat 座位对象
     */
    private void logCasFailure(Seat seat) {
        log.debug("[座位清理] 座位释放失败（CAS校验未通过，可能已被其他操作修改） - seatId:{}, ticketId:{}, 当前status:{}",
                seat.getId(), seat.getTicketId(), seat.getStatus());
    }

    /**
     * 记录座位释放异常日志
     *
     * @param seat 座位对象
     * @param e    异常对象
     */
    private void logReleaseException(Seat seat, Exception e) {
        log.error("[座位清理] 座位释放异常 - seatId:{}, ticketId:{}, error:{}",
                seat.getId(), seat.getTicketId(), e.getMessage(), e);
    }

    /**
     * 记录监控指标和统计日志
     *
     * @param stats     统计信息
     * @param startTime 开始时间戳
     */
    private void recordMetricsAndLog(CleanupStatistics stats, long startTime) {
        if (stats.getTotalProcessed() == 0) {
            return;
        }

        long durationMs = System.currentTimeMillis() - startTime;

        log.info("[座位清理] 完成 - 总数:{}, 成功:{}, 跳过(锁仍有效):{}, 失败(CAS校验未通过):{}, 耗时:{}ms",
                stats.getTotalProcessed(),
                stats.successCount,
                stats.skipCount,
                stats.failCount,
                durationMs);

        recordMonitoringMetrics(stats, durationMs);
    }

    /**
     * 记录Prometheus监控指标
     *
     * @param stats      统计信息
     * @param durationMs 执行耗时(毫秒)
     */
    private void recordMonitoringMetrics(CleanupStatistics stats, long durationMs) {
        try {
            orderMetricsService.recordSeatCleanupStats(
                    stats.successCount,
                    stats.skipCount,
                    stats.failCount,
                    durationMs
            );
        } catch (Exception ignored) {
        }
    }

    /**
     * 座位清理统计信息(内部使用)
     */
    private static class CleanupStatistics {
        int successCount = 0;
        int skipCount = 0;
        int failCount = 0;

        int getTotalProcessed() {
            return successCount + skipCount + failCount;
        }
    }
}
