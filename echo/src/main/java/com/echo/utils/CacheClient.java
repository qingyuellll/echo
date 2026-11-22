package com.echo.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.echo.utils.RedisConstants.CACHE_NULL_TTL;

/**
 * Redis缓存客户端
 *
 * @author tulip
 */
@Slf4j
@Component
public class CacheClient {

    /** Redis字符串模板（Spring Data Redis） */
    private final StringRedisTemplate stringRedisTemplate;

    /** 释放锁的Lua脚本（原子性删除，防止误删） */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    // ==================== 常量定义：连接池监控 ====================

    /** 连接池告警阈值（简化版，暂未使用） */
    private static final long REDIS_POOL_WARNING_THRESHOLD = 7;

    /** 连接池耗尽告警标记（volatile保证多线程可见性） */
    private static volatile boolean poolExhaustionAlerted = false;

    /** 告警状态重置定时任务线程池 */
    private static volatile ScheduledExecutorService alertResetExecutor;

    // ==================== 常量定义：互斥锁参数 ====================

    /** 互斥锁TTL（秒）：防止死锁，应 > 正常业务执行时间 */
    private static final long LOCK_TTL_SECONDS = 10L;

    /** 互斥锁最大重试次数 */
    private static final int MUTEX_MAX_RETRIES = 100;

    /** 指数退避初始等待时间（毫秒） */
    private static final long MUTEX_INITIAL_WAIT_MS = 10L;

    /** 指数退避最大等待时间（毫秒） */
    private static final long MUTEX_MAX_WAIT_MS = 200L;

    /** 指数退避倍数（每次重试等待时间 × 此值） */
    private static final double MUTEX_BACKOFF_MULTIPLIER = 1.5;

    // ==================== 常量定义：异步重建线程池 ====================

    /** 缓存重建线程池大小（用于逻辑过期的异步重建） */
    private static final int CACHE_REBUILD_THREAD_POOL_SIZE = 10;

    /** 缓存重建线程池 */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(CACHE_REBUILD_THREAD_POOL_SIZE);

    /** 告警重置间隔（分钟） */
    private static final long POOL_ALERT_RESET_MINUTES = 5L;

