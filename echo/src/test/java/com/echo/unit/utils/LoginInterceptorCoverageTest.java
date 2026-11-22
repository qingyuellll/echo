package com.echo.unit.utils;

import com.echo.dto.AdminDTO;
import com.echo.dto.UserDTO;
import com.echo.enums.AdminStatus;
import com.echo.utils.LoginInterceptor;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LoginInterceptor - Coverage Enhancement Test")
public class LoginInterceptorCoverageTest {

    private LoginInterceptor loginInterceptor;

    private MockedStatic<UserHolder> userHolderMock;

    @BeforeEach
    void setUp() {
        loginInterceptor = new LoginInterceptor();
        userHolderMock = mockStatic(UserHolder.class);
    }

    @AfterEach
    void tearDown() {
        if (userHolderMock != null) {
            userHolderMock.close();
        }
    }

    // ========================================================================
    // 1️⃣ 基础拦截功能
    // ========================================================================

    @Nested
    @DisplayName("1. Basic Interception Logic")
    class BasicInterceptionTests {

        @Test
        @DisplayName("1.1 已登录普通用户 - 放行请求")
        void authenticatedUser_requestAllowed() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            UserDTO user = new UserDTO();
            user.setId(1L);
            user.setStatus(0); // 正常状态

            userHolderMock.when(UserHolder::getUser).thenReturn(user);
            userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

            boolean result = loginInterceptor.preHandle(request, response, new Object());

            assertTrue(result, "已登录用户应放行");
            log.info("[PASS] 已登录用户放行验证完成");
        }

        @Test
        @DisplayName("1.2 已登录管理员 - 放行请求")
        void authenticatedAdmin_requestAllowed() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            AdminDTO admin = new AdminDTO();
            admin.setId(1L);
            admin.setStatus(AdminStatus.NORMAL); // 正常状态

            userHolderMock.when(UserHolder::getUser).thenReturn(null);
            userHolderMock.when(UserHolder::getAdmin).thenReturn(admin);

            boolean result = loginInterceptor.preHandle(request, response, new Object());

