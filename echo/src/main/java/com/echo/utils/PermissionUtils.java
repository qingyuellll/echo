package com.echo.utils;

import com.echo.constants.AppMessages;
import com.echo.dto.AdminDTO;
import com.echo.enums.AdminRoleLevel;
import com.echo.exception.PermissionDeniedException;
import lombok.extern.slf4j.Slf4j;

/**
 * 权限校验工具类
 *
 * @author tulip
 */
@Slf4j
public class PermissionUtils {

    private PermissionUtils() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 检查当前用户是否为超级管理员
     *
     * @return true=超级管理员, false=非超级管理员或未登录
     */
    public static boolean isSuperAdmin() {
        AdminDTO admin = UserHolder.getAdmin();
        if (admin == null) {
            log.warn("[权限校验] 用户未登录或无管理员信息");
            return false;
        }

        // [OK] 使用枚举比较，类型安全且语义清晰
        boolean isSuper = admin.getRoleLevel() != null &&
                admin.getRoleLevel() == AdminRoleLevel.SUPER_ADMIN;

        if (isSuper) {
            log.debug("[权限校验] 超级管理员权限验证通过 - adminId:{}, name:{}",
                admin.getId(), admin.getName());
        } else {
            log.warn("[权限校验] 非超级管理员尝试执行特权操作 - adminId:{}, roleLevel:{}",
                admin.getId(), admin.getRoleLevel());
        }

        return isSuper;
    }

    /**
     * 校验超级管理员权限（未通过时抛出权限拒绝异常）
     *
     * [NEW] NEW-25改进：抛出 PermissionDeniedException（HTTP 403）
     * [OLD] 旧版：抛出 RuntimeException（被当成500处理）
     *
     * @throws PermissionDeniedException 非超级管理员时抛出
     */
    public static void requireSuperAdmin() {
        if (!isSuperAdmin()) {
            log.warn("[权限校验] 超级管理员权限验证失败 - 抛出PermissionDeniedException");
            throw new PermissionDeniedException(
                    AppMessages.PERMISSION_SUPER_ADMIN_ONLY,
                    "REQUIRE_SUPER_ADMIN_FAILED"
            );
        }
    }

    /**
     * 检查当前用户是否已登录为管理员
     *
     * @return true=已登录, false=未登录
     */
    public static boolean isAdminLoggedIn() {
        return UserHolder.getAdmin() != null;
    }

    /**
     * 校验管理员登录状态（未登录时抛出权限拒绝异常）
     *
     * [NEW] NEW-25改进：抛出 PermissionDeniedException（HTTP 403）
     * [OLD] 旧版：抛出 RuntimeException（被当成500处理）
     *
     * @throws PermissionDeniedException 未登录时抛出
     */
    public static void requireAdminLogin() {
        if (!isAdminLoggedIn()) {
            log.warn("[权限校验] 管理员登录状态验证失败 - 抛出PermissionDeniedException");
            throw new PermissionDeniedException(
                    AppMessages.PERMISSION_ADMIN_REQUIRED,
                    "REQUIRE_ADMIN_LOGIN_FAILED"
            );
        }
    }

    /**
     * 获取当前管理员ID
     *
     * @return 管理员ID，未登录返回null
     */
    public static Long getCurrentAdminId() {
        AdminDTO admin = UserHolder.getAdmin();
        return admin != null ? admin.getId() : null;
    }

    /**
     * 获取当前管理员名称
     *
     * @return 管理员名称，未登录返回"未知管理员"
     */
    public static String getCurrentAdminName() {
        AdminDTO admin = UserHolder.getAdmin();
        return admin != null ? admin.getName() : "未知管理员";
    }
}