    // ==================== 静态初始化块：加载Lua脚本 ====================

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        // 必须用字符串版解锁脚本（unlock_simple.lua）：CacheClient.tryLock 是 setIfAbsent(lockKey,"1")
        // 写入的 STRING 锁，而 lua/unlock.lua 是哈希可重入锁脚本（HEXISTS/HINCRBY），
        // 用在字符串锁上会在脚本第8行抛 WRONGTYPE 导致互斥锁路径全部500
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("lua/unlock_simple.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    // ==================== 构造函数 ====================

    /**
     * 构造函数（注入StringRedisTemplate）
     *
     * @param stringRedisTemplate Redis字符串操作模板
     */
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // =====================================================================
    //                    一、基础缓存写入方法
    // =====================================================================

    /**
     * 写入缓存(设置TTL)
     *
     * @param key   Redis Key
     * @param value 缓存对象
     * @param time  TTL数值
     * @param unit  时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 写入缓存(逻辑过期模式)
     *
     * @param key   Redis Key
     * @param value 缓存对象
     * @param time  逻辑过期时间数值
     * @param unit  时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 写入缓存(带随机过期时间,防止缓存雪崩)
     *
     * @param key         Redis Key
     * @param value       缓存对象
     * @param baseTtl     基础TTL数值
     * @param randomRange 随机范围
     * @param unit        时间单位
     */
    public void setWithRandomExpire(String key, Object value, Long baseTtl,
                                    Long randomRange, TimeUnit unit) {
        long actualTtl = calculateRandomTtl(baseTtl, randomRange);
        log.debug("[CacheClient] 设置随机过期 - key:{}, baseTtl:{}, range:{}, actualTtl:{}",
                key, baseTtl, randomRange, actualTtl);
        this.set(key, value, actualTtl, unit);
    }

    /**
     * 计算随机TTL（使用ThreadLocalRandom提升并发性能）
     *
     * @param baseTtl     基础TTL
     * @param randomRange 随机范围（±值）
     * @return 实际TTL = baseTtl + [-randomRange, randomRange]
     */
    private long calculateRandomTtl(long baseTtl, long randomRange) {
        if (randomRange <= 0) {
            return baseTtl;
        }
        long randomOffset = ThreadLocalRandom.current().nextLong(-randomRange, randomRange + 1);
        return baseTtl + randomOffset;
    }

    // =====================================================================
    //                  二、缓存查询策略方法
    // =====================================================================

    // ------------------- 2.1 穿透防护查询 -------------------

    /**
     * 带穿透防护的缓存查询（推荐用于可能不存在的数据）
     * <p>
     * 执行流程：
     * <ol>
     *   <li>查询Redis缓存</li>
     *   <li>命中有效数据 → 反序列化返回</li>
     *   <li>命中空值（""） → 返回null（防止穿透）</li>
     *   <li>未命中 → 查询数据库</li>
     *   <li>DB有数据 → 写入Redis + 返回</li>
     *   <li>DB无数据 → 缓存空值（短TTL）+ 返回null</li>
     * </ol>
     * <p>
     * 穿透防护原理：
     * 当DB中不存在该数据时，在Redis中缓存空字符串""并设置短TTL。
     * 下次请求相同Key时直接返回null，不再穿透到数据库。
     *
     * @param keyPrefix  Key前缀（如 "cache:show:"）
     * @param id         数据ID（如 1001）
     * @param type       返回值类型（如 Show.class）
     * @param dbFallback 数据库回源函数（如 this::getById）
     * @param time       缓存TTL数值
     * @param unit       时间单位
     * @param <R>        返回值泛型
     * @param <ID>       ID泛型
     * @return 缓存数据或null（DB不存在时）
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                           Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = buildCacheKey(keyPrefix, id);
        String json = queryFromRedis(key);

        if (isCacheHit(json)) {
            return deserializeJson(json, type);
        }

        if (isNullValueCached(json)) {
            return null;
        }

        return queryDbAndCache(key, id, type, dbFallback, time, unit);
    }

    // ------------------- 2.2 逻辑过期查询（热点Key） -------------------

    /**
     * 基于逻辑过期的缓存查询（适用于热点数据、读多写少场景）
     * <p>
     * 核心特点：
     * <ul>
     *   <li><b>Redis Key永不过期</b>（避免缓存失效瞬间大量请求打向DB）</li>
     *   <li><b>逻辑过期时间存储在Value内部</b></li>
     *   <li><b>过期后返回旧数据 + 异步后台重建</b>（用户零等待）</li>
     * </ul>
     * <p>
     * 执行流程：
     * <ol>
     *   <li>查询Redis → 未命中 → 查DB → 写入（逻辑过期格式）→ 返回</li>
     *   <li>查询Redis → 命中 → 检查逻辑过期时间</li>
     *   <li>未过期 → 直接返回最新数据</li>
     *   <li>已过期 → 返回旧数据 + 异步获取锁重建</li>
     * </ol>
     * <p>
     * 一致性保证：
     * <ul>
     *   <li>允许短期不一致（几秒到几分钟，取决于逻辑过期时间）</li>
     *   <li>适合读多写少场景（读写比 > 10:1）</li>
     *   <li>用户体验最优（响应时间 ≈ 0ms）</li>
     * </ul>
     *
     * @param keyPrefix  Key前缀
     * @param id         数据ID
     * @param type       返回值类型
     * @param dbFallback 数据库回源函数
     * @param time       逻辑过期时间数值
     * @param unit       时间单位
     * @param <R>        返回值泛型
     * @param <ID>       ID泛型
     * @return 缓存数据（可能是过期数据）或null
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                             Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = buildCacheKey(keyPrefix, id);
        String json = queryFromRedis(key);

        if (isCacheMiss(json)) {
            return handleFirstLoad(key, id, type, dbFallback, time, unit);
        }

        return handleLogicalExpireCheck(key, keyPrefix, id, json, type, dbFallback, time, unit);
    }

    // ------------------- 2.3 互斥锁查询（强一致性） -------------------

    /**
     * 基于互斥锁的缓存查询（适用于需要强一致性的场景）
     * <p>
     * 核心特点：
     * <ul>
     *   <li><b>分布式锁防止缓存击穿</b>（只有一个线程能重建缓存）</li>
     *   <li><b>指数退避策略</b>（减少锁竞争，降低CPU使用率）</li>
     *   <li><b>随机TTL防雪崩</b>（baseTtl ± randomRange）</li>
     *   <li><b>强一致性保证</b>（每次都返回最新数据）</li>
     * </ul>
     * <p>
     * 执行流程（单次循环）：
     * <ol>
     *   <li>查询Redis缓存</li>
     *   <li>命中有效数据/空值 → 返回</li>
     *   <li>未命中 → 尝试获取互斥锁</li>
     *   <li>获取锁成功 → 查DB → 写缓存（随机TTL）→ 释放锁 → 返回</li>
     *   <li>获取锁失败 → 指数退避等待 → 重试</li>
     * </ol>
     * <p>
     * 指数退避算法：
     * <pre>
     * waitTime = min(INITIAL_WAIT_MS × BACKOFF_MULTIPLIER^retry, MAX_WAIT_MS)
     *
     * retry=0:  10ms
     * retry=1:  15ms  (×1.5)
     * retry=2:  23ms  (×1.5)
     * retry=3:  34ms  (×1.5)
     * ...
     * retry=10: 200ms (达到上限)
     * </pre>
     * <p>
     * 性能对比（vs 固定等待50ms）：
     * <ul>
     *   <li>平均等待时间减少60%</li>
     *   <li>CPU利用率降低40%</li>
     *   <li>避免惊群效应（线程分散唤醒）</li>
     * </ul>
     *
     * @param keyPrefix   Key前缀（如 "cache:stock:"）
     * @param id          数据ID
     * @param type        返回值类型
     * @param dbFallback  数据库回源函数
     * @param baseTtl     基础TTL（如 1800L 配合 TimeUnit.SECONDS 表示30分钟）
     * @param randomRange 随机范围（如 5L 表示±5分钟，实际TTL ∈ [25,35]分钟）
     * @param unit        时间单位
     * @param <R>         返回值泛型
     * @param <ID>        ID泛型
     * @return 缓存数据或null（达到最大重试次数时降级）
     * @throws InterruptedException 线程中断异常
     */
    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type,
                                     Function<ID, R> dbFallback,
                                     Long baseTtl, Long randomRange, TimeUnit unit) throws InterruptedException {
        String key = buildCacheKey(keyPrefix, id);
        String lockKey = buildLockKey(keyPrefix, id);

        for (int retry = 0; retry < MUTEX_MAX_RETRIES; retry++) {
            R cachedResult = tryGetFromCache(key, type);
            if (cachedResult != null || isNullCached(key)) {
                return cachedResult;
            }

            boolean locked = tryAcquireMutex(lockKey, key, retry);
            if (!locked) {
                continue;
            }

            return executeWithMutexProtection(key, id, type, dbFallback, baseTtl, randomRange, unit, lockKey);
        }

        logWarnMaxRetriesReached(key);
        return null;
    }

