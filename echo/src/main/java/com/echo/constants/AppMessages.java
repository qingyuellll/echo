package com.echo.constants;

import java.text.MessageFormat;

/**
 * 应用统一消息常量类（聚合层 - 向后兼容）
 * <p>
 * 设计原则：
 * 1. 所有现有代码无需修改，继续使用 AppMessages.XXX
 * 2. 新代码可以使用模块化引用（如 AuthMessages.LOGIN_SUCCESS）
 * 3. 支持消息模板（使用 {0}, {1} 占位符）
 * 4. 提供静态方法 format() 用于动态参数
 */
public final class AppMessages {

    private AppMessages() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    /** 格式化消息模板 */
    public static String format(String template, Object... args) {
        if (args == null || args.length == 0) {
            return template;
        }
        return MessageFormat.format(template, args);
    }

    // ==================== 1. 认证模块 (Authentication) ====================

    public static final String AUTH_PHONE_PASSWORD_EMPTY = AuthMessages.PHONE_PASSWORD_EMPTY;
    public static final String AUTH_PHONE_REQUIRED = AuthMessages.PHONE_REQUIRED;
    public static final String AUTH_CREDENTIAL_REQUIRED = AuthMessages.CREDENTIAL_REQUIRED;
    public static final String AUTH_ACCOUNT_OR_PASSWORD_ERROR = AuthMessages.ACCOUNT_OR_PASSWORD_ERROR;
    public static final String AUTH_NOT_LOGGED_IN = AuthMessages.NOT_LOGGED_IN;
    public static final String AUTH_LOGIN_SUCCESS = AuthMessages.LOGIN_SUCCESS;
    public static final String AUTH_LOGOUT_SUCCESS = AuthMessages.LOGOUT_SUCCESS;
    public static final String AUTH_CODE_SEND_SUCCESS = AuthMessages.CODE_SEND_SUCCESS;
    public static final String AUTH_CODE_SEND_FAIL = AuthMessages.CODE_SEND_FAIL;
    public static final String AUTH_CODE_INVALID = AuthMessages.CODE_INVALID;
    public static final String AUTH_CODE_INCORRECT = AuthMessages.CODE_INCORRECT;
    public static final String AUTH_CODE_SEND_FREQUENT = AuthMessages.CODE_SEND_FREQUENT;
    public static final String AUTH_CODE_SEND_FREQUENT_SIMPLE = AuthMessages.CODE_SEND_FREQUENT_SIMPLE;
    public static final String AUTH_CODE_SEND_FAIL_RETRY = AuthMessages.CODE_SEND_FAIL_RETRY;
    public static final String AUTH_LOGIN_FAIL_RETRY = AuthMessages.LOGIN_FAIL_RETRY;

    public static final String PASSWORD_CHANGE_SUCCESS = AuthMessages.PASSWORD_CHANGE_SUCCESS;
    public static final String PASSWORD_CHANGE_FAIL = AuthMessages.PASSWORD_CHANGE_FAIL;
    public static final String PASSWORD_CODE_SEND_SUCCESS = AuthMessages.PASSWORD_CODE_SEND_SUCCESS;
    public static final String PASSWORD_OLD_ERROR = AuthMessages.PASSWORD_OLD_ERROR;
    public static final String PASSWORD_SAME_AS_OLD = AuthMessages.PASSWORD_SAME_AS_OLD;
    public static final String PASSWORD_NOT_SET = AuthMessages.PASSWORD_NOT_SET;
    public static final String PASSWORD_EMPTY = AuthMessages.PASSWORD_EMPTY;
    public static final String PASSWORD_MIN_LENGTH = AuthMessages.PASSWORD_MIN_LENGTH;
    public static final String PASSWORD_MAX_LENGTH = AuthMessages.PASSWORD_MAX_LENGTH;
    public static final String PASSWORD_UPDATE_FAIL_PREFIX = AuthMessages.PASSWORD_UPDATE_FAIL_PREFIX;
    public static final String PASSWORD_OLD_REQUIRED = AuthMessages.PASSWORD_OLD_REQUIRED;
    public static final String PASSWORD_CHANGE_FAIL_RETRY = AuthMessages.PASSWORD_CHANGE_FAIL_RETRY;

    public static final String PERMISSION_SUPER_ADMIN_ONLY = AuthMessages.PERMISSION_SUPER_ADMIN_ONLY;
    public static final String PERMISSION_ADMIN_REQUIRED = AuthMessages.PERMISSION_ADMIN_REQUIRED;
    public static final String PERMISSION_USER_LOGIN_REQUIRED = AuthMessages.PERMISSION_USER_LOGIN_REQUIRED;
    public static final String ADMIN_LOGIN_REQUIRED = AuthMessages.ADMIN_LOGIN_REQUIRED;
    public static final String ADMIN_BOOTSTRAP_DISABLED = AuthMessages.ADMIN_BOOTSTRAP_DISABLED;
    public static final String ADMIN_BOOTSTRAP_TOKEN_INVALID = AuthMessages.ADMIN_BOOTSTRAP_TOKEN_INVALID;

    public static final int ROLE_LEVEL_SUPER_ADMIN = AuthMessages.ROLE_LEVEL_SUPER_ADMIN;
    public static final int ROLE_LEVEL_NORMAL_ADMIN = AuthMessages.ROLE_LEVEL_NORMAL_ADMIN;

    // ==================== 2. 用户模块 (User) ====================

    public static final String USER_NOT_EXIST = UserMessages.NOT_EXIST;
    public static final String USER_LIST_GET_SUCCESS = UserMessages.LIST_GET_SUCCESS;
    public static final String USER_DETAIL_GET_SUCCESS = UserMessages.DETAIL_GET_SUCCESS;
    public static final String USER_DISABLE_SUCCESS = UserMessages.DISABLE_SUCCESS;
    public static final String USER_ENABLE_SUCCESS = UserMessages.ENABLE_SUCCESS;
    public static final String USER_CREDIT_UPDATE_SUCCESS = UserMessages.CREDIT_UPDATE_SUCCESS;
    public static final String USER_ACCOUNT_DISABLED = UserMessages.ACCOUNT_DISABLED;
    public static final String USER_OPERATION_FAIL_RETRY = UserMessages.OPERATION_FAIL_RETRY;
    public static final String USER_QUERY_FAIL_RETRY = UserMessages.QUERY_FAIL_RETRY;
    public static final String SIGN_FAIL_RETRY = UserMessages.SIGN_FAIL_RETRY;

    public static final String FOLLOW_USER_ID_INVALID = UserMessages.FOLLOW_USER_ID_INVALID;
    public static final String FOLLOW_TYPE_EMPTY = UserMessages.FOLLOW_TYPE_EMPTY;
    public static final String FOLLOW_STATUS_QUERY_FAIL = UserMessages.FOLLOW_STATUS_QUERY_FAIL;
    public static final String FOLLOW_COUNT_QUERY_FAIL = UserMessages.FOLLOW_COUNT_QUERY_FAIL;
    public static final String FOLLOW_CANNOT_QUERY_SELF = UserMessages.FOLLOW_CANNOT_QUERY_SELF;
    public static final String FOLLOW_COMMON_QUERY_FAIL = UserMessages.FOLLOW_COMMON_QUERY_FAIL;

