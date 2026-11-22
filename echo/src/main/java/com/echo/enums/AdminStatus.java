package com.echo.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 管理员账号状态
 * <p>
 * 对外契约统一使用数字码（0=正常，1=禁用），与数据库存储值、前端状态判断保持一致。
 */
public enum AdminStatus {

    NORMAL(0, "正常"),
    DISABLED(1, "禁用");

    /** 实体映射的存储值。必须标注 @EnumValue（同 AdminRoleLevel 说明）。 */
    @EnumValue
    private final int code;

    private final String description;

    AdminStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 序列化为数字码，前端据此做状态判断
     */
    @JsonValue
    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static AdminStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (AdminStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }

    public static String descriptionOf(Integer code) {
        AdminStatus status = fromCode(code);
        return status != null ? status.description : ("未知状态(" + code + ")");
    }

    public boolean isEnabled() {
        return this == NORMAL;
    }

    /**
     * 反序列化入口
     * <p>
     * 接受数字码（{@code 0}）与枚举名（{@code NORMAL}）两种写法，
     * 数字码是当前契约，枚举名用于兼容历史调用方。
     *
     * @param raw JSON 原始值
     * @return 对应枚举值；入参为空时返回 null
     * @throws IllegalArgumentException 入参无法识别
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AdminStatus fromJson(Object raw) {
        if (raw == null) {
            return null;
        }

        if (raw instanceof Number) {
            AdminStatus status = fromCode(((Number) raw).intValue());
            if (status == null) {
                throw new IllegalArgumentException("未知的管理员状态: " + raw);
            }
            return status;
        }

        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return null;
        }

        for (AdminStatus status : values()) {
            if (status.name().equalsIgnoreCase(text)) {
                return status;
            }
        }

        try {
            AdminStatus status = fromCode(Integer.valueOf(text));
            if (status != null) {
                return status;
            }
        } catch (NumberFormatException ignored) {
            // 非数字，落到下方统一抛错
        }

        throw new IllegalArgumentException("未知的管理员状态: " + raw);
    }
}