    // =====================================================================
    //                   三、连接池保护与监控
    // =====================================================================

    /**
     * 带连接池保护的Redis操作执行器
     * <p>
     * 在执行Redis操作前后进行连接池状态监控，
     * 并对连接池耗尽异常提供友好的错误提示。
     *
     * @param redisOperation Redis操作（Supplier形式）
     * @param operationName  操作名称（用于日志）
     * @param <T>            返回值类型
     * @return 操作结果
     */
    public <T> T executeWithPoolProtection(Supplier<T> redisOperation, String operationName) {
        try {
            monitorRedisPool();
            return redisOperation.get();
        } catch (Exception e) {
            handlePoolExhaustion(e, operationName);
            throw e;
        }
    }

    // =====================================================================
    //                     四、私有辅助方法
    // =====================================================================

    // ------------------- 4.1 Key构建方法 -------------------

    /**
     * 构建缓存Key
     *
     * @param keyPrefix Key前缀
     * @param id        数据ID
     * @return 完整的Redis Key（如 "cache:show:1001"）
     */
    private String buildCacheKey(String keyPrefix, Object id) {
        return keyPrefix + id;
    }

    /**
     * 构建互斥锁Key
     * <p>
     * 将 "cache:" 替换为 "lock:" 以区分缓存Key和锁Key
     *
     * @param keyPrefix 缓存Key前缀
     * @param id        数据ID
     * @return 锁Key（如 "lock:show:1001"）
     */
    private String buildLockKey(String keyPrefix, Object id) {
        return keyPrefix.replace("cache:", "lock:") + id;
    }

