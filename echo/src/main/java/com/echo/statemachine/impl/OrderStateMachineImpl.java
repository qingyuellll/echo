package com.echo.statemachine.impl;

import com.echo.dto.OrderStatusDTO;
import com.echo.event.OrderStatusChangeEvent;
import com.echo.monitor.OrderMetricsService;
import com.echo.service.ITicketOrderService;
import com.echo.statemachine.OrderStateMachine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单状态机实现(RocketMQ Clustering模式)
 * <p>
 * 基于有限状态机(FSM)模式管理订单生命周期，定义合法流转路径并执行业务校验
 *
 * @author tulip
 */
@Slf4j
@Component
public class OrderStateMachineImpl implements OrderStateMachine {

    /** 状态码：预占(等待支付) */
    private static final int STATUS_RESERVED = 0;

    /** 状态码：已支付 */
    private static final int STATUS_PAID = 2;

    /** 状态码：已核销 */
    private static final int STATUS_USED = 3;

    /** 状态码：已取消 */
    private static final int STATUS_CANCELLED = 4;

    /** 状态码：已退款 */
    private static final int STATUS_REFUNDED = 5;

    /** 状态码：退款中(待审核) */
    private static final int STATUS_REFUND_PENDING = 6;

    /** 状态码：已拒绝(退款被拒) */
    private static final int STATUS_REJECTED = 7;

    /** 秒杀票类型标识 */
    private static final int TICKET_TYPE_SECKILL = 1;

    /** 有效订单状态集合(用于一客一单校验) */
    private static final List<Integer> VALID_ORDER_STATUSES = Arrays.asList(STATUS_PAID, STATUS_USED, STATUS_REFUND_PENDING);

    /** 订单服务(查询票种信息、统计历史订单) */
    @Resource
    @Lazy
    private ITicketOrderService ticketOrderService;

    /**
     * 自身代理（@Lazy注入的是代理对象而非原始实例）。
     * <p>
     * Spring事务基于AOP代理实现，this.transition(...)这类自调用会绕过代理导致@Transactional
     * 无声失效。applyTransition内部必须通过self.transition(...)走代理调用——REQUIRED传播下
     * 会加入applyTransition已有的事务，语义不变；而transition被外部直接调用时（如
     * TicketOrderServiceImpl的只读校验路径）注解也真实生效。
     */
    @Resource
    @Lazy
    private OrderStateMachine self;

    /** 订单指标监控服务(可选，用于记录状态转换指标) */
    @Resource
    private OrderMetricsService orderMetricsService;

    /** 状态流转规则表(线程安全的ConcurrentHashMap) */
    private final Map<Integer, Set<Integer>> transitionRules = new ConcurrentHashMap<>();

    /** 状态信息缓存(避免频繁创建DTO对象) */
    private final Map<Integer, OrderStatusDTO> statusInfoCache = new ConcurrentHashMap<>();

    /**
     * 构造函数：初始化状态流转规则和状态信息缓存
     */
    public OrderStateMachineImpl() {
        initTransitionRules();
        initStatusInfoCache();
    }

    /** 初始化状态流转规则矩阵 */
    private void initTransitionRules() {
        transitionRules.put(STATUS_RESERVED, new HashSet<>(Arrays.asList(STATUS_PAID, STATUS_CANCELLED)));
        transitionRules.put(STATUS_PAID, new HashSet<>(Arrays.asList(STATUS_USED, STATUS_REFUNDED, STATUS_REFUND_PENDING)));
        transitionRules.put(STATUS_USED, Collections.emptySet());
        transitionRules.put(STATUS_CANCELLED, Collections.emptySet());
        transitionRules.put(STATUS_REFUNDED, Collections.emptySet());
        transitionRules.put(STATUS_REFUND_PENDING, new HashSet<>(Arrays.asList(STATUS_REFUNDED, STATUS_REJECTED)));
        transitionRules.put(STATUS_REJECTED, new HashSet<>(Arrays.asList(STATUS_REFUND_PENDING)));
    }

    /** 初始化状态信息缓存 */
    private void initStatusInfoCache() {
        statusInfoCache.put(STATUS_RESERVED, OrderStatusDTO.RESERVED());
        statusInfoCache.put(STATUS_PAID, OrderStatusDTO.PAID());
        statusInfoCache.put(STATUS_USED, OrderStatusDTO.USED());
        statusInfoCache.put(STATUS_CANCELLED, OrderStatusDTO.CANCELLED());
        statusInfoCache.put(STATUS_REFUNDED, OrderStatusDTO.REFUNDED());
        statusInfoCache.put(STATUS_REFUND_PENDING, OrderStatusDTO.REFUND_PENDING());
        statusInfoCache.put(STATUS_REJECTED, OrderStatusDTO.REJECTED());
    }

