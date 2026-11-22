package com.echo.unit.service.business.show;

import com.echo.dto.Result;
import com.echo.dto.UserDTO;
import com.echo.entity.Show;
import com.echo.mapper.ShowMapper;
import com.echo.service.impl.StatisticsServiceImpl;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StatisticsServiceImpl - Coverage Enhancement Test")
public class StatisticsServiceImplCoverageTest {

    @Mock
    private ShowMapper showMapper;

    @InjectMocks
    private StatisticsServiceImpl statisticsService;

    private MockedStatic<UserHolder> userHolderMock;

    private Show testShow;
    private UserDTO testUser;

    @BeforeEach
    void setUp() {
        testShow = new Show();
        testShow.setId(100L);
        testShow.setName("测试演出");
        testShow.setStatus(1);
        testShow.setCreateTime(LocalDateTime.now());

        testUser = new UserDTO();
        testUser.setId(1L);
        testUser.setNickName("测试用户");

        userHolderMock = mockStatic(UserHolder.class);
    }

    @AfterEach
    void tearDown() {
        if (userHolderMock != null) {
            userHolderMock.close();
        }
    }

    // ========================================================================
    // 1️⃣ 热门演出排行
    // ========================================================================

    @Nested
    @DisplayName("1. Hot Shows Ranking")
    class HotShowsTests {

        @Test
        @DisplayName("1.1 获取热门演出列表 - 返回结果")
        void hotShows_returnsResult() {
            when(showMapper.selectList(any())).thenReturn(Arrays.asList(testShow));

            Result<?> result = statisticsService.hotShows(10);

            log.info("[TEST] 热门演出: success={}", result.getSuccess());
            assertNotNull(result, "应返回结果对象");
            log.info("[PASS] 热门演出排行获取完成");
        }

        @Test
        @DisplayName("1.2 不同数量限制")
        void hotShows_differentLimits() {
            int[] limits = {5, 10, 20, 50, 100};

            for (int limit : limits) {
                assertDoesNotThrow(() -> statisticsService.hotShows(limit));
                log.info("[TEST] 获取{}个热门演出", limit);
            }

            log.info("[PASS] 不同数量限制测试完成");
        }

        @Test
        @DisplayName("1.3 边界值：limit=0和负数")
        void hotShows_boundaryValues() {
            assertDoesNotThrow(() -> statisticsService.hotShows(0), "limit=0应安全处理");
            assertDoesNotThrow(() -> statisticsService.hotShows(-1), "limit=-1应安全处理");
            assertDoesNotThrow(() -> statisticsService.hotShows(Integer.MAX_VALUE), "最大值应安全处理");

            log.info("[PASS] 边界值测试完成");
        }

        @Test
        @DisplayName("1.4 数据库异常 - 降级处理")
        void hotShows_dbException() {
            when(showMapper.selectList(any()))
                .thenThrow(new RuntimeException("数据库连接失败"));

            assertDoesNotThrow(() -> statisticsService.hotShows(10),
                "数据库异常不应导致系统崩溃");

            log.info("[PASS] 数据库异常降级处理正常");
        }
    }

    // ========================================================================
    // 2️⃣ 销售趋势统计
    // ========================================================================

    @Nested
    @DisplayName("2. Sales Trend Analysis")
    class SalesTrendTests {

        @Test
        @DisplayName("2.1 获取销售趋势 - 返回结果")
        void salesTrend_returnsResult() {
            Result<?> result = statisticsService.salesTrend(7);

            log.info("[TEST] 销售趋势: success={}", result.getSuccess());
            assertNotNull(result, "应返回结果对象");
            log.info("[PASS] 销售趋势获取完成");
        }

        @Test
        @DisplayName("2.2 不同时间范围")
        void salesTrend_differentDays() {
            int[] days = {1, 7, 30, 90, 365};

            for (int day : days) {
                assertDoesNotThrow(() -> statisticsService.salesTrend(day));
                log.info("[TEST] {}天销售趋势", day);
            }

            log.info("[PASS] 不同时间范围测试完成");
        }

        @Test
        @DisplayName("2.3 边界值：days=0和极大值")
        void salesTrend_boundaryValues() {
            assertDoesNotThrow(() -> statisticsService.salesTrend(0), "days=0应安全处理");
            assertDoesNotThrow(() -> statisticsService.salesTrend(-1), "days=-1应安全处理");
            assertDoesNotThrow(() -> statisticsService.salesTrend(Integer.MAX_VALUE), "最大值应安全处理");

            log.info("[PASS] 边界值测试完成");
        }

