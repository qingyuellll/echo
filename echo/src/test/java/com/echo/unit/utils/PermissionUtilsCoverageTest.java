package com.echo.unit.utils;

import com.echo.dto.AdminDTO;
import com.echo.enums.AdminRoleLevel;
import com.echo.utils.PermissionUtils;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PermissionUtils - Coverage Enhancement Test")
public class PermissionUtilsCoverageTest {

    private MockedStatic<UserHolder> userHolderMock;

    @BeforeEach
    void setUp() {
        userHolderMock = mockStatic(UserHolder.class);
    }

    @AfterEach
    void tearDown() {
        if (userHolderMock != null) {
            userHolderMock.close();
        }
    }

    // ========================================================================
    //  超级管理员权限检查
    // ========================================================================

    @Nested
    @DisplayName("1. Super Admin Permission Checks")
    class SuperAdminTests {

        @Test
        @DisplayName("1.1 超级管理员 (roleLevel=0) - 返回true")
        void superAdmin_returnsTrue() {
            AdminDTO superAdmin = new AdminDTO();
            superAdmin.setId(1L);
            superAdmin.setName("超级管理员");
            superAdmin.setRoleLevel(AdminRoleLevel.SUPER_ADMIN); // 超级管理员

            userHolderMock.when(UserHolder::getAdmin).thenReturn(superAdmin);

            boolean isSuperAdmin = PermissionUtils.isSuperAdmin();

            assertTrue(isSuperAdmin, "超级管理员应返回true");
            log.info("[PASS] 超级管理员权限验证完成");
        }

        @Test
        @DisplayName("1.2 普通管理员 (roleLevel=1) - 返回false")
        void normalAdmin_returnsFalse() {
            AdminDTO normalAdmin = new AdminDTO();
            normalAdmin.setId(2L);
            normalAdmin.setName("普通管理员");
            normalAdmin.setRoleLevel(AdminRoleLevel.NORMAL_ADMIN); // 普通管理员

            userHolderMock.when(UserHolder::getAdmin).thenReturn(normalAdmin);

            boolean isSuperAdmin = PermissionUtils.isSuperAdmin();

            assertFalse(isSuperAdmin, "普通管理员应返回false");
            log.info("[PASS] 普通管理员权限验证完成");
        }

        @Test
        @DisplayName("1.3 未登录 - 返回false")
        void notLoggedIn_returnsFalse() {
            userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

            boolean isSuperAdmin = PermissionUtils.isSuperAdmin();

            assertFalse(isSuperAdmin, "未登录应返回false");
            log.info("[PASS] 未登录状态验证完成");
        }

        @Test
        @DisplayName("1.4 requireSuperAdmin - 超级管理员通过")
        void requireSuperAdmin_superAdminPasses() {
            AdminDTO superAdmin = new AdminDTO();
            superAdmin.setId(1L);
            superAdmin.setRoleLevel(AdminRoleLevel.SUPER_ADMIN);

            userHolderMock.when(UserHolder::getAdmin).thenReturn(superAdmin);

            assertDoesNotThrow(() -> PermissionUtils.requireSuperAdmin(),
                "超级管理员应通过验证");

            log.info("[PASS] requireSuperAdmin通过验证");
        }

        @Test
        @DisplayName("1.5 requireSuperAdmin - 非超级管理员抛异常")
        void requireSuperAdmin_nonSuperAdminThrows() {
            AdminDTO normalAdmin = new AdminDTO();
            normalAdmin.setId(2L);
            normalAdmin.setRoleLevel(AdminRoleLevel.NORMAL_ADMIN);

            userHolderMock.when(UserHolder::getAdmin).thenReturn(normalAdmin);

            assertThrows(RuntimeException.class, () -> PermissionUtils.requireSuperAdmin(),
                "非超级管理员应抛出异常");

            log.info("[PASS] 非超级管理员require异常验证完成");
        }
    }

    // ========================================================================
    //  管理员登录状态检查
    // ========================================================================

    @Nested
    @DisplayName("2. Admin Login Status Checks")
    class AdminLoginStatusTests {