    /**
     * 执行状态转换(核心入口方法，CQS中的"查询"侧：只读，不落库)
     * <p>
     * readOnly=true：本方法只做规则矩阵断言+业务校验+事件构建，不产生任何写操作。
     * 被外部直接调用时提供一致性读快照；被applyTransition经self代理调用时
     * （REQUIRED传播）加入外层读写事务，readOnly提示被外层覆盖，无副作用。
     *
     * @param orderId       订单ID
     * @param currentStatus 当前状态码
     * @param targetStatus  目标状态码
     * @param context       转换上下文
     * @return 状态转换结果
     */
    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public StateTransitionResult transition(String orderId, Integer currentStatus, Integer targetStatus,
                                              TransitionContext context) {
        long startTime = System.currentTimeMillis();

        log.info("[状态机] 开始状态转换 - orderId:{}, {}→{}, userId:{}, ticketId:{}",
                orderId, currentStatus, targetStatus, context.getUserId(), context.getTicketId());

        try {
            if (!canTransition(currentStatus, targetStatus)) {
                String error = String.format("非法状态转换: %d → %d", currentStatus, targetStatus);
                log.warn("[状态机] 转换失败 - {}", error);
                recordFailMetrics(orderId, currentStatus, targetStatus, error, startTime);
                return StateTransitionResult.failure(error);
            }

            validateBusinessRules(orderId, currentStatus, targetStatus, context);

            OrderStatusChangeEvent event = buildEvent(orderId, currentStatus, targetStatus, context);

            log.info("[状态机] 转换成功 - eventId:{}, orderId:{}, {}→{}",
                    event.getEventId(), orderId, currentStatus, targetStatus);

            recordSuccessMetrics(orderId, currentStatus, targetStatus, startTime);
            return StateTransitionResult.success(event);

        } catch (IllegalStateException e) {
            log.error("[状态机] 业务校验失败 - orderId:{}, error:{}", orderId, e.getMessage());
            recordFailMetrics(orderId, currentStatus, targetStatus, e.getMessage(), startTime);
            return StateTransitionResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[状态机] 系统异常 - orderId:{}, error:{}", orderId, e.getMessage(), e);
            recordFailMetrics(orderId, currentStatus, targetStatus, "系统异常", startTime);
            return StateTransitionResult.failure("系统繁忙，请稍后重试");
        }
    }

    /**
     * 执行状态转换并落库（订单状态写入的唯一入口）
     * <p>
     * 状态机负责"合法性"（规则矩阵+业务校验），SQL旧状态条件负责"并发安全"（CAS式条件更新），
     * 影响行数为0即视为被其他事务抢先，转换失败。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public StateTransitionResult applyTransition(String orderId, Integer targetStatus,
                                                 Map<String, Object> extraColumns,
                                                 TransitionContext context) {
        com.echo.entity.TicketOrder order = ticketOrderService.getById(orderId);
        if (order == null) {
            return StateTransitionResult.failure("订单不存在");
        }
        Integer currentStatus = order.getStatus() != null ? order.getStatus() : STATUS_RESERVED;

        // 复用既有校验链：规则矩阵合法性 + 业务规则 + 事件构建
        // 必须经self代理调用：this.transition(...)绕过AOP代理会让@Transactional失效
        // （Spring事务基于代理实现）；REQUIRED传播下加入本方法事务，语义等价于原自调用
        StateTransitionResult result = self.transition(orderId, currentStatus, targetStatus, context);
        if (!result.isSuccess()) {
            return result;
        }

        // 条件更新：where id=? and status=旧状态，保证并发下只有一个事务能完成本次流转
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.echo.entity.TicketOrder> wrapper =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.echo.entity.TicketOrder>()
                        .eq("id", Long.valueOf(orderId))
                        .eq("status", currentStatus)
                        .set("status", targetStatus);
        if (extraColumns != null) {
            extraColumns.forEach(wrapper::set);
        }
        boolean updated = ticketOrderService.update(wrapper);
        if (!updated) {
            log.warn("[状态机] 并发冲突 - orderId:{}，期望状态{}已被其他事务变更，转换到{}失败",
                    orderId, currentStatus, targetStatus);
            recordFailMetrics(orderId, currentStatus, targetStatus, "并发冲突", System.currentTimeMillis());
            return StateTransitionResult.failure("订单状态已变更，请刷新后重试");
        }

        log.info("[状态机] 状态已落库 - orderId:{}, {}→{}", orderId, currentStatus, targetStatus);
        return result;
    }

    /**
     * 检查状态转换是否合法
     * <p>
     * 通过查询状态流转规则矩阵判断是否允许从fromStatus转换到toStatus
     *
     * @param fromStatus 起始状态码
     * @param toStatus   目标状态码
     * @return true=合法转换, false=非法转换
     */
    @Override
    public boolean canTransition(Integer fromStatus, Integer toStatus) {
        if (fromStatus == null || toStatus == null) {
            return false;
        }
        Set<Integer> allowedTargets = transitionRules.get(fromStatus);
        return allowedTargets != null && allowedTargets.contains(toStatus);
    }