    public static final String ADMIN_SUPER_ADMIN_NOT_EXIST = UserMessages.ADMIN_SUPER_ADMIN_NOT_EXIST;
    public static final String ADMIN_USER_ALREADY_ADMIN = UserMessages.ADMIN_USER_ALREADY_ADMIN;
    public static final String ADMIN_USER_NOT_ADMIN = UserMessages.ADMIN_USER_NOT_ADMIN;
    public static final String ADMIN_NOT_EXIST = UserMessages.ADMIN_NOT_EXIST;
    public static final String ADMIN_CANNOT_DISABLE_ADMIN = UserMessages.ADMIN_CANNOT_DISABLE_ADMIN;
    public static final String ADMIN_CANNOT_OPERATE_SELF = UserMessages.ADMIN_CANNOT_OPERATE_SELF;
    public static final String ADMIN_CANNOT_DISABLE_SELF = UserMessages.ADMIN_CANNOT_DISABLE_SELF;

    public static final String USER_QUERY_RATE_LIMIT = UserMessages.QUERY_RATE_LIMIT;
    public static final String USER_QUERY_FAIL = UserMessages.QUERY_FAIL;
    public static final String USER_INFO_GET_FAIL = UserMessages.INFO_GET_FAIL;

    // 字段校验（用户模块）
    public static final String USER_CITY_TOO_LONG = UserMessages.CITY_TOO_LONG;
    public static final String USER_INTRODUCE_TOO_LONG = UserMessages.INTRODUCE_TOO_LONG;

    // ==================== 3. 演出模块 (Show) ====================

    public static final String SHOW_NOT_EXIST = ShowMessages.NOT_EXIST;
    public static final String SHOW_ID_INVALID = ShowMessages.ID_INVALID;
    public static final String SHOW_ID_NOT_EMPTY = ShowMessages.ID_NOT_EMPTY;
    public static final String SHOW_CREATE_SUCCESS = ShowMessages.CREATE_SUCCESS;
    public static final String SHOW_UPDATE_SUCCESS = ShowMessages.UPDATE_SUCCESS;
    public static final String SHOW_DELETE_SUCCESS = ShowMessages.DELETE_SUCCESS;
    public static final String SHOW_DELETE_FAIL = ShowMessages.DELETE_FAIL;
    public static final String SHOW_DELETE_HAS_ACTIVE_TICKETS = ShowMessages.DELETE_HAS_ACTIVE_TICKETS;
    public static final String SHOW_ON_SHELF_SUCCESS = ShowMessages.ON_SHELF_SUCCESS;
    public static final String SHOW_OFF_SHELF_SUCCESS = ShowMessages.OFF_SHELF_SUCCESS;
    public static final String SHOW_OFFLINE_SUCCESS = ShowMessages.OFFLINE_SUCCESS;
    public static final String SHOW_ONLINE_SUCCESS = ShowMessages.ONLINE_SUCCESS;
    public static final String SHOW_TARGET_NOT_EXIST = ShowMessages.TARGET_NOT_EXIST;
    public static final String SHOW_UPDATE_FAIL = ShowMessages.UPDATE_FAIL;
    public static final String SHOW_NO_DATA = ShowMessages.NO_DATA;
    public static final String SHOW_TYPE_QUERY_FAIL = ShowMessages.TYPE_QUERY_FAIL;
    public static final String SHOW_REVIEW_SCORE_INVALID = ShowMessages.REVIEW_SCORE_INVALID;
    public static final String SHOW_REVIEW_SEARCH_FAIL = ShowMessages.REVIEW_SEARCH_FAIL;
    public static final String SHOW_REVIEW_QUERY_FAIL = ShowMessages.REVIEW_QUERY_FAIL;
    public static final String SHOW_FOLLOW_FAIL = ShowMessages.FOLLOW_FAIL;
    public static final String SHOW_UNFOLLOW_FAIL = ShowMessages.UNFOLLOW_FAIL;
    public static final String SHOW_FOLLOW_LIST_QUERY_FAIL = ShowMessages.FOLLOW_LIST_QUERY_FAIL;
    public static final String SHOW_CREATE_FAIL = ShowMessages.CREATE_FAIL;

    // 字段校验（演出模块）
    public static final String SHOW_NAME_NOT_EMPTY = ShowMessages.NAME_NOT_EMPTY;
    public static final String SHOW_NAME_TOO_LONG = ShowMessages.NAME_TOO_LONG;
    public static final String SHOW_TYPE_ID_NOT_EMPTY = ShowMessages.TYPE_ID_NOT_EMPTY;
    public static final String SHOW_ADDRESS_NOT_EMPTY = ShowMessages.ADDRESS_NOT_EMPTY;
    public static final String SHOW_ADDRESS_TOO_LONG = ShowMessages.ADDRESS_TOO_LONG;
    public static final String SHOW_AREA_TOO_LONG = ShowMessages.AREA_TOO_LONG;
    public static final String SHOW_LONGITUDE_MIN = ShowMessages.LONGITUDE_MIN;
    public static final String SHOW_LONGITUDE_MAX = ShowMessages.LONGITUDE_MAX;
    public static final String SHOW_LATITUDE_MIN = ShowMessages.LATITUDE_MIN;
    public static final String SHOW_LATITUDE_MAX = ShowMessages.LATITUDE_MAX;
    public static final String SHOW_AVG_PRICE_NEGATIVE = ShowMessages.AVG_PRICE_NEGATIVE;
    public static final String SHOW_SOLD_NEGATIVE = ShowMessages.SOLD_NEGATIVE;
    public static final String SHOW_COMMENTS_NEGATIVE = ShowMessages.COMMENTS_NEGATIVE;
    public static final String SHOW_SCORE_MIN = ShowMessages.SCORE_MIN;
    public static final String SHOW_SCORE_MAX = ShowMessages.SCORE_MAX;
    public static final String SHOW_STATUS_NOT_EMPTY = ShowMessages.STATUS_NOT_EMPTY;
    public static final String SHOW_STATUS_MIN = ShowMessages.STATUS_MIN;
    public static final String SHOW_STATUS_MAX = ShowMessages.STATUS_MAX;
    public static final String SHOW_OPEN_TIME_NOT_EMPTY = ShowMessages.OPEN_TIME_NOT_EMPTY;
    public static final String SHOW_END_TIME_NOT_EMPTY = ShowMessages.END_TIME_NOT_EMPTY;
    public static final String SHOW_OPEN_HOURS_TOO_LONG = ShowMessages.OPEN_HOURS_TOO_LONG;
    public static final String SHOW_IMAGES_TOO_LONG = ShowMessages.IMAGES_TOO_LONG;
    public static final String SHOW_DESCRIPTION_TOO_LONG = ShowMessages.DESCRIPTION_TOO_LONG;

    // ==================== 4. 票种模块 (Ticket) ====================

