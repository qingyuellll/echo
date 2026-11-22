package com.echo.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;

/**
 * Redis Lua脚本安全执行工具类
 *
 * @author tulip
 */
@Slf4j
@Component
public class RedisLuaHelper {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_INTERVAL_MS = 100;
    private static final long SLOW_EXECUTION_THRESHOLD_MS = 1000;

    public Long executeSafely(DefaultRedisScript<Long> script, List<String> keys, String... args) {
        return executeWithRetry(script, keys, 0, DEFAULT_MAX_RETRIES, "未知操作", args);
    }

    public Long executeWithOperationContext(DefaultRedisScript<Long> script, List<String> keys,
                                            String operation, String... args) {
        return executeWithRetry(script, keys, 0, DEFAULT_MAX_RETRIES, operation, args);
    }

    public Long executeWithRetry(DefaultRedisScript<Long> script, List<String> keys,
                                 int currentRetry, int maxRetries, String operation, String... args) {
        String scriptName = getScriptName(script);
        long startTime = System.currentTimeMillis();

        try {
            log.info("[Lua脚本] [{}] [重试{}/{}] 开始执行 - script:{}, keys:{}, args:{}",
                    operation, currentRetry + 1, maxRetries, scriptName, keys, Arrays.toString(args));

            Long result = doExecute(script, keys, args);

            long duration = System.currentTimeMillis() - startTime;
            logExecutionMetrics(operation, scriptName, duration, true);

            log.info("[Lua脚本] [{}] [OK] 执行成功 - script:{}, result:{}, duration:{}ms",
                    operation, scriptName, result, duration);

            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logExecutionMetrics(operation, scriptName, duration, false);

            if (currentRetry < maxRetries) {
                long waitTime = DEFAULT_RETRY_INTERVAL_MS * (currentRetry + 1);
                log.warn("[Lua脚本] [{}] 第{}次重试（{}ms后）- error:{}",
                        operation, currentRetry + 1, waitTime, e.getMessage());

                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("线程中断", ie);
                }

                return executeWithRetry(script, keys, currentRetry + 1, maxRetries, operation, args);
            }

            log.error("[Lua脚本] [{}] [ERROR] 重试耗尽，执行最终失败 - script:{}, totalRetries:{}, duration:{}ms, error:{}",
                    operation, scriptName, maxRetries, duration, e.getMessage(), e);

            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }

            throw new RuntimeException("系统繁忙，请稍后重试", e);
        }
    }

    private Long doExecute(DefaultRedisScript<Long> script, List<String> keys, Object[] args) {
        String scriptName = getScriptName(script);

        Long result = stringRedisTemplate.execute(script, keys, args);

        if (result == null) {
            log.error("[Lua脚本] 返回值为空（可能连接异常或脚本错误）- script:{}, keys:{}, args:{}",
                    scriptName, keys, Arrays.toString(args));
            throw new RuntimeException("Redis Lua脚本执行异常（返回值为空）: " + scriptName);
        }

        return result;
    }

    private void logExecutionMetrics(String operation, String scriptName, long duration, boolean success) {
        if (!success || duration > SLOW_EXECUTION_THRESHOLD_MS) {
            log.warn("[Lua脚本] [{}] {} - script:{}, duration:{}ms",
                    success ? "慢执行" : "执行失败",
                    operation, scriptName, duration);
        }
    }

    private String getScriptName(DefaultRedisScript<?> script) {
        if (script.getScriptAsString() != null && !script.getScriptAsString().isEmpty()) {
            String scriptText = script.getScriptAsString();
            if (scriptText.contains("/")) {
                return scriptText.substring(scriptText.lastIndexOf("/") + 1);
            }
            if (scriptText.contains("\\")) {
                return scriptText.substring(scriptText.lastIndexOf("\\") + 1);
            }
            if (scriptText.length() <= 50) {
                return scriptText;
            }
            return scriptText.substring(0, 47) + "...";
        }
        return script.getClass().getSimpleName();
    }

    public boolean isSuccess(Long result, long... successCodes) {
        if (result == null) {
            return false;
        }

        if (successCodes.length == 0) {
            return result > 0;
        }

        for (long code : successCodes) {
            if (result == code) {
                return true;
            }
        }

        return false;
    }
}
