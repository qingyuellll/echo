package com.echo.constants;

/**
 * 票种模块消息常量
 * <p>
 * 包含：票种CRUD/库存/秒杀/座位/状态等相关消息
 */
public final class TicketMessages {

    private TicketMessages() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    // 基础操作
    public static final String TITLE_EMPTY = "票种标题不能为空";
    public static final String SHOW_ID_REQUIRED = "请选择关联的演出";
    public static final String NOT_EXIST = "票种不存在";
    public static final String CREATE_SUCCESS = "票种创建成功";
    public static final String CREATE_FAIL = "票种创建失败，请稍后重试";
    public static final String UPDATE_SUCCESS = "票种更新成功";
    public static final String UPDATE_FAIL = "票种更新失败，请稍后重试";
    public static final String DELETE_SUCCESS = "票种删除成功";
    public static final String DELETE_FAIL = "票种删除失败，请稍后重试";
    public static final String DELETE_ACTIVE_FORBIDDEN = "无法删除：该票种正在售卖中\n请先下架该票种后再删除（如有未完成订单需先处理）";
    public static final String ONLINE_SUCCESS = "票种已成功上架";
    public static final String OFFLINE_SUCCESS = "票种已成功下架";
    public static final String ALREADY_ONLINE = "该票种已经处于上架状态，无需重复操作";
    public static final String ALREADY_OFFLINE = "该票种已经处于下架状态，无需重复操作";
    public static final String STATUS_CHANGE_FAIL = "票种状态变更失败，请稍后重试";

    // 库存相关
    public static final String STOCK_ADJUST_SUCCESS = "库存调整成功：{0} → {1}";
    public static final String STOCK_ADJUST_FAIL = "库存调整失败，请稍后重试";
    public static final String STOCK_INVALID = "请输入有效的库存数量";
    public static final String STOCK_MUST_GREATER_THAN_ZERO = "库存必须大于0";
    public static final String STOCK_MAX_INVALID = "库存数量超出限制";

    // 错误前缀
    public static final String CREATE_FAIL_PREFIX = "创建票种失败：";
    public static final String UPDATE_FAIL_PREFIX = "更新票种失败：";
    public static final String SAVE_FAIL = "保存票种失败";
    public static final String QUERY_FAIL = "查询票种失败";

    // 状态检查
    public static final String LOCKED_CANNOT_ONLINE = "该票种所属演出已删除，请先更改所属演出后再上架";
    public static final String RELATED_NOT_EXIST = "关联票种不存在";
    public static final String ID_INVALID = "票种ID无效";
    public static final String CHECK_PURCHASE_FAIL = "检查购买记录失败";
    public static final String TYPE_INVALID = "票种类型无效";
    public static final String INFO_EMPTY = "票种信息不能为空";
    public static final String SEAT_AREA_REQUIRED = "需要选座的票种必须提供座位区域配置";

    // 秒杀相关
    public static final String SECKILL_NOT_EXIST = "秒杀票不存在";
    public static final String SECKILL_CREATE_SUCCESS = "秒杀票创建成功";
    public static final String SECKILL_UPDATE_SUCCESS = "秒杀票更新成功";
    public static final String SECKILL_SAVE_FAIL = "保存秒杀配置失败";
    public static final String SECKILL_CREATE_FAIL_PREFIX = "新增秒杀票失败：";
    public static final String SECKILL_TIME_REQUIRED = "秒杀票必须设置开始时间和结束时间";
    public static final String SECKILL_TIME_INVALID = "秒杀开始时间必须早于结束时间";
    public static final String STATUS_QUERY_FAIL = "查询票种状态失败";
    public static final String DETAIL_QUERY_FAIL = "查询票种详情失败";

    // 重要业务逻辑消息（多行）
    public static final String OFFLINE_HAS_ORDERS =
            """
            下架失败：该票种已有 {0} 个已售订单

            原因：为了保障已购票用户的权益，有销售记录的票种禁止下架

            建议方案：
            • 让活动自然结束（设置结束时间）
            • 如需紧急处理：联系系统管理员""";

    public static final String ONLINE_SHOW_NOT_EXIST =
            """
            [FAIL] 上架失败：该票种关联的演出({0})不存在或已删除！
            请先将票种转移到有效的演出后再上架""";

    public static final String LOCKED_BY_DELETED_SHOW =
            """
            操作失败：该票种已被锁定（原因：原演出可能已删除）
            解决方案：请勿操作此票种，联系系统管理员处理数据一致性""";

    public static final String DELETE_HAS_ORDERS =
            """
            [FAIL] 删除失败：该票种已有 {0} 个已售订单！
            原因：有购买记录的票种禁止删除，以保护用户权益和历史数据

            可选操作：
            1. 【下架】票种（如无已售订单）
            2. 联系系统管理员进行数据归档""";

    public static final String NOT_SECKILL = "非秒杀票";

    // ==================== 字段校验相关（座位/区域配置）====================

    /** 区域配置列表 */
    public static final String AREAS_NOT_EMPTY = "区域配置不能为空";
    public static final String AREAS_MAX_SIZE = "区域数量不能超过10个";

    /** 区域名称 */
    public static final String AREA_NAME_NOT_EMPTY = "区域名称不能为空";
    public static final String AREA_NAME_TOO_LONG = "区域名称最长20个字符";

    /** 排数 */
    public static final String ROWS_NOT_EMPTY = "排数不能为空";
    public static final String ROWS_MIN = "排数至少为1";
    public static final String ROWS_MAX = "排数不能超过50";

