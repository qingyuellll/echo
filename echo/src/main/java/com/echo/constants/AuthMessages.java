package com.echo.constants;

/**
 * 认证模块消息常量
 * <p>
 * 包含：登录/登出/验证码/密码/权限等相关消息
 */
public final class AuthMessages {

    private AuthMessages() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    // 基础认证
    public static final String PHONE_PASSWORD_EMPTY = "手机号和密码不能为空";
    public static final String PHONE_REQUIRED = "手机号不能为空";
    public static final String CREDENTIAL_REQUIRED = "请提供密码或验证码";
    public static final String ACCOUNT_OR_PASSWORD_ERROR = "账号或密码错误";
    public static final String NOT_LOGGED_IN = "未登录";
    public static final String LOGIN_SUCCESS = "登录成功";
    public static final String LOGOUT_SUCCESS = "登出成功";

    // 验证码相关
    public static final String CODE_SEND_SUCCESS = "验证码发送成功";
    public static final String CODE_SEND_FAIL = "验证码发送失败";
    public static final String CODE_INVALID = "验证码无效或已过期";
    public static final String CODE_INCORRECT = "验证码错误";
    public static final String CODE_SEND_FREQUENT = "验证码发送过于频繁，请{0}分钟后再试";
    public static final String CODE_SEND_FREQUENT_SIMPLE = "验证码发送过于频繁，请稍后再试";
    public static final String CODE_SEND_FAIL_RETRY = "验证码发送失败，请稍后重试";
    public static final String LOGIN_FAIL_RETRY = "登录失败，请稍后重试";

    // 密码相关
    public static final String PASSWORD_CHANGE_SUCCESS = "密码修改成功";
    public static final String PASSWORD_CHANGE_FAIL = "密码修改失败";
    public static final String PASSWORD_CODE_SEND_SUCCESS = "密码重置验证码已发送";
    public static final String PASSWORD_OLD_ERROR = "旧密码不正确";
    public static final String PASSWORD_SAME_AS_OLD = "新密码不能与旧密码相同";
    public static final String PASSWORD_NOT_SET = "用户尚未设置密码，请先设置密码";
    public static final String PASSWORD_EMPTY = "密码不能为空";
    public static final String PASSWORD_MIN_LENGTH = "密码长度不能少于6位";
    public static final String PASSWORD_MAX_LENGTH = "密码长度不能超过20位";
    public static final String PASSWORD_UPDATE_FAIL_PREFIX = "密码更新失败：";
    public static final String PASSWORD_OLD_REQUIRED = "请输入旧密码";
    public static final String PASSWORD_CHANGE_FAIL_RETRY = "密码修改失败，请稍后重试";

    // 权限相关
    public static final String PERMISSION_SUPER_ADMIN_ONLY = "仅超级管理员可执行此操作";
    public static final String PERMISSION_ADMIN_REQUIRED = "无管理员权限";
    public static final String PERMISSION_USER_LOGIN_REQUIRED = "请先登录";
    public static final String ADMIN_LOGIN_REQUIRED = "请先登录管理员账号";
    public static final String ADMIN_BOOTSTRAP_DISABLED = "引导通道未开启，请先配置 ADMIN_BOOTSTRAP_TOKEN";
    public static final String ADMIN_BOOTSTRAP_TOKEN_INVALID = "引导令牌不正确";

    // 角色等级
    public static final int ROLE_LEVEL_SUPER_ADMIN = 0;
    public static final int ROLE_LEVEL_NORMAL_ADMIN = 1;

    // ==================== 字段校验相关 ====================

    /** 手机号格式 */
    public static final String PHONE_FORMAT_INVALID = "手机号格式不正确";

    /** 密码长度 */
    public static final String PASSWORD_LENGTH_INVALID = "密码长度应为6-20位";
}
