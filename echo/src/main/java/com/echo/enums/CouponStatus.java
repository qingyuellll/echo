package com.echo.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CouponStatus {

    UNUSED(0, "未使用"),
    USED(1, "已使用"),
    EXPIRED(2, "已过期");

    private final int code;
    private final String description;

    public static CouponStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (CouponStatus status : values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        return null;
    }

    public static String descriptionOf(Integer code) {
        CouponStatus status = fromCode(code);
        return status != null ? status.getDescription() : ("未知状态(" + code + ")");
    }

    public boolean isAvailable() {
        return this == UNUSED;
    }
}
