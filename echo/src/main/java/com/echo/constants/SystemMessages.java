package com.echo.constants;

/**
 * 系统通用消息常量
 * <p>
 * 包含：座位/文件上传/通知/缓存/积分/异常处理/批量操作/运行时异常等通用消息
 */
public final class SystemMessages {

    private SystemMessages() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    // ==================== 座位模块 (Seat) ====================
    public static final String SEAT_CREATE_SUCCESS = "座位创建成功";
    public static final String SEAT_BATCH_CREATE_SUCCESS = "批量创建座位成功";
    public static final String SEAT_DELETE_SUCCESS = "座位删除成功";
    public static final String SEAT_LOCK_SUCCESS = "座位锁定成功";
    public static final String SEAT_UNLOCK_SUCCESS = "座位解锁成功";
    public static final String SEAT_AREA_CONFIG_EMPTY = "区域配置不能为空";
    public static final String SEAT_NOT_FOUND = "座位不存在";
    public static final String SEAT_LOCKED = "座位已被锁定";
    public static final String SEAT_SOLD = "座位已售出";
    public static final String SEAT_LOCK_FAIL = "锁座失败";
    public static final String SEAT_NOT_LOCKED = "座位未被锁定，无法释放";

    // 座位动态参数消息模板
    public static final String SEAT_SOLD_BY_OTHERS_WITH_ID = "座位已被他人购买，请重新选择 (seatId:{0})";
    public static final String SEAT_NOT_EXIST_WITH_ID = "座位不存在 (seatId:{0})";
    public static final String SEAT_STATUS_UNKNOWN_WITH_ID = "座位状态未知，数据库可能未初始化 (seatId:{0})";
    public static final String SEAT_STATUS_DESC_WITH_ID = "{0} (seatId:{1})";
    public static final String SEAT_NOT_BELONG_TO_ORDER_WITH_ID = "座位不属于当前预订单，可能已被抢占 (seatId:{0})";
    public static final String SEAT_OPTIMISTIC_LOCK_FAIL_WITH_ID = "乐观锁冲突，座位已被他人购买 (seatId:{0})";
    public static final String SEAT_STATUS_INVALID_WITH_CODE = "座位状态异常({0})";
    public static final String SEAT_LOCK_FAIL_PREFIX = "座位锁定失败: ";
    public static final String DATABASE_QUERY_FAIL_PREFIX = "数据库查询失败: ";

    // ==================== 文件上传模块 (Upload) ====================
    public static final String UPLOAD_FILE_EMPTY = "文件不能为空";
    public static final String UPLOAD_FILE_SIZE_EXCEED = "文件大小不能超过{0}";
    public static final String UPLOAD_FILE_FORMAT_INVALID = "仅支持jpg/png/gif/webp格式的图片";
    public static final String UPLOAD_FILENAME_EMPTY = "原始文件名不能为空";
    public static final String UPLOAD_FILENAME_INVALID = "文件名包含非法字符";
    public static final String UPLOAD_AVATAR_SIZE_EXCEED = "头像图片不能超过5MB";
    public static final String UPLOAD_FILENAME_CANNOT_EMPTY = "文件名不能为空";
    public static final String UPLOAD_PATH_INVALID = "非法文件路径";
    public static final String UPLOAD_PATH_PARSE_FAIL = "文件路径解析失败";
    public static final String UPLOAD_FILE_NOT_EXIST = "文件不存在";
    public static final String UPLOAD_FILE_DELETE_FAIL = "文件删除失败";
    public static final String UPLOAD_DELETE_FILE_FAIL_PREFIX = "删除文件失败：";
    public static final String FILE_UPLOAD_FAIL = "文件上传失败";
    public static final String INVALID_FILENAME = "错误的文件名称";

