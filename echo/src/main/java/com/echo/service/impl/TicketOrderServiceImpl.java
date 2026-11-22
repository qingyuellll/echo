package com.echo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.constants.AppMessages;
import com.echo.dto.OrderStatusDTO;
import com.echo.dto.Result;
import com.echo.dto.UserDTO;
import com.echo.entity.*;
import com.echo.enums.NotificationType;
import com.echo.enums.OperationResultStatus;
import com.echo.enums.OrderStatus;
import com.echo.enums.PreOrderStatus;
import com.echo.enums.ShowStatus;
import com.echo.event.OrderStatusChangeEvent;
import com.echo.exception.BusinessException;
import com.echo.mapper.TicketOrderMapper;
import com.echo.message.NotificationMessage;
import com.echo.message.OrderTimeoutMessage;
import com.echo.service.*;
import com.echo.service.strategy.SeatStrategy;
import com.echo.service.strategy.SeatStrategyFactory;
import com.echo.utils.MqConstants;
import com.echo.utils.RedisConstants;
import com.echo.utils.RedisWorker;
import com.echo.utils.SimpleRedisLock;
import com.echo.utils.TokenBucketLimiter;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.echo.utils.BusinessConstants.*;

/**
 * 票务订单服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
@SuppressWarnings("unchecked")
public class TicketOrderServiceImpl extends ServiceImpl<TicketOrderMapper, TicketOrder> implements ITicketOrderService {

    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private RedisWorker redisWorker;
    @Resource
    private com.echo.statemachine.OrderStateMachine orderStateMachine;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ISeckillTicketService seckillTicketService;
    @Resource
    private ITicketService ticketService;
    @Resource
    private IShowService showService;
    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private IUserService userService;
    @Resource
    private ISeatService seatService;
    @Resource
    private SeatStrategyFactory seatStrategyFactory;
    @Resource
    private IOperationLogService operationLogService;

    @Resource
    private DefaultRedisScript<Long> seckillScript;

    /** 秒杀幂等门闸脚本：请求级三态占位（PROCESSING/SUCCESS/FAILED），setIfAbsent原子占位 */
    @Resource
    private DefaultRedisScript<Long> seckillIdemScript;

    @Resource
    private DefaultRedisScript<Long> seckillRollbackScript;

    @Resource
    private DefaultRedisScript<Long> rateLimitScript;  // S8修复：原子性限流脚本

    /** 秒杀排队原子入队脚本（上限校验+入队+明细写入+历史结果清理，单次RTT原子完成） */
    @Resource
    private DefaultRedisScript<Long> seckillEnqueueScript;

    /** 秒杀入口削读：票种本地缓存（TTL 3秒） */
    @Resource
    private com.github.benmanes.caffeine.cache.Cache<Long, com.echo.entity.SeckillTicket> seckillTicketLocalCache;

    /** 秒杀入口削读：用户信誉分本地缓存（TTL 10秒） */
    @Resource
    private com.github.benmanes.caffeine.cache.Cache<Long, com.echo.entity.UserInfo> userCreditLocalCache;

    /** 秒杀入口削读：票种本地缓存（TTL 10秒，仅用hasSeats与showId） */
    @Resource
    private com.github.benmanes.caffeine.cache.Cache<Long, com.echo.entity.Ticket> ticketLocalCache;

    /** 秒杀入口削读：演出本地缓存（复用CaffeineConfig.showLocalCache，与读路径一致的一致性语义） */
    @Resource
    private com.github.benmanes.caffeine.cache.Cache<String, com.echo.entity.Show> entryShowLocalCache;

    @Resource
    private TokenBucketLimiter seckillLocalLimiter;  // JVM级本地令牌桶（RateLimitConfig定义）

    /** 秒杀排队：每批消费的最大单数（SeckillQueueConsumer每100ms调度一次 → 500单/秒，5000张约10秒售罄；消费速率应≈DB写入能力，按压测结果调整） */
    private static final int SECKILL_QUEUE_BATCH_SIZE = 50;
    /** 秒杀排队：队列容量上限（ZADD按用户去重，正常量=真实用户数；超限快速失败，防恶意灌队列） */
    @Value("${echo.seckill.queue-max-size:100000}")
    private long seckillQueueMaxSize;
    /** 秒杀排队结果Key的有效期（分钟） */
    private static final int SECKILL_QUEUE_RESULT_TTL_MINUTES = 10;

    @Resource
    private DefaultRedisScript<Long> seckillRefundScript;

    @Resource
    private IPreOrderService preOrderService;

    @Resource
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    @Resource(name = "asyncTaskExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor asyncTaskExecutor;

    @Resource(name = "compensationExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor compensationExecutor;

    private static final String VERIFY_CODE_KEY = "verify:code:";
    private static final String VERIFY_USED_KEY = "verify:used:";  // ✅ 新增：记录每张票的核销状态

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @PostConstruct
    public void init() {
        // 初始化秒杀Lua脚本（如有必要可在此添加其他初始化逻辑）
    }

    /**
     * 秒杀票下单
     * <p>
     * 流程：
     * 1. 校验秒杀时间窗口（beginTime ~ endTime，使用Redis缓存）
     * 2. 执行Lua脚本（原子性：库存校验 + 重复下单校验 + 库存扣减）
     * 3. 发送RocketMQ异步消息创建订单
     * <p>
     * Lua脚本（seckill.lua）：
     *   Redis KEYS[1] = seckill:stock:{ticketId}   → GET 校验库存
     *   Redis KEYS[2] = seckill:order:{ticketId}   → SISMEMBER 校验重复下单
     *   返回码：0=秒杀成功, 1=库存不足, 2=重复下单
     * <p>
     * 防重与削峰策略（层层递进）：
     * - 本地限流: JVM级令牌桶 seckillLocalLimiter（容量2000，1000 token/秒），被拒绝的流量不穿透到Redis
     * - 分布式限流: Redis Lua 用户级（5次/秒）+ 全局级（5000次/秒）
     * - 排队削峰: 校验通过的请求进入Redis ZSET队列（seckill:queue:{ticketId}），
     *   由SeckillQueueConsumer按500单/秒顺序消费；售罄时向全部排队用户广播"已售罄"结果
     * - 队列消费互斥: lock:seckill:queue:consume:{ticketId}（TTL:30s+看门狗续期），多实例部署时保证同一队列不被并发消费
     * - Lua原子判重: 库存扣减与 SADD seckill:order:{ticketId} 在同一Lua事务内原子执行；
     *   退款时由 seckill_refund.lua SREM 释放购买资格（允许重新购买）。
     *   因此不建立数据库唯一索引(user_id, ticket_id)——绝对唯一索引会阻断退款后重购，
     *   MySQL也不支持只约束"有效订单"的部分索引，防重由Redis Lua层原子保证
     *
     * @param ticketId 秒杀票种ID
     * @return 操作结果
     */
    @Override
    public Result<?> seckillTicket(Long ticketId, @org.springframework.web.bind.annotation.RequestParam(required = false) List<Long> seatIds) {
        log.info("=========================================");
        log.info("[秒杀入口] 开始处理秒杀请求，ticketId:{}, seatIds:{}", ticketId, seatIds);

        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("[秒杀拒绝] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }
        long userId = user.getId();
        log.info("[秒杀参数] userId:{}, ticketId:{}", userId, ticketId);

        // 第一道闸门：JVM级本地令牌桶，被本地拒绝的请求不再穿透到Redis限流层与下游
        if (!seckillLocalLimiter.tryAcquire()) {
            log.warn("[秒杀限流] JVM本地令牌桶已耗尽，请求被直接拒绝，userId:{}", userId);
            return Result.fail("当前请求过多，请稍后再试");
        }

        String rateLimitKey = "rate:seckill:user:" + userId;
        try {
            Long userRateResult = stringRedisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(rateLimitKey),
                    String.valueOf(5),   // 用户限流：5次/秒
                    String.valueOf(1)    // 时间窗口：1秒
            );

            if (userRateResult != null && userRateResult == -1L) {
                log.warn("[秒杀限流] 用户请求过于频繁，userId:{}", userId);
                return Result.fail("操作过于频繁，请稍后再试（1秒内最多5次）");
            }
        } catch (Exception e) {
            log.warn("[秒杀限流] 限流检查异常（不限流）:{}", e.getMessage());
        }

        String globalRateLimitKey = "rate:seckill:global";
        try {
            Long globalRateResult = stringRedisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(globalRateLimitKey),
                    String.valueOf(5000), // 全局限流：5000次/秒，与本地令牌桶1000/s×集群规模匹配，避免本地放开后全局卡住
                    String.valueOf(1)     // 时间窗口：1秒
            );

            if (globalRateResult != null && globalRateResult == -1L) {
                log.error("[秒杀全局限流] 全局QPS过高，触发降级");
                return Result.fail("当前参与用户过多，请稍后再试");
            }
        } catch (Exception e) {
            log.warn("[秒杀全局限流] 检查异常（不限流）:{}", e.getMessage());
        }

        // 削读：票种信息走本地缓存（TTL 3秒），稳态下每秒至多3次DB查询而非每请求1次；
        // 开售瞬间的时间校验最多滞后3秒（前端有倒计时，可接受）
        SeckillTicket seckillTicket = seckillTicketLocalCache.get(ticketId,
                k -> seckillTicketService.getById(k));
        if (seckillTicket == null) {
            log.warn("[秒杀拒绝] 秒杀票务不存在，ticketId:{}", ticketId);
            return Result.fail(AppMessages.SECKILL_FAIL);
        }

        Ticket ticket = ticketLocalCache.get(ticketId, k -> ticketService.getById(k));
        if (ticket != null) {
            Show show = entryShowLocalCache.get(String.valueOf(ticket.getShowId()),
                    k -> showService.getById(Long.parseLong(k)));
            if (show != null && ShowStatus.isOffline(show.getStatus())) {
                log.warn("[秒杀拒绝] 演出已下架，showId:{}", show.getId());
                return Result.fail(AppMessages.TICKET_OFFLINE_ERROR);
            }
        }

        // ✅ 使用系统默认时区（避免UTC时区导致的时间偏差）
        java.time.ZoneId zoneId = java.time.ZoneId.systemDefault();
        long now = LocalDateTime.now().atZone(zoneId).toEpochSecond();
        long beginTime = seckillTicket.getBeginTime() != null
                ? seckillTicket.getBeginTime().atZone(zoneId).toEpochSecond() : 0;
        long endTime = seckillTicket.getEndTime() != null
                ? seckillTicket.getEndTime().atZone(zoneId).toEpochSecond() : 0;

        // 详细日志：便于排查时间问题
        log.info("[秒杀时间校验] 当前时间:{}, 开始时间:{}, 结束时间:{} - ticketId:{}",
            LocalDateTime.now(), seckillTicket.getBeginTime(), seckillTicket.getEndTime(), ticketId);

        if (beginTime == 0) {
            log.error("[秒杀拒绝] 秒杀开始时间未设置 - ticketId:{}", ticketId);
            return Result.fail("秒杀活动配置异常：开始时间未设置");
        }

        if (now < beginTime) {
            long waitSeconds = beginTime - now;
            long waitMinutes = waitSeconds / 60;
            log.warn("[秒杀拒绝] 秒杀未开始 - 当前:{}, 开始:{}, 还需等待:{}分{}秒",
                LocalDateTime.now(), seckillTicket.getBeginTime(), waitMinutes, waitSeconds % 60);
            return Result.fail("秒杀尚未开始，请于 " + seckillTicket.getBeginTime() + " 后再来（还需等待约" + waitMinutes + "分钟）");
        }

        if (endTime > 0 && now > endTime) {
            log.warn("[秒杀拒绝] 秒杀已结束 - 当前:{}, 结束:{}",
                LocalDateTime.now(), seckillTicket.getEndTime());
            return Result.fail(AppMessages.SECKILL_OVER);
        }

        String orderRateKey = RedisConstants.RATE_LIMIT_ORDER_KEY + userId;
        try {
            Long orderRateResult = stringRedisTemplate.execute(
                    rateLimitScript,
                    java.util.Collections.singletonList(orderRateKey),
                    String.valueOf(5),
                    String.valueOf(RedisConstants.RATE_LIMIT_ORDER_TTL)
            );
            if (orderRateResult != null && orderRateResult == -1L) {
                log.warn("[秒杀-风控拒绝] 用户下单频率过高，userId:{}", userId);
                return Result.fail(AppMessages.OPERATION_TOO_FREQUENT);
            }
        } catch (Exception e) {
            log.warn("[风控] 用户下单限流检查异常（不限流）:{}", e.getMessage());
        }

        // 削读：信誉分走本地缓存（TTL 10秒），刚被扣分的用户最多延迟10秒被拦截
        com.echo.entity.UserInfo userInfo = userCreditLocalCache.get(userId,
                k -> userInfoService.getById(k));
        Result<?> creditCheckResult = validateUserCreditScore(userId, userInfo, "秒杀");
        if (creditCheckResult != null) {
            return creditCheckResult;
        }

        int currentCreditScore = (userInfo != null && userInfo.getCreditScore() != null) ? userInfo.getCreditScore() : 100;
        log.info("[秒杀-风控通过] 信誉分:{}, userId:{}, ticketId:{}",
                currentCreditScore, userId, ticketId);

        // 秒杀票业务规则：每人限购1张（Redis Set 校验，避免 MySQL 压力）
        // 设计说明：
        //   1. 使用 Redis SISMEMBER 检查 seckill:order:{ticketId} Set 集合
        //   2. 该集合由 Lua 脚本原子性维护（SADD/SREM），与库存扣减保持一致
        //   3. 同一 ticketId 级别的一人一单限制（不是演出级别）
        //   4. 用户退款成功后，从 Set 中移除 userId，允许重新购买
        //   5. 完全避免在流程前期访问 MySQL，大幅降低数据库压力
        String orderKey = RedisConstants.SECKILL_ORDER_KEY + ticketId;
        log.info("[秒杀限购检查] Redis Set 校验 - userId:{}, ticketId:{}, orderKey:{}", userId, ticketId, orderKey);

        Boolean isPurchased = stringRedisTemplate.opsForSet().isMember(orderKey, String.valueOf(userId));

        if (Boolean.TRUE.equals(isPurchased)) {
            log.warn("[秒杀拒绝] 用户已购买过该秒杀票（Redis Set） - userId:{}, ticketId:{}", userId, ticketId);
            return Result.fail(AppMessages.ORDER_SECKILL_PURCHASED);
        }
        log.info("[秒杀限购检查] 通过 - 允许购买 - userId:{}, ticketId:{}", userId, ticketId);

        // 库存key缺失时的裁决：入口保持纯Redis读，回源预热由启动初始化(SeckillDataInitializer)、
        // 管理端发布动作(syncSeckillStockToRedis)与周期守卫(SeckillStockGuard)完成，
        // 秒杀入口在活动期间对MySQL零访问
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisConstants.SECKILL_STOCK_KEY + ticketId))) {
            String soldOutMarkerKey = RedisConstants.Seckill.SOLD_OUT_KEY + ticketId;
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(soldOutMarkerKey))) {
                log.warn("[秒杀入口] 库存key缺失且已标记售罄 - ticketId:{}", ticketId);
                return Result.fail(AppMessages.SECKILL_STOCK_NOT_ENOUGH);
            }
            log.warn("[秒杀入口] 库存key缺失，返回活动准备中，等待周期守卫重建 - ticketId:{}", ticketId);
            return Result.fail("秒杀活动准备中，请稍后再试");
        }

        int quantity = (seatIds != null && !seatIds.isEmpty()) ? seatIds.size() : 1;

        // 复用入口已加载的票种缓存（原此处重复查库一次）
        boolean isSeckillWithSeats = ticket != null && Boolean.TRUE.equals(ticket.getHasSeats());

        if (isSeckillWithSeats && (seatIds == null || seatIds.isEmpty())) {
            log.warn("[秒杀拒绝] 有座秒杀票必须选择座位，ticketId:{}, hasSeats:{}", ticketId, true);
            return Result.fail("有座秒杀票必须选择座位");
        }

        if (!isSeckillWithSeats && seatIds != null && !seatIds.isEmpty()) {
            log.warn("[秒杀拒绝] 无座秒杀票不应传座位ID，ticketId:{}, hasSeats:{}, seatIds.size:{}",
                    ticketId, false, seatIds.size());
            return Result.fail("无座秒杀票不需要选择座位");
        }

        if (isMqOverloaded()) {
            log.error("[MQ限流] 消息积压过多，秒杀请求被拒绝，ticketId:{}, userId:{}", ticketId, userId);
            return Result.fail("系统繁忙，请稍后再试");
        }

        // ==================== 排队模式：入队后由后台按固定速率消费 ====================
        // 3万人抢5000张的场景下，请求不再被同步拒绝后要求重试，而是全部进入队列：
        // 未抢到的用户将收到明确的"已售罄"广播结果，前端轮询 /seckill/queue/status/{id} 展示排队进度

        // 已售罄快速判断：标记存在且当前无余票时不再入队（退款回补库存后允许重新入队）
        String soldOutKey = RedisConstants.Seckill.SOLD_OUT_KEY + ticketId;
        String stockValue = stringRedisTemplate.opsForValue().get(RedisConstants.SECKILL_STOCK_KEY + ticketId);
        boolean soldOutNoStock = Boolean.TRUE.equals(stringRedisTemplate.hasKey(soldOutKey))
                && (stockValue == null || Integer.parseInt(stockValue) <= 0);
        if (soldOutNoStock) {
            log.warn("[秒杀拒绝] 已售罄（排队模式） - ticketId:{}, userId:{}", ticketId, userId);
            return Result.fail(AppMessages.SECKILL_STOCK_NOT_ENOUGH);
        }

        // 入队：ZSET member=userId，score=入队时间戳（天然去重+按入队时间FIFO）
        String queueKey = RedisConstants.Seckill.QUEUE_KEY + ticketId;
        String queueDetailKey = RedisConstants.Seckill.QUEUE_DETAIL_KEY + ticketId;
        String member = String.valueOf(userId);

        // 队列与明细的TTL：活动结束后1小时自动清理，防止活动结束未消费的成员永久残留
        long queueTtlSeconds = 24 * 3600L;  // 活动时间异常时的兜底值
        if (seckillTicket != null && seckillTicket.getEndTime() != null) {
            long remainSeconds = java.time.Duration.between(
                    LocalDateTime.now(), seckillTicket.getEndTime()).getSeconds();
            if (remainSeconds > 0) {
                queueTtlSeconds = remainSeconds + 3600;
            }
        }

        // 排队明细（数量/座位），消费者处理时使用
        java.util.Map<String, Object> detail = new java.util.HashMap<>();
        detail.put("quantity", quantity);
        detail.put("seatIds", seatIds);

        // 原子入队：上限校验(ZCARD)+入队(ZADD)+明细写入+历史结果清理+TTL续期在一段Lua内原子完成
        // - ZADD按member幂等，判重即入队本身，不存在"判重与入队之间两次都通过"的并发窗口
        //   （重复入队仅覆盖明细并返回0，队列中不会出现两个成员）
        // - 原ZCARD-then-ZADD两步在并发下可超出容量上限，脚本内校验严格成立
        Long enqueueResult;
        try {
            enqueueResult = stringRedisTemplate.execute(seckillEnqueueScript,
                    java.util.Arrays.asList(queueKey, queueDetailKey, RedisConstants.Seckill.QUEUE_ACTIVE_KEY),
                    member,
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(seckillQueueMaxSize),
                    com.alibaba.fastjson.JSON.toJSONString(detail),
                    RedisConstants.Seckill.QUEUE_RESULT_KEY + ticketId + ":" + userId,
                    String.valueOf(queueTtlSeconds),
                    String.valueOf(ticketId));
        } catch (Exception e) {
            log.error("[秒杀入队失败] Redis异常 - ticketId:{}, userId:{}, error:{}", ticketId, userId, e.getMessage());
            return Result.fail("排队系统繁忙，请稍后再试");
        }
        if (enqueueResult == null || enqueueResult == -1L) {
            log.warn("[秒杀拒绝] 排队队列已达上限({}) - ticketId:{}, userId:{}",
                    seckillQueueMaxSize, ticketId, userId);
            return Result.fail("当前排队人数过多，请稍后再试");
        }
        if (enqueueResult == 0L) {
            log.info("[秒杀排队] 用户已在队列中（幂等入队，明细已覆盖），ticketId:{}, userId:{}", ticketId, userId);
        }

        Long rank = stringRedisTemplate.opsForZSet().rank(queueKey, member);
        long ahead = rank == null ? 0 : rank;
        log.info("[秒杀排队] 入队成功，前方还有{}人 - ticketId:{}, userId:{}", ahead, ticketId, userId);

        java.util.Map<String, Object> queuedResult = new java.util.HashMap<>();
        queuedResult.put("status", "QUEUED");
        queuedResult.put("ahead", ahead);
        return Result.ok(queuedResult);
    }

    /**
     * 秒杀排队队列消费入口（SeckillQueueConsumer每100ms调度一次）
     * <p>
     * 扫描活跃队列集合中的票种，逐票种消费排队请求。
     */
    @Override
    public void consumeSeckillQueueBatch() {
        try {
            java.util.Set<String> activeTickets = stringRedisTemplate.opsForSet()
                    .members(RedisConstants.Seckill.QUEUE_ACTIVE_KEY);
            if (activeTickets == null || activeTickets.isEmpty()) {
                return;
            }

            for (String ticketIdStr : activeTickets) {
                consumeTicketQueue(ticketIdStr);
            }
        } catch (Exception e) {
            log.error("[秒杀队列] 消费调度异常:{}", e.getMessage(), e);
        }
    }

    /**
     * 消费单个票种的排队队列（每批最多SECKILL_QUEUE_BATCH_SIZE单）
     * <p>
     * 多实例部署时由票种级分布式锁保证同一队列不被并发消费。
     * 锁启用看门狗续期（TTL 30s + 3次续期≈最长持有约70s）：一批50单在DB抖动下可能超过固定TTL，
     * 锁提前失效会导致另一实例双消费——同一排队成员被重复处理时Lua判重会误标FAILED。
     * 正常批次秒级完成，unlock在finally中提前释放；看门狗上限保证锁最长持有有界。
     */
    private void consumeTicketQueue(String ticketIdStr) {
        Long ticketId;
        try {
            ticketId = Long.valueOf(ticketIdStr);
        } catch (NumberFormatException e) {
            log.warn("[秒杀队列] 非法票种ID，清理活跃标记:{}", ticketIdStr);
            stringRedisTemplate.opsForSet().remove(RedisConstants.Seckill.QUEUE_ACTIVE_KEY, ticketIdStr);
            return;
        }

        String queueKey = RedisConstants.Seckill.QUEUE_KEY + ticketIdStr;
        String detailKey = RedisConstants.Seckill.QUEUE_DETAIL_KEY + ticketIdStr;

        SimpleRedisLock consumeLock = new SimpleRedisLock(
                "seckill:queue:consume:" + ticketIdStr, stringRedisTemplate);
        if (!consumeLock.tryLock(30, true)) {
            // 其他实例正在消费该队列，跳过本轮
            return;
        }

        try {
            for (int i = 0; i < SECKILL_QUEUE_BATCH_SIZE; i++) {
                java.util.Set<String> head = stringRedisTemplate.opsForZSet().range(queueKey, 0, 0);
                if (head == null || head.isEmpty()) {
                    // 队列已空：注销活跃标记
                    stringRedisTemplate.opsForSet().remove(RedisConstants.Seckill.QUEUE_ACTIVE_KEY, ticketIdStr);
                    return;
                }
                String userIdStr = head.iterator().next();

                boolean keepGoing = processSeckillOrder(ticketId, Long.valueOf(userIdStr), detailKey, queueKey);
                if (!keepGoing) {
                    // 该票种已售罄且队列已清空
                    return;
                }
            }
        } finally {
            consumeLock.unlock();
        }
    }

    /**
     * 处理单个排队请求：执行seckill.lua原子扣减库存，成功则走原下单链路（预订单+MQ），
     * 结果写入排队结果Key（seckill:queue:result:{ticketId}:{userId}）供前端轮询
     *
     * @return false=该票种已售罄且排队队列已清空，本轮停止消费
     */
    private boolean processSeckillOrder(Long ticketId, Long userId, String detailKey, String queueKey) {
        String resultKey = RedisConstants.Seckill.QUEUE_RESULT_KEY + ticketId + ":" + userId;
        try {
            // 幂等门闸（Lua原子三态，请求级幂等）：与seckill.lua里的SISMEMBER业务判重（买过没有）正交。
            //   PROCESSING → 其他执行方正在处理本次请求，跳过且不写排队结果（结果由持有方负责写入）
            //   SUCCESS    → 本次请求已成功终结，直接返回
            //   FAILED     → 上次处理失败后补偿显式落下的标记，允许重做（重置为PROCESSING放行）
            // 仅业务拒绝（库存不足/重复下单）不落FAILED——那是业务状态的裁决，用户可重试。
            Long idem = stringRedisTemplate.execute(seckillIdemScript,
                    Collections.singletonList(RedisConstants.Seckill.buildIdemKey(ticketId, userId)),
                    String.valueOf(RedisConstants.Seckill.IDEM_TTL_MINUTES * 60));
            if (idem != null && idem != 0L && idem != 3L) {
                log.info("[秒杀幂等] 跳过已处理请求 - 状态:{}, ticketId:{}, userId:{}",
                        idem == 1L ? "PROCESSING" : "SUCCESS", ticketId, userId);
                return true;
            }

            // 从排队明细解析下单参数
            int quantity = 1;
            List<Long> seatIds = null;
            Object detailJson = stringRedisTemplate.opsForHash().get(detailKey, String.valueOf(userId));
            if (detailJson != null) {
                com.alibaba.fastjson.JSONObject detail = com.alibaba.fastjson.JSON.parseObject(detailJson.toString());
                if (detail != null) {
                    Integer q = detail.getInteger("quantity");
                    if (q != null && q > 0) {
                        quantity = q;
                    }
                    com.alibaba.fastjson.JSONArray seatArr = detail.getJSONArray("seatIds");
                    if (seatArr != null && !seatArr.isEmpty()) {
                        seatIds = seatArr.toJavaList(Long.class);
                    }
                }
            }

            Ticket seckillTicketInfo = ticketService.getById(ticketId);
            String tempId = generateTempId(userId);

            String seckillStockKey = RedisConstants.SECKILL_STOCK_KEY + ticketId;
            String seckillOrderKey = RedisConstants.SECKILL_ORDER_KEY + ticketId;
            String seckillGraceKey = "seckill:grace:" + ticketId + ":" + userId;

            Long result = stringRedisTemplate.execute(
                    seckillScript,
                    java.util.Arrays.asList(seckillStockKey, seckillOrderKey, seckillGraceKey),
                    String.valueOf(ticketId),
                    String.valueOf(userId),
                    String.valueOf(quantity),
                    "30");

            log.info("[秒杀队列] Lua脚本执行结果:{}, ticketId:{}, userId:{}, quantity:{}",
                    result, ticketId, userId, quantity);

            switch (result.intValue()) {
            case 0:
                log.info("[秒杀成功] 库存扣减成功，进入写入管道 - tempId:{}, quantity:{}", tempId, quantity);

                // Lua扣减成功后进入"已扣减状态"，此后所有写入经由管道推进：
                // 任一步骤失败统一路由到compensate累计回滚，不依赖各失败点各自配对补偿
                SeckillWritePipeline pipeline = new SeckillWritePipeline(ticketId, userId, quantity, seatIds, tempId, resultKey);

                PreOrder preOrder = new PreOrder();
                preOrder.setTempId(tempId);
                preOrder.setUserId(userId);
                preOrder.setTicketId(ticketId);
                preOrder.setQuantity(quantity);
                preOrder.setSeatIds(seatIds);
                preOrder.setStatus(PreOrderStatus.PENDING_PAYMENT.getCode());
                preOrder.setCreateTime(LocalDateTime.now());
                preOrder.setExpireTime(LocalDateTime.now().plusMinutes(MqConstants.ORDER_TIMEOUT_MINUTES));

                Ticket ticketForSeckill = ticketService.getById(ticketId);
                if (ticketForSeckill != null && ticketForSeckill.getShowId() != null) {
                    preOrder.setShowId(ticketForSeckill.getShowId());
                }
                if (ticketForSeckill != null) {
                    preOrder.setPayValue(ticketForSeckill.getPayValue());
                    preOrder.setActualValue(ticketForSeckill.getActualValue());
                }

                if (!pipeline.savePreOrder(preOrder)) {
                    return true;
                }
                pipeline.cachePendingOrder();

                if (seatIds != null && !seatIds.isEmpty()) {
                    boolean needsValidation = seckillTicketInfo != null
                            && Boolean.TRUE.equals(seckillTicketInfo.getHasSeats());
                    if (!pipeline.preOccupySeats(needsValidation)) {
                        return true;
                    }
                }

                if (!pipeline.sendOrderMessage()) {
                    return true;
                }

                pipeline.writeSuccessResult(preOrder.getExpireTime().toString());
                return true;
            case 1:
                log.warn("[秒杀队列] 库存不足，标记售罄并广播清空队列 - ticketId:{}, quantity:{}", ticketId, quantity);
                markSoldOutAndDrain(ticketId);
                return false;
            case 2:
                log.warn("[秒杀队列] 重复下单，userId:{}, ticketId:{}", userId, ticketId);
                failQueueItem(resultKey, AppMessages.SECKILL_REPEAT_ORDER);
                return true;
            case 3:
                log.error("[秒杀队列] 参数错误（quantity无效），userId:{}, ticketId:{}, quantity:{}",
                        userId, ticketId, quantity);
                failQueueItem(resultKey, "秒杀数量参数错误");
                return true;
            default:
                log.error("[秒杀队列] 未知返回码:{}, userId:{}, ticketId:{}, quantity:{}",
                        result, userId, ticketId, quantity);
                failQueueItem(resultKey, AppMessages.SECKILL_FAIL);
                return true;
            }
        } catch (Exception e) {
            log.error("[秒杀队列] 处理排队请求异常, ticketId:{}, userId:{}", ticketId, userId, e);
            failQueueItem(resultKey, "系统繁忙，请稍后重试");
            markIdemFailed(ticketId, userId);
            return true;
        } finally {
            // 从队列移除已处理成员（售罄清空场景下队列已删除，此操作幂等无害）
            stringRedisTemplate.opsForZSet().remove(queueKey, String.valueOf(userId));
            stringRedisTemplate.opsForHash().delete(detailKey, String.valueOf(userId));
        }
    }

    /**
     * 秒杀写入管道：Lua扣减成功后进入"已扣减状态"，此后每个写入步骤失败统一路由到compensate，
     * 补偿内容按已完成步骤累加，消除"靠写代码时想起来配对补偿"的结构性风险。
     *
     * 步骤序：savePreOrder(建单落库) → cachePendingOrder(预订单缓存，软失败) → preOccupySeats(座位校验+预占)
     *         → sendOrderMessage(MQ发送) → writeSuccessResult(排队结果)
     * 补偿序：Redis原子回滚(库存+购买标记+预订单缓存) → 释放预占座位 → 删除预订单行 → 写入FAILED结果
     *
     * 兜底网：预订单行删除失败时的残留行交由PreOrderTimeoutScheduler处理——调度器对秒杀票
     * 跳过MySQL库存恢复（Redis为唯一真相源），只释放座位+删行删缓存，与本管道的补偿幂等兼容，
     * 不存在双重归还；座位释放采用pre_order_id=tempId条件更新，天然幂等。
     */
    private class SeckillWritePipeline {
        private final Long ticketId;
        private final Long userId;
        private final int quantity;
        private final List<Long> seatIds;
        private final String tempId;
        private final String resultKey;
        private final String stockKey;
        private final String orderKey;
        private final String pendingPreOrderKey;
        /** 请求级幂等键：成功推SUCCESS（重放直接跳过），补偿推FAILED（显式允许重做） */
        private final String idemKey;

        // 步骤完成标记：补偿内容据此累加（"完成"=已尝试，覆盖超时半提交场景）
        private boolean preOrderSaved = false;    // 建单已落库（或可能半提交）
        private boolean cacheWritten = false;     // 预订单缓存已写入
        private boolean seatsPreOccupied = false; // 已尝试座位预占（半提交座位由条件更新兜底释放）
        private boolean redisRolledBack = false;  // Redis侧已回滚：防重入，避免重试导致INCRBY超回

        SeckillWritePipeline(Long ticketId, Long userId, int quantity, List<Long> seatIds,
                             String tempId, String resultKey) {
            this.ticketId = ticketId;
            this.userId = userId;
            this.quantity = quantity;
            this.seatIds = seatIds;
            this.tempId = tempId;
            this.resultKey = resultKey;
            this.stockKey = RedisConstants.SECKILL_STOCK_KEY + ticketId;
            this.orderKey = RedisConstants.SECKILL_ORDER_KEY + ticketId;
            this.pendingPreOrderKey = RedisConstants.PENDING_PRE_ORDER + tempId;
            this.idemKey = RedisConstants.Seckill.buildIdemKey(ticketId, userId);
        }

        /**
         * 步骤1：建单落库（半提交超时场景由补偿中的removeById兜底清理）
         */
        boolean savePreOrder(PreOrder preOrder) {
            preOrderSaved = true; // 先标记再写入：半提交（超时但已提交）也纳入补偿范围
            try {
                preOrderService.save(preOrder);
                return true;
            } catch (Exception e) {
                compensate("建单落库失败", "系统繁忙，请稍后重试", e);
                return false;
            }
        }

        /**
         * 步骤2：预订单缓存（软失败：DB+TTL为真相源，缓存丢失不破坏一致性，
         * 回滚脚本中的DEL对未写入的key是空操作，幂等）
         */
        void cachePendingOrder() {
            try {
                Map<String, Object> orderCache = new HashMap<>();
                orderCache.put("tempId", tempId);
                orderCache.put("userId", userId);
                orderCache.put("ticketId", ticketId);
                orderCache.put("seatIds", seatIds);
                orderCache.put("quantity", quantity);
                orderCache.put("status", 0);
                orderCache.put("createTime", LocalDateTime.now().toString());

                stringRedisTemplate.opsForValue().set(pendingPreOrderKey,
                        com.alibaba.fastjson.JSON.toJSONString(orderCache),
                        MqConstants.ORDER_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                cacheWritten = true;
                log.info("[秒杀缓存] 预订单已缓存到Redis，key:{}, 有效期{}分钟", pendingPreOrderKey, MqConstants.ORDER_TIMEOUT_MINUTES);
            } catch (Exception cacheEx) {
                log.warn("[秒杀缓存] 缓存失败（软失败不中断管道），error:{}", cacheEx.getMessage());
            }
        }

        /**
         * 步骤3：座位并发校验+逐座预占（仅seatIds非空时调用）
         */
        boolean preOccupySeats(boolean needsValidation) {
            if (needsValidation) {
                log.info("[秒杀选座] 检测到有座票，开始并发安全校验 - ticketId:{}, seatIds:{}", ticketId, seatIds);

                SeatStrategy strategy = seatStrategyFactory.getStrategy(true);
                SeatStrategy.SeatValidationResult validation = strategy.validateSeats(ticketId, seatIds, seatIds.size());

                if (!validation.isValid()) {
                    log.warn("[秒杀选座拒绝] 座位校验失败 - ticketId:{}, 原因:{}", ticketId, validation.getErrorMessage());
                    // 业务拒绝同样处于已扣减状态，必须走统一补偿
                    compensate("座位校验失败（业务拒绝）", validation.getErrorMessage(), null);
                    return false;
                }

                log.info("[秒杀选座校验通过] 并发安全检查通过，继续预占座位 - ticketId:{}, seatIds:{}", ticketId, seatIds);
            }

            seatsPreOccupied = true; // 进入预占即标记：超时半提交的座位也必须纳入释放范围
            for (Long seatId : seatIds) {
                try {
                    boolean updated = seatService.update()
                            .set("status", 1)
                            .set("pre_order_id", tempId)
                            .eq("id", seatId)
                            .eq("status", 0)
                            .update();

                    if (!updated) {
                        log.error("[秒杀选座失败] 座位预占失败，seatId:{}, tempId:{}", seatId, tempId);
                        compensate("座位预占失败", "座位已被占用或不存在，请重新选座 (seatId:" + seatId + ")", null);
                        return false;
                    }
                    log.debug("[秒杀选座] 座位预占成功 - seatId:{}, tempId:{}", seatId, tempId);
                } catch (Exception e) {
                    log.error("[秒杀选座异常] 座位预占异常，seatId:{}, error:{}", seatId, e.getMessage(), e);
                    compensate("座位预占异常", "系统繁忙，请稍后重试", e);
                    return false;
                }
            }
            return true;
        }

        /**
         * 步骤4：发送秒杀订单MQ消息
         * 同步异常立即补偿；异步onException提交带重试的补偿任务（无人值守路径必须自带重试）
         */
        boolean sendOrderMessage() {
            try {
                java.util.Map<String, Object> seckillMsgBody = new java.util.HashMap<>();
                seckillMsgBody.put("tempId", tempId);
                seckillMsgBody.put("userId", userId);
                seckillMsgBody.put("ticketId", ticketId);
                seckillMsgBody.put("seatIds", seatIds);

                rocketMQTemplate.asyncSend(
                        MqConstants.SECKILL_ORDER_TOPIC,
                        org.springframework.messaging.support.MessageBuilder.withPayload(seckillMsgBody).build(),
                        new SendCallback() {
                            @Override
                            public void onSuccess(SendResult sendResult) {
                                log.info("[MQ发送成功] 秒杀预订单消息已发送，tempId:{}, msgId:{}", tempId, sendResult.getMsgId());
                            }

                            @Override
                            public void onException(Throwable e) {
                                log.error("[MQ发送失败] 秒杀预订单消息发送失败，提交异步补偿回滚任务，tempId:{}", tempId, e);
                                compensateAsyncWithRetry(e);
                            }
                        },
                        3000,
                        MqConstants.ORDER_TIMEOUT_DELAY_LEVEL);
                return true;
            } catch (Exception mqSendEx) {
                log.error("[MQ发送异常] 秒杀预订单消息发送同步异常，立即同步补偿回滚，tempId:{}, error:{}",
                        tempId, mqSendEx.getMessage());
                compensate("MQ发送异常", "系统繁忙，请稍后重试", mqSendEx);
                return false;
            }
        }

        /**
         * 步骤5：写入秒杀成功排队结果
         */
        void writeSuccessResult(String expireTime) {
            java.util.Map<String, Object> successPayload = new java.util.HashMap<>();
            successPayload.put("tempId", tempId);
            successPayload.put("message", "秒杀成功！请在" + MqConstants.ORDER_TIMEOUT_MINUTES + "分钟内完成支付");
            successPayload.put("expireTime", expireTime);

            log.info("[秒杀成功响应] 已写入排队结果，tempId:{}", tempId);
            writeQueueResult(resultKey, "SUCCESS", successPayload);

            // 幂等键推进到 SUCCESS：本次请求已成功终结，重放的同请求在门闸处直接跳过。
            // 注意：退款路径只 SREM 业务购买标记（seckill:order:*），绝不删除本键——
            // "买过"可被业务事件改写，"处理过"不能；陈旧SUCCESS靠TTL过期自然释放。
            try {
                stringRedisTemplate.opsForValue().set(idemKey, "SUCCESS",
                        RedisConstants.Seckill.IDEM_TTL_MINUTES, TimeUnit.MINUTES);
            } catch (Exception idemEx) {
                log.warn("[秒杀幂等] SUCCESS标记写入失败（软失败，PROCESSING将由TTL过期释放）- idemKey:{}, error:{}",
                        idemKey, idemEx.getMessage());
            }
        }

        /**
         * MQ异步回调场景的补偿任务：在独立补偿线程池内指数退避重试，仅当关键回滚（Redis侧）未确认成功时继续重试。
         * 池满拒绝时降级为定期兜底：落FAILED终态标记，恢复交由预订单超时补偿(30s)与秒杀对账(2min)两条链路，
         * 保证补偿重试始终在调度线程之外执行，调度线程被占满时兜底任务与主链路各自独立运行。
         */
        private void compensateAsyncWithRetry(Throwable cause) {
            try {
                compensationExecutor.submit(() -> {
                    int maxRetries = 3;
                    for (int retry = 1; retry <= maxRetries; retry++) {
                        try {
                            log.info("[异步补偿回滚] 第{}次尝试，tempId:{}", retry, tempId);
                            if (compensate("MQ异步发送失败", "系统繁忙，请稍后重试", cause)) {
                                log.info("[异步补偿回滚成功] Redis侧关键回滚完成（第{}次），tempId:{}", retry, tempId);
                                return;
                            }
                            log.warn("[异步补偿回滚未确认] 第{}次返回false，tempId:{}", retry, tempId);
                        } catch (Exception retryEx) {
                            log.warn("[异步补偿回滚失败] 第{}次异常，tempId:{}, error:{}", retry, tempId, retryEx.getMessage());
                        }
                        if (retry < maxRetries) {
                            try {
                                long sleepTime = (long) (100 * Math.pow(2, retry - 1));
                                log.info("[异步补偿回滚] 指数退避等待{}ms后重试（第{}/{}次）", sleepTime, retry, maxRetries);
                                Thread.sleep(sleepTime);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    log.error("[致命错误] 异步补偿回滚{}次后仍未确认成功，需人工介入！tempId:{}, ticketId:{}, userId:{}",
                            maxRetries, tempId, ticketId, userId);
                });
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                log.error("[异步补偿回滚] 补偿线程池已满，本次降级为定期兜底 - tempId:{}, ticketId:{}, userId:{}",
                        tempId, ticketId, userId);
                failQueueItem(resultKey, "系统繁忙，请稍后重试");
                markIdemFailed(ticketId, userId);
            }
        }

        /**
         * 统一补偿入口：按已完成步骤累计回滚，幂等可重入
         *
         * @return true = 关键回滚（Redis侧库存+购买标记）已确认完成
         */
        boolean compensate(String stage, String userMessage, Throwable cause) {
            if (cause == null) {
                log.error("[秒杀写入管道补偿] 阶段:{} - 步骤完成标记 [建单:{}, 缓存:{}, 座位预占:{}]，开始累计回滚 - ticketId:{}, userId:{}, tempId:{}",
                        stage, preOrderSaved, cacheWritten, seatsPreOccupied, ticketId, userId, tempId);
            } else {
                log.error("[秒杀写入管道补偿] 阶段:{} - 步骤完成标记 [建单:{}, 缓存:{}, 座位预占:{}]，开始累计回滚 - ticketId:{}, userId:{}, tempId:{}",
                        stage, preOrderSaved, cacheWritten, seatsPreOccupied, ticketId, userId, tempId, cause);
            }

            // 1) Redis侧原子回滚：库存+购买标记+预订单缓存
            //    redisRolledBack防重入：重试路径不会二次INCRBY造成库存超回
            if (!redisRolledBack) {
                try {
                    Long rollbackResult = stringRedisTemplate.execute(seckillRollbackScript,
                            java.util.Arrays.asList(stockKey, orderKey, pendingPreOrderKey),
                            String.valueOf(userId), String.valueOf(quantity));
                    if (rollbackResult != null && rollbackResult == 0) {
                        redisRolledBack = true;
                        log.warn("[秒杀写入管道补偿] Redis库存+购买标记+预订单缓存已原子恢复 - ticketId:{}, userId:{}, tempId:{}",
                                ticketId, userId, tempId);
                    } else {
                        log.error("[秒杀写入管道补偿] Lua回滚脚本返回异常值:{}，降级逐条补偿 - tempId:{}", rollbackResult, tempId);
                        rollbackSeckillRedisFull(ticketId, userId, quantity);
                        redisRolledBack = true;
                    }
                } catch (Exception e) {
                    log.error("[秒杀写入管道补偿] Lua回滚脚本执行异常，降级rollbackSeckillRedisFull - tempId:{}", tempId, e);
                    rollbackSeckillRedisFull(ticketId, userId, quantity); // 内部自带逐条降级与日志
                    redisRolledBack = true;
                }
            }

            // 2) 释放预占座位：pre_order_id=tempId条件更新，幂等；内部异常自吞并逐条记录
            if (seatsPreOccupied) {
                rollbackPreOccupiedSeats(tempId, seatIds);
            }

            // 3) 删除预订单行：覆盖半提交场景；删除失败时残留行由PreOrderTimeoutScheduler兜底
            //    （调度器对秒杀票只释放座位+删行删缓存，不恢复MySQL库存，与本次补偿无双重归还风险）
            try {
                preOrderService.removeById(tempId);
            } catch (Exception e) {
                log.error("[秒杀写入管道补偿] 预订单行删除失败，残留行交由超时调度器兜底，需人工核对 - tempId:{}, error:{}",
                        tempId, e.getMessage());
            }

            // 4) 写入FAILED排队结果：前端轮询立即感知失败，不再停留于"排队中"
            failQueueItem(resultKey, userMessage);

            // 5) 幂等键落FAILED：显式标记"本次请求处理失败"，用户重新发起时门闸放行重做。
            //    对照：业务拒绝（库存不足/重复下单）不落FAILED——幂等状态只记录"处理过没有"。
            markIdemFailed(ticketId, userId);
            return redisRolledBack;
        }
    }

    /**
     * 幂等键落FAILED：补偿路径的终态标记，允许用户重做本次请求。
     * 软失败设计：Redis不可用时落键同样失败，靠TTL过期自然释放PROCESSING，不向上抛出。
     */
    private void markIdemFailed(Long ticketId, Long userId) {
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.Seckill.buildIdemKey(ticketId, userId), "FAILED",
                    RedisConstants.Seckill.IDEM_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("[秒杀幂等] FAILED标记写入失败（软失败，等待TTL过期释放）- ticketId:{}, userId:{}, error:{}",
                    ticketId, userId, e.getMessage());
        }
    }

    /**
     * 标记票种售罄，并向队列中所有剩余排队用户广播"已售罄"结果后清空队列
     */
    private void markSoldOutAndDrain(Long ticketId) {
        log.warn("[秒杀售罄] 库存不足，标记售罄并清空排队队列 - ticketId:{}", ticketId);

        // 售罄标记：置位后余票为0的请求不再入队（退款回补库存后，入队检查会因余票>0放行）
        stringRedisTemplate.opsForValue().set(
                RedisConstants.Seckill.SOLD_OUT_KEY + ticketId, "1", 1, TimeUnit.HOURS);

        String queueKey = RedisConstants.Seckill.QUEUE_KEY + ticketId;
        String detailKey = RedisConstants.Seckill.QUEUE_DETAIL_KEY + ticketId;

        java.util.Set<String> remaining = stringRedisTemplate.opsForZSet().range(queueKey, 0, -1);
        if (remaining != null && !remaining.isEmpty()) {
            final Long ticketIdFinal = ticketId;
            // 批量写入"已售罄"结果（pipeline，避免万级用户逐个SET）
            stringRedisTemplate.executePipelined(new org.springframework.data.redis.core.SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(org.springframework.data.redis.core.RedisOperations operations) {
                    for (String uid : remaining) {
                        java.util.Map<String, Object> r = new java.util.HashMap<>();
                        r.put("status", "SOLD_OUT");
                        r.put("message", "很遗憾，票已售罄");
                        r.put("timestamp", System.currentTimeMillis());
                        operations.opsForValue().set(
                                RedisConstants.Seckill.QUEUE_RESULT_KEY + ticketIdFinal + ":" + uid,
                                com.alibaba.fastjson.JSON.toJSONString(r),
                                SECKILL_QUEUE_RESULT_TTL_MINUTES, TimeUnit.MINUTES);
                    }
                    return null;
                }
            });
            log.info("[秒杀售罄] 已向{}名排队用户广播售罄结果 - ticketId:{}", remaining.size(), ticketId);
        }

        stringRedisTemplate.delete(queueKey);
        stringRedisTemplate.delete(detailKey);
        stringRedisTemplate.opsForSet().remove(RedisConstants.Seckill.QUEUE_ACTIVE_KEY, String.valueOf(ticketId));
    }

    /**
     * 写入排队结果（成功等终态），供前端轮询读取
     */
    private void writeQueueResult(String resultKey, String status, java.util.Map<String, Object> payload) {
        java.util.Map<String, Object> r = new java.util.HashMap<>();
        r.put("status", status);
        r.put("timestamp", System.currentTimeMillis());
        if (payload != null) {
            r.putAll(payload);
        }
        stringRedisTemplate.opsForValue().set(resultKey,
                com.alibaba.fastjson.JSON.toJSONString(r), SECKILL_QUEUE_RESULT_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 写入失败排队结果
     */
    private void failQueueItem(String resultKey, String message) {
        java.util.Map<String, Object> r = new java.util.HashMap<>();
        r.put("status", "FAILED");
        r.put("message", message);
        r.put("timestamp", System.currentTimeMillis());
        stringRedisTemplate.opsForValue().set(resultKey,
                com.alibaba.fastjson.JSON.toJSONString(r), SECKILL_QUEUE_RESULT_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 查询当前用户在指定秒杀票种的排队状态
     * <p>
     * 返回：QUEUED(排队中，含前方人数) / SUCCESS(成功，含tempId) / FAILED(失败，含原因) /
     * SOLD_OUT(已售罄) / NOT_IN_QUEUE(未在排队)
     */
    @Override
    public Result<?> getSeckillQueueStatus(Long ticketId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }
        long userId = user.getId();

        // 已有最终结果：直接返回
        String resultJson = stringRedisTemplate.opsForValue()
                .get(RedisConstants.Seckill.QUEUE_RESULT_KEY + ticketId + ":" + userId);
        if (resultJson != null) {
            return Result.ok(com.alibaba.fastjson.JSON.parseObject(resultJson));
        }

        // 仍在排队：返回前方人数
        String queueKey = RedisConstants.Seckill.QUEUE_KEY + ticketId;
        String member = String.valueOf(userId);
        Double score = stringRedisTemplate.opsForZSet().score(queueKey, member);
        if (score != null) {
            Long rank = stringRedisTemplate.opsForZSet().rank(queueKey, member);
            java.util.Map<String, Object> queued = new java.util.HashMap<>();
            queued.put("status", "QUEUED");
            queued.put("ahead", rank == null ? 0 : rank);
            return Result.ok(queued);
        }

        java.util.Map<String, Object> notInQueue = new java.util.HashMap<>();
        notInQueue.put("status", "NOT_IN_QUEUE");
        return Result.ok(notInQueue);
    }

    /**
     * 普通票下单
     * <p>
     * 下单流程：
     * 1. 风控校验：IP频率限制 + 信誉分门槛(60分)
     * 2. 防重校验：分布式锁 + 应用层查重 + 唯一索引
     * 3. 创建订单：雪花ID + 订单入库
     * 4. 发送延时消息：5分钟后自动取消未支付订单
     * <p>
     * 数据库操作：
     *   INSERT INTO tb_ticket_order (id, user_id, ticket_id, status, create_time)
     *   VALUES (雪花ID, {userId}, {ticketId}, 0, now())
     *   其中 id = SnowFlake.nextId()（雪花算法生成）
     * <p>
     * 超时取消：
     *   RocketMQ延时消息 → 5分钟后 → 检查订单状态
     *   UPDATE tb_ticket_order SET status = 4 WHERE id = {orderId} AND status = 0
     *
     * @param ticketId 票种ID
     * @return 订单ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> createTicketOrder(Long ticketId, Integer quantity) {
        log.info("[创建订单] 开始创建票务订单，ticketId:{}, quantity:{}", ticketId, quantity);

        if (quantity == null || quantity < 1) {
            quantity = 1;
        }
        if (quantity > 10) {
            log.warn("[订单拒绝] 单次购买数量过多，quantity:{}", quantity);
            return Result.fail(AppMessages.ORDER_MAX_PURCHASE_10);
        }

        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("[订单拒绝] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        String orderRateKey = RedisConstants.RATE_LIMIT_ORDER_KEY + user.getId();
        try {
            Long orderRateResult = stringRedisTemplate.execute(
                    rateLimitScript,
                    java.util.Collections.singletonList(orderRateKey),
                    String.valueOf(5),
                    String.valueOf(RedisConstants.RATE_LIMIT_ORDER_TTL)
            );
            if (orderRateResult != null && orderRateResult == -1L) {
                log.warn("[风控拒绝] 用户下单频率过高，userId:{}", user.getId());
                return Result.fail(AppMessages.OPERATION_TOO_FREQUENT);
            }
        } catch (Exception e) {
            log.warn("[风控] 用户下单限流检查异常（不限流）:{}", e.getMessage());
        }

        com.echo.entity.UserInfo userInfo = userInfoService.getById(user.getId());
        Result<?> creditCheckResult = validateUserCreditScore(user.getId(), userInfo, "普通票");
        if (creditCheckResult != null) {
            return creditCheckResult;
        }

        Ticket ticket = ticketService.getById(ticketId);
        if (ticket == null) {
            return Result.fail(AppMessages.ORDER_TICKET_NOT_EXIST);
        }

        // 最终验证：创建订单前最后一次检查票种状态（最重要）
        log.info("[创建订单] 开始最终票种状态验证，ticketId:{}, userId:{}", ticketId, user.getId());
        boolean isTicketActive = ticketService.isTicketActiveForPurchase(ticketId);
        if (!isTicketActive) {
            log.warn("[创建订单] [失败] 最终验证失败！票种不可售卖，拒绝创建订单。ticketId:{}, userId:{}",
                    ticketId, user.getId());

            // 查询具体原因用于返回友好提示
            com.echo.dto.Result<?> statusResult = ticketService.checkTicketStatus(ticketId);
            if (statusResult != null && statusResult.getData() != null) {
                java.util.Map<String, Object> statusInfo =
                        (java.util.Map<String, Object>) statusResult.getData();
                Integer status = (Integer) statusInfo.get("status");

                if (status == null || status != 1) {
                    log.error("[创建订单] 🚨 安全拦截：尝试为已下架/停用的票种创建订单！ticketId:{}, status:{}, userId:{}",
                            ticketId, status, user.getId());
                    return Result.fail(AppMessages.TICKET_OFFLINE_ERROR);  // 已下架或停用
                } else {
                    Boolean isSeckill = (Boolean) statusInfo.get("isSeckill");
                    if (Boolean.TRUE.equals(isSeckill)) {
                        log.warn("[创建订单] 🚨 安全拦截：秒杀票不在时间窗口内！ticketId:{}, userId:{}",
                                ticketId, user.getId());
                        return Result.fail(AppMessages.TICKET_SECKILL_NOT_STARTED);  // 秒杀未开始/已结束
                    }
                }
            }

            log.error("[创建订单] [安全拦截] 票种状态异常，拒绝创建订单！ticketId:{}, userId:{}",
                    ticketId, user.getId());
            return Result.fail(AppMessages.TICKET_STATUS_INVALID);  // 其他异常情况
        }
        log.info("[创建订单] [成功] 最终验证通过，票种可售卖，ticketId:{}, userId:{}", ticketId, user.getId());

        if (ticket.getType() != null && ticket.getType() == 1) {
            log.warn("[订单拒绝] 秒杀票请使用秒杀接口下单，ticketId:{}", ticketId);
            return Result.fail(AppMessages.ORDER_SECKILL_CHANNEL_REQUIRED);
        }

        if (Boolean.TRUE.equals(ticket.getHasSeats())) {
            log.warn("[订单拒绝] 有座票必须使用选座接口下单，ticketId:{}, 票名:{}", ticketId, ticket.getTitle());
            return Result.fail("该票种为有座票，请使用选座接口下单（POST /ticket-orders/" + ticketId + "/seats）");
        }

        Show show = showService.getById(ticket.getShowId());
        if (show != null && ShowStatus.isOffline(show.getStatus())) {
            log.warn("[订单拒绝] 演出已下架，showId:{}, name:{}", show.getId(), show.getName());
            return Result.fail(AppMessages.TICKET_OFFLINE_ERROR);
        }

        // 普通票业务规则：允许重复购买，不限制购买次数
        // 只有秒杀票才需要限购逻辑（见 seckillTicket 方法）
        log.info("[普通票下单] 业务规则验证通过 - 允许重复购买 - userId:{}, ticketId:{}", user.getId(), ticketId);

        int currentStock = ticket.getStock() != null ? ticket.getStock() : 0;
        if (currentStock < quantity) {
            log.warn("[订单拒绝] 票种库存不足，ticketId:{}, stock:{}, need:{}", ticketId, currentStock, quantity);
            return Result.fail(AppMessages.format(AppMessages.ORDER_STOCK_INSUFFICIENT, currentStock));
        }

        //  普通票/无座票：下单时预扣MySQL库存（乐观锁）
        // 设计说明：
        //   1. 使用乐观锁（ge("stock", quantity)）防止超卖
        //   2. 预扣库存后，支付成功确认扣减，超时/取消自动回滚
        //   3. 与秒杀票的Redis Lua脚本保持一致的"先扣后买"策略
        log.info("[普通票下单] 开始预扣库存，ticketId:{}, 当前库存:{}, 预扣数量:{}", ticketId, currentStock, quantity);

        boolean stockPreReduced = ticketService.update()
                .setSql("stock = stock - " + quantity)
                .eq("id", ticketId)
                .ge("stock", quantity)
                .update();

        if (!stockPreReduced) {
            log.warn("[订单拒绝] 库存预扣失败（可能并发不足），ticketId:{}, 需要数量:{}", ticketId, quantity);

            int latestStock = ticketService.getById(ticketId).getStock();
            return Result.fail(AppMessages.format(AppMessages.ORDER_STOCK_INSUFFICIENT,
                    latestStock));
        }

        log.info("[普通票下单] 库存预扣成功，ticketId:{}, 剩余库存:{}",
                ticketId, currentStock - quantity);

        String tempId = generateTempId(user.getId());

        PreOrder preOrder = new PreOrder();
        preOrder.setTempId(tempId);
        preOrder.setUserId(user.getId());
        preOrder.setTicketId(ticketId);
        preOrder.setShowId(ticket.getShowId());
        preOrder.setQuantity(quantity);
        preOrder.setPayValue(ticket.getPayValue() != null ? ticket.getPayValue() : 0L);
        preOrder.setActualValue(ticket.getActualValue() != null ? ticket.getActualValue() : 0L);
        preOrder.setStatus(PreOrderStatus.PENDING_PAYMENT.getCode());
        preOrder.setCreateTime(LocalDateTime.now());
        preOrder.setExpireTime(LocalDateTime.now().plusMinutes(MqConstants.ORDER_TIMEOUT_MINUTES));

        preOrderService.save(preOrder);

        cachePreOrderWithSeatIds(preOrder);

        try {
            Map<String, Object> msgBody = new HashMap<>();
            msgBody.put("tempId", tempId);
            msgBody.put("orderId", tempId);
            msgBody.put("userId", user.getId());
            msgBody.put("ticketId", ticketId);
            rocketMQTemplate.asyncSend(MqConstants.ORDER_TIMEOUT_TOPIC,
                    MessageBuilder.withPayload(msgBody).build(),
                    new SendCallback() {
                        @Override
                        public void onSuccess(SendResult result) {
                            log.info("[普通下单-MQ发送成功] 超时取消消息已发送，tempId:{}, msgId:{}", tempId, result.getMsgId());
                        }
                        @Override
                        public void onException(Throwable e) {
                            log.error("[普通下单-MQ发送失败] 超时取消消息发送失败，tempId:{}, error:{}", tempId, e.getMessage());
                        }
                    },
                    3000,
                    MqConstants.ORDER_TIMEOUT_DELAY_LEVEL);
            log.info("[普通下单成功] 已异步发送超时取消消息（延迟5分钟），tempId:{}, 数量:{}", tempId, quantity);
        } catch (Exception mqEx) {
            log.warn("[普通下单警告] 发送超时取消消息失败（不影响下单），error:{}", mqEx.getMessage());
        }

        log.info("[普通下单成功] 创建预订单！ticketId:{}, userId:{}, tempId:{}, 数量:{}, 当前库存:{}",
                ticketId, user.getId(), tempId, quantity, currentStock);

        Map<String, Object> result = new HashMap<>();
        result.put("tempId", tempId);
        result.put("status", 0);
        result.put("quantity", quantity);
        result.put("unitPrice", ticket.getPayValue());
        result.put("totalPrice", ticket.getPayValue() * quantity);
        result.put("createTime", LocalDateTime.now());
        result.put("expireTime", preOrder.getExpireTime());
        result.put("message", "请5分钟内完成支付");

        return Result.ok(result);
    }

    /**
     * 模拟支付
     * <p>
     * 支付流程：
     * 1. 校验订单状态（仅 status=0/1 可支付）
     * 2. 校验演出时间（已结束的演出不可支付）
     * 3. 积分抵扣：10积分=1元（最多抵扣50%订单金额）
     * 4. 更新订单状态为已支付 + 记录支付时间
     * 5. 生成8位核销码（大写字母数字）→ 存入Redis
     * 6. 赠送积分：固定赠送10积分（与实付金额无关）
     * 7. 发送支付成功通知
     * <p>
     * 数据库操作：
     *   UPDATE tb_ticket_order
     *   SET status = 2, pay_type = 1, pay_time = now(), points_used = {pointsToUse}, discount_amount = {discountAmount}
     *   WHERE id = {orderId}
     * <p>
     * 积分抵扣计算公式：
     *   discountAmount = pointsToUse / 10（单位：元）
     *   实际抵扣上限 = payValue * 50%
     * <p>
     * 赠送积分规则：
     *   固定赠送10积分（无论订单金额大小，统一奖励10积分）
     * <p>
     * 核销码存储：
     *   Redis: SET verify:code:{8位大写字母数字} {orderId} EX 2592000（30天）
     * <p>
     * 座位状态更新：
     *   UPDATE tb_seat SET status = 2, order_id = {orderId}
     *   WHERE ticket_id = {ticketId} AND status = 1
     *
     * @param orderId 订单ID
     * @return 操作结果（含核销码）
     */
    @Override
    public Result<?> getOrderById(String orderId) {
        log.info("[查询订单] 开始查询订单详情，orderId:{}, 类型:{}", orderId, orderId.getClass().getName());

        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("[查询拒绝] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        log.info("[查询订单] 当前用户ID:{}, 查询订单ID:{}", user.getId(), orderId);

        if (orderId.startsWith("TEMP_")) {
            return queryPendingOrderFromCache(orderId, user.getId());
        }

        Long numericOrderId;
        try {
            numericOrderId = Long.parseLong(orderId);
        } catch (NumberFormatException e) {
            log.error("[查询订单] 订单ID格式错误，既不是TEMP_前缀也不是数字，orderId:{}", orderId);
            return Result.fail(AppMessages.ORDER_NOT_EXIST_OR_EXPIRED);
        }

        TicketOrder order = getById(numericOrderId);
        log.info("[查询订单] 数据库查询结果:{}", order != null ? "找到订单" : "订单不存在");

        if (order == null) {
            String pendingKey = RedisConstants.PENDING_SECKILL_ORDER + numericOrderId;
            log.info("[查询订单] 尝试从Redis缓存查询，key:{}", pendingKey);

            try {
                Boolean keyExists = stringRedisTemplate.hasKey(pendingKey);
                log.info("[查询订单] Redis key是否存在:{}", keyExists);

                String cachedJson = stringRedisTemplate.opsForValue().get(pendingKey);
                log.info("[查询订单] Redis缓存内容:{}，长度:{}",
                    cachedJson != null ? "有数据" : "为空/不存在",
                    cachedJson != null ? cachedJson.length() : 0);

                if (cachedJson != null && !cachedJson.isEmpty()) {
                    log.info("[查询订单] 从Redis缓存找到待处理秒杀订单，orderId:{}", orderId);
                    log.info("[查询订单] 缓存原始JSON:{}", cachedJson);

                    Map<String, Object> cachedOrder = com.alibaba.fastjson.JSON.parseObject(cachedJson, Map.class);

                    Long cachedUserId = ((Number) cachedOrder.get("userId")).longValue();
                    if (!cachedUserId.equals(user.getId())) {
                        log.warn("[查询拒绝] 无权查看他人订单（缓存），orderId:{}", orderId);
                        return Result.fail(AppMessages.ORDER_NO_PERMISSION);
                    }

                    Long ticketId = ((Number) cachedOrder.get("ticketId")).longValue();
                    Ticket ticket = ticketService.getById(ticketId);

                    Map<String, Object> orderDetail = new HashMap<>();
                    orderDetail.put("id", orderId);
                    orderDetail.put("userId", cachedUserId);
                    orderDetail.put("ticketId", ticketId);
                    Integer cachedStatus = cachedOrder.get("status") != null ?
                            ((Number) cachedOrder.get("status")).intValue() : 1;
                    orderDetail.put("status", cachedStatus);
                    orderDetail.put("createTime", cachedOrder.get("createTime"));
                    orderDetail.put("pending", true);

                    if (ticket != null) {
                        orderDetail.put("ticketTitle", ticket.getTitle());
                        // 计算总金额（单价 × 数量）
                        int quantity = cachedOrder.get("quantity") != null ?
                                ((Number) cachedOrder.get("quantity")).intValue() : 1;
                        long unitPrice = ticket.getPayValue() != null ? ticket.getPayValue() : 0L;
                        long totalPrice = unitPrice * quantity;
                        orderDetail.put("quantity", quantity);
                        orderDetail.put("unitPrice", unitPrice);
                        orderDetail.put("payValue", totalPrice);
                    }

                    log.info("[查询成功（缓存）] orderId:{}, 状态:待支付（MQ处理中）", orderId);
                    return Result.ok(orderDetail);
                }
            } catch (Exception e) {
                log.warn("[查询订单] Redis缓存读取失败，继续查询数据库，error:{}", e.getMessage());
            }

            log.warn("[查询失败] 订单不存在（缓存也未找到），orderId:{}", orderId);

            log.info("[查询订单] 订单可能正在MQ处理中，返回'处理中'状态，建议前端1-2秒后重试");

            Map<String, Object> pendingResult = new HashMap<>();
            pendingResult.put("id", orderId);
            pendingResult.put("status", 0);  // 0=待支付/处理中
            pendingResult.put("message", "订单正在处理中，请稍后刷新查看");
            pendingResult.put("retryAfter", 1000);  // 建议前端1秒后重试

            return Result.ok(pendingResult);
        }

        if (!order.getUserId().equals(user.getId())) {
            log.warn("[查询拒绝] 无权查看他人订单，orderId:{}, userId:{}", orderId, user.getId());
            return Result.fail(AppMessages.ORDER_NO_PERMISSION);
        }

        Ticket ticket = ticketService.getById(order.getTicketId());
        if (ticket != null) {
            java.util.Map<String, Object> orderDetail = new java.util.HashMap<>();
            orderDetail.put("id", order.getId());
            orderDetail.put("userId", order.getUserId());
            orderDetail.put("ticketId", order.getTicketId());
            orderDetail.put("status", order.getStatus());
            orderDetail.put("createTime", order.getCreateTime());
            orderDetail.put("payTime", order.getPayTime());
            orderDetail.put("ticketTitle", ticket.getTitle());

            // 计算总金额（单价 × 数量）
            int quantity = order.getQuantity() != null ? order.getQuantity() : 1;
            long unitPrice = ticket.getPayValue() != null ? ticket.getPayValue() : 0L;
            long totalPrice = unitPrice * quantity;
            orderDetail.put("quantity", quantity);
            orderDetail.put("unitPrice", unitPrice);
            orderDetail.put("payValue", totalPrice);

            // [核销] 核销码返回逻辑（含自动修复）
            if (OrderStatus.isPostPayment(order.getStatus())) {
                if (order.getVerificationCode() != null && !order.getVerificationCode().isEmpty()) {
                    // 正常情况：有核销码，直接返回
                    orderDetail.put("verificationCode", order.getVerificationCode());
                    log.info("[查询订单] [成功] 返回核销码 - orderId:{}, code:{}", orderId, order.getVerificationCode());
                } else {
                    // 异常情况：已支付但无核销码，自动生成并保存
                    log.warn("[查询订单] [警告] 订单已支付但无核销码，开始自动修复 - orderId:{}, status:{}", orderId, order.getStatus());
                    try {
                        String newCode = generateVerificationCode();
                        order.setVerificationCode(newCode);
                        boolean updateSuccess = updateById(order);

                        if (updateSuccess) {
                            // 同时写入Redis（单票订单默认ticketIndex=0）
                            stringRedisTemplate.opsForValue().set(
                                VERIFY_CODE_KEY + newCode,
                                orderId + ":0",
                                30,
                                TimeUnit.DAYS
                            );
                            orderDetail.put("verificationCode", newCode);
                            log.info("[查询订单] [成功] 自动修复成功 - orderId:{}, 新核销码:{}", orderId, newCode);
                        } else {
                            log.error("[查询订单] [失败] 自动修复失败（数据库保存失败） - orderId:{}", orderId);
                        }
                    } catch (Exception e) {
                        log.error("[查询订单] [异常] 自动修复异常 - orderId:{}, error:{}", orderId, e.getMessage(), e);
                    }
                }
            }

            // 查询订单关联的座位信息
            Ticket ticketForSeat = ticketService.getById(order.getTicketId());
            if (ticketForSeat != null && ticketForSeat.getHasSeats() != null && ticketForSeat.getHasSeats()) {
                try {
                    com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.Seat> seatQueryForDetail =
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
                    seatQueryForDetail.eq("order_id", order.getId());
                    java.util.List<com.echo.entity.Seat> seatsForOrder = seatService.list(seatQueryForDetail);

                    if (!seatsForOrder.isEmpty()) {
                        java.util.List<java.util.Map<String, Object>> seatInfoListForDetail = new java.util.ArrayList<>();
                        for (com.echo.entity.Seat seat : seatsForOrder) {
                            java.util.Map<String, Object> seatInfo = new java.util.HashMap<>();
                            seatInfo.put("id", seat.getId());
                            seatInfo.put("area", seat.getArea());
                            seatInfo.put("rowNum", seat.getRowNum());
                            seatInfo.put("colNum", seat.getColNum());
                            seatInfo.put("seatLabel", getSeatLabel(seat));
                            seatInfoListForDetail.add(seatInfo);
                        }
                        orderDetail.put("seatInfoList", seatInfoListForDetail);
                        log.debug("[查询订单详情] orderId:{}, 座位数量:{}", orderId, seatsForOrder.size());
                    }
                } catch (Exception e) {
                    log.warn("[查询订单详情] 查询座位信息失败，orderId:{}, error:{}", orderId, e.getMessage());
                }
            }

            log.info("[查询成功] orderId:{}, 状态:{}, 数量:{}, 总价:{}",
                    orderId, order.getStatus(), quantity, totalPrice);
            return Result.ok(orderDetail);
        }

        log.info("[查询成功] orderId:{}, 状态:{}", order.getStatus());
        return Result.ok(order);
    }

    /**
     * 选座下单（核心业务逻辑）
     * <p>
     * 流程：
     * 1. 校验用户登录状态和票种有效性
     * 2. 验证座位ID列表（非空、数量限制、状态检查）
     * 3. 风控校验（IP频率 + 信誉分）
     * 4. 创建订单并关联座位
     * 5. 更新座位状态为"已售"
     * 6. 发送延时消息（超时自动取消）
     *
     * @param ticketId 票种ID
     * @param seatIds 已锁定的座位ID列表
     * @param count 座位数量
     * @return 订单信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> createSeatOrder(Long ticketId, List<Long> seatIds, Integer count) {
        log.info("[选座下单] 开始创建选座订单，ticketId:{}, seatIds:{}, count:{}",
                ticketId, seatIds, count);

        // 参数校验
        if (seatIds == null || seatIds.isEmpty()) {
            log.warn("[选座下单拒绝] 座位ID列表为空");
            return Result.fail(AppMessages.ORDER_SELECT_AT_LEAST_ONE_SEAT);
        }
        if (count == null || count < 1 || count > 10) {
            log.warn("[选座下单拒绝] 座位数量无效，count:{}", count);
            return Result.fail(AppMessages.ORDER_SEAT_COUNT_RANGE);
        }
        if (seatIds.size() != count) {
            log.warn("[选座下单拒绝] 座位数量不匹配，seatIds.size:{}, count:{}", seatIds.size(), count);
            return Result.fail(AppMessages.ORDER_SEAT_COUNT_MISMATCH);
        }

        // 用户校验
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("[选座下单拒绝] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }
        Long userId = user.getId();

        // 票种存在性快速检查
        Ticket ticket = ticketService.getById(ticketId);
        if (ticket == null) {
            log.warn("[选座下单拒绝] 票种不存在，ticketId:{}", ticketId);
            return Result.fail(AppMessages.ORDER_TICKET_NOT_EXIST);
        }

        // 票种状态 + 秒杀时间窗口 完整校验（与 createTicketOrder 保持一致）
        boolean isTicketActive = ticketService.isTicketActiveForPurchase(ticketId);
        if (!isTicketActive) {
            log.warn("[选座下单拒绝] 票种不可售卖，ticketId:{}, userId:{}", ticketId, userId);

            com.echo.dto.Result<?> statusResult = ticketService.checkTicketStatus(ticketId);
            if (statusResult != null && statusResult.getData() != null) {
                java.util.Map<String, Object> statusInfo =
                        (java.util.Map<String, Object>) statusResult.getData();
                Integer status = (Integer) statusInfo.get("status");

                if (status == null || status != 1) {
                    return Result.fail(AppMessages.TICKET_OFFLINE_ERROR);
                } else {
                    Boolean isSeckill = (Boolean) statusInfo.get("isSeckill");
                    if (Boolean.TRUE.equals(isSeckill)) {
                        return Result.fail(AppMessages.TICKET_SECKILL_NOT_STARTED);
                    }
                }
            }

            return Result.fail(AppMessages.TICKET_STATUS_INVALID);
        }

        // 演出下架校验（与 createTicketOrder 保持一致）
        Show show = showService.getById(ticket.getShowId());
        if (show != null && ShowStatus.isOffline(show.getStatus())) {
            log.warn("[选座下单拒绝] 演出已下架，showId:{}, name:{}", show.getId(), show.getName());
            return Result.fail(AppMessages.TICKET_OFFLINE_ERROR);
        }

        // [秒杀校验] 秒杀票重复购买检查
        boolean isSeckill = (ticket.getType() != null && ticket.getType() == 1);
        if (isSeckill) {
            log.info("[选座下单] 检测到秒杀票，开始重复购买校验 - userId:{}, ticketId:{}", userId, ticketId);

            // 使用LambdaQueryWrapper进行类型安全的查询
            long existingOrderCount = this.count(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TicketOrder>()
                    .eq(TicketOrder::getUserId, userId)
                    .eq(TicketOrder::getTicketId, ticketId)
                    .in(TicketOrder::getStatus, 0, 1, 2, 3)  // 待支付/预占/已支付/已核销
            );

            if (existingOrderCount > 0) {
                log.warn("[选座下单拒绝] [失败] 秒杀票重复购买 - userId:{}, ticketId:{}, 已有订单数:{}",
                        userId, ticketId, existingOrderCount);
                return Result.fail(AppMessages.ORDER_SECKILL_PURCHASED);
            }

            // 校验座位数量必须为1（秒杀票限购1张）
            if (count > 1) {
                log.warn("[选座下单拒绝] [失败] 秒杀票数量超限 - count:{}, 限制:1", count);
                return Result.fail(AppMessages.ORDER_SECKILL_LIMIT_1);
            }

            log.info("[选座下单] [成功] 秒杀票重复购买校验通过 - userId:{}, ticketId:{}", userId, ticketId);
        }

        String orderRateKey = RedisConstants.RATE_LIMIT_ORDER_KEY + userId;
        try {
            Long orderRateResult = stringRedisTemplate.execute(
                    rateLimitScript,
                    java.util.Collections.singletonList(orderRateKey),
                    String.valueOf(5),
                    String.valueOf(RedisConstants.RATE_LIMIT_ORDER_TTL)
            );
            if (orderRateResult != null && orderRateResult == -1L) {
                log.warn("[选座下单拒绝] 用户下单频率过高，userId:{}", userId);
                return Result.fail(AppMessages.OPERATION_TOO_FREQUENT);
            }
        } catch (Exception e) {
            log.warn("[风控] 用户下单限流检查异常（不限流）:{}", e.getMessage());
        }

        Result<?> creditCheckResult = validateUserCreditScore(userId, null, "选座票");
        if (creditCheckResult != null) {
            return creditCheckResult;
        }

        // 查询并验证所有座位（委托给座位策略：按票种是否有座选择 WithSeat / WithoutSeat）
        SeatStrategy seatStrategy = seatStrategyFactory.getStrategy(ticket.getHasSeats());
        SeatStrategy.SeatValidationResult validationResult = seatStrategy.validateSeats(ticketId, seatIds, count);
        if (!validationResult.isValid()) {
            log.warn("[选座下单拒绝] 座位校验失败：{}", validationResult.getErrorMessage());
            return Result.fail(validationResult.getErrorMessage());
        }

        long unitPrice = ticket.getPayValue() != null ? ticket.getPayValue() : 0L;
        long totalPrice = unitPrice * count;

        String tempId = generateTempId(userId);

        PreOrder preOrder = new PreOrder();
        preOrder.setTempId(tempId);
        preOrder.setUserId(userId);
        preOrder.setTicketId(ticketId);
        preOrder.setShowId(ticket.getShowId());
        preOrder.setSeatIds(seatIds);
        preOrder.setQuantity(count);
        preOrder.setPayValue(unitPrice);
        preOrder.setActualValue(ticket.getActualValue() != null ? ticket.getActualValue() : 0L);
        preOrder.setStatus(PreOrderStatus.PENDING_PAYMENT.getCode());
        preOrder.setCreateTime(LocalDateTime.now());
        preOrder.setExpireTime(LocalDateTime.now().plusMinutes(MqConstants.ORDER_TIMEOUT_MINUTES));

        preOrderService.save(preOrder);

        for (Long seatId : seatIds) {
            boolean updated = seatService.update()
                    .set("status", 1)
                    .set("pre_order_id", tempId)
                    .eq("id", seatId)
                    .in("status", 0, 1)
                    .update();

            if (!updated) {
                throw BusinessException.ofUserFriendly("座位已被占用或不存在 (seatId:" + seatId + ")");
            }

            log.debug("[选座下单] 座位关联成功 - seatId:{}, tempId:{}", seatId, tempId);
        }

        cachePreOrderWithSeatIds(preOrder);

        try {
            Map<String, Object> msgBody = new HashMap<>();
            msgBody.put("tempId", tempId);
            msgBody.put("orderId", tempId);
            msgBody.put("userId", userId);
            msgBody.put("ticketId", ticketId);
            rocketMQTemplate.asyncSend(MqConstants.ORDER_TIMEOUT_TOPIC,
                    MessageBuilder.withPayload(msgBody).build(),
                    new SendCallback() {
                        @Override
                        public void onSuccess(SendResult result) {
                            log.info("[选座下单-MQ发送成功] 超时取消消息已发送，tempId:{}, msgId:{}", tempId, result.getMsgId());
                        }
                        @Override
                        public void onException(Throwable e) {
                            log.error("[选座下单-MQ发送失败] 超时取消消息发送失败，tempId:{}, error:{}", tempId, e.getMessage());
                        }
                    },
                    3000,
                    MqConstants.ORDER_TIMEOUT_DELAY_LEVEL);
            log.info("[选座下单成功] 已异步发送超时取消消息（延迟5分钟），tempId:{}, 座位数:{}", tempId, count);
        } catch (Exception mqEx) {
            log.warn("[选座下单警告] 发送超时取消消息失败（不影响下单），error:{}", mqEx.getMessage());
        }

        log.info("[选座下单成功] 创建预订单！ticketId:{}, userId:{}, tempId:{}, 座位数:{}, 总价格:{}",
                ticketId, userId, tempId, count, totalPrice);

        Map<String, Object> result = new HashMap<>();
        result.put("tempId", tempId);
        result.put("status", 0);
        result.put("quantity", count);
        result.put("unitPrice", unitPrice);
        result.put("totalPrice", totalPrice);
        result.put("seats", seatIds);
        result.put("createTime", LocalDateTime.now());
        result.put("expireTime", preOrder.getExpireTime());
        result.put("message", "请5分钟内完成支付");

        return Result.ok(result);
    }

    /**
     * 校验用户信誉分（统一复用，避免重复代码）
     *
     * @param userId 用户ID
     * @param userInfo 用户信息对象（可为null，为null时自动查询）
     * @param scene 业务场景描述（用于日志，如"秒杀"、"普通票"）
     * @return 校验不通过返回Result.fail()，通过返回null
     */
    private Result<?> validateUserCreditScore(Long userId, com.echo.entity.UserInfo userInfo, String scene) {
        if (userInfo == null) {
            userInfo = userInfoService.getById(userId);
        }

        int creditScore = (userInfo != null && userInfo.getCreditScore() != null) ? userInfo.getCreditScore() : 100;

        if (creditScore < 60) {
            LocalDateTime penaltyEndTime = userInfo != null ? userInfo.getCreditPenaltyEndTime() : null;

            if (penaltyEndTime != null && LocalDateTime.now().isBefore(penaltyEndTime)) {
                long minutesLeft = java.time.Duration.between(LocalDateTime.now(), penaltyEndTime).toMinutes();
                log.warn("[{}-风控拒绝] 用户在惩罚期内，userId:{}, creditScore:{}, 剩余时间:{}分钟",
                        scene, userId, creditScore, minutesLeft);
                return Result.fail("信誉分过低，暂时无法参与" + scene + "，剩余" + minutesLeft + "分钟后恢复");
            } else {
                // 惩罚期已过，自动恢复到60分
                if (userInfo != null) {
                    userInfo.setCreditScore(60);
                    userInfo.setCreditPenaltyEndTime(null);
                    userInfo.setUpdateTime(LocalDateTime.now());
                    userInfoService.updateById(userInfo);
                    log.info("[{}-信誉分恢复] 用户信誉分{}已过惩罚期，已恢复到60分，userId:{}", scene, creditScore, userId);
                }
            }
        }

        return null;  // 校验通过
    }

    private static String generateTempId(Long userId) {
        int suffix = ThreadLocalRandom.current().nextInt(10000, 100000);
        return "TEMP_" + System.currentTimeMillis() + "_" + userId + "_" + suffix;
    }

    private void rollbackPreOccupiedSeats(String tempId, List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            return;
        }

        log.warn("[回滚预占座位] 开始清理预占座位 - tempId:{}, 座位数:{}", tempId, seatIds.size());

        for (Long seatId : seatIds) {
            try {
                boolean updated = seatService.update()
                        .set("status", 0)
                        .set("pre_order_id", null)
                        .eq("id", seatId)
                        .eq("pre_order_id", tempId)
                        .update();

                if (updated) {
                    log.debug("[回滚预占座位] 成功 - seatId:{}", seatId);
                } else {
                    log.warn("[回滚预占座位] 失败（可能已被释放） - seatId:{}", seatId);
                }
            } catch (Exception e) {
                log.error("[回滚预占座位] 异常 - seatId:{}, error:{}", seatId, e.getMessage(), e);
            }
        }

        stringRedisTemplate.delete(RedisConstants.PENDING_PRE_ORDER + tempId);
        log.info("[回滚预占座位] 完成 - tempId:{}", tempId);
    }

    private void cachePreOrderWithSeatIds(PreOrder preOrder) {
        if (preOrder == null || preOrder.getTempId() == null) {
            return;
        }

        try {
            String cacheKey = RedisConstants.PENDING_PRE_ORDER + preOrder.getTempId();

            Map<String, Object> orderCache = new HashMap<>();
            orderCache.put("tempId", preOrder.getTempId());
            orderCache.put("userId", preOrder.getUserId());
            orderCache.put("ticketId", preOrder.getTicketId());
            orderCache.put("showId", preOrder.getShowId());
            orderCache.put("seatIds", preOrder.getSeatIds());
            orderCache.put("quantity", preOrder.getQuantity());
            orderCache.put("status", preOrder.getStatus());
            orderCache.put("createTime", preOrder.getCreateTime() != null ? preOrder.getCreateTime().toString() : null);

            stringRedisTemplate.opsForValue().set(
                    cacheKey,
                    com.alibaba.fastjson.JSON.toJSONString(orderCache),
                    MqConstants.ORDER_TIMEOUT_MINUTES,
                    TimeUnit.MINUTES
            );

            log.info("[Redis缓存] 预订单已缓存（含座位列表） - tempId:{}, 座位数:{}",
                    preOrder.getTempId(),
                    preOrder.getSeatIds() != null ? preOrder.getSeatIds().size() : 0);
        } catch (Exception e) {
            log.warn("[Redis缓存] 缓存失败（不影响主流程） - tempId:{}, error:{}",
                    preOrder.getTempId(), e.getMessage());
        }
    }

    private void loadSeatIdsFromDatabase(PreOrder preOrder) {
        if (preOrder == null || preOrder.getTempId() == null) {
            return;
        }

        try {
            List<Seat> occupiedSeats = seatService.list(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Seat>()
                            .eq("pre_order_id", preOrder.getTempId())
                            .eq("status", 1)
            );

            if (occupiedSeats != null && !occupiedSeats.isEmpty()) {
                List<Long> seatIds = occupiedSeats.stream()
                        .map(Seat::getId)
                        .sorted()
                        .collect(java.util.stream.Collectors.toList());

                preOrder.setSeatIds(seatIds);
                log.info("[数据库查询] 从座位表加载预占座位列表 - tempId:{}, 座位数:{}",
                        preOrder.getTempId(), seatIds.size());
            } else {
                log.warn("[数据库查询] 未找到预占座位 - tempId:{}, 可能已被释放或不存在",
                        preOrder.getTempId());
            }
        } catch (Exception e) {
            log.error("[数据库查询] 加载座位列表异常 - tempId:{}, error:{}",
                    preOrder.getTempId(), e.getMessage(), e);
        }
    }

    private Result<?> ResultFail(String msg) {
        return Result.fail(msg);
    }

    /**
     * 生成座位标签（如：A区-3排-5座）
     */
    private String getSeatLabel(com.echo.entity.Seat seat) {
        if (seat == null) return "未知座位";

        StringBuilder label = new StringBuilder();

        // 区域
        if (seat.getArea() != null && !seat.getArea().isEmpty()) {
            label.append(seat.getArea()).append("-");
        }

        // 排号（转换为字母）
        if (seat.getRowNum() != null && seat.getRowNum() > 0) {
            int rowNum = seat.getRowNum();
            if (rowNum <= 26) {
                label.append((char)(64 + rowNum)).append("排-");
            } else {
                label.append(rowNum).append("排-");
            }
        }

        // 座位号
        if (seat.getColNum() != null && seat.getColNum() > 0) {
            label.append(seat.getColNum()).append("座");
        }

        return !label.isEmpty() ? label.toString() : "座位#" + seat.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> payOrder(String tempId, Integer usePoints) {
        log.info("[模拟支付] 开始处理支付请求，tempId:{}, usePoints:{}", tempId, usePoints);

        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("[支付拒绝] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }
        Long userId = user.getId();

        PreOrder preOrder = preOrderService.getById(tempId);
        if (preOrder == null) {
            log.warn("[支付拒绝] 预订单不存在，tempId:{}", tempId);
            return Result.fail("预订单不存在或已过期，请重新下单");
        }

        loadSeatIdsFromDatabase(preOrder);

        if (!preOrder.getUserId().equals(userId)) {
            log.warn("[支付拒绝] 预订单不属于当前用户，tempId:{}, preOrderUserId:{}, currentUserId:{}",
                    tempId, preOrder.getUserId(), userId);
            return Result.fail(AppMessages.ORDER_NOT_BELONG);
        }

        Integer status = preOrder.getStatus();
        if (PreOrderStatus.CANCELLED.getCode() == status) {
            log.warn("[支付拒绝] 预订单已取消，tempId:{}", tempId);
            return Result.fail(AppMessages.ORDER_CANCELLED);
        }

        com.echo.statemachine.OrderStateMachine.TransitionContext payContext =
                com.echo.statemachine.OrderStateMachine.TransitionContext.of(
                        userId, preOrder.getTicketId(), preOrder.getShowId(),
                        preOrder.getQuantity(), "积分抵扣:" + usePoints
                );

        var stateTransitionResult = orderStateMachine.transition(
                tempId,
                // 显式传订单状态码：预订单状态(PreOrderStatus)与订单状态(OrderStatus)编码仅在0/4上恰好重合，语义不同，
                // 此处校验的是订单"预占(0)→已支付(2)"流转；混用预订单状态会在其未来扩展状态值时静默错映射
                OrderStatus.RESERVED.getCode(),
                OrderStatus.PAID.getCode(),
                payContext
        );

        if (!stateTransitionResult.isSuccess()) {
            log.warn("[支付拒绝] 状态机校验失败 - tempId:{}, 原因:{}", tempId, stateTransitionResult.getErrorMessage());
            return Result.fail(stateTransitionResult.getErrorMessage());
        }

        log.info("[支付] 状态机校验通过 - eventId:{}, 准备执行支付逻辑", stateTransitionResult.getEvent().getEventId());

        Ticket ticket = ticketService.getById(preOrder.getTicketId());
        if (ticket == null) {
            log.error("[支付失败] 票种不存在，ticketId:{}", preOrder.getTicketId());
            return Result.fail(AppMessages.ORDER_TICKET_NOT_EXIST);
        }

        Show show = showService.getById(preOrder.getShowId());
        if (show != null) {
            boolean showEnded = false;

            if (show.getEndTime() != null) {
                showEnded = LocalDateTime.now().isAfter(show.getEndTime());
                log.info("[支付校验] 使用endTime字段判断 - showId:{}, endTime:{}, 当前时间:{}, 是否已结束:{}",
                    show.getId(), show.getEndTime(), LocalDateTime.now(), showEnded);
            } else if (show.getOpenHours() != null) {
                try {
                    String[] parts = show.getOpenHours().split("~");
                    if (parts.length >= 2) {
                        LocalDate showEndDate = LocalDate.parse(parts[1].trim());
                        showEnded = LocalDate.now().isAfter(showEndDate);
                    } else if (parts.length == 1) {
                        LocalDate showDate = LocalDate.parse(parts[0].trim());
                        showEnded = LocalDate.now().isAfter(showDate);
                    }
                    log.info("[支付校验] 使用openHours字段兜底 - showId:{}, openHours:{}, 是否已结束:{}",
                        show.getId(), show.getOpenHours(), showEnded);
                } catch (Exception e) {
                    log.warn("[支付] 演出时间解析失败，跳过校验，openHours:{}, error:{}", show.getOpenHours(), e.getMessage());
                }
            }

            if (showEnded) {
                log.warn("[支付拒绝] 演出已结束，showId:{}", show.getId());
                return Result.fail(AppMessages.ORDER_SHOW_ENDED);
            }
        }

        com.echo.entity.UserInfo userInfo = userInfoService.getById(userId);
        int currentPoints = (userInfo != null && userInfo.getCredits() != null) ? userInfo.getCredits() : 0;
        int pointsToUse = 0;
        long discountAmount = 0;

        if (usePoints != null && usePoints > 0) {
            if (currentPoints < usePoints) {
                return Result.fail(AppMessages.POINTS_NOT_ENOUGH);
            }

            long frozenUnitPrice = preOrder.getPayValue() != null ? preOrder.getPayValue() : 0L;
            long orderAmount = frozenUnitPrice * preOrder.getQuantity();
            long maxDiscountAmount = orderAmount / 2;
            long requestedDiscountAmount = usePoints / 10;

            if (requestedDiscountAmount > maxDiscountAmount) {
                log.warn("[积分抵扣拒绝] 用户{}请求抵扣{}元，超过订单金额50%上限（{}元）",
                        userId, requestedDiscountAmount, maxDiscountAmount);
                return Result.fail("积分抵扣不能超过订单金额的50%（最多可抵扣" + maxDiscountAmount + "元）");
            }

            pointsToUse = usePoints;
            discountAmount = requestedDiscountAmount;
            log.info("[积分抵现] 用户{}使用{}积分，抵扣{}元（订单金额{}元，上限{}元）",
                    userId, pointsToUse, discountAmount, orderAmount, maxDiscountAmount);
        }

        // ========== 关键事务路径：任何异常直接抛出，@Transactional 回滚 ==========

        boolean isSeckillTicket = ticket.getType() != null && ticket.getType() == 1;
        if (isSeckillTicket) {
            int seckillQty = preOrder.getQuantity() != null ? preOrder.getQuantity() : 1;
            log.info("[支付-秒杀] MySQL乐观锁前置扣减，ticketId:{}, quantity:{}", preOrder.getTicketId(), seckillQty);

            boolean seckillStockReduced = seckillTicketService.update(
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.echo.entity.SeckillTicket>()
                            .setSql("stock = stock - " + seckillQty)
                            .eq(SeckillTicket::getTicketId, preOrder.getTicketId())
                            .ge(SeckillTicket::getStock, seckillQty)
            );

            if (!seckillStockReduced) {
                log.error("[支付-秒杀] MySQL乐观锁扣减失败，补偿Redis库存+清除用户购买标记，ticketId:{}, userId:{}, quantity:{}",
                        preOrder.getTicketId(), userId, seckillQty);
                rollbackSeckillRedisFull(preOrder.getTicketId(), userId, seckillQty);
                throw new com.echo.exception.BusinessException(
                        "秒杀库存校验失败，请重新下单",
                        org.springframework.http.HttpStatus.CONFLICT);
            }

            log.info("[支付-秒杀] MySQL乐观锁扣减成功，继续创建子订单 - ticketId:{}", preOrder.getTicketId());
        }

        SeatStrategy strategy = seatStrategyFactory.getStrategy(ticket.getHasSeats());
        log.info("[支付] 调用策略模式拆单 - tempId:{}, 票种类型:{}",
                tempId, ticket.getHasSeats() != null && ticket.getHasSeats() ? "有座票" : "无座票");

        List<TicketOrder> subOrders = strategy.buildSubOrdersOnPayment(preOrder, ticket, pointsToUse, discountAmount);

        boolean allSaved = saveBatch(subOrders);
        if (!allSaved) {
            throw new com.echo.exception.BusinessException(
                    "批量保存子订单失败，tempId:" + tempId,
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
        log.info("[支付成功] 策略模式拆单完成 - tempId:{}, 共创建{}个子订单", tempId, subOrders.size());

        preOrderService.removeById(tempId);
        stringRedisTemplate.delete(RedisConstants.PENDING_PRE_ORDER + tempId);
        log.info("[清理预订单] 预订单已删除，tempId:{}", tempId);

        if (userInfo == null) {
            userInfo = new com.echo.entity.UserInfo();
            userInfo.setUserId(userId);
            userInfo.setCredits(10);
            userInfo.setCreditScore(100);
            userInfo.setCreateTime(LocalDateTime.now());
            userInfo.setUpdateTime(LocalDateTime.now());
            userInfoService.save(userInfo);
            log.info("[积分] 支付成功赠送10积分，userId:{}, 当前积分:10（新用户）", userId);
        } else {
            boolean creditsUpdated = userInfoService.update()
                    .setSql("credits = GREATEST(credits - " + pointsToUse + ", 0) + " + com.echo.utils.BusinessConstants.POINTS_PAYMENT_REWARD_VALUE)
                    .eq("user_id", userId)
                    .update();
            int newCredits = (currentPoints - pointsToUse < 0 ? 0 : currentPoints - pointsToUse) + 10;
            log.info("[积分] 支付成功赠送10积分，使用{}积分，userId:{}, 当前积分(估算):{}, updated:{}", pointsToUse, userId, newCredits, creditsUpdated);
        }

        // ========== 非关键路径：独立 try-catch，失败不回滚事务 ==========

        String primaryVerificationCode = subOrders.get(0).getVerificationCode();

        try {
            for (int i = 0; i < subOrders.size(); i++) {
                TicketOrder subOrder = subOrders.get(i);
                stringRedisTemplate.opsForValue().set(
                        VERIFY_CODE_KEY + subOrder.getVerificationCode(),
                        String.valueOf(subOrder.getId()) + ":" + i,
                        30,
                        java.util.concurrent.TimeUnit.DAYS
                );
            }
            log.info("[核销码写入完成] tempId:{}, 共{}个核销码", tempId, subOrders.size());
        } catch (Exception e) {
            log.error("[核销码写入失败] 不阻塞主流程 - tempId:{}, error:{}", tempId, e.getMessage());
        }

        try {
            OperationLog payLog = new OperationLog();
            payLog.setOperatorId(userId);
            payLog.setOperatorName("用户#" + userId);
            payLog.setOperationType("PAY_SUCCESS");
            payLog.setTargetType("order");
            payLog.setTargetIds(tempId);
            payLog.setOperationDetail(String.format("预订单 %s 支付成功，票种：%s（共%d张），积分抵扣：%d分",
                    tempId, ticket.getTitle(), subOrders.size(), discountAmount));
            payLog.setResultStatus(OperationResultStatus.SUCCESS);
            payLog.setIpAddress(getClientIp());
            operationLogService.save(payLog);
            log.info("[操作日志] 支付操作已记录 - tempId:{}", tempId);
        } catch (Exception e) {
            log.warn("[操作日志] 记录支付操作失败（不影响主流程） - tempId:{}, error:{}", tempId, e.getMessage());
        }

        try {
            sendNotification(userId, NotificationType.PAYMENT_SUCCESS, NOTIFICATION_PAY_SUCCESS_TITLE,
                    NOTIFICATION_PAY_SUCCESS_CONTENT + primaryVerificationCode + "（共" + subOrders.size() + "张）",
                    subOrders.get(0).getId());
        } catch (Exception e) {
            log.warn("[通知] 发送支付成功通知失败（不影响主流程） - userId:{}, error:{}", userId, e.getMessage());
        }

        try {
            String primaryOrderId = String.valueOf(subOrders.get(0).getId());
            com.echo.event.OrderStatusChangeEvent payEvent = com.echo.event.OrderStatusChangeEvent.payEvent(
                    primaryOrderId, userId, preOrder.getTicketId(), preOrder.getShowId(), 0,
                    com.echo.dto.OrderStatusDTO.PAID(),
                    "支付成功，quantity=" + subOrders.size()
            );
            applicationEventPublisher.publishEvent(payEvent);
            log.info("[支付成功] 已发布 OrderPaidEvent - orderId:{}, ticketId:{}, showId:{}, quantity:{}",
                    primaryOrderId, preOrder.getTicketId(), preOrder.getShowId(), subOrders.size());
        } catch (Exception e) {
            log.warn("[事件] 发布 OrderPaidEvent 失败（不影响主流程） - tempId:{}, error:{}", tempId, e.getMessage());
        }

        try {
            stringRedisTemplate.delete(RedisConstants.CACHE_USER_INFO_KEY + userId);
        } catch (Exception cacheEx) {
            log.warn("[积分] 清理用户信息缓存失败（不影响主流程）- userId:{}, error:{}", userId, cacheEx.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("tempId", tempId);
        result.put("orderIds", subOrders.stream().map(TicketOrder::getId).toList());
        result.put("quantity", subOrders.size());
        result.put("primaryVerificationCode", primaryVerificationCode);
        result.put("message", "支付成功！");

        log.info("[模拟支付成功] 全部流程完成 - tempId:{}, 子订单数:{}, 核销码:{}",
                tempId, subOrders.size(), primaryVerificationCode);

        return Result.ok(result);
    }

    @Override
    public Result<?> queryMyOrders(Integer status, Integer current, Integer size) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }
        Long userId = user.getId();

        int pageSize = (size != null && size > 0) ? size : 10;
        Page<TicketOrder> page = query()
                .eq("user_id", userId)
                .eq(status != null && status >= 0, "status", status)
                .orderByDesc("create_time")
                .page(new Page<>(current != null ? current : 1, pageSize));

        List<TicketOrder> records = page.getRecords();
        if (records.isEmpty()) {
            return Result.ok(page);
        }

        List<Long> ticketIds = records.stream()
                .map(TicketOrder::getTicketId)
                .toList();
        Map<Long, Ticket> ticketMap = ticketService.listByIds(ticketIds).stream()
                .collect(Collectors.toMap(Ticket::getId, Function.identity()));

        List<Long> showIds = ticketMap.values().stream()
                .map(Ticket::getShowId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Show> showMap = showIds.isEmpty() ? Collections.emptyMap()
                : showService.listByIds(showIds).stream()
                        .collect(Collectors.toMap(Show::getId, Function.identity()));

        User currentUser = userService.getById(userId);
        String userName = currentUser != null ? currentUser.getNickName() : null;

        for (TicketOrder order : records) {
            order.setUserName(userName);

            Ticket ticket = ticketMap.get(order.getTicketId());
            if (ticket != null) {
                order.setTicketTitle(ticket.getTitle());
                order.setShowId(ticket.getShowId());

                // 计算实际支付总价（单价 × 数量）
                int quantity = order.getQuantity() != null ? order.getQuantity() : 1;
                long unitPrice = ticket.getPayValue() != null ? ticket.getPayValue() : 0L;
                long totalPrice = unitPrice * quantity;

                order.setPayValue(totalPrice);
                order.setActualValue(ticket.getActualValue());

                Show show = showMap.get(ticket.getShowId());
                if (show != null) {
                    order.setShowName(show.getName());
                }

                // 查询订单关联的座位信息
                if (ticket.getHasSeats() != null && ticket.getHasSeats()) {
                    try {
                        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.Seat> seatQuery =
                            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
                        seatQuery.eq("order_id", order.getId());
                        java.util.List<com.echo.entity.Seat> orderSeats = seatService.list(seatQuery);

                        if (!orderSeats.isEmpty()) {
                            // 将座位信息转换为可序列化的格式
                            java.util.List<java.util.Map<String, Object>> seatInfoList = new java.util.ArrayList<>();
                            for (com.echo.entity.Seat seat : orderSeats) {
                                java.util.Map<String, Object> seatInfo = new java.util.HashMap<>();
                                seatInfo.put("id", seat.getId());
                                seatInfo.put("area", seat.getArea());
                                seatInfo.put("rowNum", seat.getRowNum());
                                seatInfo.put("colNum", seat.getColNum());
                                seatInfo.put("seatLabel", getSeatLabel(seat));
                                seatInfoList.add(seatInfo);
                            }
                            // 设置到订单对象的扩展字段
                            order.setSeatInfoList(seatInfoList);
                            log.debug("[订单列表] orderId:{}, 座位数量:{}", order.getId(), orderSeats.size());
                        }
                    } catch (Exception e) {
                        log.warn("[订单列表] 查询座位信息失败，orderId:{}, error:{}", order.getId(), e.getMessage());
                    }
                }

                log.debug("[订单列表] orderId:{}, quantity:{}, unitPrice:, totalPrice:{}",
                        order.getId(), quantity, unitPrice, totalPrice);

                // [核销] 核销码处理（含自动修复）
                if (OrderStatus.isPostPayment(order.getStatus())) {
                    if (order.getVerificationCode() == null || order.getVerificationCode().isEmpty()) {
                        // 订单已支付但无核销码，自动生成并保存
                        log.warn("[订单列表] [警告] 订单已支付但无核销码，开始自动修复 - orderId:{}", order.getId());
                        try {
                            String newCode = generateVerificationCode();
                            order.setVerificationCode(newCode);
                            boolean updateSuccess = updateById(order);

                            if (updateSuccess) {
                                // 单票订单默认ticketIndex=0，多票订单需要特殊处理
                                stringRedisTemplate.opsForValue().set(
                                    VERIFY_CODE_KEY + newCode,
                                    order.getId().toString() + ":0",
                                    30,
                                    TimeUnit.DAYS
                                );
                                log.info("[订单列表] [成功] 自动修复成功 - orderId:{}, 新核销码:{}", order.getId(), newCode);
                            }
                        } catch (Exception e) {
                            log.error("[订单列表] [失败] 自动修复失败 - orderId:{}, error:{}", order.getId(), e.getMessage());
                        }
                    } else {
                        log.debug("[订单列表] 核销码 - orderId:{}, code:{}", order.getId(), order.getVerificationCode());
                    }
                }
            }
        }

        page.setRecords(records);
        return Result.ok(page);
    }

    /**
     * 用户主动取消订单（仅限未支付预订单）
     * <p>
     * 库存回滚策略（核心设计依据：MySQL扣减时机）：
     * <p>
     * 普通票：下单时预扣 tb_ticket.stock（-N），取消时回滚 MySQL（+N）+ 释放座位
     * 秒杀票：下单时仅扣 Redis（-N），MySQL 在支付时才扣 tb_seckill_ticket.stock（-N），
     *         取消时仅回滚 Redis（+N），跳过 MySQL（从未 -N，+N 会导致库存虚高）
     * <p>
     * 已支付订单应走退款流程（refundOrder），此处仅处理 PreOrder 阶段的未支付订单。
     *
     * @param orderId 预订单tempId或正式订单ID
     * @return 操作结果
     */
    @Override
    public Result<?> cancelOrder(String orderId) {
        log.info("[用户取消订单] 开始处理取消请求，orderId:{}", orderId);

        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("[取消拒绝] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }
        Long userId = user.getId();

        PreOrder preOrder = preOrderService.getById(orderId);
        if (preOrder == null) {
            TicketOrder paidOrder = null;
            try {
                paidOrder = getById(Long.valueOf(orderId));
            } catch (NumberFormatException ignored) {
            }
            if (paidOrder != null) {
                log.warn("[取消拒绝] 已支付订单请申请退款，orderId:{}", orderId);
                return Result.fail(AppMessages.ORDER_PAID_CANNOT_CANCEL);
            }
            log.warn("[取消失败] 订单不存在，orderId:{}", orderId);
            return Result.fail(AppMessages.ORDER_NOT_EXIST);
        }

        if (!preOrder.getUserId().equals(userId)) {
            log.warn("[取消拒绝] 无权操作他人订单，orderId:{}, userId:{}", orderId, userId);
            return Result.fail(AppMessages.ORDER_NO_PERMISSION);
        }

        if (PreOrderStatus.CANCELLED.getCode() == preOrder.getStatus()) {
            log.warn("[取消拒绝] 预订单已取消，orderId:{}", orderId);
            return Result.fail(AppMessages.ORDER_ALREADY_CANCELLED);
        }

        if (PreOrderStatus.PENDING_PAYMENT.getCode() != preOrder.getStatus()) {
            log.warn("[取消拒绝] 预订单状态不支持取消，orderId:{}, status:{}", orderId, preOrder.getStatus());
            return Result.fail(AppMessages.ORDER_STATUS_UNSUPPORTED);
        }

        boolean claimed = preOrderService.update()
                .set("status", PreOrderStatus.CANCELLED.getCode())
                .eq("temp_id", orderId)
                .eq("status", PreOrderStatus.PENDING_PAYMENT.getCode())
                .update();
        if (!claimed) {
            log.warn("[取消拒绝] 幂等拦截：预订单状态已被其他线程更新，orderId:{}", orderId);
            return Result.fail(AppMessages.ORDER_STATUS_UNSUPPORTED);
        }

        Long ticketId = preOrder.getTicketId();
        int cancelQuantity = (preOrder.getQuantity() != null) ? preOrder.getQuantity() : 1;

        Ticket ticket = ticketService.getById(ticketId);
        boolean isSeckillOrder = ticket != null && ticket.getType() != null && ticket.getType() == 1;

        try {
            if (isSeckillOrder) {
                log.info("[取消-秒杀票] 仅回滚Redis库存（MySQL在支付时才扣减，下单阶段从未-N），ticketId:{}, userId:{}, quantity:{}",
                        ticketId, userId, cancelQuantity);
                rollbackSeckillRedisOnCancel(ticketId, userId, cancelQuantity, orderId);
            } else {
                log.info("[取消-普通票] 回滚MySQL库存（下单时已预扣-N）+ 释放座位，ticketId:{}, userId:{}, quantity:{}",
                        ticketId, userId, cancelQuantity);
                restoreNormalStockAndSeats(ticketId, cancelQuantity, orderId);
            }

            preOrderService.removeById(orderId);

            try {
                String cacheKey = RedisConstants.PENDING_PRE_ORDER + orderId;
                stringRedisTemplate.delete(cacheKey);
            } catch (Exception cacheEx) {
                log.warn("[取消订单] Redis缓存清理失败（不影响主流程） - error:{}", cacheEx.getMessage());
            }

            try {
                com.echo.event.OrderStatusChangeEvent cancelEvent = com.echo.event.OrderStatusChangeEvent.cancelEvent(
                        orderId,
                        userId,
                        ticketId,
                        PreOrderStatus.PENDING_PAYMENT.getCode(),
                        com.echo.dto.OrderStatusDTO.CANCELLED(),
                        "用户主动取消预订单，cancelQuantity=" + cancelQuantity
                );
                applicationEventPublisher.publishEvent(cancelEvent);
                log.info("[取消订单] 已发布 OrderCancelledEvent - orderId:{}, ticketId:{}", orderId, ticketId);
            } catch (Exception eventEx) {
                log.warn("[取消订单] 发布领域事件失败（不影响主流程） - error:{}", eventEx.getMessage());
            }

            try {
                String userOrderKey = "user:order:list:" + userId;
                stringRedisTemplate.delete(userOrderKey);
            } catch (Exception cacheEx) {
                log.warn("[取消订单] 清理用户订单列表缓存失败（不影响主流程） - error:{}", cacheEx.getMessage());
            }

            log.info("[用户取消订单] 完成 - orderId:{}, userId:{}, ticketId:{}, seckill:{}", orderId, userId, ticketId, isSeckillOrder);
            return Result.ok();

        } catch (Exception e) {
            log.error("[取消失败] orderId:{}, error:{}", orderId, e.getMessage(), e);
            return Result.fail(AppMessages.ORDER_CANCEL_FAIL);
        }
    }

    private void rollbackSeckillRedisOnCancel(Long ticketId, Long userId, int quantity, String preOrderId) {
        try {
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + ticketId;
            String orderKey = RedisConstants.SECKILL_ORDER_KEY + ticketId;
            String pendingSeckillOrderKey = RedisConstants.PENDING_SECKILL_ORDER + preOrderId;

            List<String> keys = java.util.Arrays.asList(stockKey, orderKey, pendingSeckillOrderKey);
            Long result = stringRedisTemplate.execute(
                    seckillRollbackScript,
                    keys,
                    String.valueOf(userId),
                    String.valueOf(quantity)
            );

            if (result != null && result == 0) {
                log.info("[取消-秒杀Redis回滚] 成功，库存+{}且用户记录已移除，ticketId:{}, userId:{}",
                        quantity, ticketId, userId);
            } else {
                log.warn("[取消-秒杀Redis回滚] 返回异常值，需补偿修复，ticketId:{}, userId:{}, quantity:{}, result:{}",
                        ticketId, userId, quantity, result);
                createRedisCompensateTask(
                        ticketId,
                        userId,
                        "REFUND_SECKILL_RELEASE",
                        "取消订单Redis回滚返回异常值：" + result,
                        quantity
                );
            }
        } catch (Exception redisEx) {
            log.error("[取消-秒杀Redis回滚] 连接异常，创建补偿任务，ticketId:{}, userId:{}, quantity:{}, error:{}",
                    ticketId, userId, quantity, redisEx.getMessage());
            createRedisCompensateTask(
                    ticketId,
                    userId,
                    "REFUND_SECKILL_RELEASE",
                    "取消订单Redis连接异常：" + redisEx.getMessage(),
                    quantity
            );
        }
    }

    private void restoreNormalStockAndSeats(Long ticketId, int quantity, String preOrderId) {
        boolean stockReleased = ticketService.update()
                .setSql("stock = LEAST(total_stock, stock + " + quantity + ")")
                .eq("id", ticketId)
                .update();

        if (stockReleased) {
            log.info("[取消-普通票库存恢复] 成功，ticketId:{}, quantity:{}", ticketId, quantity);
        } else {
            log.error("[取消-普通票库存恢复] 失败，ticketId:{}", ticketId);
        }

        List<Seat> occupiedSeats = seatService.list(
                new QueryWrapper<Seat>()
                        .eq("pre_order_id", preOrderId)
                        .in("status", 1)
        );

        if (occupiedSeats.isEmpty()) {
            return;
        }

        int releasedCount = 0;
        for (Seat seat : occupiedSeats) {
            boolean updated = seatService.update()
                    .set("status", 0)
                    .set("pre_order_id", null)
                    .eq("id", seat.getId())
                    .eq("pre_order_id", preOrderId)
                    .update();
            if (updated) {
                String lockKey = RedisConstants.LOCK_SEAT_KEY + ticketId + ":" + seat.getId();
                stringRedisTemplate.delete(lockKey);
                releasedCount++;
            }
        }
        log.info("[取消-释放座位] 完成，总数:{}, 成功:{}, preOrderId:{}", occupiedSeats.size(), releasedCount, preOrderId);
    }

    @Override
    public Result<?> refundOrder(Long orderId) {
        log.info("[退款申请] 开始处理退款请求，orderId:{}", orderId);

        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("[退款拒绝] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }
        Long userId = user.getId();

        TicketOrder order = getById(orderId);
        if (order == null) {
            log.warn("[退款拒绝] 订单不存在，orderId:{}", orderId);
            return Result.fail(AppMessages.ORDER_NOT_EXIST);
        }
        if (!order.getUserId().equals(userId)) {
            log.warn("[退款拒绝] 订单不属于当前用户，orderId:{}, userId:{}", orderId, userId);
            return Result.fail(AppMessages.ORDER_NOT_BELONG);
        }

        int status = order.getStatus() != null ? order.getStatus() : OrderStatus.RESERVED.getCode();
        if (!OrderStatus.canApplyRefund(status)) {
            log.warn("[退款拒绝] 订单状态不支持申请退款，orderId:{}, status:{}", orderId, status);
            return Result.fail(AppMessages.REFUND_NOT_ALLOWED);
        }

        try {
            // 状态写入统一走状态机唯一入口：规则校验 + 条件更新(并发安全)；
            // 已支付(2)→退款中(6)与已拒绝(7)→重新申请(6)均由此流转
            com.echo.statemachine.OrderStateMachine.TransitionContext refundContext =
                    com.echo.statemachine.OrderStateMachine.TransitionContext.of(
                            order.getUserId(), order.getTicketId(), order.getShowId(),
                            order.getQuantity(), "用户申请退款");
            var stateResult = orderStateMachine.applyTransition(
                    String.valueOf(orderId), OrderStatus.REFUND_PENDING.getCode(), null, refundContext);
            if (!stateResult.isSuccess()) {
                log.warn("[申请退款失败] 状态机拦截 - orderId:{}, 原因:{}", orderId, stateResult.getErrorMessage());
                return Result.fail(stateResult.getErrorMessage());
            }
            log.info("[申请退款成功] 订单状态已更新为'申请退款中', orderId:{}, userId:{}", orderId, userId);

            try {
                applicationEventPublisher.publishEvent(stateResult.getEvent());
                log.info("[申请退款] 已发布 OrderRefundEvent - orderId:{}, ticketId:{}", orderId, order.getTicketId());
            } catch (Exception eventEx) {
                log.warn("[申请退款] 发布领域事件失败（不影响主流程） - error:{}", eventEx.getMessage());
            }

            // 清理订单相关缓存（与 cancelOrder / adminRefundOrder 保持一致）
            try {
                String userOrderKey = "user:order:list:" + userId;
                stringRedisTemplate.delete(userOrderKey);
                log.debug("[申请退款] 已清理用户订单列表缓存 - userId:{}", userId);
            } catch (Exception cacheEx) {
                log.warn("[申请退款] 清理缓存失败（不影响主流程） - error:{}", cacheEx.getMessage());
            }

            return Result.ok();
        } catch (Exception e) {
            log.error("[申请退款失败] orderId:{}, userId:{}, error:{}", orderId, userId, e.getMessage(), e);
            return Result.fail(AppMessages.REFUND_FAIL);
        }
    }

    /**
     * 管理员审批退款（不校验用户登录和订单归属）
     * <p>
     * 与 refundOrder 的区别：
     * - 不调用 UserHolder.getUser()（管理员 token 中无 UserDTO）
     * - 不校验 order.userId == userId（订单不属于管理员）
     * - 仅校验订单状态为已支付(status=2)
     *
     * @param orderId 订单ID
     * @return 操作结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)  // Bug20修复：多操作（订单状态+信誉分+库存+Redis）必须有事务保护
    public Result<?> adminRefundOrder(Long orderId) {
        log.info("[管理退款] 管理员审批退款，orderId:{}", orderId);

        TicketOrder order = getById(orderId);
        if (order == null) {
            log.warn("[管理退款拒绝] 订单不存在，orderId:{}", orderId);
            return Result.fail(AppMessages.ORDER_NOT_EXIST);
        }

        int status = order.getStatus() != null ? order.getStatus() : OrderStatus.RESERVED.getCode();

        // 根据不同状态返回明确的错误提示
        if (OrderStatus.REFUNDED.getCode() == status) {
            log.info("[管理退款] 订单已退款，无需重复操作，orderId:{}", orderId);
            return Result.ok(AppMessages.REFUND_ALREADY_COMPLETED);
        }

        if (OrderStatus.CANCELLED.getCode() == status) {
            log.warn("[管理退款拒绝] 订单已取消，无法退款，orderId:{}, status:{}", orderId, status);
            return Result.fail(AppMessages.REFUND_ORDER_CANCELLED);
        }

        if (OrderStatus.PAID.getCode() == status || OrderStatus.USED.getCode() == status) {
            log.warn("[管理退款拒绝] 订单未申请退款，请先让用户发起退款申请，orderId:{}, status:{}", orderId, status);
            return Result.fail(AppMessages.REFUND_NO_APPLICATION);
        }

        if (OrderStatus.REFUND_PENDING.getCode() != status) {
            log.warn("[管理退款拒绝] 订单状态异常，无法审批，orderId:{}, status:{}", orderId, status);
            return Result.fail(AppMessages.REFUND_NOT_ALLOWED);
        }

        try {
            // 状态写入统一走状态机唯一入口：规则校验(仅退款中6可退款) + 条件更新(并发安全)
            java.util.Map<String, Object> refundExtra = new java.util.HashMap<>();
            LocalDateTime now = LocalDateTime.now();
            refundExtra.put("refund_time", now);
            refundExtra.put("update_time", now);
            com.echo.statemachine.OrderStateMachine.TransitionContext refundContext =
                    com.echo.statemachine.OrderStateMachine.TransitionContext.of(
                            order.getUserId(), order.getTicketId(), order.getShowId(),
                            order.getQuantity(), "管理员审批退款");
            var stateResult = orderStateMachine.applyTransition(
                    String.valueOf(orderId), OrderStatus.REFUNDED.getCode(), refundExtra, refundContext);
            if (!stateResult.isSuccess()) {
                log.warn("[管理退款失败] 状态机拦截 - orderId:{}, 原因:{}", orderId, stateResult.getErrorMessage());
                return Result.fail(stateResult.getErrorMessage());
            }
            // 保持内存实体与落库结果一致，供后续业务逻辑读取
            order.setStatus(OrderStatus.REFUNDED.getCode());
            order.setRefundTime(now);
            order.setUpdateTime(now);
            log.info("[管理退款成功] orderId:{}, userId:{}", orderId, order.getUserId());

            logRefundOperation(orderId, order.getUserId());

            sendNotification(order.getUserId(), NotificationType.REFUND_SUCCESS, NOTIFICATION_REFUND_SUCCESS_TITLE, NOTIFICATION_REFUND_SUCCESS_CONTENT, orderId);

            com.echo.entity.UserInfo userInfo = userInfoService.getById(order.getUserId());
            if (userInfo != null) {
                int score = userInfo.getCreditScore() != null ? userInfo.getCreditScore() : 100;
                int newScore = Math.max(0, score - 5);
                userInfo.setCreditScore(newScore);
                userInfo.setUpdateTime(LocalDateTime.now());

                // 如果信誉分低于60分，设置24小时惩罚期
                if (newScore < 60) {
                    LocalDateTime penaltyEndTime = LocalDateTime.now().plusHours(24);
                    userInfo.setCreditPenaltyEndTime(penaltyEndTime);
                    log.info("[风控] 退款扣信誉分5分，userId:{}, 当前信誉分:{}，已设置惩罚期至:{}",
                            order.getUserId(), newScore, penaltyEndTime);
                } else {
                    // 清除惩罚期（如果之前有的话）
                    userInfo.setCreditPenaltyEndTime(null);
                    log.info("[风控] 退款扣信誉分5分，userId:{}, 当前信誉分:{}", order.getUserId(), newScore);
                }

                userInfoService.updateById(userInfo);

                // 【P2修复】信誉分变动后失效 user-info 缓存，避免用户主页/个人中心脏读信誉分
                try {
                    stringRedisTemplate.delete(RedisConstants.CACHE_USER_INFO_KEY + order.getUserId());
                } catch (Exception cacheEx) {
                    log.warn("[管理退款] 清理用户信息缓存失败（不影响主流程）- userId:{}, error:{}", order.getUserId(), cacheEx.getMessage());
                }
            }

            // 原子性释放秒杀票库存 + 清除Redis用户记录（优化版）
            // 核心策略：先MySQL后Redis + 失败补偿机制
            //
            // 执行流程：
            //   Step 1: MySQL 更新库存表（在 @Transactional 事务内）
            //     ├── 成功 → 继续 Step 2
            //     └── 失败 → 抛出异常 → 整个事务回滚（包括订单状态、信誉分等所有操作）
            //
            //   Step 2: Redis Lua 原子操作（库存+1, SREM用户）
            //     ├── 成功 → 完成
            //     └── 失败 → 记录补偿任务到数据库，后台异步重试（不影响主流程返回成功）
            //
            // 为什么选择这个顺序？
            //    MySQL 是关系型数据的核心，必须优先保证其一致性
            //    MySQL 失败可完全回滚（@Transactional），无残留数据
            //    Redis 失败可通过补偿机制修复（最终一致性）
            //    退款是低频操作（vs 秒杀的万级QPS），不会造成MySQL压力
            //
            boolean isSeckillTicket = false;
            Long redisCompensateTaskId = null;

            try {
                String seckillStockKey = RedisConstants.SECKILL_STOCK_KEY + order.getTicketId();
                // 【边界修复】用数据层（tb_seckill_ticket 存在性）判断是否为秒杀票，而非 Redis key 是否存在。
                // 原因：秒杀结束后库存 key 会过期（hasKey=false），若据此跳过 Redis 操作，则 SREM 不执行，
                //      导致 Redis Set 中用户记录永久残留、退款后无法重买。
                isSeckillTicket = seckillTicketService.getById(order.getTicketId()) != null;
                Boolean hasSeckillStock = stringRedisTemplate.hasKey(seckillStockKey);

                if (isSeckillTicket) {
                    log.info("[管理退款-优化流程] 检测到秒杀票，开始先MySQL后Redis流程，ticketId:{}, userId:{}, redisKeyExists:{}",
                            order.getTicketId(), order.getUserId(), hasSeckillStock);

                    int refundQuantity = (order.getQuantity() != null) ? order.getQuantity() : 1;

                    //  Step 1: 先更新 MySQL（必须在事务内完成）
                    // 注意：tb_seckill_ticket 只有 stock 列（无 total_stock），不能套用
                    // 普通票的 LEAST(total_stock, ...) 写法，否则 Unknown column 500
                    boolean dbSuccess = seckillTicketService.update()
                            .setSql("stock = stock + " + refundQuantity)
                            .eq("ticket_id", order.getTicketId())
                            .update();

                    if (!dbSuccess) {
                        log.error("[管理退款-MySQL失败] 库存更新失败，整个事务将回滚，ticketId:{}, quantity:{}", order.getTicketId(), refundQuantity);
                        throw new com.echo.exception.BusinessException(
                                AppMessages.format(AppMessages.RUNTIME_TICKET_STOCK_RELEASE_FAIL, order.getTicketId()),
                                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                    log.info("[管理退款-MySQL成功] 库存表已更新+{}，继续执行Redis操作，ticketId:{}, quantity:{}",
                            refundQuantity, order.getTicketId(), refundQuantity);

                    // Step 2: 再执行 Redis Lua 操作
                    try {
                        Long refundResult = stringRedisTemplate.execute(
                                seckillRefundScript,
                                java.util.Arrays.asList(
                                        seckillStockKey,                                      // KEYS[1]
                                        RedisConstants.SECKILL_ORDER_KEY + order.getTicketId() // KEYS[2]
                                ),
                                String.valueOf(order.getUserId()),                          // ARGV[1]
                                String.valueOf((order.getQuantity() != null) ? order.getQuantity() : 1) // ARGV[2] ✅ Bug18修复：传quantity而非缺省1，支持多票退款
                        );

                        if (refundResult != null && refundResult == 0) {
                            log.info("[管理退款-Redis成功] Lua原子操作完成，ticketId:{}, userId:{}",
                                    order.getTicketId(), order.getUserId());
                        } else {
                            String errorMsg = switch (refundResult != null ? refundResult.intValue() : -99) {
                                case -1 -> "秒杀库存Key不存在或已过期";
                                case -2 -> "秒杀库存数据格式异常（非数字）";
                                default -> "未知错误（返回码：" + refundResult + "）";
                            };

                            int adminRefundQuantity = (order.getQuantity() != null) ? order.getQuantity() : 1;
                            log.warn("[管理退款-Redis失败] 需要补偿修复，ticketId:{}, userId:{}, quantity:{}, result:{}, error:{}",
                                    order.getTicketId(), order.getUserId(), adminRefundQuantity, refundResult, errorMsg);

                            //  创建补偿任务（不抛异常，允许主流程正常返回）
                            redisCompensateTaskId = createRedisCompensateTask(
                                    order.getTicketId(),
                                    order.getUserId(),
                                    "REFUND_SECKILL_RELEASE",
                                    String.format("Redis退款操作失败：%s", errorMsg),
                                    adminRefundQuantity
                            );
                            log.warn("[管理退款-补偿任务已创建] taskId:{}, 将由后台任务异步修复Redis数据",
                                    redisCompensateTaskId);
                        }

                    } catch (Exception redisEx) {
                        int adminRefundQuantity2 = (order.getQuantity() != null) ? order.getQuantity() : 1;
                        log.error("[管理退款-Redis异常] 捕获异常并创建补偿任务，ticketId:{}, userId:{}, quantity:{}, error:{}",
                                order.getTicketId(), order.getUserId(), adminRefundQuantity2, redisEx.getMessage());

                        //  Redis 异常也创建补偿任务（不阻断主流程）
                        redisCompensateTaskId = createRedisCompensateTask(
                                order.getTicketId(),
                                order.getUserId(),
                                "REFUND_SECKILL_RELEASE",
                                "Redis连接异常：" + redisEx.getMessage(),
                                adminRefundQuantity2
                        );
                    }

                } else {
                    log.debug("[管理退款] 非秒杀票或库存Key不存在，跳过Redis操作，ticketId:{}", order.getTicketId());
                }

            } catch (Exception mainEx) {
                log.error("[管理退款-主流程异常] ticketId:{}, userId:{}, error:{}",
                        order.getTicketId(), order.getUserId(), mainEx.getMessage(), mainEx);
                throw mainEx;  // MySQL 相关异常必须抛出，触发事务回滚
            }

            // 退款成功后推进幂等键：SUCCESS → FAILED（不删键）。
            // FAILED的语义是"允许重做"：既保留"这次请求已处理过"的处理痕迹（删键会留下
            // 可被重放利用的空窗），又立即打开重买开关——不再等30分钟TTL自然过期。
            // 注意：仅秒杀票退款才翻转幂等键；普通票没有秒杀幂等键，写入反而制造语义污染。
            // 产品缺口（技术侧已打通，通知责任在产品侧）：用户此刻技术上可重新秒杀，
            // 但并不知情——缺一个"回流票订阅提醒"（退款成功通知应携带"该票种仍有余票，
            // 可再次购买"的引导），否则这批用户永远不会知道可以回来。
            if (isSeckillTicket) {
                markIdemFailed(order.getTicketId(), order.getUserId());
            }

            // 如果存在补偿任务，在响应中提示（但不影响退款成功的判断）
            if (redisCompensateTaskId != null) {
                log.info("[管理退款-完成含补偿] 退款成功，但Redis需异步修复，taskId:{}", redisCompensateTaskId);
            }

            // 恢复普通票剩余可卖库存（stock += quantity）——仅普通票需要
            // 秒杀票库存在 tb_seckill_ticket，已由上方 Redis+MySQL 分支处理，禁止重复回退
            int refundQuantity = (order.getQuantity() != null) ? order.getQuantity() : 1;
            com.echo.entity.Ticket ticket = ticketService.getById(order.getTicketId());

            if (!isSeckillTicket) {
                // 仅普通票/选座票需要回退 tb_ticket.stock
                if (ticket != null) {
                    boolean normalSuccess = ticketService.update()
                            .setSql("stock = LEAST(total_stock, stock + " + refundQuantity + ")")
                            .eq("id", order.getTicketId())
                            .update();
                    if (normalSuccess) {
                        log.info("[管理退款] 普通票库存已释放，ticketId:{}, 数量:{}, 恢复后库存:{}",
                                order.getTicketId(), refundQuantity, ticket.getStock() + refundQuantity);
                    } else {
                        log.error("[管理退款] 普通票库存释放失败，ticketId:{}, 当前stock:{}, totalStock:{}",
                                order.getTicketId(), ticket.getStock(), ticket.getTotalStock());
                        throw new com.echo.exception.BusinessException(
                                AppMessages.format(AppMessages.RUNTIME_TICKET_STOCK_RELEASE_FAIL, order.getTicketId()),
                                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                } else {
                    log.warn("[管理退款] 票种无库存记录或库存为null，跳过释放，ticketId:{}",
                            order.getTicketId());
                }
            } else {
                // 秒杀票：跳过普通票库存回退（已在上方 seckillRefundScript 中处理 tb_seckill_ticket.stock）
                log.info("[管理退款] 秒杀票跳过普通票库存回退（已由Redis+MySQL分支处理），ticketId:{}, quantity:{}",
                        order.getTicketId(), refundQuantity);
            }

            // 释放所有已售座位（支持多座位订单）
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.Seat> soldSeatsQuery = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            soldSeatsQuery.eq("order_id", orderId);
            List<com.echo.entity.Seat> soldSeats = seatService.list(soldSeatsQuery);

            if (!soldSeats.isEmpty()) {
                int releasedCount = 0;
                for (com.echo.entity.Seat seat : soldSeats) {
                    try {
                        seat.setStatus(0);  // 0=空闲可选
                        seat.setOrderId(null);  // 清除订单关联
                        boolean updated = seatService.updateById(seat);
                        if (updated) {
                            String lockKey = RedisConstants.LOCK_SEAT_KEY + order.getTicketId() + ":" + seat.getId();
                            stringRedisTemplate.delete(lockKey);
                            releasedCount++;
                            log.debug("[管理退款] 座位已释放，seatId:{}, ticketId:{}", seat.getId(), order.getTicketId());
                        } else {
                            log.warn("[管理退款] 座位更新失败，seatId:{}", seat.getId());
                        }
                    } catch (Exception seatEx) {
                        log.error("[管理退款] 座位释放异常，seatId:{}, error:{}", seat.getId(), seatEx.getMessage());
                    }
                }
                log.info("[管理退款] 座位释放完成，总数:{}，成功释放:{}", soldSeats.size(), releasedCount);
            } else {
                log.info("[管理退款] 无需释放座位（无座票或座位已被释放），orderId:{}", orderId);
            }


            try {
                com.echo.event.OrderStatusChangeEvent refundEvent = com.echo.event.OrderStatusChangeEvent.refundEvent(
                        String.valueOf(order.getId()),
                        order.getUserId(),
                        order.getTicketId(),
                        order.getShowId(),
                        order.getStatus(),
                        com.echo.dto.OrderStatusDTO.REFUNDED(),
                        "管理员审批退款，refundQuantity=" + refundQuantity
                );
                applicationEventPublisher.publishEvent(refundEvent);
                log.info("[管理退款] 已发布 OrderRefundedEvent - orderId:{}, ticketId:{}, quantity:{}",
                        order.getId(), order.getTicketId(), refundQuantity);
            } catch (Exception eventEx) {
                log.warn("[管理退款] 发布领域事件失败（不影响主流程） - error:{}", eventEx.getMessage());
            }

            // 清理关键业务缓存（订单相关）
            try {
                Set<String> adminOrderKeys = stringRedisTemplate.keys("admin:orders:*");
                if (adminOrderKeys != null && !adminOrderKeys.isEmpty()) {
                    stringRedisTemplate.delete(adminOrderKeys);
                }

                String userOrderKey = "user:order:list:" + order.getUserId();
                stringRedisTemplate.delete(userOrderKey);

                log.info("[管理退款] 订单相关缓存已清理（票种缓存由领域事件监听器异步处理）");

                log.info("[管理退款] 缓存清理完成");

            } catch (Exception cacheEx) {
                log.warn("[管理退款] 缓存清理失败（不影响主流程），error:{}", cacheEx.getMessage());
            }

            return Result.ok();
        } catch (Exception e) {
            log.error("[管理退款失败] orderId:{}, error:{}", orderId, e.getMessage(), e);
            return Result.fail(AppMessages.REFUND_FAIL);
        }
    }

    /**
     * 核销订单（入场验票）
     * <p>
     * 核销流程：
     * 1. 从Redis查询核销码对应的订单ID
     * 2. 校验订单状态（仅 status=2 已支付可核销）
     * 3. 更新订单状态为已核销 + 记录核销时间
     * 4. 删除Redis核销码（防止重复核销）
     * <p>
     * 数据库操作：
     *   UPDATE tb_ticket_order SET status = 3, use_time = now() WHERE id = {orderId}
     * <p>
     * Redis操作：
     *   GET verify:code:{verificationCode} → 获取orderId
     *   DEL verify:code:{verificationCode} → 删除核销码，防止重复核销
     *
     * @param verificationCode 8位核销码
     * @return 操作结果
     */
    @Override
    public Result<?> verifyOrder(String verificationCode) {
        log.info("[核销] 开始处理核销请求，code:{}", verificationCode);

        if (verificationCode == null || verificationCode.trim().isEmpty()) {
            log.warn("[核销拒绝] 核销码为空");
            return Result.fail(AppMessages.ORDER_VERIFY_CODE_EMPTY);
        }

        if (verificationCode.length() != 8 || !verificationCode.matches("^[A-Z0-9]{8}$")) {
            log.warn("[核销拒绝] 核销码格式错误，length:{}, code:{}",
                    verificationCode.length(),
                    verificationCode.length() > 8 ? verificationCode.substring(0, 8) + "***" : verificationCode);
            return Result.fail(AppMessages.ORDER_VERIFY_CODE_FORMAT_ERROR);
        }

        String redisValue = stringRedisTemplate.opsForValue().get(VERIFY_CODE_KEY + verificationCode);
        if (redisValue == null) {
            log.warn("[核销拒绝] 核销码无效，code:{}", verificationCode);
            return Result.fail(AppMessages.VERIFICATION_CODE_INVALID);
        }

        Long orderId;
        int ticketIndex = 0;

        try {
            String[] parts = redisValue.split(":");
            orderId = Long.valueOf(parts[0]);
            if (parts.length > 1) {
                ticketIndex = Integer.parseInt(parts[1]);
            }
        } catch (Exception e) {
            log.warn("[核销拒绝] Redis数据格式异常，redisValue:{}", redisValue);
            return Result.fail(AppMessages.ORDER_VERIFY_CODE_DATA_ERROR);
        }

        TicketOrder order = getById(orderId);
        if (order == null) {
            log.warn("[核销拒绝] 订单不存在，orderId:{}", orderId);
            return Result.fail(AppMessages.ORDER_NOT_EXIST);
        }
        if (order.getStatus() == null || order.getStatus() != OrderStatus.PAID.getCode()) {
            log.warn("[核销拒绝] 订单状态异常，orderId:{}, status:{}", orderId, order.getStatus());
            return Result.fail(AppMessages.VERIFICATION_CODE_INVALID);
        }

        Ticket ticket = ticketService.getById(order.getTicketId());
        if (ticket == null) {
            log.warn("[核销拒绝] 票种不存在，ticketId:{}", order.getTicketId());
            return Result.fail("票种信息不存在");
        }

        Show show = showService.getById(ticket.getShowId());
        if (show != null) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime openTime = show.getOpenTime();
            LocalDateTime endTime = show.getEndTime();

            log.info("[核销] 演出时间校验 - showId:{}, openTime:{}, endTime:{}, 当前时间:{}",
                    show.getId(), openTime, endTime, now);

            if (openTime != null && now.isBefore(openTime)) {
                long minutesUntilStart = java.time.Duration.between(now, openTime).toMinutes();
                log.warn("[核销拒绝] 演出未开始 - orderId:{}, showId:{}, 距离开始还有{}分钟",
                        orderId, show.getId(), minutesUntilStart);
                return Result.fail(String.format("演出尚未开始（%s后开场），暂不可核销",
                        formatDuration(minutesUntilStart)));
            }

            if (endTime != null && now.isAfter(endTime)) {
                log.warn("[核销拒绝] 演出已结束 - orderId:{}, showId:{}, endTime:{}, 当前时间:{}",
                        orderId, show.getId(), endTime, now);
                return Result.fail("演出已结束，无法核销");
            }

            log.info("[核销] 演出时间校验通过 - showId:{}, 演出进行中", show.getId());
        }

        String usedKey = VERIFY_USED_KEY + orderId + ":" + ticketIndex;
        Boolean alreadyUsed = stringRedisTemplate.hasKey(usedKey);
        if (Boolean.TRUE.equals(alreadyUsed)) {
            String usedTimeStr = stringRedisTemplate.opsForValue().get(usedKey);
            if (usedTimeStr != null) {
                try {
                    LocalDateTime usedTime = LocalDateTime.parse(usedTimeStr);
                    log.warn("[核销拒绝] 该票已核销，orderId:{}, ticketIndex:{}, usedTime:{}",
                            orderId, ticketIndex, usedTime);
                } catch (Exception e) {
                    log.warn("[核销拒绝] 该票已核销（时间解析失败），orderId:{}, ticketIndex:{}", orderId, ticketIndex);
                }
            }
            return Result.fail(AppMessages.format(AppMessages.ORDER_VERIFY_CODE_USED, ticketIndex + 1));
        }

        LocalDateTime now = LocalDateTime.now();
        stringRedisTemplate.opsForValue().set(usedKey, now.toString(), 30, java.util.concurrent.TimeUnit.DAYS);

        String codeListJson = order.getVerificationCode();

        boolean isSingleTicketMode = true;
        List<String> allCodes = null;

        if (codeListJson != null && codeListJson.trim().startsWith("[")) {
            try {
                allCodes = com.alibaba.fastjson.JSON.parseArray(codeListJson, String.class);
                if (allCodes != null && !allCodes.isEmpty()) {
                    isSingleTicketMode = false;
                    log.debug("[核销] 检测到多票模式（JSON数组），总票数:{}", allCodes.size());
                }
            } catch (Exception e) {
                log.warn("[核销] JSON解析失败，回退到单票模式 - orderId:{}, error:{}", orderId, e.getMessage());
            }
        }

        if (isSingleTicketMode) {
            log.info("[核销] 单票核销模式（一子单一票）- orderId:{}, ticketIndex:{}", orderId, ticketIndex);

            order.setStatus(OrderStatus.USED.getCode());
            order.setUseTime(now);
            order.setUpdateTime(now);
            // 状态写入统一走状态机唯一入口：规则校验(仅已支付2可核销) + 条件更新(并发安全)
            java.util.Map<String, Object> verifyExtra = new java.util.HashMap<>();
            verifyExtra.put("use_time", now);
            verifyExtra.put("update_time", now);
            com.echo.statemachine.OrderStateMachine.TransitionContext verifyCtx =
                    com.echo.statemachine.OrderStateMachine.TransitionContext.of(
                            order.getUserId(), order.getTicketId(), order.getShowId(),
                            order.getQuantity(), "管理员核销");
            var verifyStateResult = orderStateMachine.applyTransition(
                    String.valueOf(orderId), OrderStatus.USED.getCode(), verifyExtra, verifyCtx);
            if (!verifyStateResult.isSuccess()) {
                stringRedisTemplate.delete(usedKey);  // 回滚本次核销码占用
                log.warn("[核销失败] 状态机拦截 - orderId:{}, 原因:{}", orderId, verifyStateResult.getErrorMessage());
                return Result.fail(verifyStateResult.getErrorMessage());
            }

            log.info("[核销] 单张票核销完成，订单状态更新为已核销 - orderId:{}, 核销码:{}", orderId, verificationCode);

            try {
                OrderStatusChangeEvent verifyEvent = OrderStatusChangeEvent.verifyEvent(
                        String.valueOf(orderId),
                        order.getUserId(),
                        order.getTicketId(),
                        order.getShowId(),
                        OrderStatus.PAID.getCode(),
                        OrderStatusDTO.USED()
                );
                applicationEventPublisher.publishEvent(verifyEvent);
                log.info("[核销] 已发布 OrderVerifyEvent - orderId:{}, ticketId:{}", orderId, order.getTicketId());
            } catch (Exception eventEx) {
                log.warn("[核销] 发布领域事件失败（不影响主流程） - error:{}", eventEx.getMessage());
            }
        } else {
            log.info("[核销] 多票核销模式（整单多子单）- orderId:{}, 总票数:{}, 当前核销第{}张}",
                    orderId, allCodes.size(), ticketIndex + 1);

            java.util.List<String> verifyKeys = new java.util.ArrayList<>();
            for (int i = 0; i < allCodes.size(); i++) {
                verifyKeys.add(VERIFY_USED_KEY + orderId + ":" + i);
            }
            java.util.List<String> verifyResults = stringRedisTemplate.opsForValue().multiGet(verifyKeys);
            long verifiedCount = verifyResults.stream().filter(java.util.Objects::nonNull).count();

            if (verifiedCount >= allCodes.size()) {
                order.setStatus(OrderStatus.USED.getCode());
                order.setUseTime(now);
                order.setUpdateTime(now);
                // 状态写入统一走状态机唯一入口：规则校验(仅已支付2可核销) + 条件更新(并发安全)
                java.util.Map<String, Object> verifyExtra = new java.util.HashMap<>();
                verifyExtra.put("use_time", now);
                verifyExtra.put("update_time", now);
                com.echo.statemachine.OrderStateMachine.TransitionContext verifyCtx =
                        com.echo.statemachine.OrderStateMachine.TransitionContext.of(
                                order.getUserId(), order.getTicketId(), order.getShowId(),
                                order.getQuantity(), "管理员核销(最后一张)");
                var verifyStateResult = orderStateMachine.applyTransition(
                        String.valueOf(orderId), OrderStatus.USED.getCode(), verifyExtra, verifyCtx);
                if (!verifyStateResult.isSuccess()) {
                    stringRedisTemplate.delete(usedKey);  // 回滚本次核销码占用
                    log.warn("[核销失败] 状态机拦截 - orderId:{}, 原因:{}", orderId, verifyStateResult.getErrorMessage());
                    return Result.fail(verifyStateResult.getErrorMessage());
                }
                log.info("[核销] 所有票已核销完毕，订单状态更新为已核销 - orderId:{}, totalTickets:{}", orderId, allCodes.size());

                try {
                    OrderStatusChangeEvent verifyEvent = OrderStatusChangeEvent.verifyEvent(
                            String.valueOf(orderId),
                            order.getUserId(),
                            order.getTicketId(),
                            order.getShowId(),
                            2,
                            OrderStatusDTO.USED()
                    );
                    applicationEventPublisher.publishEvent(verifyEvent);
                    log.info("[核销] 已发布 OrderVerifyEvent（多票完成） - orderId:{}, ticketId:{}", orderId, order.getTicketId());
                } catch (Exception eventEx) {
                    log.warn("[核销] 发布领域事件失败（不影响主流程） - error:{}", eventEx.getMessage());
                }
            } else {
                log.info("[核销] 单张票核销成功 - orderId:{}, ticketIndex:{}, 进度:{}/{}",
                        orderId, ticketIndex, verifiedCount + 1, allCodes.size());
            }
        }

        try {
            OperationLog verifyLog = new OperationLog();
            verifyLog.setOperatorId(order.getUserId());
            verifyLog.setOperatorName("用户#" + order.getUserId());
            verifyLog.setOperationType("VERIFY_ORDER");
            verifyLog.setTargetType("order");
            verifyLog.setTargetIds(orderId + ":" + ticketIndex);
            verifyLog.setOperationDetail(String.format("核销订单 #%d 第%d张票，核销码：%s", orderId, ticketIndex + 1, verificationCode));
            verifyLog.setResultStatus(OperationResultStatus.SUCCESS);
            verifyLog.setIpAddress(getClientIp());
            // createdAt 由 MetaObjectHandler 自动填充
            operationLogService.save(verifyLog);
            log.info("[操作日志] [成功] 核销操作已记录 - orderId:{}, ticketIndex:{}", orderId, ticketIndex);
        } catch (Exception e) {
            log.error("[操作日志] [失败] 记录核销操作失败 - orderId:{}, error:{}", orderId, e.getMessage());
        }

        log.info("[核销成功] orderId:{}, userId:{}, ticketIndex:{}, code:{}", orderId, order.getUserId(), ticketIndex, verificationCode);
        sendNotification(order.getUserId(), NotificationType.VERIFICATION_SUCCESS, NOTIFICATION_VERIFICATION_SUCCESS_TITLE, NOTIFICATION_VERIFICATION_SUCCESS_CONTENT, orderId);

        Map<String, Object> resultData = new HashMap<>();
        resultData.put("orderId", orderId);
        resultData.put("ticketIndex", ticketIndex);
        resultData.put("totalTickets", (isSingleTicketMode ? 1 : (allCodes != null ? allCodes.size() : 1)));

        return Result.ok(resultData);
    }

    @Override
    public Result<?> checkVerificationStatus(Long orderId, Integer ticketIndex) {
        log.info("[核销状态检查] 开始，orderId:{}, ticketIndex:{}", orderId, ticketIndex);

        if (orderId == null || ticketIndex == null) {
            log.warn("[核销状态检查] 参数为空");
            return Result.fail(AppMessages.REVIEW_PARAM_EMPTY);
        }

        try {
            TicketOrder order = getById(orderId);
            if (order == null) {
                log.warn("[核销状态检查] 订单不存在，orderId:{}", orderId);
                return Result.fail(AppMessages.ORDER_NOT_EXIST);
            }

            String usedKey = VERIFY_USED_KEY + orderId + ":" + ticketIndex;
            Boolean hasUsed = stringRedisTemplate.hasKey(usedKey);
            boolean isUsed = Boolean.TRUE.equals(hasUsed);

            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("used", isUsed);

            if (isUsed) {
                String useTimeStr = stringRedisTemplate.opsForValue().get(usedKey);
                result.put("useTime", useTimeStr != null ? useTimeStr : "");
            }

            log.info("[核销状态检查] 完成，orderId:{}, ticketIndex:{}, used:{}", orderId, ticketIndex, hasUsed);

            return Result.ok(result);
        } catch (Exception e) {
            log.error("[核销状态检查] 异常，orderId:{}, ticketIndex:{}, error:{}", orderId, ticketIndex, e.getMessage(), e);
            return Result.fail(AppMessages.ORDER_VERIFY_STATUS_QUERY_FAIL);
        }
    }

    /**
     * 获取订单状态的中文描述
     *
     * @param status 订单状态码
     * @return 状态描述
     */
    private String formatDuration(long totalMinutes) {
        if (totalMinutes < 60) {
            return totalMinutes + "分钟";
        }
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (minutes == 0) {
            return hours + "小时";
        }
        return hours + "小时" + minutes + "分钟";
    }

    private String getOrderStatusDescription(int status) {
        return OrderStatus.descriptionOf(status);
    }

    private String getClientIp() {
        try {
            org.springframework.web.context.request.ServletRequestAttributes attrs =
                    (org.springframework.web.context.request.ServletRequestAttributes)
                            org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                javax.servlet.http.HttpServletRequest request = attrs.getRequest();
                String ip = request.getHeader("X-Forwarded-For");
                if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                    ip = request.getHeader("X-Real-IP");
                }
                if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                    ip = request.getRemoteAddr();
                }
                return ip != null ? ip : "unknown";
            }
        } catch (Exception e) {
            log.warn("[IP获取] 获取客户端IP失败", e);
        }
        return "unknown";
    }

    private void logRefundOperation(Long orderId, Long userId) {
        try {
            Long operatorId = 0L;
            String operatorName = "系统";
            if (com.echo.utils.UserHolder.getAdmin() != null) {
                operatorId = com.echo.utils.UserHolder.getAdmin().getId();
                operatorName = com.echo.utils.UserHolder.getAdmin().getName();
            }

            OperationLog refundLog = new OperationLog();
            refundLog.setOperatorId(operatorId);
            refundLog.setOperatorName(operatorName);
            refundLog.setOperationType("APPROVE_REFUND");
            refundLog.setTargetType("order");
            refundLog.setTargetIds(String.valueOf(orderId));
            refundLog.setOperationDetail(String.format("管理员审批订单 #%d 退款，用户：%d", orderId, userId));
            refundLog.setResultStatus(OperationResultStatus.SUCCESS);
            refundLog.setIpAddress(getClientIp());
            // createdAt 由 MetaObjectHandler 自动填充
            operationLogService.save(refundLog);
            log.info("[操作日志] [成功] 审批退款操作已记录 - orderId:{}", orderId);
        } catch (Exception e) {
            log.error("[操作日志] [失败] 记录审批退款操作失败 - orderId:{}, error:{}", orderId, e.getMessage());
        }
    }

    private void sendNotification(Long userId, NotificationType type, String title, String content, Long relatedId) {
        try {
            NotificationMessage msg = NotificationMessage.of(userId, type, title, content, relatedId);
            rocketMQTemplate.asyncSend(MqConstants.NOTIFICATION_TOPIC,
                    MessageBuilder.withPayload(msg).build(),
                    new SendCallback() {
                        @Override
                        public void onSuccess(SendResult sendResult) {
                            log.debug("[通知] 通知消息已发送，userId:{}, type:{}", userId, type);
                        }
                        @Override
                        public void onException(Throwable e) {
                            log.warn("[通知] 通知消息发送失败（不影响核心功能），userId:{}, type:{}, error:{}", userId, type, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("[通知] 发送通知异常（不影响核心功能），userId:{}, error:{}", userId, e.getMessage());
        }
    }

    /**
     * 生成8位随机核销码（数字+大写字母混合）
     * <p>
     * 规则：
     * - 长度：8位
     * - 字符集：0-9数字 + A-Z大写字母（排除易混淆的I、O）
     * - 格式：数字和字母交替出现，增强可读性
     * - 安全性：10次重试 + 时间戳兜底，杜绝碰撞
     * <p>
     * 示例输出：
     * - A3K7M2P9
     * - 5B8X1Q4R
     * - M2N6P3K8
     */
    private String generateVerificationCode() {
        final String DIGITS = "0123456789";
        final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        final int MAX_RETRY = 10;

        for (int retry = 0; retry < MAX_RETRY; retry++) {
            StringBuilder code = new StringBuilder(8);

            for (int i = 0; i < 8; i++) {
                if (i % 2 == 0) {
                    code.append(LETTERS.charAt(SECURE_RANDOM.nextInt(LETTERS.length())));
                } else {
                    code.append(DIGITS.charAt(SECURE_RANDOM.nextInt(DIGITS.length())));
                }
            }

            String generatedCode = code.toString();

            String existingOrderId = stringRedisTemplate.opsForValue().get(VERIFY_CODE_KEY + generatedCode);
            if (existingOrderId == null) {
                log.debug("[核销码生成] 成功 - code:{}, 重试次数:{}", generatedCode, retry);
                return generatedCode;
            }

            log.warn("[核销码生成] 碰撞重试 - code:{}, 已存在orderId:{}, 第{}/{}次重试",
                    generatedCode, existingOrderId, retry + 1, MAX_RETRY);
        }

        // 兜底方案：使用时间戳+随机数组合，确保唯一性
        String timestamp = String.valueOf(System.currentTimeMillis());
        String randomPart = String.valueOf(SECURE_RANDOM.nextInt(10000));
        String fallbackCode = (timestamp + randomPart).substring(0, 8).toUpperCase();

        log.warn("[核销码生成] 使用时间戳兜底方案 - code:{}（已重试{}次）", fallbackCode, MAX_RETRY);

        // 再次检查兜底码是否冲突（理论上不会，但防御性编程）
        String existingOrderId = stringRedisTemplate.opsForValue().get(VERIFY_CODE_KEY + fallbackCode);
        if (existingOrderId != null) {
            // 极端情况：使用完整UUID作为最终兜底
            fallbackCode = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            log.error("[核销码生成] 极端情况使用UUID兜底 - code:{}", fallbackCode);
        }

        return fallbackCode;
    }

    private Result<?> buildOrderDetail(TicketOrder order, Long currentUserId) {
        // 权限校验：确保当前用户只能查看自己的订单
        if (currentUserId != null && !currentUserId.equals(order.getUserId())) {
            log.warn("[构建订单详情] 权限不足 - 当前用户:{}，订单所有者:{}", currentUserId, order.getUserId());
            return Result.fail(AppMessages.ORDER_NO_PERMISSION);
        }

        Map<String, Object> orderDetail = new HashMap<>();
        orderDetail.put("id", order.getId());
        orderDetail.put("userId", order.getUserId());
        orderDetail.put("ticketId", order.getTicketId());
        orderDetail.put("status", order.getStatus());
        orderDetail.put("createTime", order.getCreateTime());
        orderDetail.put("payTime", order.getPayTime());

        // 获取购买数量（重要：普通票可以买多张）
        int quantity = order.getQuantity() != null ? order.getQuantity() : 1;
        orderDetail.put("quantity", quantity);

        Ticket ticket = ticketService.getById(order.getTicketId());
        if (ticket != null) {
            orderDetail.put("ticketTitle", ticket.getTitle());

            // 计算总金额（单价 × 数量）
            long unitPrice = ticket.getPayValue() != null ? ticket.getPayValue() : 0L;
            long totalPrice = unitPrice * quantity;
            orderDetail.put("unitPrice", unitPrice);      // 单价
            orderDetail.put("payValue", totalPrice);         // [总价] 总价

            log.info("[构建订单详情] 票价计算 - ticketId:{}, unitPrice:{}, quantity:{}, totalPrice:{}",
                    ticket.getId(), unitPrice, quantity, totalPrice);

            Show show = showService.getById(ticket.getShowId());
            if (show != null) {
                orderDetail.put("showName", show.getName());
            }
        } else {
            log.warn("[构建订单详情] 票种信息不存在，ticketId:{}", order.getTicketId());
        }

        if (order.getVerificationCode() != null) {
            int status = order.getStatus() != null ? order.getStatus() : OrderStatus.RESERVED.getCode();

            if (OrderStatus.PAID.getCode() == status) {
                orderDetail.put("verificationCode", order.getVerificationCode());
                log.info("[构建订单详情] [显示核销码] orderId:{}, status:已支付", order.getId());
            } else if (OrderStatus.REFUND_PENDING.getCode() == status) {
                orderDetail.put("verificationCodeHidden", true);
                orderDetail.put("verificationCodeReason", "订单正在申请退款中，核销码已暂时隐藏");
                log.info("[构建订单详情] [隐藏核销码] orderId:{}, status:申请退款中, 原因:退款审核期间隐藏", order.getId());
            } else if (OrderStatus.REFUNDED.getCode() == status) {
                orderDetail.put("verificationCodeHidden", true);
                orderDetail.put("verificationCodeReason", "订单已退款，核销码已失效");
                log.info("[构建订单详情] [隐藏核销码] orderId:{}, status:已退款, 原因:订单已取消", order.getId());
            } else if (OrderStatus.REFUND_REJECTED.getCode() == status) {
                orderDetail.put("verificationCode", order.getVerificationCode());
                log.info("[构建订单详情] [恢复显示核销码] orderId:{}, status:退款被拒绝, 核销码重新可见", order.getId());
            } else if (OrderStatus.USED.getCode() == status) {
                orderDetail.put("verificationCode", order.getVerificationCode());
                log.info("[构建订单详情] [显示核销码] orderId:{}, status:已核销（历史记录）", order.getId());
            } else {
                log.debug("[构建订单详情] [无核销码] orderId:{}, status:{}（未支付状态无核销码）", order.getId(), status);
            }
        }

        log.info("[查询成功（数据库）] orderId:{}, 状态:{}, quantity:{}",
                order.getId(), order.getStatus(), quantity);
        return Result.ok(orderDetail);
    }

    private Result<?> buildPendingOrderFromCache(String cachedJson, Long orderId, Long currentUserId) {
        try {
            Map<String, Object> cachedOrder = com.alibaba.fastjson.JSON.parseObject(cachedJson, Map.class);

            Long cachedUserId = ((Number) cachedOrder.get("userId")).longValue();
            if (!cachedUserId.equals(currentUserId)) {
                log.warn("[查询拒绝] 无权查看他人订单（缓存重试），orderId:{}", orderId);
                return Result.fail(AppMessages.ORDER_NO_PERMISSION);
            }

            Long ticketId = ((Number) cachedOrder.get("ticketId")).longValue();
            Ticket ticket = ticketService.getById(ticketId);

            Map<String, Object> orderDetail = new HashMap<>();
            orderDetail.put("id", orderId);
            orderDetail.put("userId", cachedUserId);
            orderDetail.put("ticketId", ticketId);
            Integer cachedStatusForCheck1 = cachedOrder.get("status") != null ?
                    ((Number) cachedOrder.get("status")).intValue() : 0;
            orderDetail.put("status", cachedStatusForCheck1);
            orderDetail.put("createTime", cachedOrder.get("createTime"));
            orderDetail.put("pending", true);

            if (ticket != null) {
                orderDetail.put("ticketTitle", ticket.getTitle());
                // 计算总金额（单价 × 数量）
                int quantity = cachedOrder.get("quantity") != null ?
                        ((Number) cachedOrder.get("quantity")).intValue() : 1;
                long unitPrice = ticket.getPayValue() != null ? ticket.getPayValue() : 0L;
                long totalPrice = unitPrice * quantity;
                orderDetail.put("quantity", quantity);
                orderDetail.put("unitPrice", unitPrice);
                orderDetail.put("payValue", totalPrice);
            }

            log.info("[查询成功（缓存重试）] orderId:{}, 状态:待支付（MQ处理中）", orderId);
            return Result.ok(orderDetail);

        } catch (Exception e) {
            log.error("[构建缓存订单失败] 解析JSON异常，orderId:{}, error:{}", orderId, e.getMessage(), e);
            return Result.fail(AppMessages.ORDER_DATA_ERROR);
        }
    }

    @Override
    public Result<?> checkSeckillPurchasedBatch(List<Long> ticketIds) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }
        Long userId = user.getId();

        if (ticketIds == null || ticketIds.isEmpty()) {
            return Result.ok(java.util.Collections.emptyMap());
        }

        log.info("[批量检查] 开始检查秒杀票购买状态 - userId:{}, ticketIds:{}", userId, ticketIds);

        try {
            // 查询用户在这些秒杀票上的有效订单（仅已支付+已核销才算"已购买"）
            // 业务规则：预占状态(1)的订单应在超时自动取消，不应阻止用户重新下单
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TicketOrder> queryWrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            queryWrapper.eq("user_id", userId)
                       .in("ticket_id", ticketIds)
                       .in("status", com.echo.enums.OrderStatus.PAID.getCode(), com.echo.enums.OrderStatus.USED.getCode()); // 2=已支付, 3=已核销（完整已购买口径）

            List<TicketOrder> purchasedOrders = list(queryWrapper);

            // 提取已购买的票种ID
            java.util.Set<Long> purchasedTicketIds = purchasedOrders.stream()
                    .map(TicketOrder::getTicketId)
                    .collect(Collectors.toSet());

            log.info("[批量检查] 检查完成 - userId:{}, 总数:{}, 已购买:{}",
                    userId, ticketIds.size(), purchasedTicketIds.size());

            // 返回结果
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("purchasedIds", new java.util.ArrayList<>(purchasedTicketIds));

            return Result.ok(result);
        } catch (Exception e) {
            log.error("[批量检查失败] 异常 - userId:{}, error:{}", userId, e.getMessage(), e);
            return Result.fail(AppMessages.ORDER_CHECK_PURCHASE_STATUS_FAIL);
        }
    }

    @Override
    public com.echo.entity.Ticket getTicketById(Long ticketId) {
        return ticketService.getById(ticketId);
    }

    @Override
    public boolean restoreStockForExpiredOrder(Long ticketId, Integer quantity) {
        log.info("[库存恢复] 开始恢复过期预订单的MySQL库存，ticketId:{}, 数量:{}", ticketId, quantity);

        try {
            // 恢复普通票/选座票的MySQL库存（与下单时预扣对应，带total_stock上限校验）
            boolean restored = ticketService.update()
                    .setSql("stock = LEAST(total_stock, stock + " + quantity + ")")
                    .eq("id", ticketId)
                    .update();

            if (restored) {
                log.info("[库存恢复] MySQL库存已恢复 +{}，ticketId:{}", quantity, ticketId);

                // 清理票种缓存，确保前端显示最新库存
                try {
                    com.echo.entity.Ticket ticket = ticketService.getById(ticketId);
                    if (ticket != null && ticket.getShowId() != null) {
                        ticketService.clearTicketCache(ticket.getShowId());
                        log.debug("[库存恢复] 已清理演出票种缓存 - showId:{}", ticket.getShowId());
                    }
                } catch (Exception cacheEx) {
                    log.warn("[库存恢复] 清理缓存失败（不影响主流程） - error:{}", cacheEx.getMessage());
                }

                return true;
            } else {
                log.warn("[库存恢复] MySQL库存恢复失败，ticketId:{}, 可能原因：票种不存在或数据异常", ticketId);
                return false;
            }

        } catch (Exception e) {
            log.error("[库存恢复] 恢复库存异常 - ticketId:{}, quantity:{}, error:{}",
                    ticketId, quantity, e.getMessage(), e);
            return false;
        }
    }

    private void rollbackSeckillRedisFull(Long ticketId, Long userId, int quantity) {
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + ticketId;
        String orderKey = RedisConstants.SECKILL_ORDER_KEY + ticketId;
        String userValue = String.valueOf(userId);
        String qtyStr = String.valueOf(quantity);

        String luaScript =
                "local stock = redis.call('INCRBY', KEYS[1], ARGV[1]) " +
                "redis.call('SREM', KEYS[2], ARGV[2]) " +
                "return stock";

        try {
            Long newStock = stringRedisTemplate.execute(
                    new org.springframework.data.redis.core.script.DefaultRedisScript<>(luaScript, Long.class),
                    java.util.Arrays.asList(stockKey, orderKey),
                    qtyStr, userValue);
            log.warn("[秒杀Redis完整补偿] MySQL扣减失败，已恢复库存+清除用户标记，ticketId:{}, userId:{}, 恢复数量:{}, 当前Redis库存:{}",
                    ticketId, userId, quantity, newStock);
        } catch (Exception ex) {
            log.error("[秒杀Redis完整补偿] Lua原子补偿失败，降级逐条执行，ticketId:{}, userId:{}, quantity:{}",
                    ticketId, userId, quantity, ex);
            try {
                Long newStock = stringRedisTemplate.opsForValue().increment(stockKey, quantity);
                log.warn("[秒杀Redis补偿-降级] 已恢复库存，ticketId:{}, 当前Redis库存:{}", ticketId, newStock);
            } catch (Exception ex2) {
                log.error("[秒杀Redis补偿] 恢复库存失败，ticketId:{}, quantity:{}, error:{}",
                        ticketId, quantity, ex2.getMessage(), ex2);
            }
            try {
                Long removed = stringRedisTemplate.opsForSet().remove(orderKey, userValue);
                log.warn("[秒杀Redis补偿-降级] 已清除用户购买标记，ticketId:{}, userId:{}, removed:{}",
                        ticketId, userId, removed);
            } catch (Exception ex3) {
                log.error("[秒杀Redis补偿] 清除用户购买标记失败，ticketId:{}, userId:{}, error:{}",
                        ticketId, userId, ex3.getMessage(), ex3);
            }
        }
    }

    private boolean isMqOverloaded() {
        try {
            long maxPendingMsgs = 1000;
            String pendingKey = "mq:seckill:pending:count";
            String pendingCountStr = stringRedisTemplate.opsForValue().get(pendingKey);

            if (pendingCountStr != null && !pendingCountStr.trim().isEmpty()) {
                try {
                    long pendingCount = Long.parseLong(pendingCountStr.trim());
                    if (pendingCount > maxPendingMsgs) {
                        log.warn("[MQ积压检查] 当前积压消息数:{}，超过阈值:{}", pendingCount, maxPendingMsgs);

                        if (pendingCount > maxPendingMsgs * 2) {
                            log.error("[MQ严重积压] 消息积压超过阈值2倍（{}），触发紧急告警！", pendingCount);
                        }
                        return true;
                    }
                } catch (NumberFormatException ne) {
                    log.warn("[MQ积压检查] 积压数量格式异常:{}，默认不限流", pendingCountStr);
                }
            }

            return false;
        } catch (Exception e) {
            log.warn("[MQ积压检查] 检查异常（默认不限流）:{}", e.getMessage());
            return false;
        }
    }

    /**
     * 创建 Redis 补偿任务（用于异步修复 Redis 数据不一致）
     * <p>
     * 使用场景：
     * - 退款时 MySQL 成功但 Redis 操作失败
     * - 需要后台定时任务扫描并重试
     * <p>
     * 设计说明：
     * - 将补偿信息写入 Redis（key = compensate:redis:task:{id}），与调度器扫描逻辑保持一致
     * - 后台任务每30秒扫描未完成的补偿任务并重试
     * - 最多重试3次，超过后转为人工处理
     *
     * @param ticketId 票种ID
     * @param userId 用户ID
     * @param taskType 任务类型（如 REFUND_SECKILL_RELEASE）
     * @param errorMessage 错误信息
     * @return 任务ID（用于跟踪）
     */
    private Long createRedisCompensateTask(Long ticketId, Long userId, String taskType, String errorMessage) {
        return createRedisCompensateTask(ticketId, userId, taskType, errorMessage, 1);
    }

    private Long createRedisCompensateTask(Long ticketId, Long userId, String taskType, String errorMessage, int quantity) {
        try {
            Long taskId = redisWorker.nextId("compensate_task");

            Map<String, Object> compensateTask = new HashMap<>();
            compensateTask.put("id", taskId);
            compensateTask.put("ticketId", ticketId);
            compensateTask.put("userId", userId);
            compensateTask.put("taskType", taskType);
            compensateTask.put("status", 0);  // 0=待执行, 1=执行中, 2=成功, 3=失败
            compensateTask.put("retryCount", 0);
            compensateTask.put("maxRetry", 3);
            compensateTask.put("errorMessage", errorMessage);
            compensateTask.put("createTime", LocalDateTime.now());
            compensateTask.put("nextExecuteTime", LocalDateTime.now().plusMinutes(1));  // 1分钟后首次重试
            compensateTask.put("quantity", quantity);

            String taskKey = "compensate:redis:task:" + taskId;
            stringRedisTemplate.opsForValue().set(
                    taskKey,
                    com.alibaba.fastjson.JSON.toJSONString(compensateTask),
                    24,
                    TimeUnit.HOURS  // 24小时过期（防止永久残留）
            );

            log.info("[补偿任务创建成功] taskId:{}, ticketId:{}, userId:{}, type:{}, quantity:{}, error:{}",
                    taskId, ticketId, userId, taskType, quantity, errorMessage);

            return taskId;

        } catch (Exception e) {
            log.error("[补偿任务创建失败] 无法创建补偿任务（将依赖人工修复），ticketId:{}, userId:{}, error:{}",
                    ticketId, userId, e.getMessage(), e);

            sendUrgentAlert(
                    "【紧急】Redis补偿任务创建失败！\n" +
                    "票种ID: " + ticketId + "\n" +
                    "用户ID: " + userId + "\n" +
                    "任务类型: " + taskType + "\n" +
                    "原始错误: " + errorMessage + "\n" +
                    "补偿任务异常: " + e.getMessage() + "\n\n" +
                    "请立即手动检查以下Redis Key的一致性：\n" +
                    "  - seckill:stock:" + ticketId + " (库存)\n" +
                    "  - seckill:order:" + ticketId + " (用户集合)\n\n" +
                    "建议操作：手动执行 Redis 命令修复数据"
            );

            return null;
        }
    }

    /**
     * 发送紧急告警通知（用于需要人工介入的场景）
     *
     * @param message 告警内容
     */
    private void sendUrgentAlert(String message) {
        try {
            log.error("[紧急告警] {}", message);

            stringRedisTemplate.convertAndSend(
                    "channel:system:alert",
                    com.alibaba.fastjson.JSON.toJSONString(java.util.Map.of(
                            "level", "URGENT",
                            "type", "REDIS_DATA_INCONSISTENCY",
                            "message", message,
                            "timestamp", System.currentTimeMillis(),
                            "needManualIntervention", true
                    ))
            );

        } catch (Exception alertEx) {
            log.error("[紧急告警发送失败] {}", alertEx.getMessage());
        }
    }

    private Result<?> queryPendingOrderFromCache(String tempId, Long userId) {
        log.info("[查询预订单] 从Redis缓存查询预订单，tempId:{}", tempId);

        String pendingKey = RedisConstants.PENDING_PRE_ORDER + tempId;
        try {
            PreOrder preOrder = preOrderService.getById(tempId);

            String cachedJson = stringRedisTemplate.opsForValue().get(pendingKey);

            if (cachedJson == null || cachedJson.isEmpty()) {
                if (preOrder == null) {
                    log.warn("[查询预订单] 缓存和DB均未找到预订单，tempId:{}", tempId);
                    return Result.fail(AppMessages.ORDER_NOT_EXIST_OR_EXPIRED);
                }
            }

            Map<String, Object> cachedOrder = cachedJson != null && !cachedJson.isEmpty()
                    ? com.alibaba.fastjson.JSON.parseObject(cachedJson, Map.class)
                    : new HashMap<>();

            Long cachedUserId = preOrder != null ? preOrder.getUserId()
                    : ((Number) cachedOrder.get("userId")).longValue();

            if (!cachedUserId.equals(userId)) {
                log.warn("[查询拒绝] 无权查看他人预订单，tempId:{}, cachedUserId:{}, currentUserId:{}",
                        tempId, cachedUserId, userId);
                return Result.fail(AppMessages.ORDER_NO_PERMISSION);
            }

            Long ticketId = preOrder != null ? preOrder.getTicketId()
                    : ((Number) cachedOrder.get("ticketId")).longValue();
            Ticket ticket = ticketService.getById(ticketId);

            Map<String, Object> orderDetail = new HashMap<>();
            orderDetail.put("id", tempId);
            orderDetail.put("userId", cachedUserId);
            orderDetail.put("ticketId", ticketId);
            Integer cachedStatusForCheck2 = cachedOrder.get("status") != null ?
                    ((Number) cachedOrder.get("status")).intValue() : 0;
            if (preOrder != null && preOrder.getStatus() != null) {
                cachedStatusForCheck2 = preOrder.getStatus();
            }
            orderDetail.put("status", cachedStatusForCheck2);
            orderDetail.put("createTime", cachedOrder.get("createTime"));
            orderDetail.put("pending", true);

            if (ticket != null) {
                orderDetail.put("ticketTitle", ticket.getTitle());
                orderDetail.put("showName", getShowName(ticket.getShowId()));
            }
            int quantity = preOrder != null && preOrder.getQuantity() != null ? preOrder.getQuantity() : 1;
            if (preOrder == null && cachedOrder.get("quantity") != null) {
                quantity = ((Number) cachedOrder.get("quantity")).intValue();
            }
            long frozenUnitPrice = preOrder != null && preOrder.getPayValue() != null
                    ? preOrder.getPayValue()
                    : (ticket != null && ticket.getPayValue() != null ? ticket.getPayValue() : 0L);
            long totalPrice = frozenUnitPrice * quantity;
            orderDetail.put("quantity", quantity);
            orderDetail.put("unitPrice", frozenUnitPrice);
            orderDetail.put("payValue", totalPrice);

            log.info("[查询成功（预订单）] tempId:{}, 状态:待支付, 冻结单价:{}", tempId, frozenUnitPrice);
            return Result.ok(orderDetail);
        } catch (Exception e) {
            log.error("[查询预订单] 缓存读取异常，tempId:{}, error:{}", tempId, e.getMessage(), e);
            return Result.fail(AppMessages.ORDER_NOT_EXIST_OR_EXPIRED);
        }
    }

    private String getShowName(Long showId) {
        if (showId == null) return "未知演出";
        try {
            Show show = showService.getById(showId);
            return show != null ? show.getName() : "未知演出";
        } catch (Exception e) {
            log.warn("[获取演出名] 查询失败，showId:{}, error:{}", showId, e.getMessage());
            return "未知演出";
        }
    }
}
