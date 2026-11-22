package com.echo.utils;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Redis缓存数据包装类(逻辑过期模式)
 *
 * @author tulip
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RedisData<T> {
    private LocalDateTime expireTime;
    private T data;

    public boolean isExpired() {
        return expireTime == null || LocalDateTime.now().isAfter(expireTime);
    }

    public static <T> RedisData<T> of(T data, LocalDateTime expireTime) {
        return RedisData.<T>builder()
                .data(data)
                .expireTime(expireTime)
                .build();
    }
}
