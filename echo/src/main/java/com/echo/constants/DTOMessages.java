package com.echo.constants;

import java.text.MessageFormat;

/**
 * DTO校验消息常量
 * <p>
 * 设计原则：
 * 1. 所有DTO层的硬编码字符串集中管理
 * 2. 支持消息模板（{0}, {1}占位符）
 * 3. 提供format()方法用于动态参数
 * 4. 按DTO类型分组，便于维护
 * <p>
 * 使用示例：
 * <pre>
 * // 直接引用
 * @NotNull(message = DTOMessages.TICKET_WITH_SEATS_TICKET_NULL)
 *
 * // 带参数的消息模板
 * String msg = DTOMessages.format(DTOMessages.TICKET_WITH_SEATS_TOTAL_SEATS_EXCEED, 15000, 10000);
 * </pre>
 */
public final class DTOMessages {

    private DTOMessages() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    /**
     * 格式化带参数的消息模板
     */
    public static String format(String template, Object... args) {
        if (args == null || args.length == 0) {
            return template;
        }
        return MessageFormat.format(template, args);
    }

    // ==================== TicketWithSeatsDTO 相关 ====================

    public static final String TICKET_WITH_SEATS_TICKET_NULL = "票种信息不能为空";

    public static final String TICKET_WITH_SEATS_SHOW_ID_NULL = "演出ID不能为空";

    public static final String TICKET_WITH_SEATS_TITLE_EMPTY = "票种标题不能为空";

    public static final String TICKET_WITH_SEATS_SEAT_CONFIG_REQUIRED = "需要选座的票种必须提供座位区域配置";

    public static final String TICKET_WITH_SEATS_AREAS_EMPTY = "座位区域配置不能为空";

    public static final String TICKET_WITH_SEATS_TOTAL_SEATS_EXCEED = "座位总数超出限制（当前：{0}，上限：{1}）";

    public static final String TICKET_WITH_SEATS_IDEMPOTENCY_KEY_INVALID = "幂等键格式无效（应为UUID格式）";

    public static final String TICKET_WITH_SEATS_SOURCE_CHANNEL_INVALID = "操作来源渠道无效";

    public static final String TICKET_WITH_SEATS_REMARK_TOO_LONG = "操作备注超出长度限制（最大500字符）";

    // ==================== TicketOrderCreateDTO 相关 ====================

    public static final String TICKET_ORDER_COUNT_NULL = "购票数量不能为空";

    public static final String TICKET_ORDER_COUNT_MIN = "购票数量至少为1张";

    public static final String TICKET_ORDER_COUNT_MAX = "单次最多购买10张";

    public static final String TICKET_ORDER_SEAT_IDS_EMPTY = "座位ID列表不能为空";

    public static final String TICKET_ORDER_SEAT_IDS_SIZE = "最多选择10个座位";

    // ==================== SeatBatchCreateDTO 相关 ====================

    public static final String SEAT_BATCH_AREAS_EMPTY = "区域配置不能为空";

    public static final String SEAT_BATCH_AREAS_SIZE = "区域数量不能超过10个";

    public static final String SEAT_BATCH_AREA_NAME_BLANK = "区域名称不能为空";

    public static final String SEAT_BATCH_AREA_NAME_SIZE = "区域名称最长20个字符";

    public static final String SEAT_BATCH_ROWS_NULL = "排数不能为空";

    public static final String SEAT_BATCH_ROWS_MIN = "排数至少为1";

    public static final String SEAT_BATCH_ROWS_MAX = "排数不能超过50";

    public static final String SEAT_BATCH_COLS_NULL = "每排座位数不能为空";

    public static final String SEAT_BATCH_COLS_MIN = "每排座位数至少为1";

    public static final String SEAT_BATCH_COLS_MAX = "每排座位数不能超过100";

    // ==================== BatchOperationDTO 相关 ====================

    /** 操作目标ID列表 */
    public static final String BATCH_IDS_NOT_EMPTY = "操作目标ID列表不能为空";

    public static final String BATCH_IDS_MAX_SIZE = "单次操作最多支持100个ID";

    // ==================== AuditableDTO 相关 ====================

    /** 幂等键 */
    public static final String IDEMPOTENCY_KEY_NOT_EMPTY = "幂等键不能为空";

    /** 操作来源渠道 */
    public static final String SOURCE_CHANNEL_NOT_EMPTY = "操作来源渠道不能为空";

    /** 操作备注 */
    public static final String REMARK_TOO_LONG = "操作备注超出长度限制（最大500字符）";

    // ==================== Result 相关 ====================

    /** 默认成功消息 */
    public static final String DEFAULT_SUCCESS_MESSAGE = "操作成功";
}
