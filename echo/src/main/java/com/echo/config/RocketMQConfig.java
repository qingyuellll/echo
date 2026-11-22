package com.echo.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * RocketMQ 配置
 */
@Slf4j
@Configuration
public class RocketMQConfig {

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @PostConstruct
    public void init() {
        log.info("[RocketMQ初始化] RocketMQTemplate 已就绪，namesrvAddr 由 application.yaml 配置");
    }
}
