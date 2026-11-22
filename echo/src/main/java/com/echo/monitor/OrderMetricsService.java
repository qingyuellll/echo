package com.echo.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 订单业务指标监控服务(Prometheus集成)
 * <p>
 * 采集订单相关业务指标(库存/秒杀/支付/积分/耗时)，通过 Micrometer 暴露给 Prometheus 抓取，
 * 支持 Tag 维度分析和 Grafana 可视化监控告警
 *
 * @author tulip
 */
@Slf4j
@Service
public class OrderMetricsService {

    /** Micrometer 指标注册中心 */
    private final MeterRegistry meterRegistry;

    /** 库存扣减失败计数器(带reason维度) */
    private static final String STOCK_REDUCE_FAILED_METRIC = "order_stock_reduce_failed_total";

    /** 秒杀成功计数器 */
    private Counter seckillSuccessCounter;

    /** 秒杀失败计数器(带reason维度) */
    private static final String SECKILL_FAIL_METRIC = "order_seckill_fail_total";

    /** 支付成功计数器 */
    private Counter paymentSuccessCounter;

    /** 支付失败计数器(带reason维度) */
    private static final String PAYMENT_FAIL_METRIC = "order_payment_fail_total";

    /** 积分抵扣被拒绝计数器(超过50%上限) */
    private Counter pointsDiscountRejectedCounter;

    /** 当前待支付订单数量(Gauge类型) */
    private AtomicInteger currentPendingOrders;

    /** 订单创建耗时计时器(Histogram) */
    private Timer orderCreateTimer;

    /** 支付处理耗时计时器(Histogram) */
    private Timer paymentProcessTimer;

    /** 秒杀处理耗时计时器(Histogram) */
    private Timer seckillProcessTimer;

    /** 缓存失效处理耗时计时器(Histogram) */
    private static final String CACHE_INVALIDATION_TIME_METRIC = "cache_invalidation_duration";

    /** 缓存失效处理失败计数器(带reason维度) */
    private static final String CACHE_INVALIDATION_FAIL_METRIC = "cache_invalidation_fail_total";

    // 初始化构造器
    public OrderMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // 初始化所有监控指标
    @PostConstruct
    void init() {
        log.info("[指标服务] 初始化Prometheus监控指标");

        this.seckillSuccessCounter = Counter.builder("order_seckill_success_total")
                .description("秒杀成功总次数")
                .tag("module", "seckill")
                .register(meterRegistry);

        this.paymentSuccessCounter = Counter.builder("order_payment_success_total")
                .description("支付成功总次数")
                .tag("module", "payment")
                .register(meterRegistry);

        this.pointsDiscountRejectedCounter = Counter.builder("order_points_discount_rejected_total")
                .description("积分抵扣被拒绝总次数(超过50%上限)")
                .tag("module", "payment")
                .tag("reason", "exceed_50_percent_limit")
                .register(meterRegistry);

        this.currentPendingOrders = meterRegistry.gauge("order_pending_current", new AtomicInteger(0));
        if (this.currentPendingOrders == null) {
            this.currentPendingOrders = new AtomicInteger(0);
            meterRegistry.gauge("order_pending_current", this.currentPendingOrders);
        }

        this.orderCreateTimer = Timer.builder("order_create_duration")
                .description("订单创建耗时")
                .tag("module", "ticket-order")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        this.paymentProcessTimer = Timer.builder("payment_process_duration")
                .description("支付处理耗时")
                .tag("module", "payment")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        this.seckillProcessTimer = Timer.builder("seckill_process_duration")
                .description("秒杀处理耗时")
                .tag("module", "seckill")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        log.info("[指标服务] 监控指标初始化完成 - 共注册3个Counter + 3个Timer + 1个Gauge");
    }

    /**
     * 记录库存扣减失败(带reason维度)
     *
     * @param ticketId 票种ID
     * @param reason 失败原因(库存不足/并发冲突/其他)
     */
    public void recordStockReduceFailed(Long ticketId, String reason) {
        Counter counter = meterRegistry.counter(STOCK_REDUCE_FAILED_METRIC,
                Tags.of("module", "ticket-order", "reason", reason));
        counter.increment();
        log.warn("[指标] 库存扣减失败 +1，ticketId:{}, reason:{}, 累计:{}",
                ticketId, reason, Math.round(counter.count()));
    }

