package com.echo.constants;

/**
 * 评测评论模块消息常量
 * <p>
 * 包含：演出评测/评论/收藏/关注等相关消息
 */
public final class ReviewMessages {

    private ReviewMessages() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    // ==================== 评测相关 ====================
    public static final String ID_INVALID = "无效的评测ID";
    public static final String NOT_EXIST = "演出笔记不存在";
    public static final String TITLE_NOT_EMPTY = "标题不能为空";
    public static final String CONTENT_NOT_EMPTY = "内容不能为空";
    public static final String SAVE_ERROR = "演出笔记保存失败";
    public static final String SCORE_INVALID = "评分必须在1-5星之间";
    public static final String CONTENT_TOO_LONG = "评测内容不能超过1000字";
    public static final String ORDER_ALREADY_REVIEWED = "您已对该订单评价过";
    public static final String SHOW_ALREADY_REVIEWED = "您已对该演出评价过";
    public static final String SUBMIT_FAIL = "评测提交失败";
    public static final String PARAM_EMPTY = "参数不能为空";
    public static final String NO_PERMISSION_DELETE = "无权删除他人评测";
    public static final String DELETE_FAIL = "删除失败";
    public static final String UPDATE_FAIL = "更新失败";
    public static final String STATS_REFRESH_FAIL = "刷新统计数据失败";

    // 补充
    public static final String INFO_EMPTY = "评测信息不能为空";
    public static final String TITLE_REQUIRED = "请填写评测标题";
    public static final String SHOW_REQUIRED = "请选择关联的演出";

    // ==================== 评论相关 ====================
    public static final String COMMENT_QUERY_FAIL = "查询评论失败";
    public static final String COMMENT_INFO_EMPTY = "评论信息不能为空";
    public static final String COMMENT_CONTENT_EMPTY = "评论内容不能为空";
    public static final String COMMENT_CONTENT_TOO_LONG = "评论内容不能超过{0}字";
    public static final String COMMENT_TOO_FREQUENT = "评论过于频繁，请{0}秒后再试";
    public static final String COMMENT_SAVE_FAIL = "保存评论失败";
    public static final String COMMENT_PUBLISH_FAIL_PREFIX = "发布评论失败：";

    // ==================== 收藏关注 ====================
    public static final String FAVORITE_ADD_FAIL = "收藏失败";
    public static final String FAVORITE_CANCEL_FAIL = "取消收藏失败";
    public static final String FOLLOW_ALREADY_EXISTS = "已关注该用户";
    public static final String FOLLOW_NOT_EXISTS = "未关注该用户";
    public static final String FOLLOW_SELF = "不能关注自己";
    public static final String FOLLOW_FAIL = "关注操作失败";
    public static final String SHOW_FOLLOW_ALREADY_EXISTS = "已关注该演出";
    public static final String SHOW_FOLLOW_NOT_EXISTS = "未关注该演出";
}
