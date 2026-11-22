package com.echo.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 票种状态枚举
 *
 * @author tulip
 */
@Getter
@AllArgsConstructor
public enum TicketStatus {

    /**
     * 下架状态（不可购买）
     */
    OFFLINE(0, "下架"),

    /**
     * 上架状态（可购买）
     */
    ONLINE(1, "上架");

    private final int code;
    private final String description;

    /**
     * 根据code获取枚举实例
     *
     * @param code 状态编码
     * @return 对应的枚举实例，如果不匹配返回null
     */
    public static TicketStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (TicketStatus status : values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        return null;
    }

    /**
     * 判断是否为上架状态
     *
     * @param code 状态编码
     * @return true=已上架, false=未上架或未知状态
     */
    public static boolean isOnline(Integer code) {
        return ONLINE.equals(fromCode(code));
    }

    /**
     * 判断是否为下架状态
     *
     * @param code 状态编码
     * @return true=已下架, false=未下架或未知状态
     */
    public static boolean isOffline(Integer code) {
        return OFFLINE.equals(fromCode(code));
    }
}
