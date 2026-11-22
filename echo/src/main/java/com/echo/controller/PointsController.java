package com.echo.controller;

import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.echo.service.IPointsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.constraints.Min;


/**
 * 积分管理控制器
 *
 * 提供积分余额查询、积分记录、抽奖记录等积分相关功能接口
 *
 * @author tulip
 */
@Tag(name = "积分管理")
@Slf4j
@Validated
@RestController
@RequestMapping("/points")
public class PointsController {

    @Resource
    private IPointsService pointsService;

    @Operation(summary = "查询我的积分余额", description = "获取当前用户的积分总数和信誉分")
    @GetMapping("/my")
    public Result<?> queryMyPoints() {
        log.debug("[Controller] 查询积分余额");
        return pointsService.queryMyPoints();
    }

    @Operation(summary = "查询积分记录列表", description = "分页查询积分变动记录，按时间倒序")
    @GetMapping("/records")
    public Result<?> queryMyPointsRecords(
            @Min(value = 1, message = "页码必须大于0")
            @RequestParam(value = "current", defaultValue = "1") Integer current) {

        log.debug("[Controller] 查询积分记录，页码:{}", current);
        return pointsService.queryMyPointsRecords(current);
    }

    @Operation(summary = "查询抽奖记录列表", description = "专门查询抽奖类型的积分记录，用于抽奖页面展示")
    @GetMapping("/lottery/records")
    public Result<?> queryLotteryRecords(
            @Min(value = 1, message = "页码必须大于0")
            @RequestParam(value = "current", defaultValue = "1") Integer current) {

        log.debug("[Controller] 查询抽奖记录，页码:{}", current);
        return pointsService.queryLotteryRecords(current);
    }

    @Operation(summary = "积分兑换优惠券", description = "消耗100积分兑换10元优惠券（满50元可用，有效期30天）")
    @PostMapping("/coupon")
    public Result<?> exchangeCoupon() {
        log.info("[Controller] 发起兑换优惠券请求");

        try {
            return pointsService.exchangeCoupon();
        } catch (Exception e) {
            log.error("[Controller] 兑换优惠券异常 - error:{}", e.getMessage(), e);

            if (e.getMessage() != null && e.getMessage().contains("积分不足")) {
                return Result.fail(AppMessages.POINTS_NOT_ENOUGH);
            }

            if (e.getMessage() != null && e.getMessage().contains("已达上限")) {
                return Result.fail(AppMessages.POINTS_LOTTERY_LIMIT);
            }

            return Result.fail(AppMessages.OPERATION_FAIL);
        }
    }

    @Operation(summary = "积分抽奖", description = "消耗20积分参与抽奖，每日限3次")
    @PostMapping("/lottery")
    public Result<?> lottery() {
        log.info("[Controller] 发起积分抽奖请求");
        return pointsService.lottery();
    }
}
