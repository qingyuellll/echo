package com.echo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 订单状态数据传输对象
 *
 * @author tulip
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "订单状态DTO")
public class OrderStatusDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 状态码
     */
    @Schema(description = "状态码", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer code;

    /**
     * 状态名称（中文）
     */
    @Schema(description = "状态名称", example = "预占", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    /**
     * 状态描述（详细说明）
     */
    @Schema(description = "状态描述", example = "订单已创建，库存已预占，等待用户支付（5分钟内有效）")
    private String description;

    /**
     * CSS类名（前端样式）
     */
    @Schema(description = "CSS类名", example = "status-pending")
    private String cssClass;

    /**
     * 是否允许支付
     */
    @Schema(description = "是否允许支付", example = "true")
    private boolean payable;

    /**
     * 是否允许取消
     */
    @Schema(description = "是否允许取消", example = "true")
    private boolean cancellable;

    /**
     * 是否允许退款
     */
    @Schema(description = "是否允许退款", example = "false")
    private boolean refundable;

    /**
     * 是否已完成交易（用于限购判断）
     */
    @Schema(description = "是否已完成交易（限购用）", example = "false")
    private boolean completedTransaction;

    // ==================== 工厂方法 ====================

    public static OrderStatusDTO RESERVED() {
        return OrderStatusDTO.builder()
                .code(0)
                .name("预占")
                .description("订单已创建，库存已预占，等待用户支付（5分钟内有效）")
                .cssClass("status-pending")
                .payable(true)
                .cancellable(true)
                .refundable(false)
                .completedTransaction(false)
                .build();
    }

    public static OrderStatusDTO PAID() {
        return OrderStatusDTO.builder()
                .code(2)
                .name("已支付")
                .description("支付成功，等待核销入场")
                .cssClass("status-paid")
                .payable(false)
                .cancellable(false)
                .refundable(true)
                .completedTransaction(true)
                .build();
    }

    public static OrderStatusDTO USED() {
        return OrderStatusDTO.builder()
                .code(3)
                .name("已核销")
                .description("已现场验票入场，订单完成")
                .cssClass("status-used")
                .payable(false)
                .cancellable(false)
                .refundable(false)
                .completedTransaction(true)
                .build();
    }

    public static OrderStatusDTO CANCELLED() {
        return OrderStatusDTO.builder()
                .code(4)
                .name("已取消")
                .description("订单已取消，库存已释放")
                .cssClass("status-cancelled")
                .payable(false)
                .cancellable(false)
                .refundable(false)
                .completedTransaction(false)
                .build();
    }

    public static OrderStatusDTO REFUNDED() {
        return OrderStatusDTO.builder()
                .code(5)
                .name("已退款")
                .description("退款成功，款项将原路返回")
                .cssClass("status-refunded")
                .payable(false)
                .cancellable(false)
                .refundable(false)
                .completedTransaction(false)
                .build();
    }

    public static OrderStatusDTO REFUND_PENDING() {
        return OrderStatusDTO.builder()
                .code(6)
                .name("申请退款中")
                .description("等待管理员审核退款申请")
                .cssClass("status-warning")
                .payable(false)
                .cancellable(false)
                .refundable(false)
                .completedTransaction(true)
                .build();
    }

    public static OrderStatusDTO REJECTED() {
        return OrderStatusDTO.builder()
                .code(7)
                .name("已拒绝")
                .description("退款申请已被管理员拒绝，订单恢复正常状态")
                .cssClass("status-rejected")
                .payable(false)
                .cancellable(false)
                .refundable(true)
                .completedTransaction(true)
                .build();
    }
}
