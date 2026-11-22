package com.echo.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum NotificationType {

    PAYMENT_SUCCESS(1, "支付成功"),
    FOLLOW_ACTIVITY(2, "关注动态"),
    REFUND_SUCCESS(3, "退款成功"),
    VERIFICATION_SUCCESS(4, "核销成功"),
    REVIEW_FOLLOW(5, "关注评测"),
    SHOW_ON_SALE(6, "演出开售");

    /** 必须标注 @EnumValue：tb_notification.type 为 int 列，
     * 缺注解时 MybatisEnumTypeHandler 走构造器反射直接抛 TypeException，通知落库失败 */
    @EnumValue
    private final int code;
    private final String description;

    public static NotificationType fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (NotificationType type : values()) {
            if (type.getCode() == code) {
                return type;
            }
        }
        return null;
    }

    public static String descriptionOf(Integer code) {
        NotificationType type = fromCode(code);
        return type != null ? type.getDescription() : ("未知类型(" + code + ")");
    }
}
