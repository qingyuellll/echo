package com.echo.utils;

/**
 * 业务常量集中管理类
 *
 * @author tulip
 */
public class BusinessConstants {

    /** 私有构造函数，防止实例化 */
    private BusinessConstants() {
        throw new UnsupportedOperationException("BusinessConstants是工具类，不允许实例化");
    }

    // ==================== 签到模块常量 ====================

    /** 今日已签到的提示消息 */
    public static final String SIGN_ALREADY_TODAY = "今日已签到";

    /** 签到成功的提示消息(包含奖励积分数) */
    public static final String SIGN_SUCCESS = "签到成功，获得5积分";

    // ==================== 积分模块常量 ====================

    /** 签到奖励的积分流水描述 */
    public static final String POINTS_SIGN_IN = "每日签到奖励";

    /** 发布评测奖励的积分流水描述 */
    public static final String POINTS_REVIEW = "发布评测奖励";

    /** 发表评论奖励的积分流水描述 */
    public static final String POINTS_COMMENT = "发表评论奖励";

    /** 点赞他人评测的奖励描述 */
    public static final String POINTS_LIKE_REWARD = "点赞他人评测奖励";

    /** 积分抽奖消耗的描述 */
    public static final String POINTS_LOTTERY_COST = "积分抽奖消耗";

    /** 抽中一等奖的奖励描述 */
    public static final String POINTS_LOTTERY_WIN_1 = "抽中一等奖，获得200积分！";

    /** 抽中二等奖的奖励描述 */
    public static final String POINTS_LOTTERY_WIN_2 = "抽中二等奖，获得100积分！";

    /** 抽中三等奖的奖励描述 */
    public static final String POINTS_LOTTERY_WIN_3 = "抽中三等奖，获得50积分！";

    /** 抽奖参与奖(保底奖)的奖励描述 */
    public static final String POINTS_LOTTERY_CONSOLE = "参与奖，获得10积分！";

    // ------------------- 2.2 积分变动数值 -------------------

    /** 每日签到奖励积分数(+5分) */
    public static final int POINTS_SIGN_IN_REWARD = 5;

    /** 发布完整评测奖励积分数(+3分) */
    public static final int POINTS_REVIEW_REWARD = 3;

    /** 发表评论奖励积分数(+2分) */
    public static final int POINTS_COMMENT_REWARD = 2;

    /** 点赞他人评测奖励积分数(+1分,同一评测仅限一次) */
    public static final int POINTS_LIKE_REWARD_VALUE = 1;

    /** 支付订单成功后的积分奖励(+10分) */
    public static final int POINTS_PAYMENT_REWARD_VALUE = 10;

    /** 订单退款时的信誉分惩罚(-5分,防止恶意下单) */
    public static final int POINTS_REFUND_PENALTY_VALUE = 5;

    /** 参与积分抽奖的消耗积分数(-20分) */
    public static final int POINTS_LOTTERY_COST_VALUE = 20;

    // =====================================================================
    //                       三、通知模块常量
    // =====================================================================

    // ------------------- 3.1 支付相关通知 -------------------

    /** 支付成功通知标题 */
    public static final String NOTIFICATION_PAY_SUCCESS_TITLE = "支付成功";

    /** 支付成功通知内容前缀(需拼接动态核销码) */
    public static final String NOTIFICATION_PAY_SUCCESS_CONTENT = "您的订单已支付成功，核销码：";

    // ------------------- 3.2 退款相关通知 -------------------

    /** 退款成功通知标题 */
    public static final String NOTIFICATION_REFUND_SUCCESS_TITLE = "退款成功";

    /** 退款成功通知内容 */
    public static final String NOTIFICATION_REFUND_SUCCESS_CONTENT = "您的订单已退款成功";

    // ------------------- 3.3 核销相关通知 -------------------

    /** 核销成功通知标题 */
    public static final String NOTIFICATION_VERIFICATION_SUCCESS_TITLE = "核销成功";

    /** 核销成功通知内容 */
    public static final String NOTIFICATION_VERIFICATION_SUCCESS_CONTENT = "您的票已核销，欢迎入场！";

    // ------------------- 3.4 演出开售提醒通知 -------------------

    /** 演出开售提醒通知标题 */
    public static final String NOTIFICATION_SHOW_ONLINE_TITLE = "演出开售提醒";

    /** 演出开售提醒通知内容前缀(需拼接演出名称) */
    public static final String NOTIFICATION_SHOW_ONLINE_CONTENT_PREFIX = "你关注的演出「";

    /** 演出开售提醒通知内容后缀 */
    public static final String NOTIFICATION_SHOW_ONLINE_CONTENT_SUFFIX = "」已开始售票！";

    // ------------------- 3.5 关注用户动态通知 -------------------

    /** 关注用户发布新内容的通知标题 */
    public static final String NOTIFICATION_FOLLOW_REVIEW_TITLE = "关注动态";

    /** 关注用户动态通知内容前缀(需拼接用户名) */
    public static final String NOTIFICATION_FOLLOW_REVIEW_CONTENT_PREFIX = "你关注的用户「";

    /** 关注用户动态通知内容中间部分(连接用户名和笔记标题) */
    public static final String NOTIFICATION_FOLLOW_REVIEW_CONTENT_MID = "」发布了新笔记：";

    // ==================== 静态初始化块：常量值校验（必须在所有字段定义之后）====================

    static {
        validatePositive(POINTS_SIGN_IN_REWARD, "POINTS_SIGN_IN_REWARD");
        validatePositive(POINTS_REVIEW_REWARD, "POINTS_REVIEW_REWARD");
        validatePositive(POINTS_COMMENT_REWARD, "POINTS_COMMENT_REWARD");
        validatePositive(POINTS_LIKE_REWARD_VALUE, "POINTS_LIKE_REWARD_VALUE");
        validatePositive(POINTS_PAYMENT_REWARD_VALUE, "POINTS_PAYMENT_REWARD_VALUE");

        validatePositive(POINTS_REFUND_PENALTY_VALUE, "POINTS_REFUND_PENALTY_VALUE");
        validatePositive(POINTS_LOTTERY_COST_VALUE, "POINTS_LOTTERY_COST_VALUE");

        if (POINTS_LOTTERY_WIN_1.length() == 0 ||
            POINTS_LOTTERY_WIN_2.length() == 0 ||
            POINTS_LOTTERY_WIN_3.length() == 0) {
            throw new IllegalStateException("抽奖奖励配置错误：不能为空字符串");
        }
    }

    /**
     * 校验正整数常量
     *
     * @param value 常量值
     * @param name  常量名称（用于错误提示）
     */
    private static void validatePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException(name + " 必须为正数，当前值：" + value);
        }
    }
}
