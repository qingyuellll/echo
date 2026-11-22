package com.echo.config;

import com.echo.dto.Result;
import com.echo.utils.CsrfTokenUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Nonnull;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * CSRF Token 校验拦截器
 * <p>
 * 自动对POST/PUT/DELETE请求进行CSRF验证，GET/HEAD/OPTIONS请求跳过。
 * <p>
 * 拦截规则：
 * - 跳过：GET, HEAD, OPTIONS（安全方法）
 * - 跳过：/api/admin/login, /api/admin/code（认证接口）
 * - 校验：所有其他POST/PUT/DELETE请求
 * <p>
 * 配置方式：
 * <pre>
 * @Configuration
 * public class WebConfig implements WebMvcConfigurer {
 *
 *     @Resource
 *     private CsrfInterceptor csrfInterceptor;
 *
 *     @Override
 *     public void addInterceptors(InterceptorRegistry registry) {
 *         registry.addInterceptor(csrfInterceptor)
 *                 .addPathPatterns("/admin/**", "/user/**")
 *                 .excludePathPatterns(
 *                     "/admin/login",
 *                     "/admin/code",
 *                     "/user/login",
 *                     "/user/code"
 *                 );
 *     }
 * }
 * </pre>
 */
@Slf4j
@Component
public class CsrfInterceptor implements HandlerInterceptor {

    @Resource
    private CsrfTokenUtil csrfTokenUtil;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${echo.csrf.exclude-paths:}")
    private List<String> excludePaths;

    /** 精确匹配白名单实例数组（@PostConstruct 初始化一次） */
    private String[] exactExcludes;

    /** 前缀匹配白名单实例数组（已补尾部斜杠，如 "/upload/"） */
    private String[] prefixExcludes;

    @PostConstruct
    public void init() {
        List<String> sources = excludePaths != null ? excludePaths : new ArrayList<>();
        this.exactExcludes = new String[sources.size()];
        this.prefixExcludes = new String[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            String p = sources.get(i);
            this.exactExcludes[i] = p;
            this.prefixExcludes[i] = p.endsWith("/") ? p : p + "/";
        }
    }

    /**
     * 检查请求是否在白名单中（精确匹配或路径前缀匹配）
     *
     * @param requestURI 请求URI
     * @return 是否在白名单中
     */
    private boolean isExcluded(String requestURI) {
        for (String exact : exactExcludes) {
            if (requestURI.equals(exact)) {
                log.debug("[CSRF-白名单] 精确匹配: {} == {}", requestURI, exact);
                return true;
            }
        }
        for (String prefix : prefixExcludes) {
            if (requestURI.startsWith(prefix)) {
                log.debug("[CSRF-白名单] 前缀匹配: {} startsWith {}", requestURI, prefix);
                return true;
            }
        }
        return false;
    }

    /**
     * 请求预处理（CSRF校验 + Token刷新）
     */
    @Override
    public boolean preHandle(@Nonnull HttpServletRequest request, @Nonnull HttpServletResponse response, @Nonnull Object handler) throws Exception {

        String method = request.getMethod().toUpperCase();
        String requestURI = request.getRequestURI();

        // 当前登录主体（管理员与用户 ID 各自独立，必须带上类型才能唯一定位）
        CsrfTokenUtil.SubjectRole role = csrfTokenUtil.resolveCurrentRole();
        Long subjectId = csrfTokenUtil.resolveCurrentSubjectId();

        // 1. 安全方法（GET/HEAD/OPTIONS）：放行，并向已登录主体下发当前 Token
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            if (role != null && subjectId != null && !isExcluded(requestURI)) {
                String token = csrfTokenUtil.getOrCreateToken(role, subjectId);
                response.setHeader("X-CSRF-Token", token);
                log.debug("[CSRF] 下发Token - {} - token:{}...", requestURI, maskToken(token));
            }
            return true;
        }

        // 2. 白名单路径放行（认证接口等）
        if (isExcluded(requestURI)) {
            log.debug("[CSRF] 白名单放行 - {}", requestURI);
            return true;
        }

        // 3. 对写操作进行CSRF校验
        log.debug("[CSRF] 开始校验 - {} {}", method, requestURI);

        boolean isValid = csrfTokenUtil.validateToken(request);

        if (!isValid) {
            log.warn("[CSRF] 校验失败 - {} {} - IP:{}",
                    method,
                    requestURI,
                    getClientIP(request));

            try {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(
                        objectMapper.writeValueAsString(Result.fail("CSRF验证失败，请刷新页面重试"))
                );
            } catch (Exception e) {
                log.error("[CSRF] 写入403响应失败 - error:{}", e.getMessage(), e);
            }
            return false;
        }

        // 4. 校验通过：回传当前 Token，客户端后续请求继续使用同一值
        if (role != null && subjectId != null) {
            String token = csrfTokenUtil.getOrCreateToken(role, subjectId);
            response.setHeader("X-CSRF-Token", token);
        }

        log.debug("[CSRF] 校验通过 - {} {}", method, requestURI);
        return true;
    }

    /**
     * 日志脱敏：保留 Token 前 8 位
     */
    private String maskToken(String token) {
        return token != null && token.length() > 8 ? token.substring(0, 8) : token;
    }

    /**
     * 获取客户端真实IP
     */
    private String getClientIP(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        // 多级代理时取第一个IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }

        return ip;
    }
}
