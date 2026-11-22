package com.echo.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单状态枚举
 *
 * @author tulip
 */
@Getter
@AllArgsConstructor
public enum OrderStatus {

    RESERVED(0, "预占"),
    PAID(2, "已支付"),
    USED(3, "已核销"),
    CANCELLED(4, "已取消"),
    REFUNDED(5, "已退款"),
    REFUND_PENDING(6, "申请退款中"),
    REFUND_REJECTED(7, "已拒绝");

    private final int code;
    private final String description;

    public static OrderStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (OrderStatus status : values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        return null;
    }

    public static boolean isPayable(Integer code) {
        return code != null && code == RESERVED.getCode();
    }

    public static boolean isCompletedTransaction(Integer code) {
        return code != null && (code == PAID.getCode() || code == USED.getCode() || code == REFUND_PENDING.getCode());
    }

    public static boolean canCancel(Integer code) {
        return code != null && code == RESERVED.getCode();
    }

    public static boolean canRefund(Integer code) {
        return code != null && code == PAID.getCode();
    }

    public static boolean canApplyRefund(Integer code) {
        // 已支付(2)首次申请 + 已拒绝(7)重新申请（对应状态机边 7→6）
        return code != null && (code == PAID.getCode() || code == REFUND_REJECTED.getCode());
    }

    public static boolean isPostPayment(Integer code) {
        return code != null && code >= PAID.getCode();
    }

    public static String descriptionOf(Integer code) {
        OrderStatus status = fromCode(code);
        return status != null ? status.description : ("未知状态(" + code + ")");
    }

    /**
     * 获取已售状态码
     *
     * @return 状态码数组 [2, 3]
     */
    public static int[] getSoldStatusCodes() {
        return new int[]{PAID.getCode(), USED.getCode()};
    }

    /**
     * 获取有效订单状态码
     *
     * @return 状态码数组
     */
    public static int[] getValidStatusCodes() {
        return new int[]{
            RESERVED.getCode(),
            PAID.getCode(),
            USED.getCode(),
            REFUND_PENDING.getCode()
        };
    }
}
