package com.echo.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 票种锁定状态枚举
 *
 * @author tulip
 */
@Getter
@AllArgsConstructor
public enum TicketLock {

    UNLOCKED(0, "正常可编辑"),
    LOCKED_ORPHAN(1, "孤儿票种，禁止上架");

    private final int code;
    private final String description;

    public static boolean isLocked(Integer code) {
        return code != null && code == LOCKED_ORPHAN.getCode();
    }
}