        @Test
        @DisplayName("2.1 已登录管理员 - 返回true")
        void adminLoggedIn_returnsTrue() {
            AdminDTO admin = new AdminDTO();
            admin.setId(1L);
            admin.setName("测试管理员");

            userHolderMock.when(UserHolder::getAdmin).thenReturn(admin);

            boolean isLoggedIn = PermissionUtils.isAdminLoggedIn();

            assertTrue(isLoggedIn, "已登录应返回true");
            log.info("[PASS] 管理员登录状态验证完成");
        }

        @Test
        @DisplayName("2.2 未登录 - 返回false")
        void adminNotLoggedIn_returnsFalse() {
            userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

            boolean isLoggedIn = PermissionUtils.isAdminLoggedIn();

            assertFalse(isLoggedIn, "未登录应返回false");
            log.info("[PASS] 管理员未登录状态验证完成");
        }

        @Test
        @DisplayName("2.3 requireAdminLogin - 已登录通过")
        void requireAdminLogin_loggedInPasses() {
            AdminDTO admin = new AdminDTO();
            admin.setId(1L);

            userHolderMock.when(UserHolder::getAdmin).thenReturn(admin);

            assertDoesNotThrow(() -> PermissionUtils.requireAdminLogin(),
                "已登录应通过验证");

            log.info("[PASS] requireAdminLogin通过验证");
        }

        @Test
        @DisplayName("2.4 requireAdminLogin - 未登录抛异常")
        void requireAdminLogin_notLoggedInThrows() {
            userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

            assertThrows(RuntimeException.class, () -> PermissionUtils.requireAdminLogin(),
                "未登录应抛出异常");

            log.info("[PASS] 未登录require异常验证完成");
        }
    }

    // ========================================================================
    //  管理员信息获取
    // ========================================================================

    @Nested
    @DisplayName("3. Admin Information Retrieval")
    class AdminInfoTests {

        @Test
        @DisplayName("3.1 获取管理员ID - 已登录")
        void getCurrentAdminId_loggedIn() {
            Long expectedId = 42L;
            AdminDTO admin = new AdminDTO();
            admin.setId(expectedId);
            admin.setName("测试管理员");

            userHolderMock.when(UserHolder::getAdmin).thenReturn(admin);

            Long actualId = PermissionUtils.getCurrentAdminId();

            assertEquals(expectedId, actualId, "应返回正确的管理员ID");
            log.info("[PASS] 获取管理员ID验证完成: {}", actualId);
        }

        @Test
        @DisplayName("3.2 获取管理员ID - 未登录返回null")
        void getCurrentAdminId_notLoggedIn() {
            userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

            Long adminId = PermissionUtils.getCurrentAdminId();

            assertNull(adminId, "未登录应返回null");
            log.info("[PASS] 未登录获取ID返回null验证完成");
        }

        @Test
        @DisplayName("3.3 获取管理员名称 - 已登录")
        void getCurrentAdminName_loggedIn() {
            String expectedName = "张三";
            AdminDTO admin = new AdminDTO();
            admin.setId(1L);
            admin.setName(expectedName);

            userHolderMock.when(UserHolder::getAdmin).thenReturn(admin);

            String actualName = PermissionUtils.getCurrentAdminName();

            assertEquals(expectedName, actualName, "应返回正确的管理员名称");
            log.info("[PASS] 获取管理员名称验证完成: {}", actualName);
        }

        @Test
        @DisplayName("3.4 获取管理员名称 - 未登录返回默认值")
        void getCurrentAdminName_notLoggedIn() {
            userHolderMock.when(UserHolder::getAdmin).thenReturn(null);

            String adminName = PermissionUtils.getCurrentAdminName();

            assertEquals("未知管理员", adminName, "未登录应返回默认名称");
            log.info("[PASS] 未登录获取名称返回默认值验证完成");
        }
    }

    // ========================================================================
    //  边界情况和异常场景
    // ========================================================================

    @Nested
    @DisplayName("4. Boundary Cases and Exception Scenarios")
    class BoundaryAndExceptionTests {