    public static final String TICKET_TITLE_EMPTY = TicketMessages.TITLE_EMPTY;
    public static final String TICKET_SHOW_ID_REQUIRED = TicketMessages.SHOW_ID_REQUIRED;
    public static final String TICKET_NOT_EXIST = TicketMessages.NOT_EXIST;
    public static final String TICKET_CREATE_SUCCESS = TicketMessages.CREATE_SUCCESS;
    public static final String TICKET_CREATE_FAIL = TicketMessages.CREATE_FAIL;
    public static final String TICKET_UPDATE_SUCCESS = TicketMessages.UPDATE_SUCCESS;
    public static final String TICKET_UPDATE_FAIL = TicketMessages.UPDATE_FAIL;
    public static final String TICKET_DELETE_SUCCESS = TicketMessages.DELETE_SUCCESS;
    public static final String TICKET_DELETE_FAIL = TicketMessages.DELETE_FAIL;
    public static final String TICKET_DELETE_ACTIVE_FORBIDDEN = TicketMessages.DELETE_ACTIVE_FORBIDDEN;
    public static final String TICKET_ONLINE_SUCCESS = TicketMessages.ONLINE_SUCCESS;
    public static final String TICKET_OFFLINE_SUCCESS = TicketMessages.OFFLINE_SUCCESS;
    public static final String TICKET_ALREADY_ONLINE = TicketMessages.ALREADY_ONLINE;
    public static final String TICKET_ALREADY_OFFLINE = TicketMessages.ALREADY_OFFLINE;
    public static final String TICKET_STATUS_CHANGE_FAIL = TicketMessages.STATUS_CHANGE_FAIL;
    public static final String TICKET_STOCK_ADJUST_SUCCESS = TicketMessages.STOCK_ADJUST_SUCCESS;
    public static final String TICKET_STOCK_ADJUST_FAIL = TicketMessages.STOCK_ADJUST_FAIL;
    public static final String TICKET_CREATE_FAIL_PREFIX = TicketMessages.CREATE_FAIL_PREFIX;
    public static final String TICKET_UPDATE_FAIL_PREFIX = TicketMessages.UPDATE_FAIL_PREFIX;
    public static final String TICKET_SAVE_FAIL = TicketMessages.SAVE_FAIL;
    public static final String TICKET_QUERY_FAIL = TicketMessages.QUERY_FAIL;
    public static final String TICKET_LOCKED_CANNOT_ONLINE = TicketMessages.LOCKED_CANNOT_ONLINE;
    public static final String TICKET_RELATED_NOT_EXIST = TicketMessages.RELATED_NOT_EXIST;
    public static final String TICKET_SECKILL_NOT_EXIST = TicketMessages.SECKILL_NOT_EXIST;
    public static final String TICKET_SECKILL_CREATE_SUCCESS = TicketMessages.SECKILL_CREATE_SUCCESS;
    public static final String TICKET_SECKILL_UPDATE_SUCCESS = TicketMessages.SECKILL_UPDATE_SUCCESS;
    public static final String TICKET_SECKILL_SAVE_FAIL = TicketMessages.SECKILL_SAVE_FAIL;
    public static final String TICKET_SECKILL_CREATE_FAIL_PREFIX = TicketMessages.SECKILL_CREATE_FAIL_PREFIX;
    public static final String TICKET_STATUS_QUERY_FAIL = TicketMessages.STATUS_QUERY_FAIL;
    public static final String TICKET_DETAIL_QUERY_FAIL = TicketMessages.DETAIL_QUERY_FAIL;
    public static final String TICKET_ID_INVALID = TicketMessages.ID_INVALID;
    public static final String TICKET_CHECK_PURCHASE_FAIL = TicketMessages.CHECK_PURCHASE_FAIL;
    public static final String TICKET_TYPE_INVALID = TicketMessages.TYPE_INVALID;
    public static final String TICKET_INFO_EMPTY = TicketMessages.INFO_EMPTY;
    public static final String TICKET_SEAT_AREA_REQUIRED = TicketMessages.SEAT_AREA_REQUIRED;
    public static final String TICKET_SECKILL_TIME_REQUIRED = TicketMessages.SECKILL_TIME_REQUIRED;
    public static final String TICKET_SECKILL_TIME_INVALID = TicketMessages.SECKILL_TIME_INVALID;
    public static final String TICKET_STOCK_INVALID = TicketMessages.STOCK_INVALID;
    public static final String TICKET_STOCK_MUST_GREATER_THAN_ZERO = TicketMessages.STOCK_MUST_GREATER_THAN_ZERO;
    public static final String TICKET_STOCK_MAX_INVALID = TicketMessages.STOCK_MAX_INVALID;
    public static final String TICKET_OFFLINE_HAS_ORDERS = TicketMessages.OFFLINE_HAS_ORDERS;
    public static final String TICKET_ONLINE_SHOW_NOT_EXIST = TicketMessages.ONLINE_SHOW_NOT_EXIST;
    public static final String TICKET_LOCKED_BY_DELETED_SHOW = TicketMessages.LOCKED_BY_DELETED_SHOW;
    public static final String TICKET_DELETE_HAS_ORDERS = TicketMessages.DELETE_HAS_ORDERS;
    public static final String TICKET_NOT_SECKILL = TicketMessages.NOT_SECKILL;

    // 字段校验（票种/座位模块）
    public static final String TICKET_AREAS_NOT_EMPTY = TicketMessages.AREAS_NOT_EMPTY;
    public static final String TICKET_AREAS_MAX_SIZE = TicketMessages.AREAS_MAX_SIZE;
    public static final String TICKET_AREA_NAME_NOT_EMPTY = TicketMessages.AREA_NAME_NOT_EMPTY;
    public static final String TICKET_AREA_NAME_TOO_LONG = TicketMessages.AREA_NAME_TOO_LONG;
    public static final String TICKET_ROWS_NOT_EMPTY = TicketMessages.ROWS_NOT_EMPTY;
    public static final String TICKET_ROWS_MIN = TicketMessages.ROWS_MIN;
    public static final String TICKET_ROWS_MAX = TicketMessages.ROWS_MAX;
    public static final String TICKET_COLS_NOT_EMPTY = TicketMessages.COLS_NOT_EMPTY;
    public static final String TICKET_COLS_MIN = TicketMessages.COLS_MIN;
    public static final String TICKET_COLS_MAX = TicketMessages.COLS_MAX;
    public static final String TICKET_START_ROW_NOT_EMPTY = TicketMessages.START_ROW_NOT_EMPTY;
    public static final String TICKET_START_ROW_MIN = TicketMessages.START_ROW_MIN;