    /** 每排座位数 */
    public static final String COLS_NOT_EMPTY = "每排座位数不能为空";
    public static final String COLS_MIN = "每排座位数至少为1";
    public static final String COLS_MAX = "每排座位数不能超过100";

    /** 起始排号 */
    public static final String START_ROW_NOT_EMPTY = "起始排号不能为空";
    public static final String START_ROW_MIN = "起始排号不能小于0";

    // ==================== Entity字段校验相关（票种/秒杀票/座位/评价）====================

    /** 票种 */
    public static final String TICKET_SHOW_ID_NOT_EMPTY = "演出ID不能为空";
    public static final String TICKET_TITLE_NOT_EMPTY = "票种标题不能为空";
    public static final String TICKET_PAY_VALUE_NEGATIVE = "支付金额不能为负数";
    public static final String TICKET_ACTUAL_VALUE_NEGATIVE = "实际价值不能为负数";

    /** 秒杀票 */
    public static final String SECKILL_TICKET_ID_NOT_EMPTY = "票种ID不能为空";
    public static final String SECKILL_STOCK_NEGATIVE = "秒杀库存不能为负数";
    public static final String SECKILL_STOCK_MAX = "单次秒杀库存不能超过100000";
    public static final String SECKILL_BEGIN_TIME_NOT_EMPTY = "秒杀开始时间不能为空";
    public static final String SECKILL_END_TIME_NOT_EMPTY = "秒杀结束时间不能为空";

    /** 座位 */
    public static final String SEAT_TICKET_ID_NOT_EMPTY = "票种ID不能为空";
    public static final String AREA_NAME_TOO_LONG_20 = "区域名称长度不能超过20";
    public static final String ROW_NUM_NOT_EMPTY = "排号不能为空";
    public static final String ROW_NUM_MIN = "排号必须大于0";
    public static final String ROW_NUM_MAX = "排号不能超过100";
    public static final String COL_NUM_NOT_EMPTY = "座号不能为空";
    public static final String COL_NUM_MIN = "座号必须大于0";
    public static final String COL_NUM_MAX = "座号不能超过100";
    public static final String SEAT_STATUS_NOT_EMPTY = "座位状态不能为空";
    public static final String SEAT_STATUS_MIN = "状态值最小为0";
    public static final String SEAT_STATUS_MAX = "状态值最大为2";
    public static final String PRE_ORDER_ID_TOO_LONG = "预订单ID长度不能超过64";

    /** 评价/评测 */
    public static final String REVIEW_SHOW_ID_NOT_EMPTY = "演出ID不能为空";

    // ==================== Entity字段校验（票种核心字段）====================

    /** 票种类型 */
    public static final String TICKET_TYPE_NOT_EMPTY = "票种类型不能为空";
    public static final String TICKET_TYPE_INVALID = "票种类型无效";

    /** 是否需要选座 */
    public static final String TICKET_HAS_SEATS_NOT_EMPTY = "是否需要选座不能为空";

    /** 锁定标记 */
    public static final String TICKET_LOCKED_INVALID = "锁定标记无效";

    /** 库存 */
    public static final String TICKET_STOCK_NOT_EMPTY = "库存不能为空";
    public static final String TICKET_STOCK_NEGATIVE = "库存不能为负数";

    /** 总库存 */
    public static final String TOTAL_STOCK_NOT_EMPTY = "总库存不能为空";
    public static final String TOTAL_STOCK_MUST_GREATER_THAN_ZERO = "总库存必须大于0";

    /** 状态 */
    public static final String TICKET_STATUS_NOT_EMPTY = "状态不能为空";
    public static final String TICKET_STATUS_INVALID = "状态无效";

    // ==================== Entity字段校验（评价/评测/点赞）====================

    /** 评测相关 */
    public static final String REVIEW_USER_ID_NOT_EMPTY = "用户ID不能为空";
    public static final String REVIEW_ORDER_ID_NOT_EMPTY = "订单ID不能为空";
    public static final String REVIEW_SCORE_NOT_EMPTY = "评分不能为空";
    public static final String REVIEW_SCORE_MIN = "评分最小为1星";
    public static final String REVIEW_SCORE_MAX = "评分最大为5星";
    public static final String REVIEW_CONTENT_TOO_LONG = "评价内容不能超过1000字";
    public static final String REVIEW_IMAGES_TOO_LONG = "图片JSON不能超过2000字符";
    public static final String REVIEW_STATUS_NOT_EMPTY = "状态不能为空";
    public static final String REVIEW_STATUS_MIN = "状态值最小为0";
    public static final String REVIEW_STATUS_MAX = "状态值最大为2";
    public static final String LIKE_COUNT_NEGATIVE = "点赞数不能为负数";

    /** 点赞记录 */
    public static final String REVIEW_LIKE_REVIEW_ID_NOT_EMPTY = "评测ID不能为空";
    public static final String REVIEW_LIKE_USER_ID_NOT_EMPTY = "点赞用户ID不能为空";

    /** 积分记录 */
    public static final String POINTS_USER_ID_NOT_EMPTY = "用户ID不能为空";
    public static final String POINTS_VALUE_NOT_EMPTY = "积分变动数量不能为空";
    public static final String POINTS_VALUE_MIN = "积分变动数量不能小于-99999";
    public static final String POINTS_VALUE_MAX = "积分变动数量不能大于99999";
    public static final String POINTS_TYPE_NOT_EMPTY = "积分变动类型不能为空";
    public static final String POINTS_TYPE_MIN = "积分类型最小值为1";
    public static final String POINTS_TYPE_MAX = "积分类型最大值为4";
    public static final String POINTS_DESCRIPTION_TOO_LONG = "变动描述长度不能超过200";
}
