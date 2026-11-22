package com.echo.utils;

import com.echo.dto.AdminDTO;
import com.echo.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * CSRF Token 工具类
 * <p>
 * 设计要点：
 * <ul>
 *   <li>每个登录主体在同一时刻持有一个有效 Token</li>
 *   <li>键为 {@code csrf:token:{主体类型}:{主体ID}}，管理员与用户的 ID 空间彼此独立</li>
 *   <li>Token 在有效期内重复使用，客户端可在任意多次写请求中携带同一个值</li>
 *   <li>每次校验通过后重置有效期，活跃会话的 Token 保持长期可用</li>
 *   <li>登录时签发，修改凭证、登出时清除</li>
 * </ul>
 *
 * @author tulip
 */
@Slf4j
@Component
public class CsrfTokenUtil {

    /** Redis Key 前缀 */
    private static final String CSRF_PREFIX = "csrf:token:";

    /** HTTP 请求头名称（前端携带 Token 的字段名） */
    private static final String CSRF_HEADER_NAME = "X-CSRF-Token";

    /** Token 有效期（2 小时） */
    private static final long TOKEN_VALIDITY_SECONDS = 7200L;

    /** 日志脱敏：保留的 Token 前缀长度 */
    private static final int TOKEN_LOG_DISPLAY_LENGTH = 8;

    /**
     * 登录主体类型
     * <p>
     * 管理员与用户各自有独立的自增 ID，只有同时带上主体类型才能唯一确定一个登录主体。
     */
    public enum SubjectRole {

        /** 普通用户（tb_user） */
        USER("user"),

        /** 管理员（tb_admin） */
        ADMIN("admin");

        private final String key;

        SubjectRole(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 获取指定主体的 Token，不存在时签发新 Token
     *
     * @param role 主体类型
     * @param id   主体ID
     * @return Token 字符串
     */
    public String getOrCreateToken(SubjectRole role, Long id) {
        String redisKey = buildTokenKey(role, id);
        String token = stringRedisTemplate.opsForValue().get(redisKey);

        if (token != null && !token.isEmpty()) {
            stringRedisTemplate.expire(redisKey, TOKEN_VALIDITY_SECONDS, TimeUnit.SECONDS);
            return token;
        }

        return generateToken(role, id);
    }

    /**
     * 签发新 Token 并覆盖该主体当前的 Token
     *
     * @param role 主体类型
     * @param id   主体ID
     * @return 新 Token
     */
    public String generateToken(SubjectRole role, Long id) {
        String token = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(
                buildTokenKey(role, id),
                token,
                TOKEN_VALIDITY_SECONDS,
                TimeUnit.SECONDS);

        log.info("[CSRF] 签发Token - {}:{}, token:{}...", role.getKey(), id, maskToken(token));
        return token;
    }

    /**
     * 校验请求头中的 Token
     * <p>
     * 校验通过后刷新有效期，Token 在会话内持续有效。
     *
     * @param request HTTP 请求
     * @return true=校验通过
     */
    public boolean validateToken(HttpServletRequest request) {
        String headerToken = request.getHeader(CSRF_HEADER_NAME);
        if (headerToken == null || headerToken.trim().isEmpty()) {
            log.warn("[CSRF] 校验失败 - 请求头缺少 {}", CSRF_HEADER_NAME);
            return false;
        }

        Long subjectId = resolveCurrentSubjectId();
        SubjectRole role = resolveCurrentRole();
        if (subjectId == null || role == null) {
            log.warn("[CSRF] 校验失败 - 用户未登录");
            return false;
        }

        String redisKey = buildTokenKey(role, subjectId);
        String storedToken = stringRedisTemplate.opsForValue().get(redisKey);

        if (storedToken == null || !storedToken.equals(headerToken)) {
            log.warn("[CSRF] 校验失败 - Token 不匹配, {}:{}, 请求Token:{}, 服务端Token:{}",
                    role.getKey(), subjectId, maskToken(headerToken), maskToken(storedToken));
            return false;
        }

        stringRedisTemplate.expire(redisKey, TOKEN_VALIDITY_SECONDS, TimeUnit.SECONDS);
        log.debug("[CSRF] 校验通过 - {}:{}, token:{}...", role.getKey(), subjectId, maskToken(headerToken));
        return true;
    }

    /**
     * 清除指定主体的 Token（登出、改密时调用）
     *
     * @param role 主体类型
     * @param id   主体ID
     */
    public void clearToken(SubjectRole role, Long id) {
        stringRedisTemplate.delete(buildTokenKey(role, id));
        log.info("[CSRF] 清除Token - {}:{}", role.getKey(), id);
    }

    /**
     * 查询 Token 剩余有效时间
     *
     * @param role 主体类型
     * @param id   主体ID
     * @return 剩余秒数；-2 表示 Token 不存在
     */
    public long getTokenTTL(SubjectRole role, Long id) {
        Long ttl = stringRedisTemplate.getExpire(buildTokenKey(role, id), TimeUnit.SECONDS);
        return ttl == null ? -2L : ttl;
    }

    /**
     * 解析当前登录主体的类型，管理员身份优先
     *
     * @return 主体类型；未登录返回 null
     */
    public SubjectRole resolveCurrentRole() {
        if (UserHolder.getAdmin() != null) {
            return SubjectRole.ADMIN;
        }
        return UserHolder.getUser() != null ? SubjectRole.USER : null;
    }

    /**
     * 解析当前登录主体的ID，与 {@link #resolveCurrentRole()} 的优先级保持一致
     *
     * @return 主体ID；未登录返回 null
     */
    public Long resolveCurrentSubjectId() {
        AdminDTO admin = UserHolder.getAdmin();
        if (admin != null) {
            return admin.getId();
        }

        UserDTO user = UserHolder.getUser();
        return user != null ? user.getId() : null;
    }

    private String buildTokenKey(SubjectRole role, Long id) {
        return CSRF_PREFIX + role.getKey() + ":" + id;
    }

    private String maskToken(String token) {
        if (token == null) {
            return "null";
        }
        return token.length() <= TOKEN_LOG_DISPLAY_LENGTH
                ? token
                : token.substring(0, TOKEN_LOG_DISPLAY_LENGTH);
    }
}
