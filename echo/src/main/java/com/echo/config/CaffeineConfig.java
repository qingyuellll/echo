package com.echo.config;

import com.echo.entity.SeckillTicket;
import com.echo.entity.Show;
import com.echo.entity.Ticket;
import com.echo.entity.UserInfo;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置（二级缓存 L1）
 * <p>
 * 缓存策略说明：
 * - maximumSize: 最大缓存条目数，超出后使用 W-TinyLFU 算法淘汰
 * - expireAfterWrite: 写入后过期时间，保证数据时效性
 * - recordStats: 开启统计，便于监控缓存命中率
 */
@Configuration
public class CaffeineConfig {

    /**
     * 演出本地缓存
     * 针对热点演出数据，减少 Redis 网络 IO
     */
    @Bean
    public Cache<String, Show> showLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(1000)
                .initialCapacity(500)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    /**
     * 通用本地缓存（用于缓存其他类型数据）
     */
    @Bean
    public Cache<String, Object> genericLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    /**
     * 秒杀票种本地缓存（秒杀入口削读）
     * TTL 3秒：稳态下每秒至多3次DB查询而非每请求1次；
     * 代价是开售瞬间的时间校验最多滞后3秒（前端有倒计时，可接受）
     */
    @Bean
    public Cache<Long, SeckillTicket> seckillTicketLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(200)
                .initialCapacity(50)
                .expireAfterWrite(3, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }

    /**
     * 用户信誉分本地缓存（秒杀入口削读）
     * TTL 10秒：刚被扣分/拉黑的用户最多延迟10秒被拦截，属可接受窗口
     */
    @Bean
    public Cache<Long, UserInfo> userCreditLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(10000)
                .initialCapacity(1000)
                .expireAfterWrite(10, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }

    /**
     * 票种本地缓存（秒杀入口削读，仅使用hasSeats与showId两个字段）
     */
    @Bean
    public Cache<Long, Ticket> ticketLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(5000)
                .initialCapacity(500)
                .expireAfterWrite(10, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }
}
