package com.echo.message;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 缓存失效消息
 * <p>
 * 通过 RocketMQ Broadcasting 广播到所有服务节点，通知删除本地 Caffeine 缓存，
 * 用于保证 Redis 与 Caffeine 两级缓存之间的一致性
 *
 * @author tulip
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CacheInvalidateMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 缓存 key 前缀(如 "cache:show:") */
    private String keyPrefix;

    /** 数据 ID */
    private Long id;

    /**
     * 创建缓存失效消息
     *
     * @param keyPrefix 缓存 key 前缀
     * @param id 数据 ID
     * @return 缓存失效消息实例
     */
    public static CacheInvalidateMessage of(String keyPrefix, Long id) {
        if (StrUtil.isBlank(keyPrefix)) {
            throw new IllegalArgumentException("keyPrefix 不能为空");
        }
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("id 必须为正整数");
        }

        return CacheInvalidateMessage.builder()
                .keyPrefix(keyPrefix)
                .id(id)
                .build();
    }
}
