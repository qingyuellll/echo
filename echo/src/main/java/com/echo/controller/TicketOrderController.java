package com.echo.controller;

import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.dto.TicketOrderCreateDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.echo.service.ITicketOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import java.util.List;


/**
 * 票务订单管理控制器
 *
 * 提供订单创建、支付、取消、退款、查询等完整订单生命周期管理接口
 *
 * @author tulip
 */
@Tag(name = "票务订单管理")
@Slf4j
@Validated
@RestController
@RequestMapping("/ticket-order")
public class TicketOrderController {

    @Resource
    private ITicketOrderService ticketOrderService;

    @Operation(summary = "秒杀下单")
    @PostMapping("/seckill/{id}")
    public Result<?> seckillTicket(
            @NotNull(message = "票种ID不能为空")
            @Min(value = 1, message = "票种ID必须大于0")
            @Parameter(description = "秒杀票种ID", required = true)
            @PathVariable("id") Long ticketId,

            @RequestParam(value = "seatIds", required = false)
            @Parameter(description = "座位ID列表（选座票必填）")
            List<Long> seatIds) {

        log.info("[Controller] 秒杀下单请求，ticketId:{}, seatIds:{}", ticketId, seatIds);
        return ticketOrderService.seckillTicket(ticketId, seatIds);
    }

    @Operation(summary = "查询秒杀排队状态")
    @GetMapping("/seckill/queue/status/{id}")
    public Result<?> seckillQueueStatus(
            @NotNull(message = "票种ID不能为空")
            @Min(value = 1, message = "票种ID必须大于0")
            @Parameter(description = "秒杀票种ID", required = true)
            @PathVariable("id") Long ticketId) {

        return ticketOrderService.getSeckillQueueStatus(ticketId);
    }

    @Operation(summary = "普通下单")
    @PostMapping("/{id:\\d+}")
    public Result<?> createTicketOrder(
            @NotNull(message = "票种ID不能为空")
            @Min(value = 1, message = "票种ID必须大于0")
            @Parameter(description = "票种ID", required = true)
            @PathVariable("id") Long ticketId,

            @Min(value = 1, message = "购票数量至少为1张")
            @Max(value = 10, message = "单次最多购买10张")
            @Parameter(description = "购票数量（可选，默认1张）")
            @RequestParam(value = "count", required = false) Integer quantity) {

        log.info("[Controller] 普通下单请求，ticketId:{}, quantity:{}", ticketId, quantity);
        return ticketOrderService.createTicketOrder(ticketId, quantity);
    }

    @Operation(summary = "选座下单")
    @PostMapping(value = "/{id:\\d+}/seats", consumes = "application/json")
    public Result<?> createSeatOrder(
            @Min(value = 1, message = "票种ID必须大于0")
            @Parameter(description = "票种ID", required = true)
            @PathVariable("id") Long ticketId,

            @RequestBody
            @Valid
            TicketOrderCreateDTO orderDTO) {

        log.info("[Controller] 选座下单请求，ticketId:{}, seatIds:{}", ticketId, orderDTO.getSeatIds());
        return ticketOrderService.createSeatOrder(ticketId, orderDTO.getSeatIds(), orderDTO.getCount());
    }

    @Operation(summary = "模拟支付", description = "支持积分抵扣(10积分=1元，最多抵扣50%订单金额)，支付成功后生成8位核销码并赠送积分(实付金额的10%)")
    @PostMapping("/pay/{id}")
    public Result<?> payOrder(
            @NotBlank(message = "预订单ID不能为空")
            @Size(min = 10, max = 64, message = "预订单ID长度必须在10-64之间")
            @Parameter(description = "预订单临时ID", required = true)
            @PathVariable("id") String tempId,

            @Min(value = 0, message = "使用积分不能为负数")
            @Max(value = 10000, message = "使用积分不能超过10000")
            @Parameter(description = "使用的积分数（可选，10积分=1元）")
            @RequestParam(value = "usePoints", required = false, defaultValue = "0") Integer usePoints) {

        log.info("[Controller] 支付请求，tempId:{}, usePoints:{}", tempId, usePoints);
        return ticketOrderService.payOrder(tempId, usePoints);
    }

    @Operation(summary = "查询订单详情", description = "根据订单ID查询订单详细信息，支持数字ID和TEMP_前缀的预订单ID")
    @GetMapping("/{id}")
    public Result<?> getOrderById(
            @Parameter(description = "订单ID（数字ID或TEMP_前缀的临时ID）", required = true)
            @PathVariable("id") String orderId) {

        log.debug("[Controller] 查询订单详情，orderId:{}", orderId);
        return ticketOrderService.getOrderById(orderId);
    }

