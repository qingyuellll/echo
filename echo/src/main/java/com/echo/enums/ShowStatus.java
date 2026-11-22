package com.echo.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 演出状态枚举
 *
 * @author tulip
 */
@Getter
@AllArgsConstructor
public enum ShowStatus {

    OFFLINE(0, "下架"),
    ONLINE(1, "上架"),
    FINISHED(2, "已结束");

    private final int code;
    private final String description;

    public static ShowStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (ShowStatus status : values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        return null;
    }

    public static boolean isOnline(Integer code) {
        return ONLINE.equals(fromCode(code));
    }

    public static boolean isOffline(Integer code) {
        return OFFLINE.equals(fromCode(code));
    }

    public static boolean isFinished(Integer code) {
        return FINISHED.equals(fromCode(code));
    }

    public static String descriptionOf(Integer code) {
        ShowStatus status = fromCode(code);
        return status != null ? status.description : ("未知状态(" + code + ")");
    }
}