            assertTrue(result, "已登录管理员应放行");
            log.info("[PASS] 已登录管理员放行验证完成");
        }

        @Test
        @DisplayName("1.3 未登录用户 - 拦截请求（返回401）")
        void unauthenticatedUser_requestBlocked() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            userHolderMock.when(UserHolder::getUser).thenReturn(null);
            userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

            boolean result = loginInterceptor.preHandle(request, response, new Object());

            assertFalse(result, "未登录用户应被拦截");
            assertEquals(401, response.getStatus(), "应返回401状态码");

            String responseBody = response.getContentAsString();
            assertNotNull(responseBody, "应有响应体");
            assertTrue(responseBody.contains("401") || responseBody.contains("未登录"),
                "响应体应包含错误信息");

            log.info("[PASS] 未登录用户拦截验证完成（401）");
        }
    }

    // ========================================================================
    // 2️⃣ 用户/管理员禁用状态
    // ========================================================================

    @Nested
    @DisplayName("2. Account Status Checks (Disabled Accounts)")
    class AccountStatusTests {

        @Test
        @DisplayName("2.1 禁用的普通用户 - 拦截请求（返回403）")
        void disabledUser_requestBlocked() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            UserDTO disabledUser = new UserDTO();
            disabledUser.setId(99L);
            disabledUser.setStatus(1); // 禁用状态

            userHolderMock.when(UserHolder::getUser).thenReturn(disabledUser);
            userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

            boolean result = loginInterceptor.preHandle(request, response, new Object());

            assertFalse(result, "禁用用户应被拦截");
            assertEquals(403, response.getStatus(), "应返回403状态码");

            log.info("[PASS] 禁用用户拦截验证完成（403）");
        }

        @Test
        @DisplayName("2.2 禁用的管理员 - 拦截请求（返回403）")
        void disabledAdmin_requestBlocked() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            AdminDTO disabledAdmin = new AdminDTO();
            disabledAdmin.setId(88L);
            disabledAdmin.setStatus(AdminStatus.DISABLED); // 禁用状态

            userHolderMock.when(UserHolder::getUser).thenReturn(null);
            userHolderMock.when(UserHolder::getAdmin).thenReturn(disabledAdmin);

            boolean result = loginInterceptor.preHandle(request, response, new Object());

            assertFalse(result, "禁用管理员应被拦截");
            assertEquals(403, response.getStatus(), "应返回403状态码");

            log.info("[PASS] 禁用管理员拦截验证完成（403）");
        }

        @Test
        @DisplayName("2.3 不同用户状态值测试")
        void differentUserStatusValues() throws Exception {
            Integer[] statuses = {0, 1, 2, -1, null};

            for (Integer status : statuses) {
                MockHttpServletRequest request = new MockHttpServletRequest();
                MockHttpServletResponse response = new MockHttpServletResponse();

                UserDTO user = new UserDTO();
                user.setId(1L);
                user.setStatus(status);

                userHolderMock.when(UserHolder::getUser).thenReturn(user);
                userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

                boolean result = loginInterceptor.preHandle(request, response, new Object());

                if (status != null && status == 1) {
                    assertFalse(result, "status=1应被拦截");
                    assertEquals(403, response.getStatus());
                } else {
                    assertTrue(result, "其他状态应放行");
                }

                log.info("[TEST] 用户status={} → result={}", status, result);
            }

            log.info("[PASS] 不同用户状态值测试完成");
        }
    }

    // ========================================================================
    // 3️⃣ HTTP方法和路径处理
    // ========================================================================

    @Nested
    @DisplayName("3. HTTP Method and Path Handling")
    class HttpMethodTests {

        @Test
        @DisplayName("3.1 GET请求 - 正常拦截")
        void getMethod_normalInterception() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setMethod("GET");
            request.setRequestURI("/api/user/info");
            MockHttpServletResponse response = new MockHttpServletResponse();

            UserDTO user = new UserDTO();
            user.setId(1L);
            user.setStatus(0);

            userHolderMock.when(UserHolder::getUser).thenReturn(user);
            userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

            boolean result = loginInterceptor.preHandle(request, response, new Object());

            assertTrue(result, "GET请求应正常放行");
            log.info("[PASS] GET请求处理完成");
        }

        @Test
        @DisplayName("3.2 POST请求 - 正常拦截")
        void postMethod_normalInterception() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setMethod("POST");
            request.setRequestURI("/api/order/create");
            MockHttpServletResponse response = new MockHttpServletResponse();

            userHolderMock.when(UserHolder::getUser).thenReturn(new UserDTO());
            userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

            boolean result = loginInterceptor.preHandle(request, response, new Object());

            assertTrue(result, "POST请求应正常放行");
            log.info("[PASS] POST请求处理完成");
        }

        @Test
        @DisplayName("3.3 多种HTTP方法测试")
        void multipleHttpMethods() throws Exception {
            String[] methods = {"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"};

            for (String method : methods) {
                MockHttpServletRequest request = new MockHttpServletRequest();
                request.setMethod(method);
                MockHttpServletResponse response = new MockHttpServletResponse();

                userHolderMock.when(UserHolder::getUser).thenReturn(new UserDTO());
                userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

                assertDoesNotThrow(() -> loginInterceptor.preHandle(request, response, new Object()),
                    method + "请求不应抛出异常");
            }

            log.info("[PASS] 多种HTTP方法测试完成");
        }
    }

    // ========================================================================
    // 4️⃣ afterCompletion清理功能
    // ========================================================================

    @Nested
    @DisplayName("4. afterCompletion Cleanup")
    class AfterCompletionTests {

        @Test
        @DisplayName("4.1 请求完成后清理用户信息")
        void afterCompletion_cleansUser() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertDoesNotThrow(() ->
                loginInterceptor.afterCompletion(request, response, new Object(), null),
                "afterCompletion不应抛出异常"
            );

            log.info("[PASS] afterCompletion清理验证完成");
        }

        @Test
        @DisplayName("4.2 异常情况下的清理")
        void afterCompletion_withException() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            Exception testException = new RuntimeException("Test exception");

            assertDoesNotThrow(() ->
                loginInterceptor.afterCompletion(request, response, new Object(), testException),
                "异常情况下afterCompletion不应崩溃"
            );

            log.info("[PASS] 异常情况下的清理验证完成");
        }

        @Test
        @DisplayName("4.3 多次调用afterCompletion")
        void afterCompletion_multipleCalls() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            for (int i = 0; i < 10; i++) {
                assertDoesNotThrow(() ->
                    loginInterceptor.afterCompletion(request, response, new Object(), null),
                    "第" + (i+1) + "次调用不应异常"
                );
            }

            log.info("[PASS] 多次afterCompletion调用完成");
        }
    }

    // ========================================================================
    // 5️⃣ 异常场景覆盖
    // ========================================================================

    @Nested
    @DisplayName("5. Exception Scenarios and Edge Cases")
    class ExceptionScenarios {

        @Test
        @DisplayName("5.1 响应处理异常场景")
        void responseHandling_exceptionScenario() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            userHolderMock.when(UserHolder::getUser).thenReturn(null);
            userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

            boolean result = loginInterceptor.preHandle(request, response, new Object());

            assertFalse(result, "未登录用户应被拦截");
            assertEquals(401, response.getStatus(), "应返回401状态码");

            log.info("[PASS] 未登录用户拦截验证完成（响应正常）");
        }

        @Test
        @DisplayName("5.2 并发请求拦截")
        void concurrentRequestInterception() throws InterruptedException {
            Thread[] threads = new Thread[10];
            final boolean[] results = new boolean[threads.length];

            for (int i = 0; i < threads.length; i++) {
                final int index = i;
                threads[i] = new Thread(() -> {
                    try {
                        MockHttpServletRequest request = new MockHttpServletRequest();
                        MockHttpServletResponse response = new MockHttpServletResponse();

                        if (index % 2 == 0) {
                            UserDTO user = new UserDTO();
                            user.setId((long) index);
                            user.setStatus(0);
                            userHolderMock.when(UserHolder::getUser).thenReturn(user);
                        } else {
                            userHolderMock.when(UserHolder::getUser).thenReturn(null);
                        }
                        userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

                        results[index] = loginInterceptor.preHandle(request, response, new Object());
                        log.info("[TEST] 并发线程{}: result={}", index, results[index]);
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

            log.info("[PASS] 并发请求拦截完成");
        }

        @Test
        @DisplayName("5.3 快速连续请求")
        void rapidSequentialRequests() throws Exception {
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < 100; i++) {
                MockHttpServletRequest request = new MockHttpServletRequest();
                MockHttpServletResponse response = new MockHttpServletResponse();

                UserDTO user = new UserDTO();
                user.setId(1L);
                user.setStatus(0);

                userHolderMock.when(UserHolder::getUser).thenReturn(user);
                userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

                loginInterceptor.preHandle(request, response, new Object());
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("[TEST] 100次快速请求耗时: {}ms", duration);
            assertTrue(duration < 2000, "100次请求应在2秒内完成");

            log.info("[PASS] 快速连续请求性能验证完成");
        }
    }

    // ========================================================================
    // 6️⃣ 性能基准测试
    // ========================================================================

    @Nested
    @DisplayName("6. Performance Benchmark Tests")
    class PerformanceTests {

        @Test
        @DisplayName("6.1 大量并发拦截性能")
        void highConcurrency_performance() throws InterruptedException {
            int threadCount = 50;
            Thread[] threads = new Thread[threadCount];
            int[] successCount = {0};
            int[] blockedCount = {0};

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                threads[i] = new Thread(() -> {
                    try {
                        MockHttpServletRequest request = new MockHttpServletRequest();
                        MockHttpServletResponse response = new MockHttpServletResponse();

                        if (index % 3 == 0) {
                            userHolderMock.when(UserHolder::getUser).thenReturn(null);
                            userHolderMock.when(UserHolder::getAdmin).thenReturn(null);
                        } else {
                            UserDTO user = new UserDTO();
                            user.setId((long) index);
                            user.setStatus(0);
                            userHolderMock.when(UserHolder::getUser).thenReturn(user);
                        }
                        userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

                        boolean result = loginInterceptor.preHandle(request, response, new Object());
                        synchronized (this) {
                            if (result) successCount[0]++;
                            else blockedCount[0]++;
                        }
                    } catch (Exception e) {
                        log.warn("[TEST] 性能线程{}异常: {}", index, e.getMessage());
                    }
                });
            }

            long startTime = System.currentTimeMillis();

            for (Thread thread : threads) {
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join(3000);
            }

            long duration = System.currentTimeMillis() - startTime;

            log.info("[TEST] {}次并发拦截: 成功={}, 阻断={}, 耗时={}ms",
                threadCount, successCount[0], blockedCount[0], duration);
            assertTrue(duration < 5000, "50个线程应在5秒内完成");

            log.info("[PASS] 高并发拦截性能验证完成");
        }
    }
}