    @Operation(summary = "我的订单列表", description = "分页查询当前用户的订单，可按订单状态筛选（0待支付/1待使用/2已支付/4已取消/5已退款），不传status则查询所有订单")
    @GetMapping("/my")
    public Result<?> queryMyOrders(
            @Parameter(description = "订单状态（可选，不传则查询所有）")
            @RequestParam(value = "status", required = false) Integer status,

            @Min(value = 1, message = "页码必须大于0")
            @Max(value = 100, message = "页码不能超过100")
            @Parameter(description = "页码", example = "1")
            @RequestParam(value = "current", defaultValue = "1") Integer current,

            @Min(value = 1, message = "每页数量必须大于0")
            @Max(value = 200, message = "每页数量不能超过200")
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(value = "size", defaultValue = "10") Integer size) {

        log.debug("[Controller] 查询我的订单，status:{}, current:{}, size:{}", status, current, size);
        return ticketOrderService.queryMyOrders(status, current, size);
    }

    @Operation(summary = "用户主动取消订单", description = "用户在未支付且未超时时可主动取消预订单，释放库存。接受预订单tempId或正式订单ID")
    @PostMapping("/cancel/{id}")
    public Result<?> cancelOrder(
            @NotBlank(message = "订单ID不能为空")
            @Parameter(description = "预订单tempId或正式订单ID", required = true)
            @PathVariable("id") String orderId) {

        log.info("[Controller] 用户取消订单请求，orderId:{}", orderId);
        return ticketOrderService.cancelOrder(orderId);
    }

    @Operation(summary = "申请退款", description = "释放库存、恢复座位状态、扣除信誉分(5分)。仅已支付订单可申请退款")
    @PostMapping("/refund/{id}")
    public Result<?> refundOrder(
            @NotNull(message = "订单ID不能为空")
            @Min(value = 1, message = "订单ID必须大于0")
            @Parameter(description = "订单ID", required = true)
            @PathVariable("id") Long orderId) {

        log.info("[Controller] 退款请求，orderId:{}", orderId);
        return ticketOrderService.refundOrder(orderId);
    }

    @Operation(summary = "核销入场", description = "输入8位大写字母数字核销码进行现场验票。仅管理员（含超级管理员和普通管理员）可操作。核销码有效期30天，已核销订单不可重复核销")
    @PostMapping("/verify/{code}")
    public Result<?> verifyOrder(
            @NotBlank(message = "核销码不能为空")
            @Parameter(description = "8位核销码（大写字母数字）", required = true)
            @PathVariable("code") String verificationCode) {

        log.info("[Controller] 核销请求，verificationCode:{}",
                verificationCode != null && !verificationCode.isEmpty() ?
                        (verificationCode.length() > 8 ? verificationCode.substring(0, 8) + "***" : verificationCode) : "");

        //权限校验：仅管理员可执行核销操作
        //核销操作仅允许管理员执行（统一使用 UserHolder.getAdmin() 校验）
        if (com.echo.utils.UserHolder.getAdmin() == null) {
            log.warn("[Controller-核销拒绝] 非管理员用户尝试核销操作");
            return com.echo.dto.Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        log.info("[Controller-核销权限通过] 管理员执行核销操作, 核销码:{}",
                verificationCode.length() > 8 ? verificationCode.substring(0, 8) + "***" : verificationCode);

        return ticketOrderService.verifyOrder(verificationCode);
    }

    @Operation(summary = "检查单张票核销状态", description = "检查指定订单的某张票是否已被核销，用于管理端显示核销进度")
    @GetMapping("/verify/status/{orderId}/{ticketIndex}")
    public Result<?> checkVerificationStatus(
            @NotNull(message = "订单ID不能为空")
            @Parameter(description = "订单ID", required = true)
            @PathVariable("orderId") Long orderId,

            @Min(value = 0, message = "票索引不能小于0")
            @Parameter(description = "票索引（从0开始）", required = true)
            @PathVariable("ticketIndex") Integer ticketIndex) {

        log.info("[Controller] 检查核销状态，orderId:{}, ticketIndex:{}", orderId, ticketIndex);
        return ticketOrderService.checkVerificationStatus(orderId, ticketIndex);
    }

    @Operation(summary = "批量检查秒杀票购买状态", description = "批量检查当前用户已购买的秒杀票，用于前端UI禁用已购票种。返回已购买的秒杀票ID列表")
    @PostMapping("/seckill/check-batch")
    public Result<?> checkSeckillPurchasedBatch(
            @org.springframework.web.bind.annotation.RequestBody java.util.List<Long> ticketIds) {

        log.info("[Controller] 批量检查秒杀票购买状态，ticketIds:{}", ticketIds);
        return ticketOrderService.checkSeckillPurchasedBatch(ticketIds);
    }
}
