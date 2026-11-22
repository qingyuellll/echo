package com.echo.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.echo.entity.PreOrder;
import com.echo.entity.SeckillTicket;
import com.echo.entity.Ticket;
import com.echo.entity.TicketOrder;
import com.echo.monitor.OrderMetricsService;
import com.echo.service.IPreOrderService;
import com.echo.service.ISeckillTicketService;
import com.echo.service.ISeatService;
import com.echo.service.ITicketOrderService;
import com.echo.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 预订单超时补偿调度器(定时任务兜底机制)
 * <p>
 * 扫描并回滚被MQ遗漏的过期预订单，保证数据最终一致性
 *
 * @author tulip
 */
@Slf4j
@Component
public class PreOrderTimeoutScheduler {

    /** 单次处理最大预订单数(防止阻塞) */
    private static final int MAX_ORDERS_PER_RUN = 50;

    /** 分布式锁Key(预订单补偿) */
    private static final String COMPENSATION_LOCK_KEY = "preorder:compensation:lock";

    /** 处理中标记前缀 */
    private static final String PROCESSING_PREFIX_KEY = "preorder:processing:";

    /** 分布式锁超时时间(秒) */
    private static final long LOCK_TIMEOUT_SECONDS = 25;

    /** 处理中标记TTL(秒) */
    private static final long PROCESSING_MARK_TTL_SECONDS = 60;

    /** 缓冲时间(秒)，避免与MQ正常处理冲突 */
    private static final int BUFFER_SECONDS = 30;

    /** 定时任务执行间隔(毫秒)-预订单补偿 */
    private static final long PRE_ORDER_SCHEDULE_INTERVAL_MS = 30000;

    /** 分布式锁Key(秒杀对账) */
    private static final String RECONCILE_LOCK_KEY = "seckill:reconcile:lock";

    /** 对账锁超时时间(秒) */
    private static final long RECONCILE_LOCK_SECONDS = 110;

    /** 孤儿记录最小年龄(分钟)，避免误杀正在处理的订单 */
    private static final int ORPHAN_AGE_MINUTES = 10;

    /** 单次最多处理孤儿记录数 */
    private static final int MAX_ORPHANS_PER_RUN = 20;

    /** 定时任务执行间隔(毫秒)-秒杀对账 */
    private static final long RECONCILE_SCHEDULE_INTERVAL_MS = 120000;

    /** 秒杀票类型标识 */
    private static final int TICKET_TYPE_SECKILL = 1;

    /** 默认购买数量 */
    private static final int DEFAULT_QUANTITY = 1;

    /** 座位空闲状态 */
    private static final int SEAT_STATUS_FREE = 0;

    /** Redis缓存Key前缀 */
    private static final String PENDING_PRE_ORDER_CACHE_PREFIX = "pending:pre_order:";

    /** Grace Key前缀(防误杀) */
    private static final String GRACE_KEY_PREFIX = "seckill:grace:";

    /** 有主订单状态集合(用于孤儿检测)
     * <p>
     * 必须包含全部订单状态（含 5已退款/6退款中/7拒绝退款）：只要
     * tb_ticket_order 存在该用户该票的订单记录，Redis 已购标记就有主。
     * 退款(5)的 Redis 库存回补由退款链路自己执行（seckillRefundScript），
     * 若对账把已退款订单判为孤儿再 INCR 归还一次，就是双重归还导致超卖。
     * 对账只回收"从未形成订单"的真正孤儿。 */
    private static final List<Integer> VALID_ORDER_STATUSES = Arrays.asList(0, 1, 2, 3, 5, 6, 7);

    /** 预订单服务(查询/删除) */
    @Resource
    private IPreOrderService preOrderService;

    /** 票种订单服务(恢复库存/查询票种) */
    @Resource
    private ITicketOrderService ticketOrderService;

    /** 座位服务(释放预占座位) */
    @Resource
    private ISeatService seatService;

    /** 秒杀票服务(获取所有秒杀票列表) */
    @Resource
    private ISeckillTicketService seckillTicketService;

