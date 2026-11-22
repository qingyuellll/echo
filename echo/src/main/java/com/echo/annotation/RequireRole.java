package com.echo.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限校验注解
 * <p>
 * 使用场景：
 * - 标注需要特定角色才能访问的接口
 * - 自动进行权限校验，未通过时返回403
 * <p>
 * 示例：
 * <pre>
 * &#64;RequireRole(AdminRoleLevel.SUPER_ADMIN)
 * &#64;PostMapping("/show")
 * public Result&lt;?&gt; createShow(&#64;RequestBody Show show) {
 *     // 无需手动校验权限，AOP自动处理
 * }
 * </pre>
 *
 * @author tulip
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /**
     * 需要的角色等级
     * <p>
     * 可选值：
     * - SUPER_ADMIN: 超级管理员（全权限）
     * - ADMIN: 普通管理员（部分权限）
     *
     * @return 角色等级枚举
     */
    com.echo.enums.AdminRoleLevel value();
}