    /**
     * 记录库存扣减失败(兼容旧接口，reason默认为unknown)
     *
     * @param ticketId 票种ID
     */
    public void recordStockReduceFailed(Long ticketId) {
        recordStockReduceFailed(ticketId, "unknown");
    }

    /**
     * 记录秒杀成功
     *
     * @param ticketId 票种ID
     * @param userId 用户ID
     */
    public void recordSeckillSuccess(Long ticketId, Long userId) {
        seckillSuccessCounter.increment();
        log.debug("[指标] 秒杀成功 +1，ticketId:{}, userId:{}", ticketId, userId);
    }

    /**
     * 记录秒杀失败(带reason维度)
     *
     * @param reason 失败原因(库存不足/重复下单/时间窗口外/风控拦截)
     */
    public void recordSeckillFail(String reason) {
        Counter counter = meterRegistry.counter(SECKILL_FAIL_METRIC,
                Tags.of("module", "seckill", "reason", reason));
        counter.increment();
        log.debug("[指标] 秒杀失败 +1，reason:{}, 累计:{}", reason, Math.round(counter.count()));
    }

    /**
     * 记录秒杀失败(兼容旧接口，reason默认为unknown)
     */
    public void recordSeckillFail() {
        recordSeckillFail("unknown");
    }

    /**
     * 记录支付成功
     *
     * @param tempId 预订单ID
     */
    public void recordPaymentSuccess(String tempId) {
        paymentSuccessCounter.increment();
        log.info("[指标] 支付成功 +1，tempId:{}", tempId);
    }

    /**
     * 记录支付失败(带reason维度)
     *
     * @param tempId 预订单ID
     * @param reason 失败原因(余额不足/订单已取消/超时/系统异常)
     */
    public void recordPaymentFail(String tempId, String reason) {
        Counter counter = meterRegistry.counter(PAYMENT_FAIL_METRIC,
                Tags.of("module", "payment", "reason", reason));
        counter.increment();
        log.warn("[指标] 支付失败 +1，tempId:{}, reason:{}, 累计:{}",
                tempId, reason, Math.round(counter.count()));
    }

    /**
     * 记录支付失败(兼容旧接口，reason默认为unknown)
     *
     * @param tempId 预订单ID
     */
    public void recordPaymentFail(String tempId) {
        recordPaymentFail(tempId, "unknown");
    }

    /**
     * 记录积分抵扣被拒绝(超过50%上限)
     *
     * @param userId 用户ID
     * @param requestedDiscount 请求抵扣金额(分)
     * @param maxDiscount 最大允许抵扣金额(分)
     */
    public void recordPointsDiscountRejected(Long userId, long requestedDiscount, long maxDiscount) {
        pointsDiscountRejectedCounter.increment();
        log.warn("[指标] 积分抵扣被拒 +1，userId:{}, 请求:{}元, 上限:{}元",
                userId, requestedDiscount / 100.0, maxDiscount / 100.0);
    }

    /**
     * 更新当前待支付订单数量
     *
     * @param delta 变化量(+1创建订单，-1支付/取消/超时)
     */
    public void updatePendingOrderCount(int delta) {
        int newValue = currentPendingOrders.addAndGet(delta);
        log.debug("[指标] 待支付订单数量更新: {} (delta:{})", newValue, delta);
    }

    /**
     * 获取当前待支付订单数量
     *
     * @return 当前待支付订单数
     */
    public int getPendingOrderCount() {
        return currentPendingOrders.get();
    }

    /**
     * 记录订单创建耗时(Histogram)
     *
     * @param durationMs 耗时(毫秒)
     */
    public void recordOrderCreateTime(long durationMs) {
        orderCreateTimer.record(durationMs, TimeUnit.MILLISECONDS);
        log.debug("[指标] 订单创建耗时: {}ms", durationMs);
    }

