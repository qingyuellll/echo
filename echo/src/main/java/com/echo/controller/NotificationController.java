package com.echo.controller;

import com.echo.dto.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.echo.service.INotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;


/**
 * 通知管理控制器
 *
 * 提供通知列表查询、已读标记、全部已读等消息通知功能接口
 *
 * @author tulip
 */
@Tag(name = "通知管理")
@Slf4j
@Validated
@RestController
@RequestMapping("/notification")
public class NotificationController {

    @Resource
    private INotificationService notificationService;

    @Operation(summary = "查询我的通知列表", description = "分页查询当前用户的通知列表，按时间倒序排列")
    @GetMapping("/my")
    public Result<?> queryMyNotifications(
            @Min(value = 1, message = "页码必须大于0")
            @Parameter(description = "页码", example = "1")
            @RequestParam(value = "current", defaultValue = "1") Integer current) {

        log.debug("[Controller] 查询通知列表，页码:{}", current);
        return notificationService.queryMyNotifications(current);
    }

    @Operation(summary = "标记单条通知为已读", description = "根据通知ID标记指定通知为已读状态")
    @PutMapping("/{id}/read")
    public Result<?> markAsRead(
            @NotNull(message = "通知ID不能为空")
            @Min(value = 1, message = "通知ID必须大于0")
            @Parameter(description = "通知ID", required = true)
            @PathVariable("id") Long id) {

        log.info("[Controller] 标记通知已读，通知ID:{}", id);
        return notificationService.markAsRead(id);
    }

    @Operation(summary = "标记所有通知为已读", description = "将当前用户的所有未读通知标记为已读")
    @PutMapping("/read-all")
    public Result<?> markAllAsRead() {
        log.info("[Controller] 标记所有通知为已读");
        return notificationService.markAllAsRead();
    }
}
