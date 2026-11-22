package com.echo.utils;

import cn.hutool.core.util.StrUtil;

/**
 * 正则表达式工具类
 *
 * @author tulip
 */
public class RegexUtils {

    /**
     * 是否是无效手机格式
     *
     * @param phone 要校验的手机号
     * @return true: 无效（为空或不符合格式）, false: 有效
     */
    public static boolean isPhoneInvalid(String phone) {
        return mismatch(phone, RegexPatterns.PHONE_REGEX);
    }

    /**
     * 是否是无效邮箱格式
     *
     * @param email 要校验的邮箱
     * @return true: 无效（为空或不符合格式）, false: 有效
     */
    public static boolean isEmailInvalid(String email) {
        return mismatch(email, RegexPatterns.EMAIL_REGEX);
    }

    /**
     * 是否是无效简单密码格式（4-32位字母数字下划线）
     *
     * @param password 要校验的密码
     * @return true: 无效（为空或不符合格式）, false: 有效
     * @see RegexPatterns#PASSWORD_REGEX
     */
    public static boolean isPasswordInvalid(String password) {
        return mismatch(password, RegexPatterns.PASSWORD_REGEX);
    }

    /**
     * 是否是无效强密码格式（8-32位，需含大小写+数字+特殊字符）
     * <p>
     * <b>适用场景：</b>金融系统、支付平台、管理员账户等高安全场景
     *
     * @param password 要校验的密码
     * @return true: 无效（为空或不符合格式）, false: 有效
     * @see RegexPatterns#STRONG_PASSWORD_REGEX
     */
    public static boolean isStrongPasswordInvalid(String password) {
        return mismatch(password, RegexPatterns.STRONG_PASSWORD_REGEX);
    }

    /**
     * 是否是无效验证码格式（6位字母数字）
     *
     * @param code 要校验的验证码
     * @return true: 无效（为空或不符合格式）, false: 有效
     */
    public static boolean isCodeInvalid(String code) {
        return mismatch(code, RegexPatterns.VERIFY_CODE_REGEX);
    }

    /**
     * 是否是无效用户名格式（4-16位，支持中文，不能纯数字）
     *
     * @param username 要校验的用户名
     * @return true: 无效（为空或不符合格式）, false: 有效
     * @see RegexPatterns#USERNAME_REGEX
     */
    public static boolean isUsernameInvalid(String username) {
        return mismatch(username, RegexPatterns.USERNAME_REGEX);
    }

    /**
     * 是否是无效昵称格式（2-20位，支持中文和空格）
     *
     * @param nickname 要校验的昵称
     * @return true: 无效（为空或不符合格式）, false: 有效
     * @see RegexPatterns#NICKNAME_REGEX
     */
    public static boolean isNicknameInvalid(String nickname) {
        return mismatch(nickname, RegexPatterns.NICKNAME_REGEX);
    }

    /**
     * 是否是无效身份证号格式（18位中国大陆身份证）
     * <p>
     * <b>注意：</b>此方法仅验证格式，不验证校验码算法正确性。
     *
     * @param idCard 要校验的身份证号
     * @return true: 无效（为空或不符合格式）, false: 有效
     * @see RegexPatterns#ID_CARD_REGEX
     */
    public static boolean isIdCardInvalid(String idCard) {
        return mismatch(idCard, RegexPatterns.ID_CARD_REGEX);
    }

    /**
     * 校验字符串是否不符合指定的正则格式
     *
     * @param str   要校验的字符串
     * @param regex 正则表达式
     * @return true: 不符合格式（包括字符串为空的情况）, false: 符合格式
     */
    private static boolean mismatch(String str, String regex) {
        if (StrUtil.isBlank(str)) {
            return true;
        }
        return !str.matches(regex);
    }
}
