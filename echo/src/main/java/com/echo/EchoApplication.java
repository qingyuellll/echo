package com.echo;

import com.echo.service.ISeckillTicketService;
import com.echo.service.impl.SeckillTicketServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.annotation.Resource;
import java.util.Set;

/**
 * Echo项目启动类
 *
 * @author tulip
 */
@Slf4j
@EnableAspectJAutoProxy(exposeProxy = true)
@EnableScheduling
@MapperScan("com.echo.mapper")
@SpringBootApplication
public class EchoApplication implements CommandLineRunner {

    @Resource
    private ISeckillTicketService seckillTicketService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public static void main(String[] args) {
        SpringApplication.run(EchoApplication.class, args);
    }

    @Override
    public void run(String... args) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        clearStaleShowCache();

        try {
            SeckillTicketServiceImpl impl = (SeckillTicketServiceImpl) seckillTicketService;
            impl.preloadAllSeckillTickets();
        } catch (Exception e) {
            log.warn("秒杀票预热失败（不影响运行）: {}", e.getMessage());
        }

        log.info(" Echo系统启动完成 - http://localhost:8081");
    }

    /**
     * 清除演出相关的过期/无效缓存
     */
    private void clearStaleShowCache() {
        try {
            String pattern = "cache:show:*";
            Set<String> keys = stringRedisTemplate.keys(pattern);
            if (!keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
        } catch (Exception ignored) {
        }
    }

}