    /** Redis模板(分布式锁/缓存清理) */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 订单指标监控服务(可选) */
    @Resource
    private OrderMetricsService orderMetricsService;

    /** 总扫描数 */
    private long totalScannedCount = 0L;

    /** 总处理数 */
    private long totalProcessedCount = 0L;

    /** 总成功数 */
    private long totalSuccessCount = 0L;

    /** 总失败数 */
    private long totalFailedCount = 0L;

    /** 上次执行时间 */
    private LocalDateTime lastExecuteTime = null;

    /**
     * 定时扫描并处理过期预订单(每30秒)
     * <p>
     * 执行流程：
     * 1. 获取分布式锁(防止与MQ消费者并发)
     * 2. 查询过期预订单(status=0且expireTime<now-30s)
     * 3. 逐个设置处理标记并回滚
     * 4. 释放锁并记录统计
     */
    @Scheduled(fixedRate = PRE_ORDER_SCHEDULE_INTERVAL_MS)
    public void processExpiredPreOrders() {
        long startTime = System.currentTimeMillis();
        TaskStatistics stats = new TaskStatistics();

        try {
            if (!acquireCompensationLock()) {
                return;
            }

            log.info("[预订单补偿] 已获取分布式锁，开始扫描");

            List<PreOrder> expiredOrders = queryExpiredPreOrders();
            if (expiredOrders.isEmpty()) {
                log.info("[预订单补偿] 当前无过期预订单");
                return;
            }

            stats.scannedCount = expiredOrders.size();
            log.info("[预订单补偿] 发现{}个过期预订单", stats.scannedCount);

            for (PreOrder preOrder : expiredOrders) {
                processSingleExpiredOrder(preOrder, stats);
            }

        } catch (Exception e) {
            log.error("[预订单补偿] 执行异常:{}", e.getMessage(), e);
        } finally {
            releaseCompensationLock();
            updateStatistics(stats, startTime);
            logTaskSummary(stats, startTime);
        }
    }

    /**
     * 获取预订单补偿分布式锁
     *
     * @return true=获取成功, false=获取失败
     */
    private boolean acquireCompensationLock() {
        Boolean lockAcquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(COMPENSATION_LOCK_KEY,
                        String.valueOf(System.currentTimeMillis()),
                        LOCK_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS);

        if (Boolean.TRUE.equals(lockAcquired)) {
            return true;
        }

        log.debug("[预订单补偿] 未获取到分布式锁，跳过");
        return false;
    }

    /**
     * 释放预订单补偿分布式锁
     */
    private void releaseCompensationLock() {
        try {
            stringRedisTemplate.delete(COMPENSATION_LOCK_KEY);
            log.debug("[预订单补偿] 已释放分布式锁");
        } catch (Exception e) {
            log.warn("[预订单补偿] 释放锁异常:{}", e.getMessage());
        }
    }

    /**
     * 查询过期预订单列表
     *
     * @return 过期预订单列表(可能为空)
     */
    private List<PreOrder> queryExpiredPreOrders() {
        LocalDateTime bufferTime = LocalDateTime.now().minusSeconds(BUFFER_SECONDS);

        return preOrderService.list(
                new QueryWrapper<PreOrder>()
                        .eq("status", 0)
                        .lt("expire_time", bufferTime)
                        .last("LIMIT " + MAX_ORDERS_PER_RUN)
        );
    }

    /**
     * 处理单个过期预订单
     *
     * @param preOrder 过期预订单
     * @param stats   统计信息
     */
    private void processSingleExpiredOrder(PreOrder preOrder, TaskStatistics stats) {
        stats.processedCount++;
        String tempId = preOrder.getTempId();

        try {
            if (!markAsProcessing(tempId)) {
                log.info("[预订单补偿] 跳过正在处理 - tempId:{}", tempId);
                return;
            }

            boolean success = rollbackExpiredPreOrder(preOrder);
            clearProcessingMark(tempId);

            if (success) {
                stats.successCount++;
            } else {
                stats.failedCount++;
            }

        } catch (Exception e) {
            stats.failedCount++;
            clearProcessingMark(tempId);
            log.error("[预订单补偿] 处理异常 - tempId:{}, error:{}", tempId, e.getMessage(), e);
        }
    }

