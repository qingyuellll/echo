package com.echo.statemachine;

import com.echo.dto.OrderStatusDTO;
import com.echo.event.OrderStatusChangeEvent;

import java.util.List;
import java.util.function.Consumer;

/**
 * 订单状态机接口(基于有限状态机FSM模式)
 * <p>
 * 定义订单状态流转的核心契约：合法性校验 → 业务规则检查 → 领域事件生成
 *
 * @author tulip
 */
public interface OrderStateMachine {

    /**
     * 执行状态转换(核心入口方法)
     *
     * @param orderId       订单ID
     * @param currentStatus 当前状态码
     * @param targetStatus  目标状态码
     * @param context       转换上下文
     * @return 状态转换结果对象
     */
    StateTransitionResult transition(String orderId, Integer currentStatus, Integer targetStatus,
                                      TransitionContext context);

    /**
     * 检查状态转换是否合法
     *
     * @param fromStatus 起始状态码
     * @param toStatus   目标状态码
     * @return true=允许转换, false=非法转换
     */
    /**
     * 执行状态转换并落库（订单状态写入的唯一入口）
     * <p>
     * 流程：读库取当前状态 → 复用transition()校验链（规则合法性+业务规则）→
     * 条件更新 update ... set status=新状态 where id=? and status=旧状态 →
     * 影响行数为0说明状态已被其他事务抢先变更，本次转换失败。
     * <p>
     * 状态机负责"合法性"，SQL的旧状态条件负责"并发安全"，两者缺一不可。
     *
     * @param orderId      订单ID
     * @param targetStatus 目标状态码
     * @param extraColumns 需在同一条件更新中一并写入的附加列（如refund_time/use_time/update_time），可为null
     * @param context      转换上下文
     * @return 状态转换结果（成功时携带领域事件，由调用方发布）
     */
    StateTransitionResult applyTransition(String orderId, Integer targetStatus,
                                          java.util.Map<String, Object> extraColumns,
                                          TransitionContext context);

    /**
     * 检查状态转换是否合法
     *
     * @param fromStatus 起始状态码
     * @param toStatus   目标状态码
     * @return true=允许转换, false=非法转换
     */
    boolean canTransition(Integer fromStatus, Integer toStatus);

    /**
     * 获取指定状态下所有合法的目标状态列表
     *
     * @param currentStatus 当前状态码
     * @return 允许转换的目标状态列表(可能为空列表，表示终态)
     */
    List<Integer> getAllowedTransitions(Integer currentStatus);

    /**
     * 获取状态的详细信息
     *
     * @param status 状态码
     * @return 状态DTO对象，如果不支持该状态则返回null
     */
    OrderStatusDTO getStatusInfo(Integer status);

    // ==================== 内部类定义 ====================

    /** 状态转换上下文接口 */
    interface TransitionContext {

        /** 获取用户ID */
        Long getUserId();

        /** 获取票种ID */
        Long getTicketId();

        /** 获取演出ID */
        Long getShowId();

        /** 获取购买数量 */
        Integer getQuantity();

        /** 获取扩展元数据(JSON格式) */
        String getMetadata();

        /**
         * 工厂方法：创建转换上下文实例
         *
         * @param userId   用户ID
         * @param ticketId 票种ID
         * @param showId   演出ID
         * @param quantity 购买数量
         * @param metadata 扩展元数据
         * @return 不可变的TransitionContext实例
         */
        static TransitionContext of(Long userId, Long ticketId, Long showId,
                                     Integer quantity, String metadata) {
            return new SimpleTransitionContext(userId, ticketId, showId, quantity, metadata);
        }
    }

    /** 简单转换上下文实现类(不可变对象) */
    class SimpleTransitionContext implements TransitionContext {

        /** 用户ID */
        private final Long userId;

        /** 票种ID */
        private final Long ticketId;

        /** 演出ID */
        private final Long showId;

        /** 购买数量 */
        private final Integer quantity;

        /** 扩展元数据 */
        private final String metadata;

        /**
         * 全参构造器
         *
         * @param userId   用户ID
         * @param ticketId 票种ID
         * @param showId   演出ID
         * @param quantity 购买数量
         * @param metadata 扩展元数据
         */
        public SimpleTransitionContext(Long userId, Long ticketId, Long showId,
                                        Integer quantity, String metadata) {
            this.userId = userId;
            this.ticketId = ticketId;
            this.showId = showId;
            this.quantity = quantity;
            this.metadata = metadata;
        }

        @Override
        public Long getUserId() {
            return userId;
        }

        @Override
        public Long getTicketId() {
            return ticketId;
        }

        @Override
        public Long getShowId() {
            return showId;
        }

        @Override
        public Integer getQuantity() {
            return quantity;
        }

        @Override
        public String getMetadata() {
            return metadata;
        }
    }

    /** 状态转换结果接口 */
    interface StateTransitionResult {

        /** 判断转换是否成功 */
        boolean isSuccess();

        /** 获取成功的领域事件 */
        OrderStatusChangeEvent getEvent();

        /** 获取失败时的错误信息 */
        String getErrorMessage();

        /**
         * 成功时执行回调
         *
         * @param successHandler 成功回调处理器
         */
        default void ifSuccess(Consumer<OrderStatusChangeEvent> successHandler) {
            if (isSuccess()) {
                successHandler.accept(getEvent());
            }
        }

        /**
         * 失败时执行回调
         *
         * @param failureHandler 失败回调处理器
         */
        default void ifFailure(Consumer<String> failureHandler) {
            if (!isSuccess()) {
                failureHandler.accept(getErrorMessage());
            }
        }

        /**
         * 创建成功结果(工厂方法)
         *
         * @param event 订单状态变更领域事件
         * @return 成功的结果对象(isSuccess()=true)
         */
        static StateTransitionResult success(OrderStatusChangeEvent event) {
            return new SuccessResult(event);
        }

        /**
         * 创建失败结果(工厂方法)
         *
         * @param errorMessage 错误信息描述
         * @return 失败的结果对象(isSuccess()=false)
         */
        static StateTransitionResult failure(String errorMessage) {
            return new FailureResult(errorMessage);
        }
    }

    /**
     * 成功结果实现类
     * <p>
     * 特点：
     * - isSuccess()始终返回true
     * - getEvent()返回有效的领域事件
     * - getErrorMessage()返回null
     */
    class SuccessResult implements StateTransitionResult {

        /** 订单状态变更领域事件 */
        private final OrderStatusChangeEvent event;

        /**
         * 构造成功结果
         *
         * @param event 订单状态变更领域事件(不能为null)
         */
        public SuccessResult(OrderStatusChangeEvent event) {
            this.event = event;
        }

        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public OrderStatusChangeEvent getEvent() {
            return event;
        }

        @Override
        public String getErrorMessage() {
            return null;
        }
    }

    /**
     * 失败结果实现类
     * <p>
     * 特点：
     * - isSuccess()始终返回false
     * - getEvent()返回null
     * - getErrorMessage()返回错误描述
     */
    class FailureResult implements StateTransitionResult {

        /** 错误信息描述 */
        private final String errorMessage;

        /**
         * 构造失败结果
         *
         * @param errorMessage 错误信息描述(不能为null或空字符串)
         */
        public FailureResult(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public OrderStatusChangeEvent getEvent() {
            return null;
        }

        @Override
        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
