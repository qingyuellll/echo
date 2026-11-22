package com.echo.utils;

/**
 * RocketMQ消息队列常量定义
 *
 * @author tulip
 */
public class MqConstants {

    private MqConstants() {}

    // ==================== 通用 Tag 定义 ====================

    /** 创建操作 */
    public static final String TAG_CREATE = "create";
    /** 取消/删除操作 */
    public static final String TAG_CANCEL = "cancel";
    /** 更新操作 */
    public static final String TAG_UPDATE = "update";
    /** 失效操作（缓存） */
    public static final String TAG_INVALIDATE = "invalidate";
    /** 通知操作 */
    public static final String TAG_NOTIFY = "notify";
    /** 超时处理 */
    public static final String TAG_TIMEOUT = "timeout";

    // ==================== RocketMQ - 秒杀订单 ====================

    /** 秒杀订单Topic */
    public static final String SECKILL_ORDER_TOPIC = "seckill-order-topic";

    /** 秒杀订单ConsumerGroup */
    public static final String SECKILL_ORDER_CONSUMER_GROUP = "seckill-order-consumer-group";

    // ==================== RocketMQ - 缓存失效广播 ====================

    /** 缓存失效Topic */
    public static final String CACHE_INVALIDATE_TOPIC = "cache-invalidate-topic";

    /** 缓存失效ConsumerGroup */
    public static final String CACHE_INVALIDATE_CONSUMER_GROUP = "cache-invalidate-consumer-group";

    /** 缓存失效 - 演出信息标签 */
    public static final String CACHE_INVALIDATE_TAG_SHOW = "show";
    /** 缓存失效 - 场次信息标签 */
    public static final String CACHE_INVALIDATE_TAG_SESSION = "session";
    /** 缓存失效 - 库存信息标签 */
    public static final String CACHE_INVALIDATE_TAG_INVENTORY = "inventory";
    /** 缓存失效 - 订单信息标签 */
    public static final String CACHE_INVALIDATE_TAG_ORDER = "order";

    // ==================== RocketMQ - 超时订单处理 ====================

    /** 超时订单Topic(延时消息) */
    public static final String ORDER_TIMEOUT_TOPIC = "order-timeout-topic";

    /** 超时订单ConsumerGroup */
    public static final String ORDER_TIMEOUT_CONSUMER_GROUP = "order-timeout-consumer-group";

    /** 订单超时延时级别(level 9=5分钟) */
    public static final int ORDER_TIMEOUT_DELAY_LEVEL = 9;

    /** 订单超时时间(分钟) */
    public static final int ORDER_TIMEOUT_MINUTES = 5;

    // ==================== RocketMQ - 通知消息 ====================

    /** 通知推送Topic */
    public static final String NOTIFICATION_TOPIC = "notification-topic";

    /** 通知推送ConsumerGroup */
    public static final String NOTIFICATION_CONSUMER_GROUP = "notification-consumer-group";

    /** 通知 - 订单成功标签 */
    public static final String NOTIFICATION_TAG_ORDER_SUCCESS = "order_success";
    /** 通知 - 订单取消标签 */
    public static final String NOTIFICATION_TAG_ORDER_CANCEL = "order_cancel";
    /** 通知 - 演出开售标签 */
    public static final String NOTIFICATION_TAG_SHOW_ONSALE = "show_onsale";
    /** 通知 - 系统公告标签 */
    public static final String NOTIFICATION_TAG_SYSTEM_ANNOUNCEMENT = "system_announcement";

    // ==================== 工具方法 ====================

    /**
     * 生成完整的Topic:Tag格式字符串
     *
     * @param topic Topic名称
     * @param tag   标签名称(可为null)
     * @return "topic:tag"或"topic"
     */
    public static String buildDestination(String topic, String tag) {
        if (tag == null || tag.isEmpty()) {
            return topic;
        }
        return topic + ":" + tag;
    }

    /**
     * 生成消息唯一标识Key
     *
     * @param businessType 业务类型
     * @param businessId   业务ID
     * @return 消息唯一标识Key
     */
    public static String generateMessageKey(String businessType, String businessId) {
        return businessType + ":" + businessId + ":" + System.currentTimeMillis();
    }

}
