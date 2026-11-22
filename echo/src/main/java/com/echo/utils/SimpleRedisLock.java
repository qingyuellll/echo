package com.echo.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于Redis的分布式锁实现(支持可重入+看门狗自动续期)
 *
 * @author tulip
 */
@Slf4j
public class SimpleRedisLock implements ILock {

    private final String name;
    private final StringRedisTemplate stringRedisTemplate;
    private final String instanceId;

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString() + "-";
    
    private static final long DEFAULT_WAIT_TIME = -1L;       // 默认永久等待
    private static final long DEFAULT_RETRY_INTERVAL_MS = 100L;  // 默认重试间隔100ms
    private static final double WATCHDOG_RENEWAL_RATIO = 2.0 / 3.0;  // 看门狗续期比例（TTL的2/3时间后续期）
    private static final int WATCHDOG_MAX_RENEWALS = 3;  // 看门狗最大续期次数，超限后放弃续期，锁随TTL自动过期

    private static final DefaultRedisScript<Long> LOCK_SCRIPT;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    private static final DefaultRedisScript<Long> RENEW_SCRIPT;

    static {
        LOCK_SCRIPT = new DefaultRedisScript<>();
        LOCK_SCRIPT.setLocation(new ClassPathResource("lua/reentrant_lock.lua"));
        LOCK_SCRIPT.setResultType(Long.class);

        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("lua/unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);

        RENEW_SCRIPT = new DefaultRedisScript<>();
        RENEW_SCRIPT.setLocation(new ClassPathResource("lua/renew_lock.lua"));
        RENEW_SCRIPT.setResultType(Long.class);
    }

    /**
     * 看门狗任务注册表（静态）：任何锁实例的 unlock 都能取消同一 key 的看门狗任务；
     * 配合 WATCHDOG_MAX_RENEWALS 上限，防止持有线程卡死且 unlock 未执行时，
     * 任务在共享调度器中被已无人引用的实例"永生"续期
     */
    private static final Map<String, ScheduledFuture<?>> WATCH_DOG_TASKS = new ConcurrentHashMap<>();

    /**
     * 看门狗共享调度器：所有锁实例复用同一个线程池，
     * 避免每次成功获取锁都创建一个新的调度线程池（线程泄漏）
     */
    private static final ScheduledExecutorService WATCHDOG_SCHEDULER = Executors.newScheduledThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "lock-watchdog");
                t.setDaemon(true);  // 设置为守护线程，不阻止JVM退出
                return t;
            });

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
        this.instanceId = ID_PREFIX;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        return doTryLock(timeoutSec, true);
    }

    /**
     * 获取锁，并指定是否启用看门狗自动续期
     *
     * @param timeoutSec 锁的TTL（秒）
     * @param autoRenew  true=启用看门狗（适合临界区耗时不确定、可能超过TTL的场景）；
     *                   false=固定TTL不续期，到期自动释放（适合毫秒级短临界区，零看门狗开销）
     * @return true=成功获取锁
     */
    public boolean tryLock(long timeoutSec, boolean autoRenew) {
        return doTryLock(timeoutSec, autoRenew);
    }

    /**
     * 阻塞式获取锁（带超时）
     *
     * @param timeoutSec     锁的超时时间（秒），也作为看门狗续期的基准时间
     * @param maxWaitTimeMs  最大等待时间（毫秒），-1表示永久等待
     * @param retryIntervalMs 重试间隔（毫秒）
     * @return true=成功获取锁，false=超时未获取到锁
     */
    public boolean tryLock(long timeoutSec, long maxWaitTimeMs, long retryIntervalMs) {
        if (maxWaitTimeMs == 0) {
            return doTryLock(timeoutSec, true);
        }

        long startTime = System.currentTimeMillis();
        long actualRetryInterval = Math.max(retryIntervalMs, 10);  // 最小10ms

        while (true) {
            if (doTryLock(timeoutSec, true)) {
                return true;
            }

            if (maxWaitTimeMs > 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed >= maxWaitTimeMs) {
                    log.warn("[分布式锁] 获取锁超时 - lock:{}, waited:{}ms, maxWait:{}ms",
                            name, elapsed, maxWaitTimeMs);
                    return false;
                }
            }

            try {
                Thread.sleep(actualRetryInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[分布式锁] 等待锁时被中断 - lock:{}", name);
                return false;
            }
        }
    }

    private boolean doTryLock(long timeoutSec, boolean autoRenew) {
        String threadId = getThreadId();
        String key = KEY_PREFIX + name;

        try {
            Long result = stringRedisTemplate.execute(
                    LOCK_SCRIPT,
                    Collections.singletonList(key),
                    threadId,
                    String.valueOf(timeoutSec)
            );

            boolean success = result != null && result > 0;

            if (success) {
                log.info("[分布式锁] 获取锁成功 - lock:{}, threadId:{}, TTL:{}s, reentrantCount:{}",
                        name, threadId, timeoutSec, result);

                if (autoRenew) {
                    startWatchDog(key, threadId, timeoutSec);
                }
            } else {
                log.debug("[分布式锁] 获取锁失败（被其他线程持有） - lock:{}, threadId:{}", 
                         name, threadId);
            }

            return success;

        } catch (Exception e) {
            log.error("[分布式锁] 获取锁异常 - lock:{}, error:{}", name, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void unlock() {
        String threadId = getThreadId();
        String key = KEY_PREFIX + name;

        try {
            stopWatchDog(key, threadId);

            Long result = stringRedisTemplate.execute(
                    UNLOCK_SCRIPT,
                    Collections.singletonList(key),
                    threadId
            );

            if (result != null && result >= 1L) {
                log.info("[分布式锁] 释放锁成功 - lock:{}, threadId:{}, remainingReentrantCount:{}",
                        name, threadId, result - 1);
            } else {
                log.warn("[分布式锁] 释放锁失败（锁已过期或非当前线程持有） - lock:{}, threadId:{}",
                        name, threadId);
            }

        } catch (Exception e) {
            log.error("[分布式锁] 释放锁异常 - lock:{}, error:{}", name, e.getMessage(), e);
        }
    }

    /**
     * 查询当前线程是否持有该锁
     *
     * @return true=当前线程持有该锁
     */
    public boolean isHeldByCurrentThread() {
        String threadId = getThreadId();
        String key = KEY_PREFIX + name;
        
        try {
            Object value = stringRedisTemplate.opsForHash().get(key, threadId);
            return value != null;
        } catch (Exception e) {
            log.error("[分布式锁] 查询锁状态异常 - lock:{}, error:{}", name, e.getMessage());
            return false;
        }
    }

    /**
     * 查询锁是否存在（被任意线程持有）
     *
     * @return true=锁存在
     */
    public boolean isLocked() {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(KEY_PREFIX + name));
        } catch (Exception e) {
            log.error("[分布式锁] 查询锁是否存在异常 - lock:{}, error:{}", name, e.getMessage());
            return false;
        }
    }

    /**
     * 获取剩余存活时间（秒）
     *
     * @return -1=锁不存在，-2=锁没有设置TTL，>=0=剩余秒数
     */
    public long getRemainingTTL() {
        try {
            return stringRedisTemplate.getExpire(KEY_PREFIX + name, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[分布式锁] 查询TTL异常 - lock:{}, error:{}", name, e.getMessage());
            return -1;
        }
    }

    private String getThreadId() {
        return instanceId + Thread.currentThread().getId();
    }

    private void startWatchDog(String key, String threadId, long timeoutSec) {
        if (timeoutSec <= 1) {
            log.debug("[看门狗] TTL太短({}s)，不启动看门狗", timeoutSec);
            return;
        }

        long renewIntervalMs = (long) (timeoutSec * WATCHDOG_RENEWAL_RATIO * 1000);
        AtomicInteger renewCount = new AtomicInteger();
        String taskKey = key + ":" + threadId;

        ScheduledFuture<?> task = WATCHDOG_SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                // 达到最大续期次数后主动放弃：锁随TTL自动过期。
                // 防止持有线程卡死且unlock未执行时，看门狗任务"永生"续期导致锁永久被占
                if (renewCount.incrementAndGet() > WATCHDOG_MAX_RENEWALS) {
                    log.warn("[看门狗] 已达最大续期次数({})，停止续期，锁将随TTL过期 - lock:{}",
                            WATCHDOG_MAX_RENEWALS, key);
                    stopWatchDog(key, threadId);
                    return;
                }

                Long result = stringRedisTemplate.execute(
                        RENEW_SCRIPT,
                        Collections.singletonList(key),
                        threadId,
                        String.valueOf(timeoutSec)
                );

                if (result != null && result > 0) {
                    log.debug("[看门狗] 续期成功({}/{}) - lock:{}, nextRenewIn:{}ms",
                            renewCount.get(), WATCHDOG_MAX_RENEWALS, key, renewIntervalMs);
                } else {
                    log.warn("[看门狗] 续期失败（锁已不存在或非当前线程持有） - lock:{}, 停止看门狗", key);
                    stopWatchDog(key, threadId);
                }
            } catch (Exception e) {
                // 续期异常（脚本加载失败/Redis不可用等）：记录一次错误即退出，
                // 避免每个续期周期（约6.6秒）重复刷错误日志
                log.error("[看门狗] 续期异常，看门狗退出 - lock:{}, error:{}", key, e.getMessage());
                stopWatchDog(key, threadId);
            }
        }, renewIntervalMs, renewIntervalMs, TimeUnit.MILLISECONDS);

        WATCH_DOG_TASKS.put(taskKey, task);

        log.info("[看门狗] 启动成功 - lock:{}, threadId:{}, interval:{}ms", 
                 name, threadId, renewIntervalMs);
    }

    private void stopWatchDog(String key, String threadId) {
        String taskKey = key + ":" + threadId;
        ScheduledFuture<?> task = WATCH_DOG_TASKS.remove(taskKey);
        
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
            log.debug("[看门狗] 已停止 - lock:{}, threadId:{}", name, threadId);
        }
    }
}