    /**
     * 记录订单创建耗时(使用Runnable包装)
     *
     * action 要执行的业务逻辑
     */
    public void recordOrderCreate(Runnable action) {
        orderCreateTimer.record(action);
    }

    /**
     * 记录支付处理耗时(Histogram)
     *
     * @param durationMs 耗时(毫秒)
     */
    public void recordPaymentProcessTime(long durationMs) {
        paymentProcessTimer.record(durationMs, TimeUnit.MILLISECONDS);
        log.debug("[指标] 支付处理耗时: {}ms", durationMs);
    }

    /**
     * 记录支付处理耗时(使用Runnable包装)
     *
     * action 要执行的业务逻辑
     */
    public void recordPaymentProcess(Runnable action) {
        paymentProcessTimer.record(action);
    }

    /**
     * 记录秒杀处理耗时(Histogram)
     *
     * @param durationMs 耗时(毫秒)
     */
    public void recordSeckillProcessTime(long durationMs) {
        seckillProcessTimer.record(durationMs, TimeUnit.MILLISECONDS);
        log.debug("[指标] 秒杀处理耗时: {}ms", durationMs);
    }

    /**
     * 记录秒杀处理耗时(使用Runnable包装)
     *
     * action 要执行的业务逻辑
     */
    public void recordSeckillProcess(Runnable action) {
        seckillProcessTimer.record(action);
    }

    /**
     * 获取各指标当前值汇总
     *
     * @return 指标汇总文本
     */
    public String getMetricsSummary() {
        double stockReduceFailedCount = 0;
        double seckillFailCount = 0;
        double paymentFailCount = 0;

        try {
            stockReduceFailedCount = meterRegistry.get(STOCK_REDUCE_FAILED_METRIC).counter().count();
        } catch (Exception ignored) {}

        try {
            seckillFailCount = meterRegistry.get(SECKILL_FAIL_METRIC).counter().count();
        } catch (Exception ignored) {}

        try {
            paymentFailCount = meterRegistry.get(PAYMENT_FAIL_METRIC).counter().count();
        } catch (Exception ignored) {}

        return String.format(
                "【订单指标汇总】" +
                        "库存扣减失败:%.0f, " +
                        "秒杀成功:%d, " +
                        "秒杀失败:%.0f, " +
                        "支付成功:%d, " +
                        "支付失败:%.0f, " +
                        "积分抵扣拒绝:%d, " +
                        "当前待支付:%d, " +
                        "订单创建P99:%.0fms, " +
                        "支付P99:%.0fms, " +
                        "秒杀P99:%.0fms",
                stockReduceFailedCount,
                Math.round(seckillSuccessCounter.count()),
                seckillFailCount,
                Math.round(paymentSuccessCounter.count()),
                paymentFailCount,
                Math.round(pointsDiscountRejectedCounter.count()),
                currentPendingOrders.get(),
                orderCreateTimer.percentile(0.99, TimeUnit.MILLISECONDS),
                paymentProcessTimer.percentile(0.99, TimeUnit.MILLISECONDS),
                seckillProcessTimer.percentile(0.99, TimeUnit.MILLISECONDS)
        );
    }

