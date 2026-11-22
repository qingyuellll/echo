package com.echo.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 票种类型枚举
 *
 * @author tulip
 */
@Getter
@AllArgsConstructor
public enum TicketType {

    /**
     * 普通票（常规销售票种）
     */
    NORMAL(0, "普通票"),

    /**
     * 秒杀票（限时限量特价票）
     */
    SECKILL(1, "秒杀票");

    private final int code;
    private final String description;

    /**
     * 根据code获取枚举实例
     *
     * @param code 类型编码
     * @return 对应的枚举实例，如果不匹配返回null
     */
    public static TicketType fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (TicketType type : values()) {
            if (type.getCode() == code) {
                return type;
            }
        }
        return null;
    }

    /**
     * 判断是否为秒杀票
     *
     * @param code 类型编码
     * @return true=秒杀票, false=非秒杀票或未知类型
     */
    public static boolean isSeckill(Integer code) {
        return SECKILL.equals(fromCode(code));
    }
}
