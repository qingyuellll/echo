package com.echo.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;


/**
 * Redis配置类
 *
 * @author tulip
 */
@Slf4j
@Configuration
public class RedisConfig {

    @Value("${spring.redis.host}")
    private String redisHost;

    @Value("${spring.redis.port}")
    private int redisPort;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    @Bean
    public RedissonClient redissonClient() {

        log.info("[Redis初始化] 正在连接Redis服务器: {}:{}", redisHost, redisPort);

        Config config = new Config();
        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort);

        if (redisPassword != null && !redisPassword.isEmpty()) {
            serverConfig.setPassword(redisPassword);
            log.info("[Redis初始化] 已配置Redis密码认证");
        }

        RedissonClient redissonClient = Redisson.create(config);

        log.info("[Redis初始化] Redisson客户端创建成功，address:{}", "redis://" + redisHost + ":" + redisPort);
        return redissonClient;
    }

    @Bean
    public DefaultRedisScript<Long> seckillScript() {
        log.info("[Redis初始化] 加载秒杀Lua脚本: lua/seckill.lua");
        return scriptFrom("lua/seckill.lua");
    }

    @Bean
    public DefaultRedisScript<Long> seckillEnqueueScript() {
        log.info("[Redis初始化] 加载秒杀排队入队Lua脚本: lua/seckill_enqueue.lua");
        return scriptFrom("lua/seckill_enqueue.lua");
    }

    @Bean
    public DefaultRedisScript<Long> seckillIdemScript() {
        log.info("[Redis初始化] 加载秒杀幂等门闸Lua脚本: lua/seckill_idem.lua");
        return scriptFrom("lua/seckill_idem.lua");
    }

    @Bean
    public DefaultRedisScript<Long> seckillRollbackScript() {
        log.info("[Redis初始化] 加载秒杀回滚Lua脚本: lua/seckill_rollback.lua");
        return scriptFrom("lua/seckill_rollback.lua");
    }

    @Bean
    public DefaultRedisScript<Long> seckillRefundScript() {
        log.info("[Redis初始化] 加载秒杀退款Lua脚本: lua/seckill_refund.lua");
        return scriptFrom("lua/seckill_refund.lua");
    }

    @Bean
    public DefaultRedisScript<Long> rateLimitScript() {
        log.info("[Redis初始化] 加载限流Lua脚本: lua/rate_limit.lua（令牌桶实现）");
        return scriptFrom("lua/rate_limit.lua");
    }

    @Bean
    public DefaultRedisScript<Long> atomicCounterScript() {
        log.info("[Redis初始化] 加载原子计数器Lua脚本: lua/atomic_counter.lua");
        return scriptFrom("lua/atomic_counter.lua");
    }

    @Bean
    public DefaultRedisScript<Long> likeReviewScript() {
        log.info("[Redis初始化] 加载点赞Lua脚本: lua/likeReview.lua");
        return scriptFrom("lua/likeReview.lua");
    }

    @Bean
    public DefaultRedisScript<Long> followShowScript() {
        log.info("[Redis初始化] 加载关注演出Lua脚本: lua/followShow.lua");
        return scriptFrom("lua/followShow.lua");
    }

    @Bean
    public DefaultRedisScript<Long> followUserScript() {
        log.info("[Redis初始化] 加载关注用户Lua脚本: lua/followUser.lua");
        return scriptFrom("lua/followUser.lua");
    }

    @Bean
    public DefaultRedisScript<Long> unlockScript() {
        log.info("[Redis初始化] 加载锁释放Lua脚本: lua/unlock.lua");
        return scriptFrom("lua/unlock.lua");
    }

    /**
     * 字符串锁专用释放脚本（GET+DEL，与 setIfAbsent 加锁配对）。
     * 注意：unlock.lua 是哈希结构可重入锁脚本，对 string 键执行会抛 WRONGTYPE，
     * 字符串锁（如 SeatServiceImpl.lockSeat 的座位锁）必须使用本脚本。
     */
    @Bean
    public DefaultRedisScript<Long> unlockSimpleScript() {
        log.info("[Redis初始化] 加载字符串锁释放Lua脚本: lua/unlock_simple.lua");
        return scriptFrom("lua/unlock_simple.lua");
    }

    @Bean
    public DefaultRedisScript<Long> incrWithTtlScript() {
        log.info("[Redis初始化] 加载序号自增Lua脚本: lua/incr_with_ttl.lua（INCRBY+首增EXPIRE原子化）");
        return scriptFrom("lua/incr_with_ttl.lua");
    }

    private DefaultRedisScript<Long> scriptFrom(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }
}
