package com.echo.aspect;

import com.echo.annotation.RequireRole;
import com.echo.constants.AppMessages;
import com.echo.dto.AdminDTO;
import com.echo.enums.AdminRoleLevel;
import com.echo.exception.PermissionDeniedException;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 权限校验AOP切面
 * <p>
 * 功能：
 * - 拦截标注了 @RequireRole 的方法
 * - 校验当前管理员角色是否满足要求
 * - 角色不足时抛 PermissionDeniedException，由 WebExceptionAdvice 统一返回 403
 * <p>
 * 注解取值方式：从连接点的方法签名反射读取注解实例。
 * 该方式与 Spring AOP 的代理实现解耦，注解实例直接来自目标方法本身。
 *
 * @author tulip
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PermissionCheckAspect {

    /**
     * 权限校验环绕通知
     * <p>
     * 处理流程：
     * 1. 读取目标方法上的 @RequireRole 注解
     * 2. 从 UserHolder 获取当前登录管理员
     * 3. 比较角色等级是否满足要求
     * 4. 不满足时抛出 PermissionDeniedException
     *
     * @param joinPoint 连接点（目标方法）
     * @return 目标方法执行结果
     * @throws Throwable 目标方法异常或权限不足异常
     */
    @Around("@annotation(com.echo.annotation.RequireRole)")
    public Object checkPermission(ProceedingJoinPoint joinPoint) throws Throwable {

        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        RequireRole requireRole = method.getAnnotation(RequireRole.class);

        AdminRoleLevel requiredRole = requireRole.value();
        String methodName = method.getName();

        AdminDTO admin = UserHolder.getAdmin();

        if (admin == null) {
            log.warn("[权限校验] 用户未登录 - 方法:{}", methodName);
            throw new PermissionDeniedException(
                    AppMessages.PERMISSION_ADMIN_REQUIRED,
                    "ADMIN_NOT_LOGGED_IN"
            );
        }

        AdminRoleLevel currentRole = admin.getRoleLevel();

        if (!hasPermission(currentRole, requiredRole)) {
            log.warn("[权限校验] 权限不足 - 方法:{}, 当前角色:{}, 需要角色:{}, 管理员ID:{}, 名称:{}",
                    methodName, currentRole, requiredRole, admin.getId(), admin.getName());
            throw new PermissionDeniedException(
                    AppMessages.PERMISSION_SUPER_ADMIN_ONLY,
                    "INSUFFICIENT_ROLE_LEVEL"
            );
        }

        log.info("[权限校验] 通过 - 方法:{}, 管理员ID:{}, 名称:{}, 角色:{}",
                methodName, admin.getId(), admin.getName(), currentRole);

        return joinPoint.proceed();
    }

    /**
     * 判断当前角色是否满足目标角色要求
     * <p>
     * 角色等级规则：
     * - SUPER_ADMIN(0)：满足全部角色要求
     * - NORMAL_ADMIN(1)：满足 NORMAL_ADMIN 及同级要求
     *
     * @param currentRole  当前管理员角色
     * @param requiredRole 目标方法要求的角色
     * @return true=满足要求
     */
    private boolean hasPermission(AdminRoleLevel currentRole, AdminRoleLevel requiredRole) {
        if (currentRole == null || requiredRole == null) {
            return false;
        }
        return currentRole.getCode() <= requiredRole.getCode();
    }
}