    /**
     * 获取指定状态的所有合法目标状态列表
     *
     * @param currentStatus 当前状态码
     * @return 可转换的目标状态列表(可能为空列表)
     */
    @Override
    public List<Integer> getAllowedTransitions(Integer currentStatus) {
        Set<Integer> allowed = transitionRules.getOrDefault(currentStatus, Collections.emptySet());
        return new ArrayList<>(allowed);
    }

    /**
     * 获取状态的详细信息(DTO)
     *
     * @param status 状态码
     * @return 状态信息DTO(如果存在), null(如果不支持该状态)
     */
    @Override
    public OrderStatusDTO getStatusInfo(Integer status) {
        return statusInfoCache.get(status);
    }

    /**
     * 执行业务规则校验(根据目标状态分发到具体校验逻辑)
     *
     * @param orderId    订单ID
     * @param fromStatus 起始状态码
     * @param toStatus   目标状态码
     * @param context    转换上下文
     * @throws IllegalStateException 如果校验不通过
     */
    private void validateBusinessRules(String orderId, Integer fromStatus, Integer toStatus,
                                         TransitionContext context) {
        Long userId = context.getUserId();
        Long ticketId = context.getTicketId();

        switch (toStatus) {
            case STATUS_PAID:
                validatePayRules(orderId, userId, ticketId);
                break;
            case STATUS_USED:
                validateVerifyRules(fromStatus);
                break;
            case STATUS_CANCELLED:
                validateCancelRules(fromStatus);
                break;
            case STATUS_REFUNDED:
                validateRefundedRules(fromStatus);
                break;
            case STATUS_REFUND_PENDING:
                validateRefundPendingRules(fromStatus);
                break;
            case STATUS_REJECTED:
                validateRejectedRules(fromStatus);
                break;
            default:
                throw new IllegalStateException("不支持的目标状态: " + toStatus);
        }
    }

    /**
     * 校验支付规则(核心：一客一单限制)
     * <p>
     * 仅对秒杀票生效：每个用户对同一秒杀票种只能购买1张有效订单
     *
     * @param orderId 订单ID
     * @param userId  用户ID
     * @param ticketId 票种ID
     * @throws IllegalStateException 如果违反一客一单规则
     */
    private void validatePayRules(String orderId, Long userId, Long ticketId) {
        if (userId == null || ticketId == null) {
            throw new IllegalStateException("用户ID和票种ID不能为空");
        }

        boolean isSeckillTicket = checkIsSeckillTicket(ticketId);

        if (isSeckillTicket) {
            enforceOneUserOneTicketRule(orderId, userId, ticketId);
        }
    }

    /**
     * 强制执行一客一单规则
     * <p>
     * 查询用户对该秒杀票种的有效订单数(已支付/已核销/退款中)，如果>0则拦截
     *
     * @param orderId  订单ID
     * @param userId   用户ID
     * @param ticketId 票种ID
     * @throws IllegalStateException 如果用户已有有效订单
     */
    private void enforceOneUserOneTicketRule(String orderId, Long userId, Long ticketId) {
        // TOCTOU射程说明：本count读数与applyTransition的CAS落库之间存在时间窗口，
        // 窗口内另一订单转为有效状态不会被本次读数感知——这是"校验里读了但不在WHERE里"的典型窗口。
        // 该窗口当前不可达：上游seckill.lua以SISMEMBER原子判重保证同一(userId,ticketId)
        // 至多存在一个未回滚订单，SREM仅在退款/取消完成后释放资格，此时旧订单已离开
        // VALID_ORDER_STATUSES集合（CAS原子切换保证count读不到中间态）。因此
        // "两个同票订单并发转PAID"的前提不存在，无需为此加锁或扩大CAS射程；
        // 若未来放松上游判重（如允许排队多单），此处必须同步补锁（select for update）。
        long existingPaidCount = countValidOrdersForUserAndTicket(userId, ticketId);

        if (existingPaidCount > 0) {
            log.warn("[一客一单拦截] 用户{}已购买过秒杀票{}，已有{}个有效订单，当前订单{}被拦截",
                    userId, ticketId, existingPaidCount, orderId);
            throw new IllegalStateException("该秒杀票每人限购1张，您已购买过此票种");
        }

        log.info("[一客一单校验通过] 用户{}购买秒杀票{}，无历史订单", userId, ticketId);
    }