    // ==================== 通知模块 (Notification) ====================
    public static final String NOTIFY_SEND_SUCCESS = "通知发送成功";
    public static final String NOTIFY_SEND_FAIL = "通知发送失败";
    public static final String NOTIFICATION_NOT_FOUND = "通知不存在";
    public static final String NOTIFICATION_NOT_BELONG = "通知不属于当前用户";
    public static final String NOTIFICATION_ID_INVALID = "无效的通知ID";
    public static final String NOTIFICATION_LIST_QUERY_FAIL = "查询通知列表失败";
    public static final String NOTIFICATION_MARK_READ_FAIL = "标记已读失败";
    public static final String NOTIFICATION_MARK_ALL_READ_FAIL = "标记全部已读失败";

    // ==================== 统计数据模块 (Statistics) ====================
    public static final String STATISTICS_GET_SUCCESS = "统计数据获取成功";
    public static final String STATISTICS_GET_FAIL = "统计数据获取失败";
    public static final String STATISTICS_HISTORY_QUERY_FAIL = "查询历史记录失败";
    public static final String STATISTICS_HOT_SHOW_QUERY_FAIL = "查询热门演出失败：";
    public static final String STATISTICS_SALES_TREND_QUERY_FAIL = "查询销售趋势失败：";
    public static final String STATISTICS_USER_PROFILE_QUERY_FAIL = "查询用户画像失败：";

    // ==================== 缓存模块 (Cache) ====================
    public static final String CACHE_CLEAR_FAIL_PREFIX = "清除缓存失败：";
    public static final String CACHE_VIEW_FAIL_PREFIX = "查看缓存失败：";
    public static final String SHOW_CACHE_CLEAR_SUCCESS = "成功清除 {0} 个演出缓存";
    public static final String SHOW_CACHE_NOT_FOUND = "没有找到演出缓存";
    public static final String SHOW_GEO_LOAD_SUCCESS = "成功加载 {0} 个演出坐标";
    public static final String USER_CACHE_NONE = "该用户无缓存";

    // ==================== 积分模块 (Points) ====================
    public static final String POINTS_NOT_ENOUGH = "积分不足";
    public static final String POINTS_LOTTERY_FAIL = "抽奖失败";
    public static final String POINTS_LOTTERY_LIMIT = "每日抽奖次数已用完，明天再来吧";
    public static final String POINTS_COUPON_EXCHANGE_FAIL = "兑换优惠券失败：";
    public static final String POINTS_ADD_FAIL = "添加积分记录失败";
    public static final String POINTS_UPDATE_FAIL = "更新积分失败";
    public static final String POINTS_DEDUCT_FAIL = "扣减积分失败";
    public static final String POINTS_REWARD_FAIL = "发放奖励失败";
    public static final String POINTS_QUERY_FAIL = "查询积分失败";
    public static final String POINTS_RECORD_QUERY_FAIL = "查询积分记录失败";
    public static final String POINTS_LOTTERY_RECORD_QUERY_FAIL = "查询抽奖记录失败";
    public static final String POINTS_COUPON_SENT_RESULT = "优惠券已发放，剩余积分:{0}";

    // ==================== 操作回滚模块 (Rollback) ====================
    public static final String ROLLBACK_FAIL_PREFIX = "回滚失败: ";
    public static final String ROLLBACK_LOG_NOT_EXIST = "操作记录不存在";
    public static final String ROLLBACK_TIMEOUT_24H = "该操作已超过24小时，无法回滚";
    public static final String ROLLBACK_TARGET_IDS_EMPTY = "目标ID列表为空或格式错误";
    public static final String ROLLBACK_UNSUPPORTED_OPERATION = "不支持该操作类型的回滚：";

    // ==================== 操作日志模块 (Operation Log) ====================
    public static final String OPERATION_LOG_QUERY_FAIL = "查询操作日志失败";
    public static final String OPERATION_LOG_EXPORT_IN_DEVELOPMENT = "导出功能开发中，敬请期待";

