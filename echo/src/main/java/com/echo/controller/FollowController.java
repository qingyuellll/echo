package com.echo.controller;

import com.echo.dto.Result;
import com.echo.service.IFollowService;
import com.echo.utils.UserHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Slf4j
@RestController
@RequestMapping("/follow")
@Validated
@Tag(name = "关注管理")
public class FollowController {

    private static final String LOG_PREFIX = "[关注管理]";

    @Resource
    private IFollowService followService;

    @Operation(summary = "关注/取关用户", description = "对指定用户进行关注或取消关注操作。需要用户登录。")
    @PutMapping("/{id:\\d+}/{isFollow}")
    public Result<?> follow(
            @NotNull(message = "目标用户ID不能为空")
            @Min(value = 1, message = "目标用户ID必须大于0")
            @Parameter(description = "目标用户ID", required = true, example = "123")
            @PathVariable("id") Long followUserId,

            @NotNull(message = "操作类型不能为空")
            @Parameter(description = "是否关注（true=关注，false=取消关注）", required = true, example = "true")
            @PathVariable("isFollow") Boolean isFollow) {

        if (UserHolder.getUser() == null) {
            log.warn("{} 用户未登录，无法执行关注/取关操作 - 目标用户:{}", LOG_PREFIX, followUserId);
            return Result.fail("请先登录");
        }

        if (UserHolder.getUser().getId().equals(followUserId)) {
            log.warn("{} 用户尝试关注自己 - userId:{}", LOG_PREFIX, followUserId);
            return Result.fail("不能关注自己");
        }

        String action = Boolean.TRUE.equals(isFollow) ? "关注" : "取关";
        log.info("{} 收到{}请求，当前用户:{}, 目标用户:{}", LOG_PREFIX, action, UserHolder.getUser().getId(), followUserId);

        try {
            return followService.follow(followUserId, isFollow);
        } catch (Exception e) {
            log.error("{} {}失败 - 目标用户:{}, error:{}", LOG_PREFIX, action, followUserId, e.getMessage(), e);
            return Result.fail(action + "操作失败，请稍后重试");
        }
    }

    @Operation(summary = "判断是否已关注", description = "检查当前登录用户是否已关注指定用户")
    @GetMapping("/status/{id:\\d+}")
    public Result<?> isFollow(
            @NotNull(message = "目标用户ID不能为空")
            @Min(value = 1, message = "目标用户ID必须大于0")
            @Parameter(description = "目标用户ID", required = true, example = "123")
            @PathVariable("id") Long followUserId) {

        if (UserHolder.getUser() == null) {
            log.debug("{} 用户未登录，返回未关注状态 - 目标用户:{}", LOG_PREFIX, followUserId);
            return Result.ok(false);
        }

        log.debug("{} 查询关注状态，当前用户:{}, 目标用户:{}", LOG_PREFIX, UserHolder.getUser().getId(), followUserId);
        return followService.isFollow(followUserId);
    }

    @Operation(summary = "获取共同关注列表", description = "获取当前用户与指定用户的共同关注好友列表")
    @GetMapping("/common/{id:\\d+}")
    public Result<?> followCommons(
            @NotNull(message = "目标用户ID不能为空")
            @Min(value = 1, message = "目标用户ID必须大于0")
            @Parameter(description = "目标用户ID", required = true, example = "123")
            @PathVariable("id") Long targetUserId) {

        if (UserHolder.getUser() == null) {
            log.warn("{} 用户未登录，无法查询共同关注 - 目标用户:{}", LOG_PREFIX, targetUserId);
            return Result.fail("请先登录");
        }

        log.debug("{} 查询共同关注，当前用户:{}, 目标用户:{}",
                LOG_PREFIX, UserHolder.getUser().getId(), targetUserId);

        try {
            return followService.followCommons(targetUserId);
        } catch (Exception e) {
            log.error("{} 查询共同关注失败 - 目标用户:{}, error:{}", LOG_PREFIX, targetUserId, e.getMessage(), e);
            return Result.fail("查询失败，请稍后重试");
        }
    }
}
