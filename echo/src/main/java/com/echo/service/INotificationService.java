package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.dto.Result;
import com.echo.entity.Notification;

/**
 * 通知服务接口
 *
 * @author tulip
 */
public interface INotificationService extends IService<Notification> {
    Result<?> queryMyNotifications(Integer current);
    Result<?> markAsRead(Long id);
    Result<?> markAllAsRead();
}