    // ==================== 通用消息 (General) ====================
    public static final String OPERATION_SUCCESS = "操作成功";
    public static final String OPERATION_FAIL = "操作失败";
    public static final String PARAM_ERROR = "参数错误";
    public static final String SYSTEM_BUSY = "系统繁忙，请稍后再试";
    public static final String PHONE_FORMAT_ERROR = "手机号格式错误";
    public static final String CREDIT_LOW = "信誉分过低，无法下单";

    // 控制器层专用
    public static final String CONTROLLER_REVIEW_PUBLISH_FAIL_PREFIX = "发布评测失败：";
    public static final String CONTROLLER_QUERY_FAIL_PREFIX = "查询失败：";
    public static final String ADMIN_DATA_REPAIR_FAIL_PREFIX = "数据修复失败：";
    public static final String ADMIN_DATA_REPAIR_SUCCESS = "数据修复成功";

    // ==================== 异常处理模块 (Exception Handler) ====================
    public static final String EXCEPTION_SERVER_ERROR = "服务器异常";
    public static final String EXCEPTION_REQUEST_PARAM_FORMAT_ERROR = "请求参数格式错误";
    public static final String EXCEPTION_PARAM_VALIDATION_FAIL = "参数校验失败";
    public static final String EXCEPTION_MISSING_REQUIRED_PARAM_PREFIX = "缺少必要参数: ";

    // ==================== 批量操作模块 (Batch Operation) ====================
    public static final String BATCH_OPERATION_LIMIT_50 = "单次操作数量不能超过50个";
    public static final String BATCH_OPERATION_LIMIT_100 = "单次操作数量不能超过100个";
    public static final String BATCH_OPERATION_LIMIT_30 = "单次操作数量不能超过30个";
    public static final String ADMIN_DISABLE_USER_REASON_REQUIRED = "禁用用户必须提供原因";

    // ==================== 管理端票种特殊消息 ====================
    public static final String ADMIN_TICKET_SYSTEM_ERROR_VERIFY_FAIL = "系统错误：无法验证订单数据";
    public static final String ADMIN_TICKET_CANNOT_MODIFY_ONLINE = "修改失败：该票种已上架\n\n原因：为了保障用户购买体验和数据一致性，已上架的票种禁止修改\n\n解决方案：\n1. 如需停止销售：请先【下架】该票种（如无已售订单）\n2. 如需调整信息：下架后再编辑并重新上架\n3. 如需紧急修改：请联系系统管理员";
    public static final String ADMIN_TICKET_ONLINE_SHOW_NOT_EXIST_PREFIX = "[FAIL] 上架失败：该票种关联的演出(";
    public static final String ADMIN_TICKET_ONLINE_SHOW_NOT_EXIST_SUFFIX = ")不存在或已删除！\n原因：演出已被删除，此票种已成为'孤儿票种'\n建议：请删除此票种或将其关联到其他有效演出";
    public static final String ADMIN_TICKET_LOCKED_PREFIX = "[NO_ENTRY] 操作失败：该票种已被锁定（原因：原演出可能已删除）\n解决方案：请先将此票种关联到其他有效演出，或直接删除此票种";
    public static final String ADMIN_TICKET_DELETE_HAS_SALES_PREFIX = "[FAIL] 删除失败：该票种已有 ";
    public static final String ADMIN_TICKET_DELETE_HAS_SALES_SUFFIX = " 个已售订单！\n原因：为了保障用户权益和交易记录完整性，已有销售记录的票种不可删除\n\n建议方案：\n• 如需停止销售：请将票种【下架】\n• 如需处理退款：请在订单管理中操作\n• 如确实需要删除：请联系系统管理员处理数据迁移";
    public static final String ADMIN_TICKET_DELETE_VERIFY_FAIL = "系统错误：无法验证订单数据，为防止误删，本次操作已终止";
    public static final String ADMIN_SECKILL_TICKET_NOT_FOUND = "秒杀配置不存在";

