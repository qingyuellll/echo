package com.echo.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.echo.dto.AdminDTO;
import com.echo.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Nonnull;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Token自动续期拦截器(滑动窗口过期机制)
 *
 * @author tulip
 */
@Slf4j
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String BEARER_PREFIX = "bearer ";
    private static final int TOKEN_MIN_LENGTH = 20;
    private static final int TOKEN_MAX_LENGTH = 64;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{20,64}$");
    private static final long SLOW_THRESHOLD_MS = 50L;

    @Override
    public boolean preHandle(@Nonnull HttpServletRequest request, 
                            @Nonnull HttpServletResponse response, 
                            @Nonnull Object handler) {
        long startTime = System.currentTimeMillis();
        
        try {
            String token = extractToken(request);
            if (token == null) {
                log.debug("[Token刷新] 无Token - uri:{}, method:{}", 
                         request.getRequestURI(), request.getMethod());
                return true;
            }

            if (!isValidTokenFormat(token)) {
                log.warn("[Token刷新] Token格式非法 - token:{}, uri:{}", 
                         maskToken(token), request.getRequestURI());
                return true;
            }

            log.debug("[Token刷新] 开始处理 - token:{}, uri:{}", 
                     maskToken(token), request.getRequestURI());

            if (tryLoadAndRefreshUser(token)) {
                logDuration(startTime, token, "用户", true);
                return true;
            }

            if (tryLoadAndRefreshAdmin(token)) {
                logDuration(startTime, token, "管理员", true);
                return true;
            }

            log.debug("[Token刷新] Token无效（非用户也非管理员） - token:{}, uri:{}", 
                     maskToken(token), request.getRequestURI());
            logDuration(startTime, token, "未知", false);
            return true;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[Token刷新] 执行异常 - duration:{}ms, uri:{}, error:{}", 
                     duration, request.getRequestURI(), e.getMessage(), e);
            return true;
        }
    }

    @Override
    public void afterCompletion(@Nonnull HttpServletRequest request, 
                               @Nonnull HttpServletResponse response, 
                               @Nonnull Object handler, 
                               Exception ex) {
        try {
            UserHolder.clearAll();
        } catch (Exception e) {
            log.error("[Token刷新] ThreadLocal清理失败 - error:{}", e.getMessage(), e);
        }
    }

    private String extractToken(HttpServletRequest request) {
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return null;
        }

        if (token.toLowerCase().startsWith(BEARER_PREFIX)) {
            return token.substring(BEARER_PREFIX.length());
        }

        return token;
    }

    private boolean isValidTokenFormat(String token) {
        if (token == null || token.length() < TOKEN_MIN_LENGTH || token.length() > TOKEN_MAX_LENGTH) {
            return false;
        }
        return TOKEN_PATTERN.matcher(token).matches();
    }

    private boolean tryLoadAndRefreshUser(String token) {
        return tryLoadAndRefresh(
                token,
                RedisConstants.LOGIN_USER_KEY,
                RedisConstants.LOGIN_USER_TTL,
                UserDTO.class,
                true
        );
    }

    private boolean tryLoadAndRefreshAdmin(String token) {
        return tryLoadAndRefresh(
                token,
                RedisConstants.LOGIN_ADMIN_KEY,
                RedisConstants.LOGIN_ADMIN_TTL,
                AdminDTO.class,
                false
        );
    }

    private <T> boolean tryLoadAndRefresh(String token, 
                                          String keyPrefix, 
                                          long ttl, 
                                          Class<T> dtoClass,
                                          boolean isUser) {
        String key = keyPrefix + token;
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(key);

        if (map.isEmpty()) {
            return false;
        }

        try {
            T dto = BeanUtil.fillBeanWithMap(map, dtoClass.getDeclaredConstructor().newInstance(), false);

            if (isUser) {
                UserHolder.saveUser((UserDTO) dto);
                log.info("[Token刷新] 用户Token续期成功 - userId:{}, token:{}", 
                        ((UserDTO) dto).getId(), maskToken(token));
            } else {
                UserHolder.saveAdmin((AdminDTO) dto);
                log.info("[Token刷新] 管理员Token续期成功 - adminId:{}, token:{}", 
                        ((AdminDTO) dto).getId(), maskToken(token));
            }

            UserHolder.saveToken(token);
            stringRedisTemplate.expire(key, ttl, TimeUnit.SECONDS);

            return true;

        } catch (Exception e) {
            log.error("[Token刷新] DTO转换失败 - type:{}, token:{}, error:{}", 
                    dtoClass.getSimpleName(), maskToken(token), e.getMessage());
            return false;
        }
    }

    private String maskToken(String token) {
        if (token == null || token.length() <= 12) {
            return "****";
        }
        return token.substring(0, 8) + "****" + token.substring(token.length() - 4);
    }

    private void logDuration(long startTime, String token, String role, boolean success) {
        long duration = System.currentTimeMillis() - startTime;
        
        if (duration > SLOW_THRESHOLD_MS) {
            log.warn("[Token刷新] {}Token刷新耗时过长 - token:{}, duration:{}ms", 
                    role, maskToken(token), duration);
        } else if (log.isDebugEnabled()) {
            log.debug("[Token刷新] {}Token刷新完成 - token:{}, duration:{}ms", 
                     role, maskToken(token), duration);
        }
    }
}