    // 字段校验（票种/秒杀票/座位/评价/积分 - Entity层）
    public static final String TICKET_ENTITY_SHOW_ID_NOT_EMPTY = TicketMessages.TICKET_SHOW_ID_NOT_EMPTY;
    public static final String TICKET_ENTITY_TITLE_NOT_EMPTY = TicketMessages.TICKET_TITLE_NOT_EMPTY;
    public static final String TICKET_ENTITY_PAY_VALUE_NEGATIVE = TicketMessages.TICKET_PAY_VALUE_NEGATIVE;
    public static final String TICKET_ENTITY_ACTUAL_VALUE_NEGATIVE = TicketMessages.TICKET_ACTUAL_VALUE_NEGATIVE;
    public static final String SECKILL_TICKET_ID_NOT_EMPTY = TicketMessages.SECKILL_TICKET_ID_NOT_EMPTY;
    public static final String SECKILL_STOCK_NEGATIVE = TicketMessages.SECKILL_STOCK_NEGATIVE;
    public static final String SECKILL_STOCK_MAX = TicketMessages.SECKILL_STOCK_MAX;
    public static final String SECKILL_BEGIN_TIME_NOT_EMPTY = TicketMessages.SECKILL_BEGIN_TIME_NOT_EMPTY;
    public static final String SECKILL_END_TIME_NOT_EMPTY = TicketMessages.SECKILL_END_TIME_NOT_EMPTY;
    public static final String SEAT_ENTITY_TICKET_ID_NOT_EMPTY = TicketMessages.SEAT_TICKET_ID_NOT_EMPTY;
    public static final String SEAT_AREA_NAME_TOO_LONG_20 = TicketMessages.AREA_NAME_TOO_LONG_20;
    public static final String ROW_NUM_NOT_EMPTY = TicketMessages.ROW_NUM_NOT_EMPTY;
    public static final String ROW_NUM_MIN = TicketMessages.ROW_NUM_MIN;
    public static final String ROW_NUM_MAX = TicketMessages.ROW_NUM_MAX;
    public static final String COL_NUM_NOT_EMPTY = TicketMessages.COL_NUM_NOT_EMPTY;
    public static final String COL_NUM_MIN = TicketMessages.COL_NUM_MIN;
    public static final String COL_NUM_MAX = TicketMessages.COL_NUM_MAX;
    public static final String SEAT_STATUS_NOT_EMPTY = TicketMessages.SEAT_STATUS_NOT_EMPTY;
    public static final String SEAT_STATUS_MIN = TicketMessages.SEAT_STATUS_MIN;
    public static final String SEAT_STATUS_MAX = TicketMessages.SEAT_STATUS_MAX;
    public static final String PRE_ORDER_ID_TOO_LONG = TicketMessages.PRE_ORDER_ID_TOO_LONG;
    public static final String REVIEW_ENTITY_SHOW_ID_NOT_EMPTY = TicketMessages.REVIEW_SHOW_ID_NOT_EMPTY;
    public static final String REVIEW_ENTITY_USER_ID_NOT_EMPTY = TicketMessages.REVIEW_USER_ID_NOT_EMPTY;
    public static final String REVIEW_ENTITY_ORDER_ID_NOT_EMPTY = TicketMessages.REVIEW_ORDER_ID_NOT_EMPTY;
    public static final String REVIEW_ENTITY_SCORE_NOT_EMPTY = TicketMessages.REVIEW_SCORE_NOT_EMPTY;
    public static final String REVIEW_ENTITY_SCORE_MIN = TicketMessages.REVIEW_SCORE_MIN;
    public static final String REVIEW_ENTITY_SCORE_MAX = TicketMessages.REVIEW_SCORE_MAX;
    public static final String REVIEW_CONTENT_TOO_LONG = TicketMessages.REVIEW_CONTENT_TOO_LONG;
    public static final String REVIEW_IMAGES_TOO_LONG = TicketMessages.REVIEW_IMAGES_TOO_LONG;
    public static final String REVIEW_ENTITY_STATUS_NOT_EMPTY = TicketMessages.REVIEW_STATUS_NOT_EMPTY;
    public static final String REVIEW_ENTITY_STATUS_MIN = TicketMessages.REVIEW_STATUS_MIN;
    public static final String REVIEW_ENTITY_STATUS_MAX = TicketMessages.REVIEW_STATUS_MAX;
    public static final String LIKE_COUNT_NEGATIVE = TicketMessages.LIKE_COUNT_NEGATIVE;
    public static final String REVIEW_LIKE_REVIEW_ID_NOT_EMPTY = TicketMessages.REVIEW_LIKE_REVIEW_ID_NOT_EMPTY;
    public static final String REVIEW_LIKE_USER_ID_NOT_EMPTY = TicketMessages.REVIEW_LIKE_USER_ID_NOT_EMPTY;
    public static final String POINTS_USER_ID_NOT_EMPTY = TicketMessages.POINTS_USER_ID_NOT_EMPTY;
    public static final String POINTS_VALUE_NOT_EMPTY = TicketMessages.POINTS_VALUE_NOT_EMPTY;
    public static final String POINTS_VALUE_MIN = TicketMessages.POINTS_VALUE_MIN;
    public static final String POINTS_VALUE_MAX = TicketMessages.POINTS_VALUE_MAX;
    public static final String POINTS_TYPE_NOT_EMPTY = TicketMessages.POINTS_TYPE_NOT_EMPTY;
    public static final String POINTS_TYPE_MIN = TicketMessages.POINTS_TYPE_MIN;
    public static final String POINTS_TYPE_MAX = TicketMessages.POINTS_TYPE_MAX;
    public static final String POINTS_DESCRIPTION_TOO_LONG = TicketMessages.POINTS_DESCRIPTION_TOO_LONG;

    // ==================== 5. 订单模块 (Order) ====================

