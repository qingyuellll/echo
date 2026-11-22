package com.echo.utils;

import com.echo.constants.AppMessages;
import com.echo.dto.AdminDTO;
import com.echo.dto.Result;
import com.echo.enums.AdminStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 管理员角色拦截器(管理端API权限控制)
 *
 * @author tulip
 */
public class AdminRoleInterceptor implements HandlerInterceptor {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(AdminRoleInterceptor.class);

    /** JSON序列化器（线程安全，全局共享） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** HTTP状态码：403 Forbidden */
    private static final int HTTP_STATUS_FORBIDDEN = 403;

    /** 响应内容类型：JSON */
    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    /** 响应字符编码：UTF-8 */
    private static final String CHARACTER_ENCODING_UTF8 = "UTF-8";

    /**
     * 拦截器前置处理(校验管理员身份)
     *
     * @param request  HTTP请求对象
     * @param response HTTP响应对象
     * @param handler  处理器对象
     * @return true=校验通过, false=校验失败
     */
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                            @NonNull HttpServletResponse response,
                            @NonNull Object handler) {
        RequestInfo requestInfo = extractRequestInfo(request);
        logRequestReceived(requestInfo);

        AdminDTO admin = UserHolder.getAdmin();

        if (isAnonymousAccess(admin)) {
            return handleAnonymousAccess(response, requestInfo);
        }

        if (isAccountDisabled(admin)) {
            return handleDisabledAccount(response, admin, requestInfo);
        }

        logAccessGranted(admin, requestInfo);
        return true;
    }

    /**
     * 提取请求基本信息
     *
     * @param request HTTP请求对象
     * @return 请求信息封装对象
     */
    private RequestInfo extractRequestInfo(HttpServletRequest request) {
        RequestInfo info = new RequestInfo();
        info.uri = request.getRequestURI();
        info.method = request.getMethod();
        return info;
    }

    /**
     * 记录收到请求的调试日志
     *
     * @param info 请求信息
     */
    private void logRequestReceived(RequestInfo info) {
        log.debug("[AdminRoleInterceptor] 收到管理端请求: {} {}", info.method, info.uri);
    }

    /**
     * 判断是否为匿名访问（无管理员身份）
     *
     * @param admin 管理员信息（可能为null）
     * @return true=匿名访问（admin为null）, false=有身份信息
     */
    private boolean isAnonymousAccess(AdminDTO admin) {
        return admin == null;
    }

    /**
     * 处理匿名访问（返回403）
     *
     * @param response   HTTP响应对象
     * @param requestInfo 请求信息
     * @return false（阻止请求继续）
     */
    private boolean handleAnonymousAccess(HttpServletResponse response, RequestInfo requestInfo) {
        log.warn("[AdminRoleInterceptor] [DENY] 无管理员身份拒绝访问 - URI:{}, Method:{}, 原因:请求未携带有效admin token或使用user token误闯",
                requestInfo.method, requestInfo.uri);

        return writeForbiddenResponse(response, "需要管理员权限", "ADMIN_ROLE_REQUIRED");
    }

    /**
     * 判断管理员账户是否已禁用
     *
     * @param admin 管理员信息
     * @return true=账户已禁用, false=账户正常或状态未知
     */
    private boolean isAccountDisabled(AdminDTO admin) {
        return admin.getStatus() != null && admin.getStatus() == AdminStatus.DISABLED;
    }

    /**
     * 处理已禁用账户访问（返回403）
     *
     * @param response   HTTP响应对象
     * @param admin      管理员信息
     * @param requestInfo 请求信息
     * @return false（阻止请求继续）
     */
    private boolean handleDisabledAccount(HttpServletResponse response, AdminDTO admin, RequestInfo requestInfo) {
        log.warn("[AdminRoleInterceptor] [DENY] 管理员账户已禁用 - adminId:{}, URI:{}",
                admin.getId(), requestInfo.uri);

        return writeForbiddenResponse(response, AppMessages.USER_ACCOUNT_DISABLED, "ADMIN_ACCOUNT_DISABLED");
    }

    /**
     * 记录访问通过的日志
     *
     * @param admin  管理员信息
     * @param info   请求信息
     */
    private void logAccessGranted(AdminDTO admin, RequestInfo info) {
        log.debug("[AdminRoleInterceptor] [OK] 管理员权限验证通过 - adminId:{}, name:{}, roleLevel:{}, URI:{}",
                admin.getId(), admin.getName(), admin.getRoleLevel(), info.uri);
    }

    /**
     * 写入HTTP 403 Forbidden响应（统一Result格式）
     * <p>
     * 响应格式：
     * <pre>
     * HTTP/1.1 403 Forbidden
     * Content-Type: application/json;charset=UTF-8
     *
     * {"code": 403, "message": "错误信息"}
     * </pre>
     * <p>
     * 降级策略：如果Jackson序列化失败，尝试手动拼接简化JSON
     *
     * @param response  HTTP响应对象
     * @param message   用户可见的错误消息
     * @param errorCode 内部错误码（用于日志追踪和前端逻辑判断）
     * @return false（阻止请求继续执行）
     */
    private boolean writeForbiddenResponse(HttpServletResponse response, String message, String errorCode) {
        try {
            setResponseHeaders(response);
            String jsonResponse = buildJsonResponse(message);
            writeResponseBody(response, jsonResponse);
            logResponseWritten(errorCode, message);
        } catch (Exception e) {
            handleWriteFailure(response, message, e);
        }

        return false;
    }

    /**
     * 设置响应头信息
     *
     * @param response HTTP响应对象
     */
    private void setResponseHeaders(HttpServletResponse response) {
        response.setStatus(HTTP_STATUS_FORBIDDEN);
        response.setContentType(CONTENT_TYPE_JSON);
        response.setCharacterEncoding(CHARACTER_ENCODING_UTF8);
    }

    /**
     * 构建JSON响应体
     *
     * @param message 错误消息
     * @return JSON字符串
     * @throws Exception 序列化异常
     */
    private String buildJsonResponse(String message) throws Exception {
        return objectMapper.writeValueAsString(Result.fail(message));
    }

    /**
     * 写入响应体
     *
     * @param response      HTTP响应对象
     * @param jsonResponse JSON字符串
     * @throws Exception IO异常
     */
    private void writeResponseBody(HttpServletResponse response, String jsonResponse) throws Exception {
        response.getWriter().write(jsonResponse);
    }

    /**
     * 记录响应写入成功的日志
     *
     * @param errorCode 错误码
     * @param message   错误消息
     */
    private void logResponseWritten(String errorCode, String message) {
        log.debug("[AdminRoleInterceptor] 已写入403响应 - code:{}, message:{}", errorCode, message);
    }

    /**
     * 处理响应写入失败的降级逻辑
     *
     * @param response HTTP响应对象
     * @param message  原始错误消息
     * @param e        原始异常
     */
    private void handleWriteFailure(HttpServletResponse response, String message, Exception e) {
        log.error("[AdminRoleInterceptor] 写入403响应失败 - error:{}", e.getMessage(), e);
        writeFallbackResponse(response, message);
    }

    /**
     * 写入降级响应（简化版JSON）
     * <p>
     * 当Jackson序列化失败时的兜底方案，确保客户端至少能收到有效的错误响应
     *
     * @param response HTTP响应对象
     * @param message  错误消息
     */
    private void writeFallbackResponse(HttpServletResponse response, String message) {
        try {
            response.setStatus(HTTP_STATUS_FORBIDDEN);
            response.getWriter().write("{\"error\":\"" + message + "\",\"code\":" + HTTP_STATUS_FORBIDDEN + "}");
        } catch (Exception ignored) {
        }
    }

    /**
     * 请求信息封装类（内部使用）
     * <p>
     * 用于在方法间传递请求的基本信息，避免重复从request对象提取
     */
    private static class RequestInfo {
        /** 请求URI */
        String uri;

        /** HTTP方法（GET/POST/PUT/DELETE等） */
        String method;
    }
}
