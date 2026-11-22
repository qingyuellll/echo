package com.echo.constants;

/**
 * 订单模块消息常量
 * <p>
 * 包含：订单CRUD/支付/退款/核销/秒杀/座位等相关消息
 */
public final class OrderMessages {

    private OrderMessages() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    // ==================== 订单基础操作 ====================
    public static final String LIST_GET_SUCCESS = "订单列表获取成功";
    public static final String DETAIL_GET_SUCCESS = "订单详情获取成功";
    public static final String CANCEL_SUCCESS = "订单取消成功";
    public static final String PAID_CANNOT_CANCEL = "已支付订单不能取消，如需取消请申请退款";
    public static final String ALREADY_CANCELLED = "订单已取消，无需重复操作";
    public static final String ALREADY_REFUNDED = "订单已退款，无法取消";
    public static final String CANCEL_FAIL = "订单取消失败，请稍后重试";
    public static final String REFUND_SUCCESS = "退款成功";
    public static final String NOT_EXIST = "订单不存在";
    public static final String MAX_PURCHASE_10 = "单次最多购买10张";
    public static final String TICKET_NOT_EXIST = "票种不存在";
    public static final String SECKILL_CHANNEL_REQUIRED = "秒杀票请使用秒杀通道购买";
    public static final String STOCK_INSUFFICIENT = "票种库存不足，当前剩余{0}";
    public static final String STOCK_INSUFFICIENT_CREATE = "库存不足，无法创建订单";
    public static final String NO_PERMISSION = "无权查看此订单";
    public static final String NOT_EXIST_OR_EXPIRED = "订单不存在或已失效，请重新下单";
    public static final String SELECT_AT_LEAST_ONE_SEAT = "请选择至少一个座位";
    public static final String TICKET_OFFLINE = "票种不存在或已下架";

    // 秒杀相关
    public static final String SECKILL_PURCHASED = "您已购买过该秒杀票，每人限购1张";
    public static final String SECKILL_LIMIT_1 = "秒杀票每人限购1张";
    public static final String OPERATION_TOO_FREQUENT = "操作过于频繁，请稍后再试";

    // 座位相关
    public static final String SEAT_COUNT_RANGE = "座位数量必须在1-10之间";
    public static final String SEAT_COUNT_MISMATCH = "座位数量与选择不一致";
    public static final String SEAT_NOT_EXIST = "座位不存在，请刷新页面重试";
    public static final String SEAT_MISMATCH = "座位与票种不匹配";
    public static final String SEAT_RELEASED = "座位已被释放";
    public static final String SEAT_SOLD_BY_OTHERS = "座位已被其他人购买";
    public static final String SEAT_STATUS_INVALID = "座位状态异常";
    public static final String SEAT_LOCK_EXPIRED = "座位锁定已过期，请重新选座";
    public static final String SEAT_UPDATE_FAIL = "座位状态更新失败";

    // 秒杀状态
    public static final String SECKILL_FAIL = "秒杀失败";
    public static final String SECKILL_OVER = "秒杀已经结束";
    public static final String SECKILL_STOCK_NOT_ENOUGH = "库存不足";
    public static final String SECKILL_REPEAT_ORDER = "用户不能重复下单";

    // 票种状态
    public static final String TICKET_OFFLINE_ERROR = "演出已下架，无法购买";
    public static final String TICKET_SECKILL_NOT_STARTED = "秒杀活动尚未开始";
    public static final String TICKET_STATUS_INVALID = "票种状态异常，无法操作";
    public static final String TICKET_SECKILL_NOT_STARTED_ERROR = "秒杀活动尚未开始或已结束";

    // 其他
    public static final String CREATE_FAIL_RETRY = "订单创建失败，请重试";
    public static final String SHOW_ENDED = "演出已结束，无法支付";

    // 核销相关
    public static final String VERIFY_CODE_EMPTY = "核销码不能为空";
    public static final String VERIFY_CODE_FORMAT_ERROR = "核销码格式错误（应为8位大写字母数字）";
    public static final String VERIFY_CODE_DATA_ERROR = "核销码数据异常";
    public static final String VERIFY_CODE_USED = "该核销码已被使用（第{0}次）";
    public static final String VERIFY_STATUS_QUERY_FAIL = "查询核销状态失败";
    public static final String DATA_ERROR = "订单数据异常";
    public static final String CHECK_PURCHASE_STATUS_FAIL = "检查购买状态失败";