    public static final String ORDER_LIST_GET_SUCCESS = OrderMessages.LIST_GET_SUCCESS;
    public static final String ORDER_DETAIL_GET_SUCCESS = OrderMessages.DETAIL_GET_SUCCESS;
    public static final String ORDER_CANCEL_SUCCESS = OrderMessages.CANCEL_SUCCESS;
    public static final String ORDER_PAID_CANNOT_CANCEL = OrderMessages.PAID_CANNOT_CANCEL;
    public static final String ORDER_ALREADY_CANCELLED = OrderMessages.ALREADY_CANCELLED;
    public static final String ORDER_ALREADY_REFUNDED = OrderMessages.ALREADY_REFUNDED;
    public static final String ORDER_CANCEL_FAIL = OrderMessages.CANCEL_FAIL;
    public static final String ORDER_REFUND_SUCCESS = OrderMessages.REFUND_SUCCESS;
    public static final String ORDER_NOT_EXIST = OrderMessages.NOT_EXIST;
    public static final String ORDER_MAX_PURCHASE_10 = OrderMessages.MAX_PURCHASE_10;
    public static final String ORDER_TICKET_NOT_EXIST = OrderMessages.TICKET_NOT_EXIST;
    public static final String ORDER_SECKILL_CHANNEL_REQUIRED = OrderMessages.SECKILL_CHANNEL_REQUIRED;
    public static final String ORDER_STOCK_INSUFFICIENT = OrderMessages.STOCK_INSUFFICIENT;
    public static final String ORDER_STOCK_INSUFFICIENT_CREATE = OrderMessages.STOCK_INSUFFICIENT_CREATE;
    public static final String ORDER_NO_PERMISSION = OrderMessages.NO_PERMISSION;
    public static final String ORDER_NOT_EXIST_OR_EXPIRED = OrderMessages.NOT_EXIST_OR_EXPIRED;
    public static final String ORDER_SELECT_AT_LEAST_ONE_SEAT = OrderMessages.SELECT_AT_LEAST_ONE_SEAT;
    public static final String ORDER_SEAT_COUNT_RANGE = OrderMessages.SEAT_COUNT_RANGE;
    public static final String ORDER_SEAT_COUNT_MISMATCH = OrderMessages.SEAT_COUNT_MISMATCH;
    public static final String ORDER_TICKET_OFFLINE = OrderMessages.TICKET_OFFLINE;
    public static final String ORDER_SECKILL_PURCHASED = OrderMessages.SECKILL_PURCHASED;
    public static final String ORDER_SECKILL_LIMIT_1 = OrderMessages.SECKILL_LIMIT_1;
    public static final String OPERATION_TOO_FREQUENT = OrderMessages.OPERATION_TOO_FREQUENT;
    public static final String ORDER_SEAT_NOT_EXIST = OrderMessages.SEAT_NOT_EXIST;
    public static final String ORDER_SEAT_MISMATCH = OrderMessages.SEAT_MISMATCH;
    public static final String ORDER_SEAT_RELEASED = OrderMessages.SEAT_RELEASED;
    public static final String ORDER_SEAT_SOLD_BY_OTHERS = OrderMessages.SEAT_SOLD_BY_OTHERS;
    public static final String ORDER_SEAT_STATUS_INVALID = OrderMessages.SEAT_STATUS_INVALID;
    public static final String ORDER_SEAT_LOCK_EXPIRED = OrderMessages.SEAT_LOCK_EXPIRED;
    public static final String ORDER_SEAT_UPDATE_FAIL = OrderMessages.SEAT_UPDATE_FAIL;
    public static final String SECKILL_FAIL = OrderMessages.SECKILL_FAIL;
    public static final String SECKILL_OVER = OrderMessages.SECKILL_OVER;
    public static final String SECKILL_STOCK_NOT_ENOUGH = OrderMessages.SECKILL_STOCK_NOT_ENOUGH;
    public static final String SECKILL_REPEAT_ORDER = OrderMessages.SECKILL_REPEAT_ORDER;
    public static final String TICKET_OFFLINE_ERROR = OrderMessages.TICKET_OFFLINE_ERROR;
    public static final String TICKET_SECKILL_NOT_STARTED = OrderMessages.TICKET_SECKILL_NOT_STARTED;
    public static final String TICKET_STATUS_INVALID = OrderMessages.TICKET_STATUS_INVALID;
    public static final String TICKET_SECKILL_NOT_STARTED_ERROR = OrderMessages.TICKET_SECKILL_NOT_STARTED_ERROR;
    public static final String ORDER_CREATE_FAIL_RETRY = OrderMessages.CREATE_FAIL_RETRY;
    public static final String ORDER_SHOW_ENDED = OrderMessages.SHOW_ENDED;
    public static final String ORDER_VERIFY_CODE_EMPTY = OrderMessages.VERIFY_CODE_EMPTY;
    public static final String ORDER_VERIFY_CODE_FORMAT_ERROR = OrderMessages.VERIFY_CODE_FORMAT_ERROR;
    public static final String ORDER_VERIFY_CODE_DATA_ERROR = OrderMessages.VERIFY_CODE_DATA_ERROR;
    public static final String ORDER_VERIFY_CODE_USED = OrderMessages.VERIFY_CODE_USED;
    public static final String ORDER_VERIFY_STATUS_QUERY_FAIL = OrderMessages.VERIFY_STATUS_QUERY_FAIL;
    public static final String ORDER_DATA_ERROR = OrderMessages.DATA_ERROR;
    public static final String ORDER_CHECK_PURCHASE_STATUS_FAIL = OrderMessages.CHECK_PURCHASE_STATUS_FAIL;
    public static final String ORDER_DUPLICATE_PURCHASE = OrderMessages.DUPLICATE_PURCHASE;
    public static final String ORDER_REPEAT_PURCHASE = OrderMessages.REPEAT_PURCHASE;
    public static final String ORDER_NOT_BELONG = OrderMessages.NOT_BELONG;
    public static final String ORDER_STATUS_UNSUPPORTED = OrderMessages.STATUS_UNSUPPORTED;
    public static final String ORDER_PAID_REPEAT = OrderMessages.PAID_REPEAT;
    public static final String ORDER_CANCELLED = OrderMessages.CANCELLED;
    public static final String ORDER_REFUNDED = OrderMessages.REFUNDED;
    public static final String PAY_FAIL = OrderMessages.PAY_FAIL;
    public static final String REFUND_FAIL = OrderMessages.REFUND_FAIL;
    public static final String REFUND_NOT_ALLOWED = OrderMessages.REFUND_NOT_ALLOWED;
    public static final String REFUND_ALREADY_COMPLETED = OrderMessages.REFUND_ALREADY_COMPLETED;
    public static final String REFUND_ORDER_CANCELLED = OrderMessages.REFUND_ORDER_CANCELLED;
    public static final String REFUND_NO_APPLICATION = OrderMessages.REFUND_NO_APPLICATION;
    public static final String REFUND_APPROVE_SUCCESS = OrderMessages.REFUND_APPROVE_SUCCESS;
    public static final String REFUND_REJECT_SUCCESS = OrderMessages.REFUND_REJECT_SUCCESS;
    public static final String REFUND_BATCH_APPROVE_PARTIAL = OrderMessages.REFUND_BATCH_APPROVE_PARTIAL;
    public static final String REFUND_PERMISSION_DENIED = OrderMessages.REFUND_PERMISSION_DENIED;
    public static final String REFUND_STATUS_PROCESSING = OrderMessages.REFUND_STATUS_PROCESSING;
    public static final String REFUND_PLEASE_REORDER = OrderMessages.REFUND_PLEASE_REORDER;
    public static final String VERIFICATION_CODE_INVALID = OrderMessages.VERIFICATION_CODE_INVALID;
    public static final String VERIFICATION_ALREADY_USED = OrderMessages.VERIFICATION_ALREADY_USED;

    // 字段校验（订单模块）
    public static final String ORDER_COUNT_NOT_EMPTY = OrderMessages.COUNT_NOT_EMPTY;
    public static final String ORDER_COUNT_MIN = OrderMessages.COUNT_MIN;
    public static final String ORDER_COUNT_MAX = OrderMessages.COUNT_MAX;
    public static final String ORDER_SEAT_IDS_NOT_EMPTY = OrderMessages.SEAT_IDS_NOT_EMPTY;
    public static final String ORDER_SEAT_IDS_MAX_SIZE = OrderMessages.SEAT_IDS_MAX_SIZE;
    public static final String ORDER_IDS_NOT_EMPTY = OrderMessages.IDS_NOT_EMPTY;
    public static final String ORDER_IDS_MAX_SIZE = OrderMessages.IDS_MAX_SIZE;
    public static final String ORDER_OPERATION_NOT_EMPTY = OrderMessages.OPERATION_NOT_EMPTY;

