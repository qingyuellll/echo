package com.echo.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum OperationResultStatus {

    IN_PROGRESS(0, "进行中"),
    SUCCESS(1, "成功"),
    FAIL(2, "失败"),
    PARTIAL_SUCCESS(3, "部分成功");

    @EnumValue
    private final Integer code;
    private final String description;

    OperationResultStatus(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public static OperationResultStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (OperationResultStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知操作结果状态: " + code);
    }

    public static String descriptionOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (OperationResultStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status.getDescription();
            }
        }
        return null;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isFail() {
        return this == FAIL;
    }

    public boolean isPartialSuccess() {
        return this == PARTIAL_SUCCESS;
    }

    public boolean isInProgress() {
        return this == IN_PROGRESS;
    }
}
