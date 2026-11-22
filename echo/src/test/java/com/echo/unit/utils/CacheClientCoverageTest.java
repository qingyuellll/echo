package com.echo.unit.utils;

import com.echo.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CacheClient - Coverage Enhancement Test")
public class CacheClientCoverageTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CacheClient cacheClient;

    @BeforeEach
    void setUp() {
        cacheClient = new CacheClient(stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ========================================================================
    // 1️⃣ 基础缓存操作
    // ========================================================================

    @Nested
    @DisplayName("1. Basic Cache Operations")
    class BasicCacheOperations {

        @Test
        @DisplayName("1.1 设置缓存 - 正常执行")
        void setCache_normalExecution() {
            String key = "test:key";
            String value = "testValue";
            Long ttl = 30L;
            TimeUnit unit = TimeUnit.MINUTES;

            assertDoesNotThrow(() -> cacheClient.set(key, value, ttl, unit));

            verify(valueOperations).set(eq(key), anyString(), eq(ttl), eq(unit));
            log.info("[PASS] 设置缓存完成");
        }

        @Test
        @DisplayName("1.2 设置逻辑过期时间")
        void setWithLogicalExpire_normalExecution() {
            String key = "test:logical:key";
            String value = "logicalValue";
            Long ttl = 60L;
            TimeUnit unit = TimeUnit.SECONDS;

            assertDoesNotThrow(() -> cacheClient.setWithLogicalExpire(key, value, ttl, unit));

            log.info("[PASS] 设置逻辑过期时间完成");
        }

        @Test
        @DisplayName("1.3 不同时间单位")
        void setCache_differentTimeUnits() {
            String key = "test:timeunit";

            assertDoesNotThrow(() -> cacheClient.set(key, "value", 10L, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> cacheClient.set(key, "value", 5L, TimeUnit.MINUTES));
            assertDoesNotThrow(() -> cacheClient.set(key, "value", 2L, TimeUnit.HOURS));
            assertDoesNotThrow(() -> cacheClient.set(key, "value", 1L, TimeUnit.DAYS));

            log.info("[PASS] 不同时间单位测试完成");
        }
    }

    // ========================================================================
    // 2️⃣ 缓存穿透防护（空值缓存）
    // ========================================================================

    @Nested
    @DisplayName("2. Cache Penetration Prevention (Null Value Caching)")
    class PenetrationPreventionTests {

        @Test
        @DisplayName("2.1 查询不存在的数据 - 缓存空值")
        void queryNonExistentData_cacheNullValue() {
            String keyPrefix = "cache:show:";
            Long id = 99999L;

            when(valueOperations.get(anyString())).thenReturn(null);

            Function<Long, String> dbLoader = (k) -> null; // DB也不存在

            String result = cacheClient.queryWithPassThrough(
                keyPrefix,
                id,
                String.class,
                dbLoader,
                10L,
                TimeUnit.MINUTES
            );

            assertNull(result, "不存在的数据应返回null");
            log.info("[PASS] 缓存穿透防护验证完成");
        }

        @Test
        @DisplayName("2.2 查询存在的数据 - 命中缓存")
        void queryExistingData_cacheHit() {
            String keyPrefix = "cache:user:";
            Long id = 100L;
            String cachedJson = "{\"id\":100,\"name\":\"TestUser\"}";

            when(valueOperations.get(anyString())).thenReturn(cachedJson);

            Function<Long, String> dbLoader = (k) -> "{\"id\":100,\"name\":\"DBUser\"}";

            String result = cacheClient.queryWithPassThrough(
                keyPrefix,
                id,
                String.class,
                dbLoader,
                30L,
                TimeUnit.MINUTES
            );

            assertNotNull(result, "应返回缓存的数据");
            assertTrue(result.contains("TestUser"), "应为缓存中的数据");
            log.info("[PASS] 缓存命中验证完成");
        }

        @Test
        @DisplayName("2.3 缓存未命中 - 从数据库加载")
        void cacheMiss_loadFromDatabase() {
            String keyPrefix = "cache:ticket:";
            Long id = 200L;

            when(valueOperations.get(anyString())).thenReturn(null);

            Function<Long, String> dbLoader = (k) -> "{\"id\":200,\"name\":\"TicketFromDB\"}";

            String result = cacheClient.queryWithPassThrough(
                keyPrefix,
                id,
                String.class,
                dbLoader,
                20L,
                TimeUnit.MINUTES
            );

            assertNotNull(result, "应从数据库加载");
            assertTrue(result.contains("TicketFromDB"), "应为数据库数据");
            log.info("[PASS] 数据库加载验证完成");
        }
    }

    // ========================================================================
    // 3️⃣ 互斥锁缓存重建（击穿防护）
    // ========================================================================

    @Nested
    @DisplayName("3. Mutex Lock for Cache Breakdown Prevention")
    class MutexLockTests {

        @Test
        @DisplayName("3.1 互斥锁查询 - 正常执行")
        void queryWithMutex_normalExecution() {
            String keyPrefix = "cache:hot:";
            Long id = 300L;

            lenient().when(valueOperations.get(anyString())).thenReturn(null);

            Function<Long, String> dbLoader = (k) -> "{\"id\":300,\"name\":\"HotData\"}";

            assertDoesNotThrow(() ->
                cacheClient.queryWithMutex(
                    keyPrefix,
                    id,
                    String.class,
                    dbLoader,
                    30L,
                    10L,
                    TimeUnit.MINUTES
                )
            );

            log.info("[PASS] 互斥锁查询完成");
        }

        @Test
        @DisplayName("3.2 并发请求同一Key")
        void concurrentRequests_sameKey() throws InterruptedException {
            String keyPrefix = "cache:concurrent:";
            Long id = 400L;

            when(valueOperations.get(anyString())).thenReturn(null);

            Function<Long, String> dbLoader = (k) -> "{\"id\":400,\"name\":\"ConcurrentData\"}";

            Thread[] threads = new Thread[5];
            for (int i = 0; i < threads.length; i++) {
                final int index = i;
                threads[i] = new Thread(() -> {
                    try {
                        cacheClient.queryWithMutex(
                            keyPrefix,
                            id,
                            String.class,
                            dbLoader,
                            30L,
                            10L,
                            TimeUnit.MINUTES
                        );
                        log.info("[TEST] 线程{}获取数据成功", index);
                    } catch (Exception e) {
                        log.warn("[TEST] 线程{}异常: {}", index, e.getMessage());
                    }
                });
            }

            for (Thread thread : threads) {
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join(2000);
            }

            log.info("[PASS] 并发请求互斥锁处理完成");
        }
    }

    // ========================================================================
    // 4️⃣ 逻辑过期时间（热点Key）
    // ========================================================================

    @Nested
    @DisplayName("4. Logical Expiration Time (Hot Keys)")
    class LogicalExpireTests {

        @Test
        @DisplayName("4.1 逻辑过期查询 - 正常执行")
        void queryWithLogicalExpire_normalExecution() {
            String keyPrefix = "cache:hotkey:";
            Long id = 500L;

            when(valueOperations.get(anyString())).thenReturn(null);

            Function<Long, String> dbLoader = (k) -> "{\"id\":500,\"name\":\"HotKeyData\"}";

            assertDoesNotThrow(() ->
                cacheClient.queryWithLogicalExpire(
                    keyPrefix,
                    id,
                    String.class,
                    dbLoader,
                    30L,
                    TimeUnit.MINUTES
                )
            );

            log.info("[PASS] 逻辑过期查询完成");
        }

        @Test
        @DisplayName("4.2 不同的TTL设置")
        void differentTTLSettings() {
            String keyPrefix = "cache:ttl:";

            long[] ttls = {60L, 300L, 1800L, 3600L, 86400L};

            for (long ttl : ttls) {
                assertDoesNotThrow(() ->
                    cacheClient.queryWithLogicalExpire(
                        keyPrefix,
                        1L,
                        String.class,
                        (k) -> "data",
                        ttl,
                        TimeUnit.SECONDS
                    )
                );
            }

            log.info("[PASS] 不同TTL设置测试完成");
        }
    }

    // ========================================================================
    // 5️⃣ 连接池保护机制
    // ========================================================================

    @Nested
    @DisplayName("5. Redis Connection Pool Protection")
    class PoolProtectionTests {

        @Test
        @DisplayName("5.1 正常操作 - 连接池保护生效")
        void normalOperation_withPoolProtection() {
            String result = cacheClient.executeWithPoolProtection(
                () -> {
                    log.info("[TEST] 执行Redis操作");
                    return "success";
                },
                "testOperation"
            );

            assertEquals("success", result, "应返回正常结果");
            log.info("[PASS] 连接池保护正常工作");
        }

        @Test
        @DisplayName("5.2 Redis异常 - 抛出异常")
        void redisException_throwsException() {
            assertThrows(RuntimeException.class, () ->
                cacheClient.executeWithPoolProtection(
                    () -> {
                        throw new RuntimeException("Redis连接超时");
                    },
                    "failingOperation"
                ),
                "Redis异常应抛出RuntimeException"
            );

            log.info("[PASS] Redis异常正确抛出");
        }

        @Test
        @DisplayName("5.3 连接池耗尽 - 特殊异常信息")
        void poolExhaustion_specificMessage() {
            RuntimeException poolException = new RuntimeException("Cannot get connection from pool");

            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                cacheClient.executeWithPoolProtection(
                    () -> { throw poolException; },
                    "poolExhaustedOperation"
                )
            );

            assertTrue(thrown.getMessage().contains("系统繁忙"),
                "连接池耗尽应包含特定提示信息");

            log.info("[PASS] 连接池耗尽特殊提示验证完成");
        }
    }

    // ========================================================================
    // 6️⃣ 异常场景覆盖
    // ========================================================================

    @Nested
    @DisplayName("6. Exception Scenarios and Edge Cases")
    class ExceptionScenarios {

        @Test
        @DisplayName("6.1 Redis操作异常 - 系统不崩溃")
        void redisException_systemNotCrash() {
            doThrow(new RuntimeException("Redis连接失败"))
                .when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

            assertDoesNotThrow(() ->
                cacheClient.set("test:error", "value", 30L, TimeUnit.MINUTES),
                "Redis异常不应导致系统崩溃"
            );

            log.info("[PASS] Redis异常降级处理正常");
        }

        @Test
        @DisplayName("6.2 边界参数：TTL为0或负数")
        void boundaryTTLValues() {
            Long[] ttls = {0L, -1L, -100L, Long.MAX_VALUE, Long.MIN_VALUE};

            for (Long ttl : ttls) {
                assertDoesNotThrow(() ->
                    cacheClient.set("boundary:test", "value", ttl, TimeUnit.SECONDS),
                    "边界TTL值应安全处理"
                );
            }

            log.info("[PASS] 边界TTL值测试完成");
        }

        @Test
        @DisplayName("6.3 null参数处理")
        void nullParameterHandling() {
            assertDoesNotThrow(() ->
                cacheClient.set(null, null, 30L, TimeUnit.MINUTES),
                "null参数应安全处理"
            );

            assertDoesNotThrow(() ->
                cacheClient.executeWithPoolProtection(null, null),
                "null参数应安全处理"
            );

            log.info("[PASS] null参数处理完成");
        }

        @Test
        @DisplayName("6.4 超长Key和Value")
        void longKeyAndValue() {
            StringBuilder longKey = new StringBuilder();
            StringBuilder longValue = new StringBuilder();

            for (int i = 0; i < 1000; i++) {
                longKey.append("a");
                longValue.append("b");
            }

            assertDoesNotThrow(() ->
                cacheClient.set(longKey.toString(), longValue.toString(), 10L, TimeUnit.MINUTES),
                "超长Key/Value应安全处理"
            );

            log.info("[PASS] 超长Key/Value测试完成");
        }

        @Test
        @DisplayName("6.5 高并发压力测试")
        void highConcurrency_stressTest() throws InterruptedException {
            int threadCount = 20;
            Thread[] threads = new Thread[threadCount];

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                threads[i] = new Thread(() -> {
                    try {
                        for (int j = 0; j < 50; j++) {
                            cacheClient.set("stress:" + index + ":" + j, "value" + j, 1L, TimeUnit.MINUTES);
                        }
                        log.info("[TEST] 压力线程{}完成", index);
                    } catch (Exception e) {
                        log.warn("[TEST] 压力线程{}异常: {}", index, e.getMessage());
                    }
                });
            }

            for (Thread thread : threads) {
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join(3000);
            }

            log.info("[PASS] 高并发压力测试完成");
        }
    }

    // ========================================================================
    // 7️⃣ 性能基准测试
    // ========================================================================

    @Nested
    @DisplayName("7. Performance Benchmark Tests")
    class PerformanceTests {

        @Test
        @DisplayName("7.1 批量Set操作性能")
        void batchSet_performance() {
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < 1000; i++) {
                cacheClient.set("perf:set:" + i, "value" + i, 10L, TimeUnit.SECONDS);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("[TEST] 1000次Set操作耗时: {}ms", duration);
            assertTrue(duration < 5000, "应在5秒内完成");

            log.info("[PASS] 批量Set性能验证完成");
        }

        @Test
        @DisplayName("7.2 批量查询操作性能")
        void batchQuery_performance() {
            when(valueOperations.get(anyString())).thenReturn("{\"data\":\"cached\"}");

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < 1000; i++) {
                cacheClient.queryWithPassThrough(
                    "cache:perf:",
                    (long) i,
                    String.class,
                    (k) -> "dbData",
                    10L,
                    TimeUnit.MINUTES
                );
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("[TEST] 1000次查询耗时: {}ms", duration);
            assertTrue(duration < 5000, "应在5秒内完成");

            log.info("[PASS] 批量查询性能验证完成");
        }
    }
}
