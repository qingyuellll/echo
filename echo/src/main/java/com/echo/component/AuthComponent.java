package com.echo.component;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.echo.utils.RedisConstants;

/**
 * 认证组件( token/验证码/限流)
 *
 * @author tulip
 */
@Component
@Slf4j
public class AuthComponent {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private DefaultRedisScript<Long> atomicCounterScript;

    public String generateToken() {
        return UUID.randomUUID().toString(true);
    }

    public String generateCode() {
        return RandomUtil.randomNumbers(6);
    }

    public void saveCode(String codeKey, String code, Long ttl) {
        stringRedisTemplate.opsForValue().set(codeKey, code, ttl, TimeUnit.SECONDS);
    }

    public String getCode(String codeKey) {
        Object val = stringRedisTemplate.opsForValue().get(codeKey);
        return val != null ? val.toString() : null;
    }

    public void deleteCode(String codeKey) {
        stringRedisTemplate.delete(codeKey);
    }

    public boolean tryRateLimit(String rateLimitKey, Long ttlSeconds) {
        Boolean ok = stringRedisTemplate.opsForValue()
                .setIfAbsent(rateLimitKey, "1", ttlSeconds, TimeUnit.SECONDS);
        return Boolean.FALSE.equals(ok);
    }

    public void saveToken(String tokenKey, Map<String, String> data, Long ttl) {
        stringRedisTemplate.opsForHash().putAll(tokenKey, data);
        stringRedisTemplate.expire(tokenKey, ttl, TimeUnit.SECONDS);
    }

    public Map<Object, Object> getTokenData(String tokenKey) {
        return stringRedisTemplate.opsForHash().entries(tokenKey);
    }

    public void refreshToken(String tokenKey, Long ttl) {
        stringRedisTemplate.expire(tokenKey, ttl, TimeUnit.SECONDS);
    }

    public void deleteToken(String tokenKey) {
        stringRedisTemplate.delete(tokenKey);
    }

    public Map<String, String> buildUserMap(Object dto) {
        Map<String, Object> map = BeanUtil.beanToMap(dto);
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue().toString());
            }
        }
        return result;
    }

    // ==================== 登录失败锁定（防爆破） ====================

    /**
     * 是否已触发登录失败锁定（失败次数达到上限）
     */
    public boolean isLoginLocked(String failKey) {
        String count = stringRedisTemplate.opsForValue().get(failKey);
        if (count == null) {
            return false;
        }
        try {
            return Integer.parseInt(count) >= RedisConstants.LOGIN_FAIL_MAX_ATTEMPTS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 记录一次登录失败，返回当前累计失败次数（原子操作）
     */
    public long recordLoginFail(String failKey) {
        try {
            Long count = stringRedisTemplate.execute(
                    atomicCounterScript,
                    Collections.singletonList(failKey),
                    String.valueOf(RedisConstants.LOGIN_FAIL_LOCK_TTL * 60)
            );
            return count != null && count > 0 ? count : 0;
        } catch (Exception e) {
            log.warn("[登录失败计数] 原子计数器异常:{}", e.getMessage());
            return 0;
        }
    }

    /**
     * 登录成功后清除失败计数
     */
    public void clearLoginFail(String failKey) {
        stringRedisTemplate.delete(failKey);
    }
}
