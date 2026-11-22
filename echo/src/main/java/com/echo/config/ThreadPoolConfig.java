package com.echo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Configuration
public class ThreadPoolConfig {

    @Value("${threadpool.follow.core-size:5}")
    private int followCoreSize;

    @Value("${threadpool.follow.max-size:10}")
    private int followMaxSize;

    @Value("${threadpool.follow.queue-capacity:100}")
    private int followQueueCapacity;

    @Value("${threadpool.follow.keep-alive-seconds:60}")
    private int followKeepAliveSeconds;

    @Value("${threadpool.cache-delete.core-size:2}")
    private int cacheDeleteCoreSize;

    @Value("${threadpool.cache-delete.max-size:5}")
    private int cacheDeleteMaxSize;

    @Value("${threadpool.cache-delete.queue-capacity:50}")
    private int cacheDeleteQueueCapacity;

    @Value("${threadpool.cache-delete.keep-alive-seconds:30}")
    private int cacheDeleteKeepAliveSeconds;

    @Value("${threadpool.async-task.core-size:10}")
    private int asyncTaskCoreSize;

    @Value("${threadpool.async-task.max-size:50}")
    private int asyncTaskMaxSize;

    @Value("${threadpool.async-task.queue-capacity:200}")
    private int asyncTaskQueueCapacity;

    @Value("${threadpool.async-task.keep-alive-seconds:60}")
    private int asyncTaskKeepAliveSeconds;

    @Value("${threadpool.compensation.core-size:2}")
    private int compensationCoreSize;

    @Value("${threadpool.compensation.max-size:5}")
    private int compensationMaxSize;

    @Value("${threadpool.compensation.queue-capacity:100}")
    private int compensationQueueCapacity;

    @Value("${threadpool.compensation.keep-alive-seconds:30}")
    private int compensationKeepAliveSeconds;

    @Value("${threadpool.await-termination-seconds:60}")
    private int awaitTerminationSeconds;

    @Bean("followExecutor")
    public ThreadPoolTaskExecutor followExecutor() {
        return createThreadPoolExecutor(
                "follow-async-",
                followCoreSize,
                followMaxSize,
                followQueueCapacity,
                followKeepAliveSeconds
        );
    }

    @Bean("cacheDeleteExecutor")
    public ThreadPoolTaskExecutor cacheDeleteExecutor() {
        return createThreadPoolExecutor(
                "cache-delete-async-",
                cacheDeleteCoreSize,
                cacheDeleteMaxSize,
                cacheDeleteQueueCapacity,
                cacheDeleteKeepAliveSeconds
        );
    }

    @Bean("asyncTaskExecutor")
    public ThreadPoolTaskExecutor asyncTaskExecutor() {
        return createThreadPoolExecutor(
                "async-task-",
                asyncTaskCoreSize,
                asyncTaskMaxSize,
                asyncTaskQueueCapacity,
                asyncTaskKeepAliveSeconds
        );
    }

    /**
     * 秒杀补偿任务线程池：MQ发送失败的回滚重试专用，与业务异步任务隔离。
     * 提交方包含调度线程，池满时由调用方捕获拒绝异常并降级到定期兜底链路，
     * 拒绝策略因此采用AbortPolicy，保证补偿重试始终在调度线程之外执行。
     */
    @Bean("compensationExecutor")
    public ThreadPoolTaskExecutor compensationExecutor() {
        ThreadPoolTaskExecutor executor = createThreadPoolExecutor(
                "compensation-async-",
                compensationCoreSize,
                compensationMaxSize,
                compensationQueueCapacity,
                compensationKeepAliveSeconds
        );
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    private ThreadPoolTaskExecutor createThreadPoolExecutor(
            String threadNamePrefix,
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            int keepAliveSeconds) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setKeepAliveSeconds(keepAliveSeconds);

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);

        AtomicLong threadNumber = new AtomicLong(0);
        executor.setThreadFactory((Runnable r) -> {
            Thread thread = new Thread(r, threadNamePrefix + threadNumber.incrementAndGet());
            thread.setUncaughtExceptionHandler((t, e) ->
                    log.error("[线程池异常] 线程:{} 发生未捕获异常", t.getName(), e)
            );
            return thread;
        });

        executor.initialize();

        log.info("[线程池初始化] {} 线程池创建成功 - 核心线程:{}, 最大线程:{}, 队列容量:{}, 存活时间:{}s, 优雅关闭等待:{}s",
                threadNamePrefix.replace("-", ""),
                corePoolSize,
                maxPoolSize,
                queueCapacity,
                keepAliveSeconds,
                awaitTerminationSeconds);

        return executor;
    }
}