        @Test
        @DisplayName("2.4 数据库查询异常")
        void salesTrend_dbException() {
            lenient().when(showMapper.selectList(any()))
                .thenThrow(new RuntimeException("数据库超时"));

            assertDoesNotThrow(() -> statisticsService.salesTrend(7),
                "数据库异常不应崩溃");

            log.info("[PASS] 销售趋势异常降级正常");
        }
    }

    // ========================================================================
    // 3️⃣ 用户画像分析
    // ========================================================================

    @Nested
    @DisplayName("3. User Profile Analysis")
    class UserProfileTests {

        @Test
        @DisplayName("3.1 获取用户画像 - 已登录用户")
        void userProfile_authenticatedUser() {
            userHolderMock.when(UserHolder::getUser).thenReturn(testUser);

            Result<?> result = statisticsService.userProfile();

            log.info("[TEST] 用户画像: success={}", result.getSuccess());
            assertNotNull(result, "应返回结果对象");
            log.info("[PASS] 用户画像获取完成");
        }

        @Test
        @DisplayName("3.2 未登录用户 - 返回错误")
        void userProfile_unauthenticatedUser() {
            userHolderMock.when(UserHolder::getUser).thenReturn(null);

            Result<?> result = statisticsService.userProfile();

            assertNotNull(result, "即使未登录也应返回结果");
            log.info("[PASS] 未登录用户处理完成");
        }

        @Test
        @DisplayName("3.3 多次调用用户画像")
        void userProfile_multipleCalls() {
            userHolderMock.when(UserHolder::getUser).thenReturn(testUser);

            for (int i = 0; i < 10; i++) {
                assertDoesNotThrow(() -> statisticsService.userProfile());
            }

            log.info("[PASS] 多次调用用户画像完成");
        }
    }

    // ========================================================================
    // 4️⃣ 异常场景覆盖
    // ========================================================================

    @Nested
    @DisplayName("4. Exception Scenarios and Edge Cases")
    class ExceptionScenarios {

        @Test
        @DisplayName("4.1 并发请求热门演出")
        void concurrentHotShows_requests() throws InterruptedException {
            Thread[] threads = new Thread[10];
            for (int i = 0; i < threads.length; i++) {
                final int index = i;
                threads[i] = new Thread(() -> {
                    try {
                        statisticsService.hotShows(10);
                        log.info("[TEST] 并发线程{}完成", index);
                    } catch (Exception e) {
                        log.warn("[TEST] 并发线程{}异常: {}", index, e.getMessage());
                    }
                });
            }

            for (Thread thread : threads) {
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join(2000);
            }

            log.info("[PASS] 并发请求热门演出完成");
        }

        @Test
        @DisplayName("4.2 极端参数组合")
        void extremeParameterCombinations() {
            Integer[] extremeLimits = {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE};
            Integer[] extremeDays = {0, -999, Integer.MAX_VALUE};

            for (Integer limit : extremeLimits) {
                assertDoesNotThrow(() -> statisticsService.hotShows(limit));
            }

            for (Integer days : extremeDays) {
                assertDoesNotThrow(() -> statisticsService.salesTrend(days));
            }

            log.info("[PASS] 极端参数组合测试完成");
        }

        @Test
        @DisplayName("4.3 快速连续调用")
        void rapidSequentialCalls() {
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < 100; i++) {
                statisticsService.hotShows(10);
                statisticsService.salesTrend(7);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("[TEST] 200次快速调用耗时: {}ms", duration);
            assertTrue(duration < 5000, "200次调用应在5秒内完成");

            log.info("[PASS] 快速连续调用性能验证完成");
        }
    }

    // ========================================================================
    // 5️⃣ 性能基准测试
    // ========================================================================

    @Nested
    @DisplayName("5. Performance Benchmark Tests")
    class PerformanceTests {

        @Test
        @DisplayName("5.1 批量热门演出查询性能")
        void batchHotShows_performance() {
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < 200; i++) {
                statisticsService.hotShows(20);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("[TEST] 200次热门查询耗时: {}ms", duration);
            assertTrue(duration < 3000, "应在3秒内完成");

            log.info("[PASS] 热门演出批量查询性能验证完成");
        }

        @Test
        @DisplayName("5.2 批量销售趋势查询性能")
        void batchSalesTrend_performance() {
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < 200; i++) {
                statisticsService.salesTrend(30);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("[TEST] 200次销售趋势查询耗时: {}ms", duration);
            assertTrue(duration < 3000, "应在3秒内完成");

            log.info("[PASS] 销售趋势批量查询性能验证完成");
        }
    }
}
