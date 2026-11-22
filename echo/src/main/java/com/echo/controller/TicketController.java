package com.echo.controller;

import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.service.ISeatService;
import com.echo.service.ITicketService;
import com.echo.utils.UserHolder;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;


/**
 * 用户端票务控制器
 *
 * @author tulip
 */
@Tag(name = "用户端票务查询")
@Slf4j
@Validated
@RestController
@RequestMapping("/ticket")
public class TicketController {

    @Resource
    private ITicketService ticketService;

    @Resource
    private ISeatService seatService;

    @Operation(summary = "查询演出票种列表", description = "获取指定演出的所有票种，含秒杀信息（含Redis缓存）")
    @GetMapping("/list/{showId}")
    public Result<?> queryTicketOfShow(
            @NotNull(message = "演出ID不能为空")
            @Min(value = 1, message = "演出ID必须大于0")
            @Parameter(description = "演出ID", required = true)
            @PathVariable("showId") Long showId) {

        log.debug("[Controller] 查询演出票种列表，showId:{}", showId);
        return ticketService.queryTicketOfShow(showId);
    }

    @Operation(summary = "刷新票种缓存", description = "立即刷新指定演出的票种缓存（Write-Through模式），用户端立即可见最新数据")
    @DeleteMapping("/cache/{showId}")
    public Result<?> refreshTicketCache(
            @NotNull(message = "演出ID不能为空")
            @Min(value = 1, message = "演出ID必须大于0")
            @Parameter(description = "演出ID", required = true)
            @PathVariable("showId") Long showId) {

        log.info("[Controller] 刷新票种缓存（Write-Through），showId:{}", showId);
        ticketService.refreshTicketCache(showId);
        return Result.ok();
    }

    @Operation(summary = "查询座位列表", description = "获取指定票种的可用座位，用于选座界面展示")
    @GetMapping("/{id}/seats")
    public Result<?> querySeats(
            @NotNull(message = "票种ID不能为空")
            @Min(value = 1, message = "票种ID必须大于0")
            @Parameter(description = "票种ID", required = true)
            @PathVariable("id") Long ticketId) {

        log.debug("[Controller] 查询座位列表，ticketId:{}", ticketId);
        return seatService.querySeatsByTicket(ticketId);
    }

    @Operation(summary = "锁定座位", description = "用户选择座位后锁定，防止被其他人购买，需登录态")
    @PostMapping("/{id}/seat/lock/{seatId}")
    public Result<?> lockSeat(
            @NotNull(message = "票种ID不能为空")
            @Min(value = 1, message = "票种ID必须大于0")
            @Parameter(description = "票种ID", required = true)
            @PathVariable("id") Long ticketId,

            @NotNull(message = "座位ID不能为空")
            @Min(value = 1, message = "座位ID必须大于0")
            @Parameter(description = "座位ID", required = true)
            @PathVariable("seatId") Long seatId) {

        log.info("[Controller] 锁定座位，ticketId:{}, seatId:{}", ticketId, seatId);
        return seatService.lockSeat(ticketId, seatId);
    }

    @Operation(summary = "释放座位", description = "取消选座时释放锁定的座位，供其他用户购买")
    @PostMapping("/{id}/seat/release/{seatId}")
    public Result<?> releaseSeat(
            @NotNull(message = "票种ID不能为空")
            @Min(value = 1, message = "票种ID必须大于0")
            @Parameter(description = "票种ID", required = true)
            @PathVariable("id") Long ticketId,

            @NotNull(message = "座位ID不能为空")
            @Min(value = 1, message = "座位ID必须大于0")
            @Parameter(description = "座位ID", required = true)
            @PathVariable("seatId") Long seatId) {

        log.info("[Controller] 释放座位，ticketId:{}, seatId:{}", ticketId, seatId);
        return seatService.releaseSeat(ticketId, seatId);
    }

    @Operation(summary = "查询票种状态")
    @GetMapping("/{id}/status")
    public Result<?> checkTicketStatus(
            @NotNull(message = "票种ID不能为空")
            @Min(value = 1, message = "票种ID必须大于0")
            @Parameter(description = "票种ID", required = true)
            @PathVariable("id") Long ticketId) {

        log.debug("[Controller] 查询票种状态，ticketId:{}", ticketId);
        return ticketService.checkTicketStatus(ticketId);
    }

    @Operation(summary = "检查秒杀票是否已购买")
    @GetMapping("/seckill/check-purchased/{ticketId}")
    public Result<?> checkSeckillPurchased(
            @NotNull(message = "票种ID不能为空")
            @Min(value = 1, message = "票种ID必须大于0")
            @Parameter(description = "秒杀票ID", required = true)
            @PathVariable("ticketId") Long ticketId) {

        log.info("[Controller] 检查秒杀票重复购买 - ticketId:{}, userId:{}", ticketId, UserHolder.getUser() != null ? UserHolder.getUser().getId() : null);
        return ticketService.checkSeckillPurchased(ticketId);
    }

    @Operation(summary = "查询票种详情")
    @GetMapping("/detail/{id}")
    public Result<?> getTicketDetail(
            @NotNull(message = "票种ID不能为空")
            @Min(value = 1, message = "票种ID必须大于0")
            @Parameter(description = "票种ID", required = true)
            @PathVariable("id") Long ticketId) {

        log.debug("[Controller] 查询票种详情，ticketId:{}", ticketId);
        return ticketService.getTicketDetail(ticketId);
    }

    @Operation(summary = "批量释放座位（页面关闭时调用）", description = "用户关闭选座页/浏览器时，通过 sendBeacon 批量释放已锁定的座位，防止座位永久占用")
    @PostMapping("/batch-release")
    public Result<?> batchReleaseSeats(@RequestBody java.util.Map<String, Object> params) {
        try {
            Integer ticketId = (Integer) params.get("ticketId");
            @SuppressWarnings("unchecked")
            java.util.List<Integer> seatIds = (java.util.List<Integer>) params.get("seatIds");

            if (ticketId == null || seatIds == null || seatIds.isEmpty()) {
                return Result.fail(AppMessages.PARAM_ERROR);
            }

            log.info("[Controller] [批量释放] 收到批量释放请求 - ticketId:{}, seatIds:{}, 数量:{}",
                    ticketId, seatIds, seatIds.size());

            int successCount = 0;
            int failCount = 0;

            for (Integer seatId : seatIds) {
                try {
                    Result<?> result = seatService.releaseSeat(ticketId.longValue(), seatId.longValue());
                    if (result != null && result.isSuccess()) {
                        successCount++;
                    } else {
                        failCount++;
                        log.warn("[Controller] [批量释放] 座位释放失败 - ticketId:{}, seatId:{}, 原因:{}",
                                ticketId, seatId, result != null ? result.getMsg() : "未知错误");
                    }
                } catch (Exception e) {
                    failCount++;
                    log.error("[Controller] [批量释放] 座位释放异常 - ticketId:{}, seatId:{}, error:{}",
                            ticketId, seatId, e.getMessage(), e);
                }
            }

            log.info("[Controller] [批量释放] 完成 - ticketId:{}, 成功:{}, 失败:{}",
                    ticketId, successCount, failCount);

            return Result.ok(java.util.Map.of(
                    "successCount", successCount,
                    "failCount", failCount,
                    "total", seatIds.size()
            ));
        } catch (Exception e) {
            log.error("[Controller] [批量释放] 处理异常", e);
            return Result.fail(AppMessages.OPERATION_FAIL);
        }
    }
}
