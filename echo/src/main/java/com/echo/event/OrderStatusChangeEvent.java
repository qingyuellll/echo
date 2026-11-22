package com.echo.event;

import com.echo.dto.OrderStatusDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 订单状态变更事件
 *
 * @author tulip
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusChangeEvent implements Serializable {

    /** 序列化版本号（显式定义以防止反序列化时版本不兼容） */
    @Serial
    private static final long serialVersionUID = 1L;

    // ==================== 业务标识字段 ====================

    /**
     * 事件ID（全局唯一标识）
     * <p>
     * 格式：EVT_{时间戳}_{UUID前8位}
     * 示例：EVT_1698144000000_A1B2C3D4
     * <p>
     * 用途：
     * - 幂等性控制（防止重复处理同一事件）
     * - 日志追踪（通过 eventId 关联完整的事件处理链路）
     * - 事件溯源查询（支持按时间范围检索历史事件）
     */
    private String eventId;

    /**
     * 订单ID（正式订单或预订单tempId）
     * <p>
     * 用于定位具体的订单记录，关联订单主表
     */
    private String orderId;

    /**
     * 操作用户ID
     * <p>
     * 标识触发此事件的用户，用于：
     * - 发送个性化通知
     * - 权限校验和审计日志
     * - 统计用户行为数据
     */
    private Long userId;

    /**
     * 票种ID
     * <p>
     * 关联票务资源，用于：
     * - 库存更新（释放/扣减对应票种的库存）
     * - 价格计算（获取票种单价用于退款金额计算）
     */
    private Long ticketId;

    /**
     * 演出ID
     * <p>
     * 关联演出信息，用于：
     * - 缓存失效（清除该演出的详情和票种缓存）
     * - 数据统计（更新演出的销售数据）
     * - 通知推送（向关注该演出的用户推送动态）
     */
    private Long showId;

    // ==================== 状态变更字段 ====================

    /**
     * 变更前的状态码
     * <p>
     * 记录状态流转的起点，用于：
     * - 状态机回溯（验证状态转换是否合法）
     * - 业务规则校验（如"只有已支付状态才能申请退款"）
     * <p>
     * 状态码定义见 OrderStatusDTO：0=预占, 2=已支付, 3=已核销, 4=已取消, 5=退款中, 6=已退款
     */
    private Integer fromStatus;

    /**
     * 变更后的状态码
     * <p>
     * 记录状态流转的终点，与 fromStatus 配合形成完整的状态变迁记录
     */
    private Integer toStatus;

    /**
     * 变更后状态的详细信息
     * <p>
     * 携带完整的 OrderStatusDTO 对象，包含：
     * - 状态名称（中文描述，如"已支付"）
     * - 状态说明（详细业务描述）
     * - CSS类名（前端展示样式）
     * - 状态权限标记（是否可支付/取消/退款）
     * <p>
     * 为什么只保留 toStatusInfo 而没有 fromStatusInfo？
     * 通常只需关心目标状态的展示信息，历史状态可通过 orderId 从数据库查询获取
     */
    private OrderStatusDTO toStatusInfo;

    // ==================== 元数据字段 ====================

    /**
     * 事件类型（标识"发生了什么"）
     * <p>
     * 可选值（详见下方 EVENT_* 常量定义）：
     * - PAY: 支付成功
     * - CANCEL: 订单取消
     * - REFUND: 申请退款
     * - REFUND_APPROVE: 退款审批通过
     * - REFUND_REJECT: 退款审批拒绝
     * - TIMEOUT: 超时自动取消
     * - VERIFY: 核销入场
     */
    private String eventType;

    /**
     * 事件来源（标识"谁触发的"）
     * <p>
     * 可选值（详见下方 SOURCE_* 常量定义）：
     * - USER_PAYMENT: 用户主动操作（支付/取消/申请退款）
     * - ADMIN_OPERATION: 管理员后台操作（强制取消/审批退款）
     * - SYSTEM_TIMEOUT: 系统自动触发（超时取消未支付订单）
     * - AUTO_VERIFY: 自动化流程（扫码核销）
     */
    private String source;

    /**
     * 事件发生时间戳
     * <p>
     * 由工厂方法自动填充为 LocalDateTime.now()，确保时间准确性。
     * 用于：
     * - 事件排序和处理顺序控制
     * - 超时检测和SLA监控
     * - 统计分析（如"今日支付成功率"）
     */
    private LocalDateTime eventTime;

    /**
     * 附加业务数据（JSON格式字符串）
     * <p>
     * 存储特定事件类型所需的额外上下文信息，避免频繁修改事件结构。
     * <p>
     * 典型使用场景：
     * - PAY 事件：{"payAmount": 50000, "payMethod": "WECHAT", "pointsUsed": 100}
     * - REFUND 事件：{"refundAmount": 50000, "refundReason": "行程冲突"}
     * - VERIFY 事件：{"verifyCode": "ABC123", "verifyLocation": "A入口"}
     * <p>
     * 注意：此字段为可选字段，部分事件类型可能不需要附加数据
     */
    private String metadata;

    // ==================== 事件类型常量 ====================

    /** 支付成功（用户完成支付操作） */
    public static final String EVENT_PAY = "PAY";

    /** 订单取消（用户主动取消或管理员强制取消） */
    public static final String EVENT_CANCEL = "CANCEL";

    /** 申请退款（用户提交退款请求，等待管理员审核） */
    public static final String EVENT_REFUND = "REFUND";

    /** 退款审批通过（管理员同意退款，资金将原路退回） */
    public static final String EVENT_REFUND_APPROVE = "REFUND_APPROVE";

    /** 退款审批拒绝（管理员驳回退款申请，订单保持原状） */
    public static final String EVENT_REFUND_REJECT = "REFUND_REJECT";

    /** 超时自动取消（系统在规定时间内未收到支付，自动释放资源） */
    public static final String EVENT_TIMEOUT = "TIMEOUT";

    /** 核销入场（用户到场扫码，订单完成交易闭环） */
    public static final String EVENT_VERIFY = "VERIFY";

    // ==================== 事件来源常量 ====================

    /** 用户主动操作（支付、取消、申请退款等） */
    public static final String SOURCE_USER = "USER_PAYMENT";

    /** 管理员后台操作（强制取消、审批退款等） */
    public static final String SOURCE_ADMIN = "ADMIN_OPERATION";

    /** 系统自动触发（超时取消、定时任务等） */
    public static final String SOURCE_SYSTEM_TIMEOUT = "SYSTEM_TIMEOUT";

    /** 自动化流程（扫码核销、定时任务等） */
    public static final String SOURCE_AUTO_VERIFY = "AUTO_VERIFY";

    // ==================== 工厂方法（静态工厂模式）====================

    /**
     * 创建支付成功事件
     * <p>
     * 当用户完成微信/支付宝支付后调用，触发后续业务流程：
     * - 发送支付成功通知（短信/站内信/App推送）
     * - 更新票种库存（扣减已售数量）
     * - 清除相关缓存（演出详情、票种列表）
     * - 赠送积分（根据支付金额计算）
     *
     * @param orderId    订单ID
     * @param userId     操作用户ID
     * @param ticketId   票种ID
     * @param showId     演出ID
     * @param fromStatus 变更前状态码（通常为 0-预占）
     * @param toStatus   变更后状态DTO（通常为 OrderStatusDTO.PAID()）
     * @param metadata   附加数据（JSON格式，包含支付金额、支付方式等信息）
     * @return 构建好的支付事件对象
     */
    public static OrderStatusChangeEvent payEvent(String orderId, Long userId, Long ticketId, Long showId,
                                                   Integer fromStatus, OrderStatusDTO toStatus, String metadata) {
        return createBaseEvent(orderId, userId, ticketId, showId, fromStatus, toStatus)
                .eventType(EVENT_PAY)
                .source(SOURCE_USER)
                .metadata(metadata)
                .build();
    }

    /**
     * 创建订单取消事件
     * <p>
     * 当用户主动取消订单或管理员强制取消时调用，触发后续业务流程：
     * - 释放预占库存（恢复票种可用数量）
     * - 发送取消确认通知
     * - 清除相关缓存
     * <p>
     * 注意：source 参数用于区分是用户取消还是管理员操作，影响通知内容和权限校验
     *
     * @param orderId    订单ID
     * @param userId     操作用户ID
     * @param ticketId   票种ID
     * @param fromStatus 变更前状态码（可能为 0-预占 或 2-已支付）
     * @param toStatus   变更后状态DTO（通常为 OrderStatusDTO.CANCELLED()）
     * @param source     事件来源（SOURCE_USER 或 SOURCE_ADMIN）
     * @return 构建好的取消事件对象
     */
    public static OrderStatusChangeEvent cancelEvent(String orderId, Long userId, Long ticketId,
                                                      Integer fromStatus, OrderStatusDTO toStatus, String source) {
        return createBaseEvent(orderId, userId, ticketId, null, fromStatus, toStatus)
                .eventType(EVENT_CANCEL)
                .source(source)
                .build();
    }

    /**
     * 创建申请退款事件
     * <p>
     * 当用户提交退款申请时调用，触发后续业务流程：
     * - 通知管理员审核（站内信/邮件提醒）
     * - 冻结订单状态（防止重复操作）
     * - 记录退款原因和时间
     *
     * @param orderId    订单ID
     * @param userId     操作用户ID
     * @param ticketId   票种ID
     * @param showId     演出ID
     * @param fromStatus 变更前状态码（通常为 2-已支付）
     * @param toStatus   变更后状态DTO（通常为 OrderStatusDTO.REFUND_PENDING()）
     * @param metadata   附加数据（JSON格式，包含退款原因、退款金额等）
     * @return 构建好的退款申请事件对象
     */
    public static OrderStatusChangeEvent refundEvent(String orderId, Long userId, Long ticketId, Long showId,
                                                     Integer fromStatus, OrderStatusDTO toStatus, String metadata) {
        return createBaseEvent(orderId, userId, ticketId, showId, fromStatus, toStatus)
                .eventType(EVENT_REFUND)
                .source(SOURCE_USER)
                .metadata(metadata)
                .build();
    }

    /**
     * 创建超时自动取消事件
     * <p>
     * 当订单在规定时间内（默认5分钟）未完成支付时，由系统定时任务触发，
     * 用于自动释放预占资源，避免资源浪费。
     * <p>
     * 触发时机：RocketMQ 延时消息投递后，消费者检查订单状态仍为"预占"
     *
     * @param orderId    订单ID
     * @param userId     下单用户ID
     * @param ticketId   票种ID
     * @param fromStatus 变更前状态码（必须为 0-预占）
     * @param toStatus   变更后状态DTO（通常为 OrderStatusDTO.CANCELLED()）
     * @return 构建好的超时取消事件对象
     */
    public static OrderStatusChangeEvent timeoutEvent(String orderId, Long userId, Long ticketId,
                                                       Integer fromStatus, OrderStatusDTO toStatus) {
        return createBaseEvent(orderId, userId, ticketId, null, fromStatus, toStatus)
                .eventType(EVENT_TIMEOUT)
                .source(SOURCE_SYSTEM_TIMEOUT)
                .build();
    }

    /**
     * 创建退款审批拒绝事件
     * <p>
     * 当管理员驳回用户的退款申请时调用，触发后续业务流程：
     * - 恢复订单为"已支付"状态
     * - 通知用户拒绝原因（短信/站内信）
     * - 记录审批日志（谁在什么时间拒绝了什么订单）
     *
     * @param orderId    订单ID
     * @param userId     申请退款的用户ID
     * @param ticketId   票种ID
     * @param showId     演出ID
     * @param fromStatus 变更前状态码（必须为 5-退款中）
     * @param toStatus   变更后状态DTO（通常为 OrderStatusDTO.PAID()，恢复原状态）
     * @return 构建好的退款拒绝事件对象
     */
    public static OrderStatusChangeEvent refundRejectEvent(String orderId, Long userId, Long ticketId, Long showId,
                                                           Integer fromStatus, OrderStatusDTO toStatus) {
        return createBaseEvent(orderId, userId, ticketId, showId, fromStatus, toStatus)
                .eventType(EVENT_REFUND_REJECT)
                .source(SOURCE_ADMIN)
                .build();
    }

    /**
     * 创建核销入场事件
     * <p>
     * 当用户到达现场扫码核销时调用（通常由自动化流程触发），触发后续业务流程：
     * - 标记订单为"已完成"状态（影响限购判断）
     * - 更新统计数据（演出实际到场人数）
     * - 发送核销成功通知（如有二次消费优惠）
     *
     * @param orderId    订单ID
     * @param userId     核销用户ID
     * @param ticketId   票种ID
     * @param showId     演出ID
     * @param fromStatus 变更前状态码（必须为 2-已支付）
     * @param toStatus   变更后状态DTO（通常为 OrderStatusDTO.VERIFIED()）
     * @return 构建好的核销事件对象
     */
    public static OrderStatusChangeEvent verifyEvent(String orderId, Long userId, Long ticketId, Long showId,
                                                     Integer fromStatus, OrderStatusDTO toStatus) {
        return createBaseEvent(orderId, userId, ticketId, showId, fromStatus, toStatus)
                .eventType(EVENT_VERIFY)
                .source(SOURCE_AUTO_VERIFY)
                .build();
    }

    // ==================== 私有工具方法 ====================

    /**
     * 创建事件构建器的通用基础方法（提取公共逻辑）
     * <p>
     * 所有工厂方法共用的初始化逻辑，避免代码重复。
     * 自动设置：eventId（唯一标识）、eventTime（当前时间）、核心业务字段
     *
     * @param orderId    订单ID
     * @param userId     用户ID
     * @param ticketId   票种ID
     * @param showId     演出ID（可为null，如取消/超时事件不一定需要）
     * @param fromStatus 变更前状态码
     * @param toStatus   变更后状态DTO
     * @return 已初始化基础字段的事件构建器
     */
    private static OrderStatusChangeEventBuilder createBaseEvent(String orderId, Long userId, Long ticketId,
                                                                  Long showId, Integer fromStatus,
                                                                  OrderStatusDTO toStatus) {
        return OrderStatusChangeEvent.builder()
                .eventId(generateEventId())
                .orderId(orderId)
                .userId(userId)
                .ticketId(ticketId)
                .showId(showId)
                .fromStatus(fromStatus)
                .toStatus(toStatus.getCode())
                .toStatusInfo(toStatus)
                .eventTime(LocalDateTime.now());
    }

    /**
     * 生成全局唯一的事件ID
     * <p>
     * 生成规则：EVT_{毫秒时间戳}_{UUID前8位大写}
     * <p>
     * 设计考量：
     * - 时间戳在前：支持按时间范围排序和查询（如"查找今天所有支付事件"）
     * - UUID 后缀：防止同一毫秒内多线程并发生成相同ID（高并发安全）
     * - 前缀 EVT：便于日志搜索和快速识别（grep/splunk/ELK）
     * - 总长度约30字符：适合数据库索引和 Redis Key 存储
     * <p>
     * 示例输出：EVT_1698144000000_A1B2C3D4
     *
     * @return 格式化的唯一事件ID字符串
     */
    public static String generateEventId() {
        return "EVT_" + System.currentTimeMillis() + "_" +
               UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