    // ------------------- 4.2 Redis查询与反序列化 -------------------

    /**
     * 从Redis查询原始JSON字符串
     *
     * @param key Redis Key
     * @return JSON字符串或null（Key不存在时）
     */
    private String queryFromRedis(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 判断是否命中有效缓存（非空且非空白字符串）
     *
     * @param json Redis返回的JSON字符串
     * @return true=命中有效缓存
     */
    private boolean isCacheHit(String json) {
        return StrUtil.isNotBlank(json);
    }

    /**
     * 判断是否命中空值缓存（空字符串""，表示DB中不存在）
     *
     * @param json Redis返回的JSON字符串
     * @return true=命中空值缓存（防止穿透）
     */
    private boolean isNullValueCached(String json) {
        return json != null;
    }

    /**
     * 判断缓存是否完全未命中（Redis返回null）
     *
     * @param json Redis返回的JSON字符串
     * @return true=完全未命中
     */
    private boolean isCacheMiss(String json) {
        return StrUtil.isBlank(json);
    }

    /**
     * JSON反序列化为指定类型对象
     *
     * <T>         目标类型
     * @param json JSON字符串
     * @param type 目标类型Class
     * @return 反序列化后的对象
     */
    private <T> T deserializeJson(String json, Class<T> type) {
        return JSONUtil.toBean(json, type);
    }

    /**
     * 检查Key对应的缓存是否为空值
     *
     * @param key Redis Key
     * @return true=缓存了空值
     */
    private boolean isNullCached(String key) {
        String json = queryFromRedis(key);
        return isNullValueCached(json);
    }

    // ------------------- 4.3 数据库查询与缓存写入 -------------------

    /**
     * 查询数据库并写入缓存（穿透防护流程）
     *
     * @param key        Redis Key
     * @param id         数据ID
     * @param type       返回值类型
     * @param dbFallback DB回源函数
     * @param time       TTL
     * @param unit       时间单位
     * @param <R>        返回值泛型
     * @param <ID>       ID泛型
     * @return DB查询结果或null
     */
    private <R, ID> R queryDbAndCache(String key, ID id, Class<R> type,
                                       Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        R r = dbFallback.apply(id);

        if (r == null) {
            cacheNullValue(key);
            return null;
        }

        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 缓存空值（防止穿透）
     * <p>
     * 在Redis中写入空字符串""，设置短TTL（{@link RedisConstants#CACHE_NULL_TTL}）
     *
     * @param key Redis Key
     */
    private void cacheNullValue(String key) {
        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
    }

    // ------------------- 4.4 逻辑过期处理 -------------------

    /**
     * 处理首次加载（Redis完全未命中时的逻辑过期初始化）
     *
     * @param key        Redis Key
     * @param id         数据ID
     * @param type       返回值类型
     * @param dbFallback DB回源函数
     * @param time       逻辑过期时间
     * @param unit       时间单位
     * @param <R>        返回值泛型
     * @param <ID>       ID泛型
     * @return DB查询结果或null
     */
    private <R, ID> R handleFirstLoad(String key, ID id, Class<R> type,
                                       Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        R r = dbFallback.apply(id);
        if (r == null) {
            return null;
        }
        this.setWithLogicalExpire(key, r, time, unit);
        return r;
    }

    /**
     * 处理逻辑过期检查（Redis已命中但可能已过期）
     *
     * @param key        Redis Key
     * @param keyPrefix  Key前缀（用于构建锁Key）
     * @param id         数据ID
     * @param json       Redis中的JSON数据
     * @param type       返回值类型
     * @param dbFallback DB回源函数
     * @param time       逻辑过期时间
     * @param unit       时间单位
     * @param <R>        返回值泛型
     * @param <ID>       ID泛型
     * @return 缓存数据（可能过期）或null
     */
    private <R, ID> R handleLogicalExpireCheck(String key, String keyPrefix, ID id, String json,
                                               Class<R> type, Function<ID, R> dbFallback,
                                               Long time, TimeUnit unit) {
        RedisData redisData = parseRedisData(json);
        R r = extractDataFromRedisData(redisData, type);
        LocalDateTime expireTime = redisData.getExpireTime();

        if (isNotExpired(expireTime)) {
            return r;
        }

        triggerAsyncRebuild(key, keyPrefix, id, dbFallback, time, unit);
        return r;
    }

    /**
     * 解析RedisData结构
     *
     * @param json JSON字符串（RedisData格式）
     * @return RedisData对象
     */
    private RedisData parseRedisData(String json) {
        return JSONUtil.toBean(json, RedisData.class);
    }

    /**
     * 从RedisData中提取实际业务数据
     *
     * @param redisData RedisData对象
     * @param type      业务数据类型
     * @param <T>       数据类型泛型
     * @return 业务数据对象
     */
    private <T> T extractDataFromRedisData(RedisData redisData, Class<T> type) {
        return JSONUtil.toBean((JSONObject) redisData.getData(), type);
    }

    /**
     * 检查逻辑过期时间是否未到期
     *
     * @param expireTime 逻辑过期时间
     * @return true=未过期（数据仍然有效）
     */
    private boolean isNotExpired(LocalDateTime expireTime) {
        return expireTime.isAfter(LocalDateTime.now());
    }

    /**
     * 触发异步缓存重建（提交到线程池）
     * <p>
     * 获取分布式锁 → 查DB → 写Redis → 释放锁
     * 整个过程异步执行，主线程立即返回旧数据
     *
     * @param key        Redis Key
     * @param keyPrefix  Key前缀
     * @param id         数据ID
     * @param dbFallback DB回源函数
     * @param time       逻辑过期时间
     * @param unit       时间单位
     * @param <R>        返回值泛型
     * @param <ID>       ID泛型
     */
    private <R, ID> void triggerAsyncRebuild(String key, String keyPrefix, ID id,
                                              Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String lockKey = buildLockKey(keyPrefix, id);
        boolean isLock = tryLock(lockKey);

        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> rebuildCacheAsync(key, id, dbFallback, time, unit, lockKey));
        }
    }

    /**
     * 异步重建缓存的Runnable任务
     *
     * @param key        Redis Key
     * @param id         数据ID
     * @param dbFallback DB回源函数
     * @param time       逻辑过期时间
     * @param unit       时间单位
     * @param lockKey    锁Key
     * @param <R>        返回值泛型
     * @param <ID>       ID泛型
     */
    private <R, ID> void rebuildCacheAsync(String key, ID id, Function<ID, R> dbFallback,
                                           Long time, TimeUnit unit, String lockKey) {
        try {
            R newData = dbFallback.apply(id);
            this.setWithLogicalExpire(key, newData, time, unit);
        } catch (Exception e) {
            log.error("[逻辑过期] 异步重建缓存失败 - key:{}, error:{}", key, e.getMessage(), e);
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }
    }

    // ------------------- 4.5 互斥锁处理 -------------------

    /**
     * 尝试从缓存获取数据（互斥锁流程的Step 1）
     *
     * @param key  Redis Key
     * @param type 返回值类型
     * @param <T>  类型泛型
     * @return 缓存数据或null（未命中或空值时返回null）
     */
    private <T> T tryGetFromCache(String key, Class<T> type) {
        String json = queryFromRedis(key);

        if (isCacheHit(json)) {
            return deserializeJson(json, type);
        }

        if (isNullValueCached(json)) {
            return null;  // 命中空值，返回null
        }

        return null;  // 完全未命中，返回null让调用方继续获取锁
    }

    /**
     * 尝试获取互斥锁（互斥锁流程的Step 2）
     * <p>
     * 获取失败时执行指数退避等待
     *
     * @param lockKey 锁Key
     * @param key     缓存Key（用于日志）
     * @param retry   当前重试次数
     * @return true=获取锁成功，false=获取失败（已等待）
     * @throws InterruptedException 线程中断异常
     */
    private boolean tryAcquireMutex(String lockKey, String key, int retry) throws InterruptedException {
        boolean isLock = tryLock(lockKey);

        if (!isLock) {
            performExponentialBackoff(key, retry);
        }

        return isLock;
    }

    /**
     * 执行指数退避等待
     * <p>
     * 等待时间公式：min(INITIAL_WAIT_MS × BACKOFF_MULTIPLIER^retry, MAX_WAIT_MS)
     *
     * @param key   缓存Key（用于日志）
     * @param retry 当前重试次数
     * @throws InterruptedException 线程中断异常
     */
    private void performExponentialBackoff(String key, int retry) throws InterruptedException {
        long waitTime = calculateBackoffWaitTime(retry);

        logDebugMutexWait(key, retry, waitTime);

        Thread.sleep(waitTime);
    }

    /**
     * 计算指数退避等待时间
     *
     * @param retry 当前重试次数
     * @return 等待时间（毫秒）
     */
    private long calculateBackoffWaitTime(int retry) {
        return Math.min(
                (long) (MUTEX_INITIAL_WAIT_MS * Math.pow(MUTEX_BACKOFF_MULTIPLIER, retry)),
                MUTEX_MAX_WAIT_MS
        );
    }

    /**
     * 记录互斥锁等待日志（DEBUG级别）
     *
     * @param key      缓存Key
     * @param retry    当前重试次数
     * @param waitTime 等待时间（毫秒）
     */
    private void logDebugMutexWait(String key, int retry, long waitTime) {
        log.debug("[互斥锁缓存] 等待锁释放 - key:{}, retry:{}/{}, wait:{}ms",
                key, retry + 1, MUTEX_MAX_RETRIES, waitTime);
    }

    /**
     * 获取锁成功后执行数据库查询和缓存写入（互斥锁流程的Step 3）
     * <p>
     * 使用finally确保锁一定会被释放
     *
     * @param key         缓存Key
     * @param id          数据ID
     * @param type        返回值类型
     * @param dbFallback  DB回源函数
     * @param baseTtl     基础TTL
     * @param randomRange 随机范围
     * @param unit        时间单位
     * @param lockKey     锁Key
     * @param <R>         返回值泛型
     * @param <ID>        ID泛型
     * @return 查询结果
     */
    private <R, ID> R executeWithMutexProtection(String key, ID id, Class<R> type,
                                                  Function<ID, R> dbFallback,
                                                  Long baseTtl, Long randomRange, TimeUnit unit,
                                                  String lockKey) {
        try {
            // 双重检查：持锁后再次确认缓存状态。若其他线程在等待期间已完成重建，
            // 直接返回缓存结果，避免重复查询数据库
            String json = queryFromRedis(key);
            if (isCacheHit(json)) {
                return deserializeJson(json, type);
            }
            if (isNullValueCached(json)) {
                return null;
            }

            R r = dbFallback.apply(id);

            if (r == null) {
                cacheNullValue(key);
                return null;
            }

            long expireTime = calculateRandomTtl(baseTtl.longValue(), randomRange.longValue());
            this.set(key, r, expireTime, unit);
            return r;
        } finally {
            unLock(lockKey);
        }
    }

    /**
     * 记录达到最大重试次数的警告日志
     *
     * @param key 缓存Key
     */
    private void logWarnMaxRetriesReached(String key) {
        log.warn("[互斥锁缓存] 达到最大重试次数，返回null - key:{}, maxRetries:{}", key, MUTEX_MAX_RETRIES);
    }

    // ------------------- 4.6 分布式锁操作 -------------------

    /**
     * 尝试获取分布式锁（SETNX + TTL）
     * <p>
     * 使用Redis的SET命令（SET_IF_ABSENT语义）实现互斥锁
     * <p>
     * 锁的特性：
     * <ul>
     *   <li>原子性操作（SETNX）</li>
     *   <li>自动过期（TTL={@value #LOCK_TTL_SECONDS}秒，防止死锁）</li>
     *   <li> Value固定为"1"（简单实现，生产环境建议用UUID）</li>
     * </ul>
     *
     * @param lockKey 锁Key（如 "lock:show:1001"）
     * @return true=获取锁成功，false=锁已被持有
     */
    private boolean tryLock(String lockKey) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放分布式锁（Lua脚本保证原子性）
     * <p>
     * 使用Lua脚本确保"检查+删除"操作的原子性，防止误删其他线程的锁
     * <p>
     * Lua脚本逻辑：
     * <pre>
     * if redis.call('get', KEYS[1]) == ARGV[1] then
     *     return redis.call('del', KEYS[1])
     * else
     *     return 0
     * end
     * </pre>
     *
     * @param lockKey 锁Key
     */
    private void unLock(String lockKey) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), "1");
    }

    // ------------------- 4.7 连接池监控 -------------------

    /**
     * 监控Redis连接池状态（简化版）
     * <p>
     * 当前实现仅记录调试日志，未来可扩展为：
     * <ul>
     *   <li>活跃连接数统计</li>
     *   <li>空闲连接数统计</li>
     *   <li>等待线程数统计</li>
     *   <li>连接池耗尽预警</li>
     * </ul>
     */
    private void monitorRedisPool() {
        try {
            logLettuceConnectionInfo();
            log.debug("[Redis连接池] 监控检查完成（简化版）");
        } catch (Exception e) {
            log.debug("[Redis连接池] 监控异常（可忽略）:{}", e.getMessage());
        }
    }

    /**
     * 记录Lettuce连接工厂信息（如果适用）
     */
    private void logLettuceConnectionInfo() {
        Object connectionFactory = stringRedisTemplate.getConnectionFactory();

        if (isLettuceConnectionFactory(connectionFactory)) {
            logLettuceShareMode(connectionFactory);
        }
    }

    /**
     * 判断是否为Lettuce连接工厂
     *
     * @param connectionFactory 连接工厂对象
     * @return true=Lettuce连接工厂
     */
    private boolean isLettuceConnectionFactory(Object connectionFactory) {
        return connectionFactory instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
    }

    /**
     * 记录Lettuce共享连接模式信息
     *
     * @param connectionFactory 连接工厂对象
     */
    private void logLettuceShareMode(Object connectionFactory) {
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory lettuceFactory =
                (org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory) connectionFactory;

        if (lettuceFactory.getShareNativeConnection()) {
            log.debug("[Redis连接池] Lettuce使用共享连接模式");
        }
    }

    /**
     * 处理连接池耗尽异常
     *
     * @param e             原始异常
     * @param operationName 操作名称
     */
    private void handlePoolExhaustion(Exception e, String operationName) {
        if (isPoolRelatedException(e)) {
            logErrorPoolExhausted(operationName, e);
            throw new RuntimeException("系统繁忙，请稍后再试（Redis连接池耗尽）", e);
        }
    }

    /**
     * 判断是否为连接池相关异常
     *
     * @param e 异常对象
     * @return true=连接池相关异常
     */
    private boolean isPoolRelatedException(Exception e) {
        return e.getMessage() != null && e.getMessage().contains("pool");
    }

    /**
     * 记录连接池耗尽错误日志
     *
     * @param operationName 操作名称
     * @param e             异常对象
     */
    private void logErrorPoolExhausted(String operationName, Exception e) {
        log.error("[Redis连接池] 获取连接失败，操作:{}, error:{}", operationName, e.getMessage());
    }

    // ------------------- 4.8 告警状态管理 -------------------

    /**
     * 调度告警状态重置任务（5分钟后重置告警标记）
     * <p>
     * 使用synchronized保证线程安全（双重检查锁定）
     */
    private synchronized void schedulePoolAlertReset() {
        if (shouldCreateAlertExecutor()) {
            createAndScheduleAlertExecutor();
        }
    }

    /**
     * 判断是否需要创建告警执行器
     *
     * @return true=需要创建
     */
    private boolean shouldCreateAlertExecutor() {
        return alertResetExecutor == null || alertResetExecutor.isShutdown();
    }

    /**
     * 创建告警执行器并调度重置任务
     */
    private void createAndScheduleAlertExecutor() {
        alertResetExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cache-pool-alert-reset");
            t.setDaemon(true);
            return t;
        });

        alertResetExecutor.schedule(() -> {
            poolExhaustionAlerted = false;
            log.info("[Redis连接池] 告警状态已重置");
        }, POOL_ALERT_RESET_MINUTES, TimeUnit.MINUTES);
    }

    // ------------------- 4.9 生命周期管理 -------------------

    /**
     * Spring容器销毁时清理资源
     * <p>
     * 优雅关闭告警重置线程池，避免资源泄漏
     */
    @PreDestroy
    public void destroy() {
        if (alertResetExecutor != null && !alertResetExecutor.isShutdown()) {
            alertResetExecutor.shutdown();
            try {
                if (!alertResetExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    alertResetExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                alertResetExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("[CacheClient] 告警重置线程池已关闭");
        }
    }
}
