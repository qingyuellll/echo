package com.echo.constants;

/**
 * 用户模块消息常量
 * <p>
 * 包含：用户CRUD/关注/管理员/角色等级等相关消息
 */
public final class UserMessages {

    private UserMessages() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    // 基础用户操作
    public static final String NOT_EXIST = "用户不存在";
    public static final String LIST_GET_SUCCESS = "用户列表获取成功";
    public static final String DETAIL_GET_SUCCESS = "用户详情获取成功";
    public static final String DISABLE_SUCCESS = "用户禁用成功";
    public static final String ENABLE_SUCCESS = "用户启用成功";
    public static final String CREDIT_UPDATE_SUCCESS = "信誉分更新成功";
    public static final String ACCOUNT_DISABLED = "账号已被禁用";
    public static final String OPERATION_FAIL_RETRY = "{0}失败，请稍后重试";
    public static final String QUERY_FAIL_RETRY = "查询失败，请稍后重试";
    public static final String SIGN_FAIL_RETRY = "签到失败，请稍后重试";

    // 关注相关
    public static final String FOLLOW_USER_ID_INVALID = "无效的用户ID";
    public static final String FOLLOW_TYPE_EMPTY = "操作类型不能为空";
    public static final String FOLLOW_STATUS_QUERY_FAIL = "查询关注状态失败";
    public static final String FOLLOW_COUNT_QUERY_FAIL = "查询关注数失败";
    public static final String FOLLOW_CANNOT_QUERY_SELF = "不能查询与自己的共同关注";
    public static final String FOLLOW_COMMON_QUERY_FAIL = "查询共同关注失败";

    // 管理员相关
    public static final String ADMIN_SUPER_ADMIN_NOT_EXIST = "超级管理员账号不存在";
    public static final String ADMIN_USER_ALREADY_ADMIN = "该用户已是管理员";
    public static final String ADMIN_USER_NOT_ADMIN = "该用户不是管理员";
    public static final String ADMIN_NOT_EXIST = "管理员不存在";
    public static final String ADMIN_CANNOT_DISABLE_ADMIN = "不能禁用管理员账号，请先在员工管理中处理";
    public static final String ADMIN_CANNOT_OPERATE_SELF = "不能对自己的账号执行此操作";
    public static final String ADMIN_CANNOT_DISABLE_SELF = "不能禁用自己的账号";

    // 查询相关
    public static final String QUERY_RATE_LIMIT = "请求过于频繁，请稍后再试";
    public static final String QUERY_FAIL = "查询用户失败";
    public static final String INFO_GET_FAIL = "获取用户信息失败";

    // ==================== 字段校验相关 ====================

    /** 城市名称 */
    public static final String CITY_TOO_LONG = "城市名称不能超过50个字符";

    /** 个人简介 */
    public static final String INTRODUCE_TOO_LONG = "个人简介不能超过200个字符";
}