    // 字段校验（订单/预订单 - Entity层）
    public static final String ENTITY_USER_ID_NOT_EMPTY = OrderMessages.USER_ID_NOT_EMPTY;
    public static final String ENTITY_SHOW_ID_NOT_EMPTY = OrderMessages.SHOW_ID_NOT_EMPTY;
    public static final String ENTITY_ORDER_ID_NOT_EMPTY = OrderMessages.ORDER_ID_NOT_EMPTY;
    public static final String TEMP_ID_NOT_EMPTY = OrderMessages.TEMP_ID_NOT_EMPTY;
    public static final String TEMP_ID_LENGTH_INVALID = OrderMessages.TEMP_ID_LENGTH_INVALID;
    public static final String ENTITY_TICKET_ID_NOT_EMPTY = OrderMessages.TICKET_ID_NOT_EMPTY;
    public static final String QUANTITY_NOT_EMPTY = OrderMessages.QUANTITY_NOT_EMPTY;
    public static final String QUANTITY_MIN = OrderMessages.QUANTITY_MIN;
    public static final String QUANTITY_MAX = OrderMessages.QUANTITY_MAX;
    public static final String PAY_VALUE_NOT_EMPTY = OrderMessages.PAY_VALUE_NOT_EMPTY;
    public static final String PAY_VALUE_NEGATIVE = OrderMessages.PAY_VALUE_NEGATIVE;
    public static final String ACTUAL_VALUE_NOT_EMPTY = OrderMessages.ACTUAL_VALUE_NOT_EMPTY;
    public static final String ACTUAL_VALUE_NEGATIVE = OrderMessages.ACTUAL_VALUE_NEGATIVE;
    public static final String PRE_ORDER_STATUS_NOT_EMPTY = OrderMessages.PRE_ORDER_STATUS_NOT_EMPTY;
    public static final String PRE_ORDER_STATUS_MIN = OrderMessages.PRE_ORDER_STATUS_MIN;
    public static final String PRE_ORDER_STATUS_MAX = OrderMessages.PRE_ORDER_STATUS_MAX;
    public static final String CREATE_TIME_NOT_EMPTY = OrderMessages.CREATE_TIME_NOT_EMPTY;
    public static final String EXPIRE_TIME_NOT_EMPTY = OrderMessages.EXPIRE_TIME_NOT_EMPTY;
    public static final String EXPIRE_TIME_PAST = OrderMessages.EXPIRE_TIME_PAST;

    // ==================== 6. 座位模块 (Seat) ====================

    public static final String SEAT_CREATE_SUCCESS = SystemMessages.SEAT_CREATE_SUCCESS;
    public static final String SEAT_BATCH_CREATE_SUCCESS = SystemMessages.SEAT_BATCH_CREATE_SUCCESS;
    public static final String SEAT_DELETE_SUCCESS = SystemMessages.SEAT_DELETE_SUCCESS;
    public static final String SEAT_LOCK_SUCCESS = SystemMessages.SEAT_LOCK_SUCCESS;
    public static final String SEAT_UNLOCK_SUCCESS = SystemMessages.SEAT_UNLOCK_SUCCESS;
    public static final String SEAT_AREA_CONFIG_EMPTY = SystemMessages.SEAT_AREA_CONFIG_EMPTY;
    public static final String SEAT_NOT_FOUND = SystemMessages.SEAT_NOT_FOUND;
    public static final String SEAT_LOCKED = SystemMessages.SEAT_LOCKED;
    public static final String SEAT_SOLD = SystemMessages.SEAT_SOLD;
    public static final String SEAT_LOCK_FAIL = SystemMessages.SEAT_LOCK_FAIL;
    public static final String SEAT_NOT_LOCKED = SystemMessages.SEAT_NOT_LOCKED;
    public static final String SEAT_SOLD_BY_OTHERS_WITH_ID = SystemMessages.SEAT_SOLD_BY_OTHERS_WITH_ID;
    public static final String SEAT_NOT_EXIST_WITH_ID = SystemMessages.SEAT_NOT_EXIST_WITH_ID;
    public static final String SEAT_STATUS_UNKNOWN_WITH_ID = SystemMessages.SEAT_STATUS_UNKNOWN_WITH_ID;
    public static final String SEAT_STATUS_DESC_WITH_ID = SystemMessages.SEAT_STATUS_DESC_WITH_ID;
    public static final String SEAT_NOT_BELONG_TO_ORDER_WITH_ID = SystemMessages.SEAT_NOT_BELONG_TO_ORDER_WITH_ID;
    public static final String SEAT_OPTIMISTIC_LOCK_FAIL_WITH_ID = SystemMessages.SEAT_OPTIMISTIC_LOCK_FAIL_WITH_ID;
    public static final String SEAT_STATUS_INVALID_WITH_CODE = SystemMessages.SEAT_STATUS_INVALID_WITH_CODE;
    public static final String SEAT_LOCK_FAIL_PREFIX = SystemMessages.SEAT_LOCK_FAIL_PREFIX;
    public static final String DATABASE_QUERY_FAIL_PREFIX = SystemMessages.DATABASE_QUERY_FAIL_PREFIX;

    // ==================== 7. 评测评论模块 (Review) ====================

    public static final String REVIEW_ID_INVALID = ReviewMessages.ID_INVALID;
    public static final String REVIEW_COMMENT_QUERY_FAIL = ReviewMessages.COMMENT_QUERY_FAIL;
    public static final String REVIEW_COMMENT_INFO_EMPTY = ReviewMessages.COMMENT_INFO_EMPTY;
    public static final String REVIEW_COMMENT_CONTENT_EMPTY = ReviewMessages.COMMENT_CONTENT_EMPTY;
    public static final String REVIEW_COMMENT_CONTENT_TOO_LONG = ReviewMessages.COMMENT_CONTENT_TOO_LONG;
    public static final String REVIEW_COMMENT_TOO_FREQUENT = ReviewMessages.COMMENT_TOO_FREQUENT;
    public static final String REVIEW_COMMENT_SAVE_FAIL = ReviewMessages.COMMENT_SAVE_FAIL;
    public static final String REVIEW_COMMENT_PUBLISH_FAIL_PREFIX = ReviewMessages.COMMENT_PUBLISH_FAIL_PREFIX;
    public static final String REVIEW_NOT_EXIST = ReviewMessages.NOT_EXIST;
    public static final String REVIEW_TITLE_NOT_EMPTY = ReviewMessages.TITLE_NOT_EMPTY;
    public static final String REVIEW_CONTENT_NOT_EMPTY = ReviewMessages.CONTENT_NOT_EMPTY;
    public static final String REVIEW_SAVE_ERROR = ReviewMessages.SAVE_ERROR;
    public static final String REVIEW_SCORE_INVALID = ReviewMessages.SCORE_INVALID;
    public static final String REVIEW_ORDER_ALREADY_REVIEWED = ReviewMessages.ORDER_ALREADY_REVIEWED;
    public static final String REVIEW_SHOW_ALREADY_REVIEWED = ReviewMessages.SHOW_ALREADY_REVIEWED;
    public static final String REVIEW_SUBMIT_FAIL = ReviewMessages.SUBMIT_FAIL;
    public static final String REVIEW_PARAM_EMPTY = ReviewMessages.PARAM_EMPTY;
    public static final String REVIEW_NO_PERMISSION_DELETE = ReviewMessages.NO_PERMISSION_DELETE;
    public static final String REVIEW_DELETE_FAIL = ReviewMessages.DELETE_FAIL;
    public static final String REVIEW_UPDATE_FAIL = ReviewMessages.UPDATE_FAIL;
    public static final String REVIEW_STATS_REFRESH_FAIL = ReviewMessages.STATS_REFRESH_FAIL;
    public static final String FAVORITE_ADD_FAIL = ReviewMessages.FAVORITE_ADD_FAIL;
    public static final String FAVORITE_CANCEL_FAIL = ReviewMessages.FAVORITE_CANCEL_FAIL;
    public static final String FOLLOW_ALREADY_EXISTS = ReviewMessages.FOLLOW_ALREADY_EXISTS;
    public static final String FOLLOW_NOT_EXISTS = ReviewMessages.FOLLOW_NOT_EXISTS;
    public static final String FOLLOW_SELF = ReviewMessages.FOLLOW_SELF;
    public static final String FOLLOW_FAIL = ReviewMessages.FOLLOW_FAIL;
    public static final String SHOW_FOLLOW_ALREADY_EXISTS = ReviewMessages.SHOW_FOLLOW_ALREADY_EXISTS;
    public static final String SHOW_FOLLOW_NOT_EXISTS = ReviewMessages.SHOW_FOLLOW_NOT_EXISTS;
    public static final String REVIEW_INFO_EMPTY = ReviewMessages.INFO_EMPTY;
    public static final String REVIEW_TITLE_REQUIRED = ReviewMessages.TITLE_REQUIRED;
    public static final String REVIEW_SHOW_REQUIRED = ReviewMessages.SHOW_REQUIRED;

