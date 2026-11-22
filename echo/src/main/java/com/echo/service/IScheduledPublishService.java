package com.echo.service;

/**
 * 定时发布服务接口
 *
 * @author tulip
 */
public interface IScheduledPublishService {

    void checkAndExecuteScheduledPublish();

    void executeImmediately();
}