    /**
     * 记录缓存失效处理耗时
     *
     * @param durationMs 处理耗时(毫秒)
     */
    public void recordCacheInvalidationTime(long durationMs) {
        Timer.builder(CACHE_INVALIDATION_TIME_METRIC)
                .description("缓存失效处理耗时")
                .tag("module", "cache-invalidation")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录缓存失效处理失败
     *
     * @param reason 失败原因
     * @param durationMs 处理耗时(毫秒)
     */
    public void recordCacheInvalidationFail(String reason, long durationMs) {
        Counter counter = meterRegistry.counter(CACHE_INVALIDATION_FAIL_METRIC,
                Tags.of("module", "cache-invalidation", "reason", reason));
        counter.increment();
        log.warn("[指标] 缓存失效处理失败 - reason:{}, 耗时:{}ms", reason, durationMs);
    }

    /** 通知处理成功计数器(带type维度) */
    private static final String NOTIFICATION_SUCCESS_METRIC = "notification_success_total";

    /** 通知处理失败计数器(带type+reason维度) */
    private static final String NOTIFICATION_FAIL_METRIC = "notification_fail_total";

    /** 通知处理耗时计时器(Histogram) */
    private static final String NOTIFICATION_TIME_METRIC = "notification_duration";

    /**
     * 记录通知处理成功
     *
     * @param type 通知类型
     * @param durationMs 处理耗时(毫秒)
     * @param notificationId 通知ID
     */
    public void recordNotificationSuccess(Object type, long durationMs, Long notificationId) {
        Counter counter = meterRegistry.counter(NOTIFICATION_SUCCESS_METRIC,
                Tags.of("module", "notification", "type", String.valueOf(type)));
        counter.increment();

        Timer.builder(NOTIFICATION_TIME_METRIC)
                .description("通知处理耗时")
                .tag("module", "notification")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        log.debug("[指标] 通知处理成功 - type:{}, id:{}, 耗时:{}ms", type, notificationId, durationMs);
    }

    /**
     * 记录通知处理失败
     *
     * @param type 通知类型
     * @param reason 失败原因
     * @param durationMs 处理耗时(毫秒)
     */
    public void recordNotificationFail(Object type, String reason, long durationMs) {
        Counter counter = meterRegistry.counter(NOTIFICATION_FAIL_METRIC,
                Tags.of("module", "notification", "type", String.valueOf(type), "reason", reason));
        counter.increment();
        log.warn("[指标] 通知处理失败 - type:{}, reason:{}, 耗时:{}ms", type, reason, durationMs);
    }

    /** 订单超时处理成功计数器(带result维度) */
    private static final String ORDER_TIMEOUT_SUCCESS_METRIC = "order_timeout_success_total";

    /** 订单超时处理失败计数器(带reason维度) */
    private static final String ORDER_TIMEOUT_FAIL_METRIC = "order_timeout_fail_total";

    /** 订单超时处理耗时计时器(Histogram) */
    private static final String ORDER_TIMEOUT_TIME_METRIC = "order_timeout_duration";

    /**
     * 记录订单超时处理成功
     *
     * @param preOrderId 预订单ID
     * @param result 结果描述(成功/幂等拦截/订单不存在)
     * @param durationMs 处理耗时(毫秒)
     */
    public void recordOrderTimeoutSuccess(String preOrderId, String result, long durationMs) {
        Counter counter = meterRegistry.counter(ORDER_TIMEOUT_SUCCESS_METRIC,
                Tags.of("module", "order-timeout", "result", result));
        counter.increment();

        Timer.builder(ORDER_TIMEOUT_TIME_METRIC)
                .description("订单超时处理耗时")
                .tag("module", "order-timeout")
                .tag("result", result)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        log.debug("[指标] 订单超时处理成功 - preOrderId:{}, result:{}, 耗时:{}ms", preOrderId, result, durationMs);
    }

    /**
     * 记录订单超时处理失败
     *
     * @param preOrderId 预订单ID
     * @param reason 失败原因
     * @param durationMs 处理耗时(毫秒)
     */
    public void recordOrderTimeoutFail(String preOrderId, String reason, long durationMs) {
        Counter counter = meterRegistry.counter(ORDER_TIMEOUT_FAIL_METRIC,
                Tags.of("module", "order-timeout", "reason", reason));
        counter.increment();
        log.warn("[指标] 订单超时处理失败 - preOrderId:{}, reason:{}, 耗时:{}ms", preOrderId, reason, durationMs);
    }

    /** 秒杀订单消费成功计数器(带result维度) */
    private static final String SECKILL_ORDER_SUCCESS_METRIC = "seckill_order_success_total";

    /** 秒杀订单消费失败计数器(带reason维度) */
    private static final String SECKILL_ORDER_FAIL_METRIC = "seckill_order_fail_total";

    /** 秒杀订单消费耗时计时器(Histogram) */
    private static final String SECKILL_ORDER_TIME_METRIC = "seckill_order_duration";

    /**
     * 记录秒杀订单消费成功
     *
     * @param tempId 预订单ID
     * @param result 结果描述(确认有效/订单不存在)
     * @param durationMs 处理耗时(毫秒)
     */
    public void recordSeckillOrderSuccess(String tempId, String result, long durationMs) {
        Counter counter = meterRegistry.counter(SECKILL_ORDER_SUCCESS_METRIC,
                Tags.of("module", "seckill-order", "result", result));
        counter.increment();

        Timer.builder(SECKILL_ORDER_TIME_METRIC)
                .description("秒杀订单消费耗时")
                .tag("module", "seckill-order")
                .tag("result", result)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        log.debug("[指标] 秒杀订单消费成功 - tempId:{}, result:{}, 耗时:{}ms", tempId, result, durationMs);
    }

    /**
     * 记录秒杀订单消费失败
     *
     * @param tempId 预订单ID
     * @param reason 失败原因
     * @param durationMs 处理耗时(毫秒)
     */
    public void recordSeckillOrderFail(String tempId, String reason, long durationMs) {
        Counter counter = meterRegistry.counter(SECKILL_ORDER_FAIL_METRIC,
                Tags.of("module", "seckill-order", "reason", reason));
        counter.increment();
        log.warn("[指标] 秒杀订单消费失败 - tempId:{}, reason:{}, 耗时:{}ms", tempId, reason, durationMs);
    }

    /** 状态转换成功计数器(带transition维度) */
    private static final String STATE_TRANSITION_SUCCESS_METRIC = "state_transition_success_total";

    /** 状态转换失败计数器(带transition和reason维度) */
    private static final String STATE_TRANSITION_FAIL_METRIC = "state_transition_fail_total";

    /** 状态转换耗时计时器(Histogram) */
    private static final String STATE_TRANSITION_TIME_METRIC = "state_transition_duration";

    /**
     * 记录状态转换成功
     *
     * @param orderId 订单ID
     * @param transition 状态转换描述(如"0→2")
     * @param durationMs 处理耗时(毫秒)
     */
    public void recordStateTransitionSuccess(String orderId, String transition, long durationMs) {
        Counter counter = meterRegistry.counter(STATE_TRANSITION_SUCCESS_METRIC,
                Tags.of("module", "state-machine", "transition", transition));
        counter.increment();

        Timer.builder(STATE_TRANSITION_TIME_METRIC)
                .description("状态转换耗时")
                .tag("module", "state-machine")
                .tag("transition", transition)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        log.debug("[指标] 状态转换成功 - orderId:{}, transition:{}, 耗时:{}ms", orderId, transition, durationMs);
    }

    /**
     * 记录状态转换失败
     *
     * @param orderId 订单ID
     * @param transition 状态转换描述(如"0→2")
     * @param reason 失败原因
     * @param durationMs 处理耗时(毫秒)
     */
    public void recordStateTransitionFail(String orderId, String transition, String reason, long durationMs) {
        Counter counter = meterRegistry.counter(STATE_TRANSITION_FAIL_METRIC,
                Tags.of("module", "state-machine", "transition", transition, "reason", reason));
        counter.increment();
        log.warn("[指标] 状态转换失败 - orderId:{}, transition:{}, reason:{}, 耗时:{}ms",
                orderId, transition, reason, durationMs);
    }

    /** 预订单补偿扫描数计数器(Gauge) */
    private static final String PRE_ORDER_COMPENSATION_SCANNED_METRIC = "pre_order_compensation_scanned_total";

    /** 预订单补偿处理成功计数器 */
    private static final String PRE_ORDER_COMPENSATION_SUCCESS_METRIC = "pre_order_compensation_success_total";

    /** 预订单补偿处理失败计数器(带reason维度) */
    private static final String PRE_ORDER_COMPENSATION_FAIL_METRIC = "pre_order_compensation_fail_total";

    /** 预订单补偿处理耗时计时器(Histogram) */
    private static final String PRE_ORDER_COMPENSATION_TIME_METRIC = "pre_order_compensation_duration";

    /**
     * 记录预订单补偿任务统计
     *
     * @param scannedCount 扫描数量
     * @param processedCount 处理数量
     * @param successCount 成功数量
     * @param failedCount 失败数量
     * @param durationMs 耗时(毫秒)
     */
    public void recordPreOrderCompensationStats(int scannedCount, int processedCount,
                                                int successCount, int failedCount, long durationMs) {
        meterRegistry.gauge(PRE_ORDER_COMPENSATION_SCANNED_METRIC, Tags.of("module", "compensation"), (double) scannedCount);

        if (successCount > 0) {
            Counter successCounter = meterRegistry.counter(PRE_ORDER_COMPENSATION_SUCCESS_METRIC,
                    Tags.of("module", "compensation"));
            successCounter.increment(successCount);
        }

        if (failedCount > 0) {
            Counter failCounter = meterRegistry.counter(PRE_ORDER_COMPENSATION_FAIL_METRIC,
                    Tags.of("module", "compensation", "reason", "rollback_failed"));
            failCounter.increment(failedCount);
        }

        Timer.builder(PRE_ORDER_COMPENSATION_TIME_METRIC)
                .description("预订单补偿处理耗时")
                .tag("module", "compensation")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        log.info("[指标] 预订单补偿统计 - 扫描:{}, 处理:{}, 成功:{}, 失败:{}, 耗时:{}ms",
                scannedCount, processedCount, successCount, failedCount, durationMs);
    }

    /** 秒杀Redis-MySQL对账发现孤儿记录计数器 */
    private static final String SECKILL_RECONCILE_ORPHANS_FOUND_METRIC = "seckill_reconcile_orphans_found_total";

    /** 秒杀Redis-MySQL对账回滚成功计数器 */
    private static final String SECKILL_RECONCILE_ROLLED_BACK_METRIC = "seckill_reconcile_rolled_back_total";

    /** 秒杀Redis-MySQL对账耗时计时器(Histogram) */
    private static final String SECKILL_RECONCILE_TIME_METRIC = "seckill_reconcile_duration";

    /**
     * 记录秒杀Redis-MySQL对账耗时
     *
     * @param durationMs 对账耗时(毫秒)
     */
    public void recordSeckillReconcileDuration(long durationMs) {
        Timer.builder(SECKILL_RECONCILE_TIME_METRIC)
                .description("秒杀Redis-MySQL对账耗时")
                .tag("module", "reconcile")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        log.debug("[指标] 秒杀对账耗时: {}ms", durationMs);
    }

    /**
     * 记录秒杀Redis-MySQL对账发现孤儿记录
     *
     * @param orphansFound 发现的孤儿记录数
     * @param orphansRolledBack 回滚成功的数量
     * @param durationMs 对账耗时(毫秒)
     */
    public void recordSeckillReconcileStats(int orphansFound, int orphansRolledBack, long durationMs) {
        if (orphansFound > 0) {
            Counter foundCounter = meterRegistry.counter(SECKILL_RECONCILE_ORPHANS_FOUND_METRIC,
                    Tags.of("module", "reconcile"));
            foundCounter.increment(orphansFound);
        }

        if (orphansRolledBack > 0) {
            Counter rolledBackCounter = meterRegistry.counter(SECKILL_RECONCILE_ROLLED_BACK_METRIC,
                    Tags.of("module", "reconcile"));
            rolledBackCounter.increment(orphansRolledBack);
        }

        Timer.builder(SECKILL_RECONCILE_TIME_METRIC)
                .description("秒杀Redis-MySQL对账耗时")
                .tag("module", "reconcile")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        log.info("[指标] 秒杀对账统计 - 孤儿:{}, 回滚:{}, 耗时:{}ms",
                orphansFound, orphansRolledBack, durationMs);
    }

    /** Redis补偿任务处理成功计数器 */
    private static final String REDIS_COMPENSATION_SUCCESS_METRIC = "redis_compensation_success_total";

    /** Redis补偿任务处理失败计数器(带reason维度) */
    private static final String REDIS_COMPENSATION_FAIL_METRIC = "redis_compensation_fail_total";

    /** Redis补偿任务跳过计数器 */
    private static final String REDIS_COMPENSATION_SKIPPED_METRIC = "redis_compensation_skipped_total";

    /** Redis补偿任务处理耗时计时器(Histogram) */
    private static final String REDIS_COMPENSATION_TIME_METRIC = "redis_compensation_duration";

    /**
     * 记录Redis补偿任务统计
     *
     * @param processedCount 处理数量
     * @param successCount 成功数量
     * @param failedCount 失败数量
     * @param skippedCount 跳过数量
     * @param durationMs 耗时(毫秒)
     */
    public void recordRedisCompensationStats(int processedCount, int successCount,
                                             int failedCount, int skippedCount, long durationMs) {
        if (successCount > 0) {
            Counter successCounter = meterRegistry.counter(REDIS_COMPENSATION_SUCCESS_METRIC,
                    Tags.of("module", "redis-compensation"));
            successCounter.increment(successCount);
        }

        if (failedCount > 0) {
            Counter failCounter = meterRegistry.counter(REDIS_COMPENSATION_FAIL_METRIC,
                    Tags.of("module", "redis-compensation", "reason", "execution_failed"));
            failCounter.increment(failedCount);
        }

        if (skippedCount > 0) {
            Counter skippedCounter = meterRegistry.counter(REDIS_COMPENSATION_SKIPPED_METRIC,
                    Tags.of("module", "redis-compensation", "reason", "not_ready_or_processing"));
            skippedCounter.increment(skippedCount);
        }

        Timer.builder(REDIS_COMPENSATION_TIME_METRIC)
                .description("Redis补偿任务处理耗时")
                .tag("module", "redis-compensation")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        log.info("[指标] Redis补偿统计 - 处理:{}, 成功:{}, 失败:{}, 跳过:{}, 耗时:{}ms",
                processedCount, successCount, failedCount, skippedCount, durationMs);
    }

    /** 座位清理成功计数器 */
    private static final String SEAT_CLEANUP_SUCCESS_METRIC = "seat_cleanup_success_total";

    /** 座位清理跳过计数器(锁仍有效) */
    private static final String SEAT_CLEANUP_SKIPPED_METRIC = "seat_cleanup_skipped_total";

    /** 座位清理失败计数器(CAS校验未通过) */
    private static final String SEAT_CLEANUP_FAIL_METRIC = "seat_cleanup_fail_total";

    /** 座位清理任务处理耗时计时器(Histogram) */
    private static final String SEAT_CLEANUP_TIME_METRIC = "seat_cleanup_duration";

    /**
     * 记录座位清理任务统计
     *
     * @param successCount 成功释放数量
     * @param skippedCount 跳过数量(锁仍有效)
     * @param failCount    失败数量(CAS未通过)
     * @param durationMs   耗时(毫秒)
     */
    public void recordSeatCleanupStats(int successCount, int skippedCount,
                                       int failCount, long durationMs) {
        if (successCount > 0) {
            Counter successCounter = meterRegistry.counter(SEAT_CLEANUP_SUCCESS_METRIC,
                    Tags.of("module", "seat-cleanup"));
            successCounter.increment(successCount);
        }

        if (skippedCount > 0) {
            Counter skippedCounter = meterRegistry.counter(SEAT_CLEANUP_SKIPPED_METRIC,
                    Tags.of("module", "seat-cleanup", "reason", "lock_still_valid"));
            skippedCounter.increment(skippedCount);
        }

        if (failCount > 0) {
            Counter failCounter = meterRegistry.counter(SEAT_CLEANUP_FAIL_METRIC,
                    Tags.of("module", "seat-cleanup", "reason", "cas_check_failed"));
            failCounter.increment(failCount);
        }

        Timer.builder(SEAT_CLEANUP_TIME_METRIC)
                .description("座位清理任务处理耗时")
                .tag("module", "seat-cleanup")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        log.info("[指标] 座位清理统计 - 成功:{}, 跳过:{}, 失败:{}, 耗时:{}ms",
                successCount, skippedCount, failCount, durationMs);
    }
}
