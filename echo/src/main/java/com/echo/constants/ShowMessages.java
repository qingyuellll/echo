package com.echo.constants;

/**
 * 演出模块消息常量
 * <p>
 * 包含：演出CRUD/上下架/评测/关注等相关消息
 */
public final class ShowMessages {

    private ShowMessages() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    // 基础操作
    public static final String NOT_EXIST = "演出不存在";
    public static final String ID_INVALID = "无效的演出ID";
    public static final String ID_NOT_EMPTY = "演出ID不能为空";
    public static final String CREATE_SUCCESS = "演出创建成功";
    public static final String UPDATE_SUCCESS = "演出更新成功";
    public static final String DELETE_SUCCESS = "演出删除成功";
    public static final String DELETE_FAIL = "演出删除失败";
    public static final String DELETE_HAS_ACTIVE_TICKETS = "无法删除：该演出下有{0}个正在售卖的票种（{1}）\n请先下架以下票种后再删除演出";
    public static final String ON_SHELF_SUCCESS = "演出已成功上架";
    public static final String OFF_SHELF_SUCCESS = "演出已成功下架";
    public static final String OFFLINE_SUCCESS = "演出下架成功";
    public static final String ONLINE_SUCCESS = "演出上架成功";
    public static final String TARGET_NOT_EXIST = "目标演出不存在";
    public static final String UPDATE_FAIL = "演出更新失败";
    public static final String NO_DATA = "没有演出数据可加载";

    // 查询相关
    public static final String TYPE_QUERY_FAIL = "查询演出类型失败";
    public static final String REVIEW_SCORE_INVALID = "请选择有效的评分（1-5分）";
    public static final String REVIEW_SEARCH_FAIL = "搜索失败，请稍后重试";
    public static final String REVIEW_QUERY_FAIL = "查询演出评测失败：";

    // 关注相关
    public static final String FOLLOW_FAIL = "关注演出失败：";
    public static final String UNFOLLOW_FAIL = "取消关注失败：";
    public static final String FOLLOW_LIST_QUERY_FAIL = "查询关注列表失败";

    // 补充
    public static final String CREATE_FAIL = "新增演出失败";

    // ==================== Entity字段校验相关 ====================

    /** 演出名称 */
    public static final String NAME_NOT_EMPTY = "演出名称不能为空";
    public static final String NAME_TOO_LONG = "演出名称长度不能超过100";

    /** 演出类型 */
    public static final String TYPE_ID_NOT_EMPTY = "演出类型ID不能为空";

    /** 详细地址 */
    public static final String ADDRESS_NOT_EMPTY = "详细地址不能为空";
    public static final String ADDRESS_TOO_LONG = "详细地址长度不能超过200";

    /** 区域名称 */
    public static final String AREA_TOO_LONG = "区域名称长度不能超过50";

    /** 经纬度坐标 */
    public static final String LONGITUDE_MIN = "经度不能小于-180";
    public static final String LONGITUDE_MAX = "经度不能大于180";
    public static final String LATITUDE_MIN = "纬度不能小于-90";
    public static final String LATITUDE_MAX = "纬度不能大于90";

    /** 票价/数量统计 */
    public static final String AVG_PRICE_NEGATIVE = "平均票价不能为负数";
    public static final String SOLD_NEGATIVE = "已售数量不能为负数";
    public static final String COMMENTS_NEGATIVE = "评论数量不能为负数";

    /** 评分 */
    public static final String SCORE_MIN = "评分最小为1星";
    public static final String SCORE_MAX = "评分最大为5星";

    /** 状态 */
    public static final String STATUS_NOT_EMPTY = "状态不能为空";
    public static final String STATUS_MIN = "状态值最小为0";
    public static final String STATUS_MAX = "状态值最大为2";

    /** 时间字段 */
    public static final String OPEN_TIME_NOT_EMPTY = "开演时间不能为空";
    public static final String END_TIME_NOT_EMPTY = "结束时间不能为空";
    public static final String OPEN_HOURS_TOO_LONG = "演出时间范围长度不能超过50";

    /** 描述/图片 */
    public static final String IMAGES_TOO_LONG = "图片JSON长度不能超过2000";
    public static final String DESCRIPTION_TOO_LONG = "演出描述长度不能超过5000";
}
