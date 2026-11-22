package com.echo.config;

import com.echo.service.IScheduledPublishService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 定时任务配置
 * <p>
 * 功能：
 * - 每分钟检查一次定时发布任务
 * - 包括：票种上架、秒杀激活、演出发布
 */
@Slf4j
@Component
@EnableScheduling
public class ScheduledTaskConfig {

    @Resource
    private IScheduledPublishService scheduledPublishService;

    /**
     * 定时发布任务
     * 每1分钟执行一次（cron表达式：每分钟的第0秒）
     */
    @Scheduled(cron = "0 */1 * * * ?")
    public void scheduledPublishTask() {
        long start = System.currentTimeMillis();
        try {
            log.info("[定时任务] 开始执行定时发布检查...");
            scheduledPublishService.checkAndExecuteScheduledPublish();
            long cost = System.currentTimeMillis() - start;
            if (cost > 5000) {
                log.warn("[定时任务] 执行耗时过长: {}ms", cost);
            } else {
                log.info("[定时任务] 执行完成, 耗时: {}ms", cost);
            }
        } catch (Exception e) {
            log.error("[定时任务] 定时发布检查异常, 耗时: {}ms", System.currentTimeMillis() - start, e);
        }
    }
}
