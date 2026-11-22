package com.echo.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 预订单状态枚举
 *
 * @author tulip
 */
@Getter
@AllArgsConstructor
public enum PreOrderStatus {

    PENDING_PAYMENT(0, "待支付"),
    CANCELLED(4, "已取消");

    private final int code;
    private final String description;

    public static PreOrderStatus fromCode(Integer code) {
        if (code == null) return null;
        for (PreOrderStatus s : values()) {
            if (s.getCode() == code) return s;
        }
        return null;
    }

    public static String descriptionOf(Integer code) {
        PreOrderStatus s = fromCode(code);
        return s != null ? s.description : ("未知状态(" + code + ")");
    }
}