    /**
     * 统计用户对某票种的有效订单数量
     *
     * @param userId   用户ID
     * @param ticketId 票种ID
     * @return 有效订单数(已支付/已核销/退款中)
     */
    private long countValidOrdersForUserAndTicket(Long userId, Long ticketId) {
        return ticketOrderService.count(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.TicketOrder>()
                        .eq("user_id", userId)
                        .eq("ticket_id", ticketId)
                        .in("status", VALID_ORDER_STATUSES)
        );
    }

    /**
     * 校验核销规则：只有已支付的订单才能核销
     *
     * @param fromStatus 起始状态码
     * @throws IllegalStateException 如果起始状态不是"已支付"
     */
    private void validateVerifyRules(Integer fromStatus) {
        if (fromStatus != null && fromStatus != STATUS_PAID) {
            throw new IllegalStateException("只有已支付的订单才能核销");
        }
    }

    /**
     * 校验取消规则：只有预占状态的订单才能取消
     *
     * @param fromStatus 起始状态码
     * @throws IllegalStateException 如果起始状态不是"预占"
     */
    private void validateCancelRules(Integer fromStatus) {
        if (fromStatus != null && fromStatus != STATUS_RESERVED) {
            throw new IllegalStateException("只有预占状态的订单才能取消");
        }
    }

    /**
     * 校验完成退款规则：只有退款中的订单才能完成退款
     *
     * @param fromStatus 起始状态码
     * @throws IllegalStateException 如果起始状态不是"退款中"
     */
    private void validateRefundedRules(Integer fromStatus) {
        if (fromStatus != null && fromStatus != STATUS_REFUND_PENDING) {
            throw new IllegalStateException("只有申请退款中的订单才能完成退款");
        }
    }

    /**
     * 校验申请退款规则：已支付或退款被拒的订单才能（重新）申请退款
     *
     * @param fromStatus 起始状态码
     * @throws IllegalStateException 如果起始状态不是"已支付"或"已拒绝"
     */
    private void validateRefundPendingRules(Integer fromStatus) {
        if (fromStatus != null && fromStatus != STATUS_PAID && fromStatus != STATUS_REJECTED) {
            throw new IllegalStateException("只有已支付或退款被拒的订单才能申请退款");
        }
    }

    /**
     * 校验拒绝退款规则：只有退款中的订单才能被拒绝
     *
     * @param fromStatus 起始状态码
     * @throws IllegalStateException 如果起始状态不是"退款中"
     */
    private void validateRejectedRules(Integer fromStatus) {
        if (fromStatus != null && fromStatus != STATUS_REFUND_PENDING) {
            throw new IllegalStateException("只有申请退款中的订单才能被拒绝");
        }
    }

    /**
     * 判断票种是否为秒杀票
     *
     * @param ticketId 票种ID
     * @return true=秒杀票, false=普通票或查询异常
     */
    private boolean checkIsSeckillTicket(Long ticketId) {
        try {
            com.echo.entity.Ticket ticket = ticketOrderService.getTicketById(ticketId);
            return ticket != null && TICKET_TYPE_SECKILL == ticket.getType();
        } catch (Exception e) {
            log.warn("[状态机] 判断票种类型异常，ticketId:{}, 默认为普通票", ticketId);
            return false;
        }
    }