    /**
     * 标记预订单为处理中(防重复处理)
     *
     * @param tempId 预订单ID
     * @return true=标记成功, false=已被其他线程标记
     */
    private boolean markAsProcessing(String tempId) {
        String processingKey = PROCESSING_PREFIX_KEY + tempId;
        Boolean marked = stringRedisTemplate.opsForValue()
                .setIfAbsent(processingKey, "compensating", PROCESSING_MARK_TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(marked);
    }

    /**
     * 清理处理中标记
     *
     * @param tempId 预订单ID
     */
    private void clearProcessingMark(String tempId) {
        try {
            stringRedisTemplate.delete(PROCESSING_PREFIX_KEY + tempId);
        } catch (Exception e) {
            log.warn("[预订单补偿] 清理标记异常 - tempId:{}, error:{}", tempId, e.getMessage());
        }
    }

    /**
     * 回滚单个过期预订单
     * <p>
     * 回滚步骤：
     * 1. 释放选座(如果有)
     * 2. 恢复库存(普通票)/跳过(秒杀票)
     * 3. 删除预订单
     * 4. 清理Redis缓存
     *
     * @param preOrder 过期预订单
     * @return true=回滚成功, false=回滚失败
     */
    private boolean rollbackExpiredPreOrder(PreOrder preOrder) {
        String tempId = preOrder.getTempId();
        Long ticketId = preOrder.getTicketId();
        Integer quantity = preOrder.getQuantity() != null ? preOrder.getQuantity() : DEFAULT_QUANTITY;

        logInfoRollbackStart(preOrder);

        try {
            releaseSeatsIfExist(preOrder);
            restoreStockByTicketType(ticketId, quantity, tempId);
            deletePreOrder(tempId);
            clearRedisCache(tempId);

            logInfoRollbackSuccess(tempId, preOrder.getUserId(), ticketId);
            return true;

        } catch (Exception e) {
            log.error("[预订单补偿] 回滚异常 - tempId:{}, error:{}", tempId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 记录回滚开始日志
     */
    private void logInfoRollbackStart(PreOrder preOrder) {
        log.info("[预订单补偿-开始] tempId:{}, userId:{}, ticketId:{}, 数量:{}, 过期时间:{}",
                preOrder.getTempId(),
                preOrder.getUserId(),
                preOrder.getTicketId(),
                preOrder.getQuantity() != null ? preOrder.getQuantity() : DEFAULT_QUANTITY,
                preOrder.getExpireTime());
    }

    /**
     * 记录回滚成功日志
     */
    private void logInfoRollbackSuccess(String tempId, Long userId, Long ticketId) {
        log.info("[预订单补偿-成功] 预订单已完整回滚 - tempId:{}, userId:{}, ticketId:{}", tempId, userId, ticketId);
    }

    /**
     * 如果有选座则释放座位(批量优化)
     *
     * @param preOrder 预订单
     */
    private void releaseSeatsIfExist(PreOrder preOrder) {
        List<Long> seatIds = preOrder.getSeatIds();
        if (seatIds == null || seatIds.isEmpty()) {
            return;
        }

        String tempId = preOrder.getTempId();
        log.info("[预订单补偿] 释放{}个预占座位 - tempId:{}", seatIds.size(), tempId);

        int releasedCount = batchReleaseSeats(seatIds, tempId);
        log.info("[预订单补偿] 座位释放完成 - 成功:{}/{}，tempId:{}", releasedCount, seatIds.size(), tempId);
    }

    /**
     * 批量释放座位(性能优化：1次SQL替代N次)
     *
     * @param seatIds 座位ID列表
     * @param tempId  预订单ID
     * @return 成功释放数量
     */
    private int batchReleaseSeats(List<Long> seatIds, String tempId) {
        try {
            boolean updated = seatService.update()
                    .set("status", SEAT_STATUS_FREE)
                    .set("pre_order_id", null)
                    .in("id", seatIds)
                    .eq("pre_order_id", tempId)
                    .update();
            return updated ? seatIds.size() : 0;
        } catch (Exception e) {
            log.warn("[预订单补偿] 批量释放失败，降级为逐个释放 - error:{}", e.getMessage());
            return fallbackReleaseSeatsOneByOne(seatIds, tempId);
        }
    }

    /**
     * 降级：逐个释放座位
     *
     * @param seatIds 座位ID列表
     * @param tempId  预订单ID
     * @return 成功释放数量
     */
    private int fallbackReleaseSeatsOneByOne(List<Long> seatIds, String tempId) {
        int releasedCount = 0;
        for (Long seatId : seatIds) {
            try {
                boolean updated = seatService.update()
                        .set("status", SEAT_STATUS_FREE)
                        .set("pre_order_id", null)
                        .eq("id", seatId)
                        .eq("pre_order_id", tempId)
                        .update();
                if (updated) {
                    releasedCount++;
                }
            } catch (Exception e) {
                log.error("[预订单补偿] 座位释放异常 - seatId:{}, error:{}", seatId, e.getMessage());
            }
        }
        return releasedCount;
    }

    /**
     * 根据票种类型恢复库存
     * <p>
     * 普通票：恢复MySQL库存(+N)
     * 秒杀票：跳过(因为下单时未扣MySQL)
     *
     * @param ticketId 票种ID
     * @param quantity 购买数量
     * @param tempId  预订单ID
     */
    private void restoreStockByTicketType(Long ticketId, Integer quantity, String tempId) {
        Ticket ticket = ticketOrderService.getTicketById(ticketId);
        boolean isSeckill = ticket != null && TICKET_TYPE_SECKILL == ticket.getType();

        if (isSeckill) {
            log.info("[预订单补偿-秒杀票] 跳过MySQL库存恢复 - ticketId:{}, tempId:{}", ticketId, tempId);
            return;
        }

        restoreNormalStock(ticketId, quantity, tempId);
    }

    /**
     * 恢复普通票MySQL库存
     *
     * @param ticketId 票种ID
     * @param quantity 数量
     * @param tempId  预订单ID
     */
    private void restoreNormalStock(Long ticketId, Integer quantity, String tempId) {
        try {
            boolean restored = ticketOrderService.restoreStockForExpiredOrder(ticketId, quantity);
            if (restored) {
                log.info("[预订单补偿-普通票] MySQL库存已恢复+{} - ticketId:{}, tempId:{}", quantity, ticketId, tempId);
            } else {
                log.warn("[预订单补偿-普通票] MySQL库存恢复失败 - ticketId:{}, tempId:{}", ticketId, tempId);
            }
        } catch (Exception e) {
            log.error("[预订单补偿-普通票] 库存恢复异常 - ticketId:{}, tempId:{}, error:{}", ticketId, tempId, e.getMessage());
        }
    }

    /**
     * 删除预订单
     *
     * @param tempId 预订单ID
     * @throws RuntimeException 如果删除失败
     */
    private void deletePreOrder(String tempId) {
        boolean deleted = preOrderService.removeById(tempId);
        if (!deleted) {
            throw new RuntimeException("预订单删除失败 - tempId:" + tempId);
        }
    }

    /**
     * 清理Redis缓存
     *
     * @param tempId 预订单ID
     */
    private void clearRedisCache(String tempId) {
        try {
            String cacheKey = PENDING_PRE_ORDER_CACHE_PREFIX + tempId;
            stringRedisTemplate.delete(cacheKey);
            log.debug("[预订单补偿] Redis缓存已清理 - key:{}", cacheKey);
        } catch (Exception e) {
            log.warn("[预订单补偿] Redis缓存清理失败 - error:{}", e.getMessage());
        }
    }

    /**
     * 更新累计统计信息
     *
     * @param stats     本轮统计
     * @param startTime 开始时间
     */
    private void updateStatistics(TaskStatistics stats, long startTime) {
        lastExecuteTime = LocalDateTime.now();
        totalScannedCount += stats.scannedCount;
        totalProcessedCount += stats.processedCount;
        totalSuccessCount += stats.successCount;
        totalFailedCount += stats.failedCount;

        try {
            long duration = System.currentTimeMillis() - startTime;
            orderMetricsService.recordPreOrderCompensationStats(
                    stats.scannedCount,
                    stats.processedCount,
                    stats.successCount,
                    stats.failedCount,
                    duration
            );
        } catch (Exception ignored) {
        }
    }

    /**
     * 记录任务执行摘要日志
     *
     * @param stats     统计信息
     * @param startTime 开始时间
     */
    private void logTaskSummary(TaskStatistics stats, long startTime) {
        if (stats.processedCount > 0) {
            long costTime = System.currentTimeMillis() - startTime;
            log.info("[预订单补偿-本轮完成] 扫描:{}, 处理:{}, 成功:{}, 失败:{}, 耗时:{}ms",
                    stats.scannedCount, stats.processedCount, stats.successCount, stats.failedCount, costTime);

            double successRate = stats.processedCount > 0 ?
                    (double) stats.successCount / stats.processedCount * 100 : 0;
            log.info("[预订单补偿-累计统计] 总扫描:{}, 总处理:{}, 总成功:{}, 总失败:{}, 成功率:{:.1f}%",
                    totalScannedCount, totalProcessedCount, totalSuccessCount, totalFailedCount, successRate);
        }
    }

    /**
     * 秒杀Redis-MySQL对账(每2分钟)
     * <p>
     * 发现并回滚孤儿记录(Redis有但MySQL无的记录)
     */
    @Scheduled(fixedRate = RECONCILE_SCHEDULE_INTERVAL_MS)
    public void reconcileSeckillRedisOrphans() {
        long startTime = System.currentTimeMillis();

        try {
            if (!acquireReconcileLock()) {
                return;
            }

            List<SeckillTicket> seckillTickets = seckillTicketService.list();
            if (seckillTickets == null || seckillTickets.isEmpty()) {
                return;
            }

            ReconcileStats stats = new ReconcileStats();
            processAllSeckillTickets(seckillTickets, stats);
            logReconcileSummary(stats);

        } catch (Exception e) {
            log.error("[秒杀对账] 执行异常:{}", e.getMessage(), e);
        } finally {
            releaseReconcileLock();
            recordReconcileMetrics(startTime);
        }
    }

    /**
     * 获取对账分布式锁
     */
    private boolean acquireReconcileLock() {
        Boolean lockAcquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(RECONCILE_LOCK_KEY,
                        String.valueOf(System.currentTimeMillis()),
                        RECONCILE_LOCK_SECONDS,
                        TimeUnit.SECONDS);

        if (Boolean.TRUE.equals(lockAcquired)) {
            return true;
        }

        log.debug("[秒杀对账] 未获取到分布式锁，跳过");
        return false;
    }

    /**
     * 释放对账分布式锁
     */
    private void releaseReconcileLock() {
        try {
            stringRedisTemplate.delete(RECONCILE_LOCK_KEY);
        } catch (Exception e) {
            log.warn("[秒杀对账] 释放锁异常 - error:{}", e.getMessage());
        }
    }

    /**
     * 处理所有秒杀票的对账
     *
     * @param seckillTickets 秒杀票列表
     * @param stats         统计信息
     */
    private void processAllSeckillTickets(List<SeckillTicket> seckillTickets, ReconcileStats stats) {
        for (SeckillTicket st : seckillTickets) {
            if (stats.totalOrphansFound >= MAX_ORPHANS_PER_RUN) {
                log.warn("[秒杀对账] 已达上限{}，剩余下轮处理", MAX_ORPHANS_PER_RUN);
                break;
            }

            processSingleSeckillTicket(st, stats);
        }
    }

    /**
     * 处理单个秒杀票的对账
     *
     * @param st    秒杀票
     * @param stats 统计信息
     */
    private void processSingleSeckillTicket(SeckillTicket st, ReconcileStats stats) {
        Long ticketId = st.getTicketId();
        Set<String> userIdsInRedis = queryUserIdsFromRedis(ticketId);

        if (userIdsInRedis == null || userIdsInRedis.isEmpty()) {
            return;
        }

        for (String userIdStr : userIdsInRedis) {
            if (stats.totalOrphansFound >= MAX_ORPHANS_PER_RUN) {
                break;
            }

            processSingleUserRecord(ticketId, userIdStr, stats);
        }
    }

    /**
     * 从Redis查询已购用户集合
     *
     * @param ticketId 票种ID
     * @return 用户ID字符串集合
     */
    private Set<String> queryUserIdsFromRedis(Long ticketId) {
        try {
            String orderKey = RedisConstants.SECKILL_ORDER_KEY + ticketId;
            return stringRedisTemplate.opsForSet().members(orderKey);
        } catch (Exception e) {
            log.error("[秒杀对账] 读取Redis失败 - ticketId:{}, error:{}", ticketId, e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * 处理单个用户的记录(检测是否为孤儿记录)
     *
     * @param ticketId  票种ID
     * @param userIdStr 用户ID(字符串)
     * @param stats    统计信息
     */
    private void processSingleUserRecord(Long ticketId, String userIdStr, ReconcileStats stats) {
        Long userId = parseUserId(userIdStr, ticketId);
        if (userId == null) {
            return;
        }

        if (isWithinGracePeriod(ticketId, userId)) {
            return;
        }

        if (hasValidPreOrder(userId, ticketId)) {
            return;
        }

        if (hasValidTicketOrder(userId, ticketId)) {
            return;
        }

        stats.totalOrphansFound++;
        log.warn("[秒杀对账] 发现孤儿记录 - ticketId:{}, userId:{}", ticketId, userId);
        rollbackOrphanRecord(ticketId, userIdStr, userId, stats);
    }

    /**
     * 解析用户ID
     *
     * @param userIdStr 用户ID字符串
     * @param ticketId  票种ID(用于日志)
     * @return 用户ID，解析失败返回null
     */
    private Long parseUserId(String userIdStr, Long ticketId) {
        try {
            return Long.valueOf(userIdStr);
        } catch (NumberFormatException e) {
            log.warn("[秒杀对账] 非法userId - ticketId:{}, value:{}", ticketId, userIdStr);
            return null;
        }
    }

    /**
     * 检查是否在宽限期内(防误杀)
     *
     * @param ticketId 票种ID
     * @param userId  用户ID
     * @return true=在宽限期内(应跳过), false=已过宽限期
     */
    private boolean isWithinGracePeriod(Long ticketId, Long userId) {
        try {
            String graceKey = GRACE_KEY_PREFIX + ticketId + ":" + userId;
            Boolean exists = stringRedisTemplate.hasKey(graceKey);
            if (Boolean.TRUE.equals(exists)) {
                log.debug("[秒杀对账] grace key存在，跳过 - ticketId:{}, userId:{}", ticketId, userId);
                return true;
            }
        } catch (Exception e) {
            log.warn("[秒杀对账] 检查grace key异常 - ticketId:{}, userId:{}, error:{}", ticketId, userId, e.getMessage());
        }
        return false;
    }

    /**
     * 检查是否存在有效的预订单
     *
     * @param userId   用户ID
     * @param ticketId 票种ID
     * @return true=存在有效预订单
     */
    private boolean hasValidPreOrder(Long userId, Long ticketId) {
        return preOrderService.count(
                new QueryWrapper<PreOrder>()
                        .eq("user_id", userId)
                        .eq("ticket_id", ticketId)
                        .eq("status", 0)
        ) > 0;
    }

    /**
     * 检查是否存在有效的正式订单
     *
     * @param userId   用户ID
     * @param ticketId 票种ID
     * @return true=存在有效订单
     */
    private boolean hasValidTicketOrder(Long userId, Long ticketId) {
        return ticketOrderService.count(
                new QueryWrapper<TicketOrder>()
                        .eq("user_id", userId)
                        .eq("ticket_id", ticketId)
                        .in("status", VALID_ORDER_STATUSES)
        ) > 0;
    }

    /**
     * 回滚孤儿记录(Lua原子操作)
     *
     * @param ticketId  票种ID
     * @param userIdStr 用户ID字符串
     * @param userId    用户ID
     * @param stats    统计信息
     */
    private void rollbackOrphanRecord(Long ticketId, String userIdStr, Long userId, ReconcileStats stats) {
        try {
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + ticketId;
            String orderKey = RedisConstants.SECKILL_ORDER_KEY + ticketId;

            String luaScript =
                    "local stock = redis.call('INCRBY', KEYS[1], ARGV[1]) " +
                            "redis.call('SREM', KEYS[2], ARGV[2]) " +
                            "return stock";

            Long newStock = stringRedisTemplate.execute(
                    new DefaultRedisScript<>(luaScript, Long.class),
                    Arrays.asList(stockKey, orderKey),
                    "1", userIdStr
            );

            stats.totalOrphansRolledBack++;
            log.info("[秒杀对账] 孤儿已回滚 - ticketId:{}, userId:{}, 新库存:{}", ticketId, userId, newStock);
        } catch (Exception e) {
            log.error("[秒杀对账] 回滚失败 - ticketId:{}, userId:{}, error:{}", ticketId, userId, e.getMessage(), e);
        }
    }

    /**
     * 记录对账摘要日志
     *
     * @param stats 统计信息
     */
    private void logReconcileSummary(ReconcileStats stats) {
        if (stats.totalOrphansFound > 0) {
            log.info("[秒杀对账-完成] 发现孤儿:{}, 回滚成功:{}",
                    stats.totalOrphansFound, stats.totalOrphansRolledBack);
        } else {
            log.debug("[秒杀对账] 未发现孤儿，数据一致");
        }
    }

    /**
     * 记录对账监控指标
     *
     * @param startTime 开始时间
     */
    private void recordReconcileMetrics(long startTime) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            orderMetricsService.recordSeckillReconcileDuration(duration);
        } catch (Exception ignored) {
        }
    }

    /**
     * 获取调度器运行状态(用于监控接口)
     *
     * @return 状态统计Map
     */
    public Map<String, Object> getSchedulerStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("totalScannedCount", totalScannedCount);
        status.put("totalProcessedCount", totalProcessedCount);
        status.put("totalSuccessCount", totalSuccessCount);
        status.put("totalFailedCount", totalFailedCount);
        status.put("lastExecuteTime", lastExecuteTime != null ? lastExecuteTime.toString() : "从未执行");
        status.put("maxOrdersPerRun", MAX_ORDERS_PER_RUN);

        double successRate = totalProcessedCount > 0 ?
                Math.round((double) totalSuccessCount / totalProcessedCount * 10000) / 100.0 : 0;
        status.put("successRate", successRate);

        return status;
    }

    /**
     * 手动触发补偿任务(用于管理接口)
     *
     * @return 执行结果Map
     */
    public Map<String, Object> manualTrigger() {
        Map<String, Object> result = new HashMap<>();
        result.put("triggerTime", LocalDateTime.now().toString());
        result.put("taskType", "PRE_ORDER_TIMEOUT_COMPENSATE");

        long startTime = System.currentTimeMillis();

        try {
            processExpiredPreOrders();
            result.put("status", "SUCCESS");
            result.put("message", "手动触发成功");
        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("message", "执行异常：" + e.getMessage());
            log.error("[预订单补偿-手动触发失败] error:{}", e.getMessage(), e);
        }

        result.put("costTime", System.currentTimeMillis() - startTime);
        result.put("statistics", getSchedulerStatus());

        return result;
    }

    /**
     * 任务统计信息(内部使用)
     */
    private static class TaskStatistics {
        int scannedCount = 0;
        int processedCount = 0;
        int successCount = 0;
        int failedCount = 0;
    }

    /**
     * 对账统计信息(内部使用)
     */
    private static class ReconcileStats {
        int totalOrphansFound = 0;
        int totalOrphansRolledBack = 0;
    }
}
