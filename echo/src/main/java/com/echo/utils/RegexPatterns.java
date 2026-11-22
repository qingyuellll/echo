package com.echo.utils;

/**
 * 正则表达式常量定义类
 *
 * @author tulip
 */
public abstract class RegexPatterns {

    // ==================== 联系方式相关 ====================

    /** 中国大陆手机号(11位) */
    public static final String PHONE_REGEX = "^1([38][0-9]|4[579]|5[0-3,5-9]|6[6]|7[0135678]|9[89])\\d{8}$";

    /** 电子邮箱地址 */
    public static final String EMAIL_REGEX = "^[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$";

    // ==================== 密码相关 ====================

    /** 简单密码(4-32位字母数字下划线) */
    public static final String PASSWORD_REGEX = "^\\w{4,32}$";

    /** 强密码(8-32位,需包含大小写+数字+特殊字符) */
    public static final String STRONG_PASSWORD_REGEX = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@#$%^&*!]).{8,32}$";

    // ==================== 验证码相关 ====================

    /** 验证码(6位字母数字) */
    public static final String VERIFY_CODE_REGEX = "^[a-zA-Z\\d]{6}$";

    // ==================== 用户信息相关 ====================

    /** 用户名(4-16位,中英文数字下划线,不能纯数字) */
    public static final String USERNAME_REGEX = "^(?![0-9]+$)[a-zA-Z0-9_\\u4e00-\\u9fa5]{4,16}$";

    /** 昵称(2-20位,中英文数字下划线空格) */
    public static final String NICKNAME_REGEX = "^[a-zA-Z0-9_\\u4e00-\\u9fa5\\s]{2,20}$";

    /** 中国大陆身份证号(18位) */
    public static final String ID_CARD_REGEX = "^[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]$";

}
