package com.echo.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 管理员角色等级
 * <p>
 * 对外契约统一使用数字码（0=超级管理员，1=普通管理员），与数据库存储值、
 * 接口文档、前端权限判断保持一致。
 */
public enum AdminRoleLevel {

    SUPER_ADMIN(0, "超级管理员"),
    NORMAL_ADMIN(1, "普通管理员");

    /**
     * 实体映射的存储值，同时是 JSON 报文的取值。必须标注 @EnumValue：MyBatis-Plus 的
     * MybatisEnumTypeHandler 对既未实现 IEnum 又没有 @EnumValue 的枚举
     * 直接抛 TypeException，导致 tb_admin 查询整体失败（管理员登录 500）。
     */
    @EnumValue
    private final int code;

    private final String description;

    AdminRoleLevel(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 序列化为数字码，前端据此做权限判断
     */
    @JsonValue
    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static AdminRoleLevel fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (AdminRoleLevel level : values()) {
            if (level.code == code) {
                return level;
            }
        }
        return null;
    }

    public static String descriptionOf(Integer code) {
        AdminRoleLevel level = fromCode(code);
        return level != null ? level.description : ("未知角色(" + code + ")");
    }

    /**
     * 反序列化入口
     * <p>
     * 接受数字码（{@code 0}）与枚举名（{@code SUPER_ADMIN}）两种写法，
     * 数字码是当前契约，枚举名用于兼容历史调用方。
     *
     * @param raw JSON 原始值
     * @return 对应枚举值；入参为空时返回 null
     * @throws IllegalArgumentException 入参无法识别
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AdminRoleLevel fromJson(Object raw) {
        if (raw == null) {
            return null;
        }

        if (raw instanceof Number) {
            AdminRoleLevel level = fromCode(((Number) raw).intValue());
            if (level == null) {
                throw new IllegalArgumentException("未知的管理员角色等级: " + raw);
            }
            return level;
        }

        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return null;
        }

        for (AdminRoleLevel level : values()) {
            if (level.name().equalsIgnoreCase(text)) {
                return level;
            }
        }

        try {
            AdminRoleLevel level = fromCode(Integer.valueOf(text));
            if (level != null) {
                return level;
            }
        } catch (NumberFormatException ignored) {
            // 非数字，落到下方统一抛错
        }

        throw new IllegalArgumentException("未知的管理员角色等级: " + raw);
    }
}