    // 购买限制
    public static final String DUPLICATE_PURCHASE = "您已购买过该票种，请勿重复下单";
    public static final String REPEAT_PURCHASE = "您已有该票种的活跃订单，请先处理现有订单后再购买";
    public static final String NOT_BELONG = "订单不属于当前用户";
    public static final String STATUS_UNSUPPORTED = "订单状态不支持此操作";
    public static final String PAID_REPEAT = "订单已支付，请勿重复支付";
    public static final String CANCELLED = "订单已取消";
    public static final String REFUNDED = "订单已退款";
    public static final String PAY_FAIL = "支付失败";
    public static final String REFUND_FAIL = "退款失败";
    public static final String REFUND_NOT_ALLOWED = "当前状态不允许退款";

    // ==================== 字段校验相关 ====================

    /** 购票数量 */
    public static final String COUNT_NOT_EMPTY = "购票数量不能为空";
    public static final String COUNT_MIN = "购票数量至少为1张";
    public static final String COUNT_MAX = "单次最多购买10张";

    /** 座位ID列表 */
    public static final String SEAT_IDS_NOT_EMPTY = "座位ID列表不能为空";
    public static final String SEAT_IDS_MAX_SIZE = "最多选择10个座位";

    /** 批量操作 */
    public static final String IDS_NOT_EMPTY = "操作目标ID列表不能为空";
    public static final String IDS_MAX_SIZE = "单次操作最多支持100个ID";
    public static final String OPERATION_NOT_EMPTY = "操作类型不能为空";

    // ==================== Entity字段校验相关（订单/预订单）====================

    /** 通用ID字段 */
    public static final String USER_ID_NOT_EMPTY = "用户ID不能为空";
    public static final String SHOW_ID_NOT_EMPTY = "演出ID不能为空";
    public static final String ORDER_ID_NOT_EMPTY = "订单ID不能为空";

    /** 预订单特有 */
    public static final String TEMP_ID_NOT_EMPTY = "临时ID不能为空";
    public static final String TEMP_ID_LENGTH_INVALID = "临时ID长度必须在10-50之间";
    public static final String TICKET_ID_NOT_EMPTY = "票种ID不能为空";
    public static final String QUANTITY_NOT_EMPTY = "购买数量不能为空";
    public static final String QUANTITY_MIN = "购买数量至少为1";
    public static final String QUANTITY_MAX = "单次购买数量不能超过100";
    public static final String PAY_VALUE_NOT_EMPTY = "冻结销售单价不能为空";
    public static final String PAY_VALUE_NEGATIVE = "冻结销售单价不能为负数";
    public static final String ACTUAL_VALUE_NOT_EMPTY = "冻结原价不能为空";
    public static final String ACTUAL_VALUE_NEGATIVE = "冻结原价不能为负数";
    public static final String PRE_ORDER_STATUS_NOT_EMPTY = "预订单状态不能为空";
    public static final String PRE_ORDER_STATUS_MIN = "状态值最小为0";
    public static final String PRE_ORDER_STATUS_MAX = "状态值最大为4";
    public static final String CREATE_TIME_NOT_EMPTY = "创建时间不能为空";
    public static final String EXPIRE_TIME_NOT_EMPTY = "过期时间不能为空";
    public static final String EXPIRE_TIME_PAST = "过期时间不能早于当前时间";

    // ==================== 退款审批相关（管理端）====================
    public static final String REFUND_ALREADY_COMPLETED = "该订单已退款完成";
    public static final String REFUND_ORDER_CANCELLED = "该订单已取消，无法退款";
    public static final String REFUND_NO_APPLICATION = "该订单尚未申请退款，无法直接审批";
    public static final String REFUND_APPROVE_SUCCESS = "退款审批成功";
    public static final String REFUND_REJECT_SUCCESS = "退款申请已拒绝";
    public static final String REFUND_BATCH_APPROVE_PARTIAL = "批量审批完成，部分订单处理失败";
    public static final String REFUND_PERMISSION_DENIED = "无权操作此订单";
    public static final String REFUND_STATUS_PROCESSING = "该订单正在申请退款中，暂无法支付";
    public static final String REFUND_PLEASE_REORDER = "该订单已退款，如需购买请重新下单";
    public static final String VERIFICATION_CODE_INVALID = "核销码无效";
    public static final String VERIFICATION_ALREADY_USED = "该票已核销，请勿重复核销";
}
