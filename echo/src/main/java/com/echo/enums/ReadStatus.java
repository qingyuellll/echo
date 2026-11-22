package com.echo.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ReadStatus {

    UNREAD(0, "未读"),
    READ(1, "已读");

    /** 必须标注 @EnumValue（同 NotificationType 说明），否则通知落库抛 TypeException */
    @EnumValue
    private final int code;
    private final String description;

    public static ReadStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (ReadStatus status : values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        return null;
    }

    public static String descriptionOf(Integer code) {
        ReadStatus status = fromCode(code);
        return status != null ? status.getDescription() : ("未知状态(" + code + ")");
    }

    public boolean isUnread() {
        return this == UNREAD;
    }
}
