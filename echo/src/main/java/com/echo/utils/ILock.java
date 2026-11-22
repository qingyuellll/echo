package com.echo.utils;

/**
 * 分布式锁抽象接口
 *
 * @author tulip
 */
public interface ILock {

    /**
     * 尝试获取分布式锁(非阻塞)
     *
     * @param timeoutSec 锁超时时间(秒)
     * @return true=获取成功, false=获取失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放分布式锁(必须在finally块中调用)
     */
    void unlock();
}