    // ==================== 运行时异常消息 (Runtime Exception) ====================
    public static final String RUNTIME_TICKET_STOCK_RELEASE_FAIL = "普通票库存释放失败，ticketId: {0}";
    public static final String RUNTIME_DISTRIBUTED_LOCK_FAIL = "分布式锁获取失败，等待重试";
    public static final String RUNTIME_ORDER_CREATE_FAIL = "订单创建失败";
    public static final String RUNTIME_SEAT_GENERATE_FAIL_PREFIX = "座位生成失败: ";
    public static final String RUNTIME_SEAT_GENERATE_FAIL_ROLLBACK_PREFIX = "座位生成失败，已回滚所有操作: ";
    public static final String RUNTIME_TARGET_SHOW_NOT_EXIST = "[FAIL] 操作失败：目标演出(ID:{0})不存在！请选择一个有效的演出";

    // ==================== Entity字段校验（操作日志）====================

    /** 操作类型 */
    public static final String OPERATION_TYPE_NOT_EMPTY = "操作类型不能为空";
    public static final String OPERATION_TYPE_TOO_LONG = "操作类型长度不能超过50";

    /** 目标类型 */
    public static final String TARGET_TYPE_TOO_LONG = "目标类型长度不能超过50";

    /** 结果状态 */
    public static final String RESULT_STATUS_NOT_EMPTY = "结果状态不能为空";

    /** 数量统计 */
    public static final String SUCCESS_COUNT_MIN = "成功数量不能小于0";
    public static final String FAIL_COUNT_MIN = "失败数量不能小于0";

    /** 错误信息 */
    public static final String ERROR_MESSAGE_TOO_LONG = "错误信息长度不能超过2000";

    /** 请求信息 */
    public static final String IP_ADDRESS_TOO_LONG = "IP地址长度不能超过50";
    public static final String USER_AGENT_TOO_LONG = "用户代理长度不能超过500";
    public static final String REQUEST_ID_TOO_LONG = "请求ID长度不能超过32";

    /** 耗时 */
    public static final String DURATION_NEGATIVE = "耗时不能为负数";

    // ==================== Entity字段校验（管理员）====================

    /** 用户ID */
    public static final String ADMIN_USER_ID_NOT_EMPTY = "用户ID不能为空";

    /** 管理员姓名 */
    public static final String ADMIN_NAME_NOT_EMPTY = "管理员姓名不能为空";

    // ==================== Entity字段校验（演出类型）====================

    /** 类型名称 */
    public static final String SHOW_TYPE_NAME_NOT_EMPTY = "类型名称不能为空";
    public static final String SHOW_TYPE_NAME_TOO_LONG = "类型名称长度不能超过50个字符";

    /** 图标URL */
    public static final String SHOW_TYPE_ICON_TOO_LONG = "图标URL长度不能超过200个字符";

    /** 排序权重 */
    public static final String SHOW_TYPE_SORT_NEGATIVE = "排序权重不能为负数";
    public static final String SHOW_TYPE_SORT_MAX = "排序权重超出范围";

    // ==================== Entity字段校验（评测/评论）====================

    /** 评测标题 */
    public static final String REVIEW_TITLE_TOO_LONG = "标题长度不能超过100个字符";

    /** 评测图片 */
    public static final String REVIEW_IMAGES_TOO_LONG = "图片列表JSON长度不能超过2000个字符";

    /** 点赞/评论数量 */
    public static final String COUNT_NEGATIVE = "数量不能为负数";

    /** 评论内容 */
    public static final String COMMENT_CONTENT_TOO_LONG = "评论内容不能超过1000字";

    /** 父评论ID */
    public static final String PARENT_COMMENT_ID_NEGATIVE = "父评论ID不能为负数";

    /** 评论状态 */
    public static final String COMMENT_STATUS_NOT_EMPTY = "评论状态不能为空";
}