    // ==================== 8. 文件上传模块 (Upload) ====================

    public static final String UPLOAD_FILE_EMPTY = SystemMessages.UPLOAD_FILE_EMPTY;
    public static final String UPLOAD_FILE_SIZE_EXCEED = SystemMessages.UPLOAD_FILE_SIZE_EXCEED;
    public static final String UPLOAD_FILE_FORMAT_INVALID = SystemMessages.UPLOAD_FILE_FORMAT_INVALID;
    public static final String UPLOAD_FILENAME_EMPTY = SystemMessages.UPLOAD_FILENAME_EMPTY;
    public static final String UPLOAD_FILENAME_INVALID = SystemMessages.UPLOAD_FILENAME_INVALID;
    public static final String UPLOAD_AVATAR_SIZE_EXCEED = SystemMessages.UPLOAD_AVATAR_SIZE_EXCEED;
    public static final String UPLOAD_FILENAME_CANNOT_EMPTY = SystemMessages.UPLOAD_FILENAME_CANNOT_EMPTY;
    public static final String UPLOAD_PATH_INVALID = SystemMessages.UPLOAD_PATH_INVALID;
    public static final String UPLOAD_PATH_PARSE_FAIL = SystemMessages.UPLOAD_PATH_PARSE_FAIL;
    public static final String UPLOAD_FILE_NOT_EXIST = SystemMessages.UPLOAD_FILE_NOT_EXIST;
    public static final String UPLOAD_FILE_DELETE_FAIL = SystemMessages.UPLOAD_FILE_DELETE_FAIL;
    public static final String UPLOAD_DELETE_FILE_FAIL_PREFIX = SystemMessages.UPLOAD_DELETE_FILE_FAIL_PREFIX;
    public static final String FILE_UPLOAD_FAIL = SystemMessages.FILE_UPLOAD_FAIL;
    public static final String INVALID_FILENAME = SystemMessages.INVALID_FILENAME;

    // ==================== 9. 统计数据模块 (Statistics) ====================

    public static final String STATISTICS_GET_SUCCESS = SystemMessages.STATISTICS_GET_SUCCESS;
    public static final String STATISTICS_GET_FAIL = SystemMessages.STATISTICS_GET_FAIL;
    public static final String STATISTICS_HISTORY_QUERY_FAIL = SystemMessages.STATISTICS_HISTORY_QUERY_FAIL;
    public static final String STATISTICS_HOT_SHOW_QUERY_FAIL = SystemMessages.STATISTICS_HOT_SHOW_QUERY_FAIL;
    public static final String STATISTICS_SALES_TREND_QUERY_FAIL = SystemMessages.STATISTICS_SALES_TREND_QUERY_FAIL;
    public static final String STATISTICS_USER_PROFILE_QUERY_FAIL = SystemMessages.STATISTICS_USER_PROFILE_QUERY_FAIL;

    // ==================== 10. 缓存模块 (Cache) ====================

    public static final String CACHE_CLEAR_FAIL_PREFIX = SystemMessages.CACHE_CLEAR_FAIL_PREFIX;
    public static final String CACHE_VIEW_FAIL_PREFIX = SystemMessages.CACHE_VIEW_FAIL_PREFIX;
    public static final String SHOW_CACHE_CLEAR_SUCCESS = SystemMessages.SHOW_CACHE_CLEAR_SUCCESS;
    public static final String SHOW_CACHE_NOT_FOUND = SystemMessages.SHOW_CACHE_NOT_FOUND;
    public static final String SHOW_GEO_LOAD_SUCCESS = SystemMessages.SHOW_GEO_LOAD_SUCCESS;
    public static final String USER_CACHE_NONE = SystemMessages.USER_CACHE_NONE;

    // ==================== 11. 积分模块 (Points) ====================

    public static final String POINTS_NOT_ENOUGH = SystemMessages.POINTS_NOT_ENOUGH;
    public static final String POINTS_LOTTERY_FAIL = SystemMessages.POINTS_LOTTERY_FAIL;
    public static final String POINTS_LOTTERY_LIMIT = SystemMessages.POINTS_LOTTERY_LIMIT;
    public static final String POINTS_COUPON_EXCHANGE_FAIL = SystemMessages.POINTS_COUPON_EXCHANGE_FAIL;
    public static final String POINTS_ADD_FAIL = SystemMessages.POINTS_ADD_FAIL;
    public static final String POINTS_UPDATE_FAIL = SystemMessages.POINTS_UPDATE_FAIL;
    public static final String POINTS_DEDUCT_FAIL = SystemMessages.POINTS_DEDUCT_FAIL;
    public static final String POINTS_REWARD_FAIL = SystemMessages.POINTS_REWARD_FAIL;
    public static final String POINTS_QUERY_FAIL = SystemMessages.POINTS_QUERY_FAIL;
    public static final String POINTS_RECORD_QUERY_FAIL = SystemMessages.POINTS_RECORD_QUERY_FAIL;
    public static final String POINTS_LOTTERY_RECORD_QUERY_FAIL = SystemMessages.POINTS_LOTTERY_RECORD_QUERY_FAIL;
    public static final String POINTS_COUPON_SENT_RESULT = SystemMessages.POINTS_COUPON_SENT_RESULT;

    // ==================== 12. 操作回滚模块 (Rollback) ====================

    public static final String ROLLBACK_FAIL_PREFIX = SystemMessages.ROLLBACK_FAIL_PREFIX;
    public static final String ROLLBACK_LOG_NOT_EXIST = SystemMessages.ROLLBACK_LOG_NOT_EXIST;
    public static final String ROLLBACK_TIMEOUT_24H = SystemMessages.ROLLBACK_TIMEOUT_24H;
    public static final String ROLLBACK_TARGET_IDS_EMPTY = SystemMessages.ROLLBACK_TARGET_IDS_EMPTY;
    public static final String ROLLBACK_UNSUPPORTED_OPERATION = SystemMessages.ROLLBACK_UNSUPPORTED_OPERATION;