        @Test
        @DisplayName("4.1 不同roleLevel值测试")
        void differentRoleLevels() {
            AdminRoleLevel[] roleLevels = {AdminRoleLevel.SUPER_ADMIN, AdminRoleLevel.NORMAL_ADMIN, null};

            for (AdminRoleLevel roleLevel : roleLevels) {
                AdminDTO admin = new AdminDTO();
                admin.setId(1L);
                admin.setRoleLevel(roleLevel);

                userHolderMock.when(UserHolder::getAdmin).thenReturn(admin);

                boolean isSuper = PermissionUtils.isSuperAdmin();

                if (roleLevel == AdminRoleLevel.SUPER_ADMIN) {
                    assertTrue(isSuper, "SUPER_ADMIN应为超级管理员");
                } else {
                    assertFalse(isSuper, "roleLevel=" + roleLevel + "不应是超级管理员");
                }

                log.info("[TEST] roleLevel={} → isSuper={}", roleLevel, isSuper);
            }

            log.info("[PASS] 不同roleLevel值测试完成");
        }

        @Test
        @DisplayName("4.2 并发权限检查")
        void concurrentPermissionChecks() throws InterruptedException {
            Thread[] threads = new Thread[10];

            for (int i = 0; i < threads.length; i++) {
                final int index = i;
                final boolean shouldBeSuper = (index % 3 == 0); // 每3个线程中1个是超级管理员

                threads[i] = new Thread(() -> {
                    try {
                        if (shouldBeSuper) {
                            AdminDTO superAdmin = new AdminDTO();
                            superAdmin.setRoleLevel(AdminRoleLevel.SUPER_ADMIN);
                            userHolderMock.when(UserHolder::getAdmin).thenReturn(superAdmin);
                        } else {
                            AdminDTO normalAdmin = new AdminDTO();
                            normalAdmin.setRoleLevel(AdminRoleLevel.NORMAL_ADMIN);
                            userHolderMock.when(UserHolder::getAdmin).thenReturn(normalAdmin);
                        }

                        boolean result = PermissionUtils.isSuperAdmin();
                        assertEquals(shouldBeSuper, result,
                            "线程" + index + "的权限检查结果不匹配");

                        log.info("[TEST] 线程{}: super={}, result={}", index, shouldBeSuper, result);
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

            log.info("[PASS] 并发权限检查完成");
        }

        @Test
        @DisplayName("4.3 快速连续调用性能")
        void rapidSequentialCalls_performance() {
            long startTime = System.currentTimeMillis();

            AdminDTO admin = new AdminDTO();
            admin.setId(1L);
            admin.setRoleLevel(AdminRoleLevel.SUPER_ADMIN);
            userHolderMock.when(UserHolder::getAdmin).thenReturn(admin);

            for (int i = 0; i < 10000; i++) {
                PermissionUtils.isSuperAdmin();
                PermissionUtils.isAdminLoggedIn();
                PermissionUtils.getCurrentAdminId();
                PermissionUtils.getCurrentAdminName();
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("[TEST] 40000次快速调用耗时: {}ms", duration);
            assertTrue(duration < 3000, "40000次调用应在3秒内完成");

            log.info("[PASS] 快速连续调用性能验证完成");
        }

        @Test
        @DisplayName("4.4 异常情况：UserHolder异常")
        void userHolderException_handling() {
            userHolderMock.when(UserHolder::getAdmin)
                .thenThrow(new RuntimeException("UserHolder异常"));

            assertDoesNotThrow(() -> {
                boolean isSuper = PermissionUtils.isSuperAdmin();
                assertFalse(isSuper, "异常时应返回false");
            }, "UserHolder异常不应导致崩溃");

            assertDoesNotThrow(() -> {
                boolean isLoggedIn = PermissionUtils.isAdminLoggedIn();
                assertFalse(isLoggedIn, "异常时应返回false");
            });

            log.info("[PASS] UserHolder异常降级处理正常");
        }
    }

    // ========================================================================
    //  实例化防护测试
    // ========================================================================

    @Nested
    @DisplayName("5. Instantiation Protection Tests")
    class InstantiationTests {

        @Test
        @DisplayName("5.1 私有构造函数 - 不允许实例化")
        void privateConstructor_preventsInstantiation() throws Exception {
            java.lang.reflect.Constructor<PermissionUtils> constructor =
                PermissionUtils.class.getDeclaredConstructor();

            constructor.setAccessible(true);

            assertThrows(UnsupportedOperationException.class, () -> constructor.newInstance(),
                "工具类不应允许实例化");

            log.info("[PASS] 实例化防护验证完成");
        }

        @Test
        @DisplayName("5.2 所有方法都是静态方法")
        void allMethodsAreStatic() {
            java.lang.reflect.Method[] methods = PermissionUtils.class.getDeclaredMethods();

            for (java.lang.reflect.Method method : methods) {
                assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()),
                    method.getName() + " 应该是静态方法");
            }

            log.info("[PASS] 所有方法都是静态方法验证完成");
        }
    }

    // ========================================================================
    //  综合场景测试
    // ========================================================================

    @Nested
    @DisplayName("6. Comprehensive Scenario Tests")
    class ComprehensiveTests {

        @Test
        @DisplayName("6.1 完整的权限检查流程")
        void completePermissionCheckFlow() {
            AdminDTO superAdmin = new AdminDTO();
            superAdmin.setId(1L);
            superAdmin.setName("超级管理员张三");
            superAdmin.setRoleLevel(AdminRoleLevel.SUPER_ADMIN);

            userHolderMock.when(UserHolder::getAdmin).thenReturn(superAdmin);

            assertTrue(PermissionUtils.isAdminLoggedIn(), "应已登录");
            assertTrue(PermissionUtils.isSuperAdmin(), "应是超级管理员");
            assertEquals(1L, PermissionUtils.getCurrentAdminId(), "ID应为1");
            assertEquals("超级管理员张三", PermissionUtils.getCurrentAdminName(), "名称应匹配");

            assertDoesNotThrow(() -> PermissionUtils.requireSuperAdmin());
            assertDoesNotThrow(() -> PermissionUtils.requireAdminLogin());

            log.info("[PASS] 完整权限检查流程验证完成");
        }

        @Test
        @DisplayName("6.2 权限不足完整流程")
        void insufficientPermissionFlow() {
            AdminDTO normalAdmin = new AdminDTO();
            normalAdmin.setId(99L);
            normalAdmin.setName("普通管理员李四");
            normalAdmin.setRoleLevel(AdminRoleLevel.NORMAL_ADMIN);

            userHolderMock.when(UserHolder::getAdmin).thenReturn(normalAdmin);

            assertTrue(PermissionUtils.isAdminLoggedIn(), "应已登录");
            assertFalse(PermissionUtils.isSuperAdmin(), "不应是超级管理员");
            assertEquals(99L, PermissionUtils.getCurrentAdminId(), "ID应为99");
            assertEquals("普通管理员李四", PermissionUtils.getCurrentAdminName(), "名称应匹配");

            assertThrows(RuntimeException.class, () -> PermissionUtils.requireSuperAdmin(),
                "普通管理员不能执行超级管理员操作");
            assertDoesNotThrow(() -> PermissionUtils.requireAdminLogin(),
                "但可以执行普通管理员操作");

            log.info("[PASS] 权限不足流程验证完成");
        }

        @Test
        @DisplayName("6.3 多次切换用户角色")
        void switchUserRolesMultipleTimes() {
            AdminDTO[] admins = {
                createAdmin(1L, "超级管理员A", AdminRoleLevel.SUPER_ADMIN),
                createAdmin(2L, "普通管理员B", AdminRoleLevel.NORMAL_ADMIN),
                createAdmin(3L, "普通管理员C", AdminRoleLevel.NORMAL_ADMIN),
                createAdmin(4L, "超级管理员D", AdminRoleLevel.SUPER_ADMIN),
                null, // 模拟登出
                createAdmin(5L, "新管理员E", AdminRoleLevel.NORMAL_ADMIN)
            };

            boolean[] expectedIsSuper = {true, false, false, true, false, false};

            for (int i = 0; i < admins.length; i++) {
                userHolderMock.when(UserHolder::getAdmin).thenReturn(admins[i]);

                boolean actualIsSuper = PermissionUtils.isSuperAdmin();
                assertEquals(expectedIsSuper[i], actualIsSuper,
                    "第" + (i+1) + "次切换结果不匹配");

                log.info("[TEST] 切换{}: name={}, roleLevel={}, isSuper={}",
                    i+1,
                    admins[i] != null ? admins[i].getName() : "null",
                    admins[i] != null ? admins[i].getRoleLevel() : "N/A",
                    actualIsSuper);
            }

            log.info("[PASS] 多次切换用户角色验证完成");
        }
    }

    private AdminDTO createAdmin(Long id, String name, AdminRoleLevel roleLevel) {
        AdminDTO admin = new AdminDTO();
        admin.setId(id);
        admin.setName(name);
        admin.setRoleLevel(roleLevel);
        return admin;
    }
}
