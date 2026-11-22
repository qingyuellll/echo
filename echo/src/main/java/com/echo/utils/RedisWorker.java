package com.echo.utils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于Redis的全局唯一ID生成器
 *
 * @author tulip
 */
@Slf4j
@Component
public class RedisWorker {

    private static final long BEGIN_TIMESTAMP = 1096761600L;

    private static final int COUNT_BITS = 32;
    private static final long MAX_COUNT = (1L << COUNT_BITS) - 1;

    private static final String KEY_PREFIX = "icr:";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    /** 序号key的TTL：按天分key，2天过期保证跨天余量，避免icr:*无限堆积 */
    private static final long KEY_TTL_SECONDS = 2 * 24 * 3600L;

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> incrWithTtlScript;

    public RedisWorker(StringRedisTemplate stringRedisTemplate,
                       DefaultRedisScript<Long> incrWithTtlScript) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.incrWithTtlScript = incrWithTtlScript;
    }

    public long nextId(String keyPrefix) {
        validateKeyPrefix(keyPrefix);

        try {
            LocalDateTime now = LocalDateTime.now();
            long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
            long timestamp = nowSecond - BEGIN_TIMESTAMP;

            validateTimestamp(timestamp);

            String date = now.format(DATE_FORMATTER);
            String redisKey = KEY_PREFIX + keyPrefix + ":" + date;
            // Lua原子执行INCRBY+首增EXPIRE，消除两步操作间的崩溃窗口
            Long countObj = stringRedisTemplate.execute(
                    incrWithTtlScript,
                    java.util.Collections.singletonList(redisKey),
                    "1", String.valueOf(KEY_TTL_SECONDS));

            if (countObj == null) {
                log.error("[RedisWorker] Redis Lua自增返回null - key:{}", redisKey);
                throw new RuntimeException("Redis操作失败：序列号生成异常");
            }

            long count = countObj & MAX_COUNT;
            long id = (timestamp << COUNT_BITS) | count;

            log.debug("[RedisWorker] 生成ID成功 - prefix:{}, id:{}, timestamp:{}, seq:{}", 
                     keyPrefix, id, timestamp, count);

            return id;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("[RedisWorker] 生成ID失败 - prefix:{}, error:{}", keyPrefix, e.getMessage(), e);
            throw new RuntimeException("ID生成失败: " + keyPrefix, e);
        }
    }

    public List<Long> nextIds(String keyPrefix, int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }

        validateKeyPrefix(keyPrefix);

        try {
            LocalDateTime now = LocalDateTime.now();
            long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
            long timestamp = nowSecond - BEGIN_TIMESTAMP;

            validateTimestamp(timestamp);

            String date = now.format(DATE_FORMATTER);
            String redisKey = KEY_PREFIX + keyPrefix + ":" + date;

            // Lua原子执行INCRBY+首增EXPIRE，消除两步操作间的崩溃窗口
            Long startCount = stringRedisTemplate.execute(
                    incrWithTtlScript,
                    java.util.Collections.singletonList(redisKey),
                    String.valueOf(count), String.valueOf(KEY_TTL_SECONDS));

            if (startCount == null) {
                log.error("[RedisWorker] Redis Lua批量自增返回null - key:{}, count:{}", redisKey, count);
                throw new RuntimeException("Redis操作失败：批量序列号生成异常");
            }

            long baseSequence = startCount - count + 1;

            List<Long> ids = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long seq = (baseSequence + i) & MAX_COUNT;
                ids.add((timestamp << COUNT_BITS) | seq);
            }

            log.info("[RedisWorker] 批量生成ID成功 - prefix:{}, count:{}, startSeq:{}, endSeq:{}", 
                    keyPrefix, count, baseSequence, baseSequence + count - 1);

            return ids;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("[RedisWorker] 批量生成ID失败 - prefix:{}, count:{}, error:{}", 
                     keyPrefix, count, e.getMessage(), e);
            throw new RuntimeException("批量ID生成失败: " + keyPrefix, e);
        }
    }

    public IdInfo parseId(long id) {
        long timestamp = id >> COUNT_BITS;
        long sequence = id & MAX_COUNT;

        LocalDateTime dateTime = LocalDateTime.ofEpochSecond(
                timestamp + BEGIN_TIMESTAMP,
                0,
                ZoneOffset.UTC
        );

        return new IdInfo(dateTime, sequence);
    }

    private void validateKeyPrefix(String keyPrefix) {
        if (!StringUtils.hasText(keyPrefix)) {
            throw new IllegalArgumentException("keyPrefix不能为空");
        }
        if (keyPrefix.contains(":")) {
            throw new IllegalArgumentException("keyPrefix不能包含冒号（与日期分隔符冲突）");
        }
    }

    private void validateTimestamp(long timestamp) {
        if (timestamp < 0) {
            log.error("[RedisWorker] 时间戳为负数 - timestamp:{}，可能系统时间早于起始时间({})", 
                     timestamp, BEGIN_TIMESTAMP);
            throw new RuntimeException("时间戳异常：系统时间可能早于2004年，请检查系统时间");
        }
        if (timestamp > MAX_COUNT) {
            log.warn("[RedisWorker] 时间戳接近上限 - timestamp:{}, max:{}", timestamp, MAX_COUNT);
        }
    }

    @Data
    @AllArgsConstructor
    public static class IdInfo {
        private LocalDateTime generateTime;
        private long sequence;

        @Override
        public String toString() {
            return String.format("IdInfo{generateTime=%s, sequence=%d}", generateTime, sequence);
        }
    }
}