    // ==================== 13. 操作日志模块 (Operation Log) ====================

    public static final String OPERATION_LOG_QUERY_FAIL = SystemMessages.OPERATION_LOG_QUERY_FAIL;
    public static final String OPERATION_LOG_EXPORT_IN_DEVELOPMENT = SystemMessages.OPERATION_LOG_EXPORT_IN_DEVELOPMENT;

    // ==================== 14. 通用消息 (General) ====================

    public static final String OPERATION_SUCCESS = SystemMessages.OPERATION_SUCCESS;
    public static final String OPERATION_FAIL = SystemMessages.OPERATION_FAIL;
    public static final String PARAM_ERROR = SystemMessages.PARAM_ERROR;
    public static final String SYSTEM_BUSY = SystemMessages.SYSTEM_BUSY;
    public static final String PHONE_FORMAT_ERROR = SystemMessages.PHONE_FORMAT_ERROR;
    public static final String CREDIT_LOW = SystemMessages.CREDIT_LOW;
    public static final String CONTROLLER_REVIEW_PUBLISH_FAIL_PREFIX = SystemMessages.CONTROLLER_REVIEW_PUBLISH_FAIL_PREFIX;
    public static final String CONTROLLER_QUERY_FAIL_PREFIX = SystemMessages.CONTROLLER_QUERY_FAIL_PREFIX;
    public static final String ADMIN_DATA_REPAIR_FAIL_PREFIX = SystemMessages.ADMIN_DATA_REPAIR_FAIL_PREFIX;
    public static final String ADMIN_DATA_REPAIR_SUCCESS = SystemMessages.ADMIN_DATA_REPAIR_SUCCESS;

    // ==================== 15. 异常处理模块 (Exception Handler) ====================

    public static final String EXCEPTION_SERVER_ERROR = SystemMessages.EXCEPTION_SERVER_ERROR;
    public static final String EXCEPTION_REQUEST_PARAM_FORMAT_ERROR = SystemMessages.EXCEPTION_REQUEST_PARAM_FORMAT_ERROR;
    public static final String EXCEPTION_PARAM_VALIDATION_FAIL = SystemMessages.EXCEPTION_PARAM_VALIDATION_FAIL;
    public static final String EXCEPTION_MISSING_REQUIRED_PARAM_PREFIX = SystemMessages.EXCEPTION_MISSING_REQUIRED_PARAM_PREFIX;

    // ==================== 16. 通知模块 (Notification) ====================

    public static final String NOTIFY_SEND_SUCCESS = SystemMessages.NOTIFY_SEND_SUCCESS;
    public static final String NOTIFY_SEND_FAIL = SystemMessages.NOTIFY_SEND_FAIL;
    public static final String NOTIFICATION_NOT_FOUND = SystemMessages.NOTIFICATION_NOT_FOUND;
    public static final String NOTIFICATION_NOT_BELONG = SystemMessages.NOTIFICATION_NOT_BELONG;
    public static final String NOTIFICATION_ID_INVALID = SystemMessages.NOTIFICATION_ID_INVALID;
    public static final String NOTIFICATION_LIST_QUERY_FAIL = SystemMessages.NOTIFICATION_LIST_QUERY_FAIL;
    public static final String NOTIFICATION_MARK_READ_FAIL = SystemMessages.NOTIFICATION_MARK_READ_FAIL;
    public static final String NOTIFICATION_MARK_ALL_READ_FAIL = SystemMessages.NOTIFICATION_MARK_ALL_READ_FAIL;

    // ==================== 17. 批量操作模块 (Batch Operation) ====================

    public static final String BATCH_OPERATION_LIMIT_50 = SystemMessages.BATCH_OPERATION_LIMIT_50;
    public static final String BATCH_OPERATION_LIMIT_100 = SystemMessages.BATCH_OPERATION_LIMIT_100;
    public static final String BATCH_OPERATION_LIMIT_30 = SystemMessages.BATCH_OPERATION_LIMIT_30;
    public static final String ADMIN_DISABLE_USER_REASON_REQUIRED = SystemMessages.ADMIN_DISABLE_USER_REASON_REQUIRED;

    // ==================== 18. 管理端票种特殊消息 ====================

    public static final String ADMIN_TICKET_SYSTEM_ERROR_VERIFY_FAIL = SystemMessages.ADMIN_TICKET_SYSTEM_ERROR_VERIFY_FAIL;
    public static final String ADMIN_TICKET_CANNOT_MODIFY_ONLINE = SystemMessages.ADMIN_TICKET_CANNOT_MODIFY_ONLINE;
    public static final String ADMIN_TICKET_ONLINE_SHOW_NOT_EXIST_PREFIX = SystemMessages.ADMIN_TICKET_ONLINE_SHOW_NOT_EXIST_PREFIX;
    public static final String ADMIN_TICKET_ONLINE_SHOW_NOT_EXIST_SUFFIX = SystemMessages.ADMIN_TICKET_ONLINE_SHOW_NOT_EXIST_SUFFIX;
    public static final String ADMIN_TICKET_LOCKED_PREFIX = SystemMessages.ADMIN_TICKET_LOCKED_PREFIX;
    public static final String ADMIN_TICKET_DELETE_HAS_SALES_PREFIX = SystemMessages.ADMIN_TICKET_DELETE_HAS_SALES_PREFIX;
    public static final String ADMIN_TICKET_DELETE_HAS_SALES_SUFFIX = SystemMessages.ADMIN_TICKET_DELETE_HAS_SALES_SUFFIX;
    public static final String ADMIN_TICKET_DELETE_VERIFY_FAIL = SystemMessages.ADMIN_TICKET_DELETE_VERIFY_FAIL;
    public static final String ADMIN_SECKILL_TICKET_NOT_FOUND = SystemMessages.ADMIN_SECKILL_TICKET_NOT_FOUND;

    // ==================== 19. 运行时异常消息 (Runtime Exception) ====================

    public static final String RUNTIME_TICKET_STOCK_RELEASE_FAIL = SystemMessages.RUNTIME_TICKET_STOCK_RELEASE_FAIL;
    public static final String RUNTIME_DISTRIBUTED_LOCK_FAIL = SystemMessages.RUNTIME_DISTRIBUTED_LOCK_FAIL;
    public static final String RUNTIME_ORDER_CREATE_FAIL = SystemMessages.RUNTIME_ORDER_CREATE_FAIL;
    public static final String RUNTIME_SEAT_GENERATE_FAIL_PREFIX = SystemMessages.RUNTIME_SEAT_GENERATE_FAIL_PREFIX;
    public static final String RUNTIME_SEAT_GENERATE_FAIL_ROLLBACK_PREFIX = SystemMessages.RUNTIME_SEAT_GENERATE_FAIL_ROLLBACK_PREFIX;
    public static final String RUNTIME_TARGET_SHOW_NOT_EXIST = SystemMessages.RUNTIME_TARGET_SHOW_NOT_EXIST;
}