    /**
     * 构建领域事件(根据目标状态生成对应类型的事件)
     * <p>
     * 领域事件用于解耦状态变更与副作用处理(如发送通知、更新缓存等)
     *
     * @param orderId    订单ID
     * @param fromStatus 起始状态码
     * @param toStatus   目标状态码
     * @param context    转换上下文
     * @return 订单状态变更领域事件
     */
    private OrderStatusChangeEvent buildEvent(String orderId, Integer fromStatus, Integer toStatus,
                                                TransitionContext context) {
        OrderStatusDTO toStatusInfo = getStatusInfo(toStatus);

        switch (toStatus) {
            case STATUS_PAID:
                return buildPayEvent(orderId, fromStatus, toStatusInfo, context);
            case STATUS_USED:
                return buildVerifyEvent(orderId, fromStatus, toStatusInfo, context);
            case STATUS_CANCELLED:
                return buildCancelEvent(orderId, fromStatus, toStatusInfo, context);
            case STATUS_REFUNDED:
                return buildRefundEvent(orderId, fromStatus, toStatusInfo, context);
            case STATUS_REJECTED:
                return buildRejectEvent(orderId, fromStatus, toStatusInfo, context);
            default:
                return buildGenericEvent(orderId, fromStatus, toStatus, toStatusInfo, context);
        }
    }

    /**
     * 构建支付事件
     */
    private OrderStatusChangeEvent buildPayEvent(String orderId, Integer fromStatus, OrderStatusDTO toStatusInfo,
                                                   TransitionContext context) {
        return OrderStatusChangeEvent.payEvent(
                orderId, context.getUserId(), context.getTicketId(), context.getShowId(),
                fromStatus, toStatusInfo, context.getMetadata()
        );
    }

    /**
     * 构建核销事件
     */
    private OrderStatusChangeEvent buildVerifyEvent(String orderId, Integer fromStatus, OrderStatusDTO toStatusInfo,
                                                      TransitionContext context) {
        return OrderStatusChangeEvent.verifyEvent(
                orderId, context.getUserId(), context.getTicketId(), context.getShowId(),
                fromStatus, toStatusInfo
        );
    }

    /**
     * 构建取消事件
     */
    private OrderStatusChangeEvent buildCancelEvent(String orderId, Integer fromStatus, OrderStatusDTO toStatusInfo,
                                                      TransitionContext context) {
        return OrderStatusChangeEvent.cancelEvent(
                orderId, context.getUserId(), context.getTicketId(),
                fromStatus, toStatusInfo, OrderStatusChangeEvent.SOURCE_USER
        );
    }

    /**
     * 构建退款事件
     */
    private OrderStatusChangeEvent buildRefundEvent(String orderId, Integer fromStatus, OrderStatusDTO toStatusInfo,
                                                     TransitionContext context) {
        return OrderStatusChangeEvent.refundEvent(
                orderId, context.getUserId(), context.getTicketId(), context.getShowId(),
                fromStatus, toStatusInfo, context.getMetadata()
        );
    }

    /**
     * 构建拒绝退款事件
     */
    private OrderStatusChangeEvent buildRejectEvent(String orderId, Integer fromStatus, OrderStatusDTO toStatusInfo,
                                                      TransitionContext context) {
        return OrderStatusChangeEvent.refundRejectEvent(
                orderId, context.getUserId(), context.getTicketId(), context.getShowId(),
                fromStatus, toStatusInfo
        );
    }

    /**
     * 构建通用事件(兜底方案)
     */
    private OrderStatusChangeEvent buildGenericEvent(String orderId, Integer fromStatus, Integer toStatus,
                                                       OrderStatusDTO toStatusInfo, TransitionContext context) {
        return OrderStatusChangeEvent.builder()
                .eventId(OrderStatusChangeEvent.generateEventId())
                .orderId(orderId)
                .userId(context.getUserId())
                .ticketId(context.getTicketId())
                .showId(context.getShowId())
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .toStatusInfo(toStatusInfo)
                .eventTime(java.time.LocalDateTime.now())
                .build();
    }

    /**
     * 记录成功的状态转换监控指标
     *
     * @param orderId       订单ID
     * @param fromStatus    起始状态
     * @param toStatus      目标状态
     * @param startTime     开始时间戳
     */
    private void recordSuccessMetrics(String orderId, Integer fromStatus, Integer toStatus, long startTime) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            String transition = fromStatus + "→" + toStatus;
            orderMetricsService.recordStateTransitionSuccess(orderId, transition, duration);
        } catch (Exception ignored) {
        }
    }

    /**
     * 记录失败的状态转换监控指标
     *
     * @param orderId    订单ID
     * @param fromStatus 起始状态
     * @param toStatus   目标状态
     * @param reason     失败原因
     * @param startTime  开始时间戳
     */
    private void recordFailMetrics(String orderId, Integer fromStatus, Integer toStatus,
                                   String reason, long startTime) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            String transition = fromStatus + "→" + toStatus;
            orderMetricsService.recordStateTransitionFail(orderId, transition, reason, duration);
        } catch (Exception ignored) {
        }
    }
}
