package com.echo.config;

import com.echo.utils.TokenBucketLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 限流组件配置
 *
 * @author tulip
 */
@Configuration
public class RateLimitConfig {

    /**
     * 秒杀入口JVM级本地令牌桶（限流链路的第一道闸门）
     * <p>
     * 请求先经本机令牌桶过滤，被本地拒绝的流量不会穿透到Redis限流层与下游服务。
     * 容量2000用于吸收瞬时突发流量，速率1000 token/秒限制单机持续压入量——
     * 按5000张库存/1000单每秒=5秒售罄的真实秒杀节奏设定；
     * 集群总入口由Redis全局限流（rate_limit.lua，5000次/秒）统一兜底。
     */
    @Bean
    public TokenBucketLimiter seckillLocalLimiter() {
        return new TokenBucketLimiter(2000, 1000);
    }
}
