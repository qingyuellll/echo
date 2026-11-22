package com.echo.utils;

import com.echo.constants.AppMessages;
import com.echo.dto.AdminDTO;
import com.echo.dto.UserDTO;
import com.echo.enums.AdminStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录认证拦截器
 *
 * @author tulip
 */
public class LoginInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoginInterceptor.class);

    // ==================== 常量定义 ====================

    /** HTTP状态码：未授权（未登录） */
    private static final int HTTP_STATUS_UNAUTHORIZED = 401;

    /** HTTP状态码：禁止访问（已禁用） */
    private static final int HTTP_STATUS_FORBIDDEN = 403;

    /** 用户状态：已禁用（数据库中的值） */
    private static final int USER_STATUS_DISABLED = 1;

    /** 响应内容类型 */
    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    // ==================== 核心拦截方法 ====================

    /**
     * 请求预处理（核心认证逻辑）
     * <p>
     * 执行三层安全检查：
     * <ol>
     *   <li><b>第一层 - 身份验证</b>：检查 user 或 admin 是否存在</li>
     *   <li><b>第二层 - 用户状态检查</b>：验证用户账户是否被禁用</li>
     *   <li><b>第三层 - 管理员状态检查</b>：验证管理员账户是否被禁用</li>
     * </ol>
     * <p>
     * 返回值含义：
     * <ul>
     *   <li>{@code true} - 验证通过，继续执行后续拦截器和 Controller</li>
     *   <li>{@code false} - 验证失败，中断请求链并返回错误响应</li>
     * </ul>
     *
     * @param request  HTTP请求对象
     * @param response HTTP响应对象（用于写入错误信息）
     * @param handler 处理器对象（通常是Controller方法）
     * @return true=验证通过并放行，false=验证失败并中断请求
     */
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {

        logRequestInfo(request);

        UserDTO user = UserHolder.getUser();
        AdminDTO admin = UserHolder.getAdmin();

        logUserStatus(user, admin);

        if (isNotLoggedIn(user, admin)) {
            return handleUnauthorized(request, response);
        }

        if (isUserDisabled(user)) {
            return handleUserDisabled(response, user);
        }

        if (isAdminDisabled(admin)) {
            return handleAdminDisabled(response, admin);
        }

        logAccessGranted(request);
        return true;
    }

    /**
     * 请求完成后清理资源
     * <p>
     * <b>⚠️ 重要：必须清理 ThreadLocal！</b>
     * <p>
     * 清理原因：
     * <ul>
     *   <li>Tomcat 使用线程池，线程会被复用</li>
     *   <li>如果不清理，下一个请求可能读到上一个请求的用户信息</li>
     *   <li>导致数据错乱或安全漏洞</li>
     * </ul>
     * <p>
     * 执行时机：无论请求成功还是异常，都会调用（类似于 finally 块）
     *
     * @param request  HTTP请求对象
     * @param response HTTP响应对象
     * @param handler 处理器对象
     * @param ex      请求处理过程中抛出的异常（如果有）
     */
    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                @Nullable Exception ex) {
        UserHolder.clearAll();
    }

    // ==================== 私有方法：日志记录 ====================

    /**
     * 记录请求基本信息
     *
     * @param request HTTP请求对象
     */
    private void logRequestInfo(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String method = request.getMethod();
        log.debug("[LoginInterceptor] 收到请求: {} {}", method, requestUri);
    }

    /**
     * 记录当前用户状态（脱敏处理，只记录ID）
     *
     * @param user  用户DTO（可为null）
     * @param admin 管理员DTO（可为null）
     */
    private void logUserStatus(UserDTO user, AdminDTO admin) {
        String userStatus = user != null ? "userId=" + user.getId() : "null";
        String adminStatus = admin != null ? "adminId=" + admin.getId() : "null";

        log.debug("[LoginInterceptor] 用户状态: user={}, admin={}", userStatus, adminStatus);
    }

    /**
     * 记录访问放行日志
     *
     * @param request HTTP请求对象
     */
    private void logAccessGranted(HttpServletRequest request) {
        log.debug("[LoginInterceptor] 验证通过: {} {}",
                request.getMethod(), request.getRequestURI());
    }

    // ==================== 私有方法：状态判断 ====================

    /**
     * 判断用户是否未登录
     *
     * @param user  用户DTO
     * @param admin 管理员DTO
     * @return true=两者都为null（未登录），false=至少一个有值
     */
    private boolean isNotLoggedIn(UserDTO user, AdminDTO admin) {
        return user == null && admin == null;
    }

    /**
     * 判断用户账户是否已被禁用
     *
     * @param user 用户DTO（不为null）
     * @return true=已禁用，false=正常或状态未知
     */
    private boolean isUserDisabled(UserDTO user) {
        return user != null
                && user.getStatus() != null
                && user.getStatus() == USER_STATUS_DISABLED;
    }

    /**
     * 判断管理员账户是否已被禁用
     *
     * @param admin 管理员DTO（不为null）
     * @return true=已禁用，false=正常或状态未知
     */
    private boolean isAdminDisabled(AdminDTO admin) {
        return admin != null
                && admin.getStatus() != null
                && admin.getStatus() == AdminStatus.DISABLED;
    }

    // ==================== 私有方法：错误响应处理 ====================

    /**
     * 处理未登录情况（返回401）
     *
     * @param request  HTTP请求对象（用于日志记录）
     * @param response HTTP响应对象
     * @return false（始终中断请求）
     */
    private boolean handleUnauthorized(HttpServletRequest request, HttpServletResponse response) {
        log.warn("[LoginInterceptor] 未授权访问 - 返回401: {} {}",
                request.getMethod(), request.getRequestURI());

        writeErrorResponse(response,
                HTTP_STATUS_UNAUTHORIZED,
                "未登录或登录已过期，请重新登录");

        return false;
    }

    /**
     * 处理用户账户禁用情况（返回403）
     *
     * @param response HTTP响应对象
     * @param user    被禁用的用户（用于日志记录）
     * @return false（始终中断请求）
     */
    private boolean handleUserDisabled(HttpServletResponse response, UserDTO user) {
        log.warn("[LoginInterceptor] 账户已禁用 - userId={}", user.getId());

        writeErrorResponse(response,
                HTTP_STATUS_FORBIDDEN,
                AppMessages.USER_ACCOUNT_DISABLED);

        return false;
    }

    /**
     * 处理管理员账户禁用情况（返回403）
     *
     * @param response HTTP响应对象
     * @param admin   被禁用的管理员（用于日志记录）
     * @return false（始终中断请求）
     */
    private boolean handleAdminDisabled(HttpServletResponse response, AdminDTO admin) {
        log.warn("[LoginInterceptor] 管理员账户已禁用 - adminId={}", admin.getId());

        writeErrorResponse(response,
                HTTP_STATUS_FORBIDDEN,
                AppMessages.USER_ACCOUNT_DISABLED);

        return false;
    }

    /**
     * 写入错误响应（统一格式）
     * <p>
     * 响应格式：
     * <pre>
     * {
     *   "success": false,
     *   "code": {statusCode},
     *   "errorMsg": "{message}"
     * }
     * </pre>
     *
     * @param response   HTTP响应对象
     * @param statusCode HTTP状态码（401/403等）
     * @param message    错误消息
     */
    private void writeErrorResponse(HttpServletResponse response, int statusCode, String message) {
        response.setStatus(statusCode);
        response.setContentType(CONTENT_TYPE_JSON);

        try {
            String json = String.format(
                    "{\"success\":false,\"code\":%d,\"errorMsg\":\"%s\"}",
                    statusCode,
                    message
            );
            response.getWriter().write(json);
        } catch (Exception e) {
            log.error("[LoginInterceptor] 写入响应失败", e);
        }
    }
}
