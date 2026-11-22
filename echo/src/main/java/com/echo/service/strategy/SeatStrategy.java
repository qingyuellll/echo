package com.echo.service.strategy;

import com.echo.entity.PreOrder;
import com.echo.entity.Ticket;
import com.echo.entity.TicketOrder;
import java.util.List;

/**
 * 座位处理策略接口
 *
 * @author tulip
 */
public interface SeatStrategy {

    /**
     * 锁定/占用座位（下单时调用）
     *
     * @param order   订单对象（包含ticketId、orderId、userId等信息）
     * @param seatIds 用户选择的座位ID列表
     *                 - 有座票：传入用户选择的1-10个座位ID
     *                 - 无座票：传入空列表或null
     * @return 操作结果
     *         - success=true: 座位锁定成功
     *         - success=false: 座位锁定失败（被占用、不存在等）
     */
    SeatOperationResult lockSeats(TicketOrder order, List<Long> seatIds);

    /**
     * 释放座位（退款/取消/超时时调用）
     *
     * @param order 订单对象（用于查询关联的座位）
     * @return 释放成功的座位数量
     */
    int releaseSeats(TicketOrder order);

    /**
     * 验证座位合法性（下单前校验）
     *
     * @param ticketId 票种ID
     * @param seatIds  用户选择的座位ID列表
     * @param quantity 购买数量
     * @return 验证结果
     */
    SeatValidationResult validateSeats(Long ticketId, List<Long> seatIds, int quantity);

    /**
     * 获取策略类型标识
     *
     * @return "WITH_SEAT" 或 "WITHOUT_SEAT"
     */
    String getStrategyType();

    /**
     * 获取策略描述（用于日志）
     *
     * @return 如"有座票策略"、"无座票策略"
     */
    String getDescription();

    /**
     * 支付时构建子订单列表（统一拆单点）
     * <p>
     * 核心设计：
     * - 所有票种（选座/普通/秒杀）都在支付成功后调用此方法
     * - 一次循环内完成：生成真正订单号 + 锁座钉死 + 生成核销码 + 构建子订单
     * - 解决痛点：核销码生成分散、拆单时机不一致、取消逻辑复杂
     * <p>
     * 调用时机：
     * - payOrder() 支付成功后统一调用（唯一拆单点）
     * - 不在下单时调用（下单只创建预订单，不生成真正订单号和核销码）
     *
     * @param preOrder      预订单（包含临时ID、用户、票种、座位信息等）
     * @param ticket        票种信息（价格、是否支持选座等）
     * @param pointsToUse   使用积分数量（仅第一张票扣积分）
     * @param discountAmount 积分抵扣金额（单位：分，仅第一张票抵扣）
     * @return 已构建完成的子订单列表（每个元素都是一张物理票的独立订单）
     *         - 已包含：真正的雪花算法订单号、核销码、座位锁定信息（如有座）
     *         - 状态：status=2（已支付），可直接保存到数据库
     */
    List<TicketOrder> buildSubOrdersOnPayment(PreOrder preOrder, Ticket ticket, int pointsToUse, long discountAmount);

    // ==================== 内部结果类 ====================

    /**
     * 座位操作结果（锁定/释放）
     */
    class SeatOperationResult {
        private boolean success;
        private String message;
        private int lockedCount;

        public static SeatOperationResult success(int count) {
            SeatOperationResult result = new SeatOperationResult();
            result.success = true;
            result.lockedCount = count;
            return result;
        }

        public static SeatOperationResult fail(String message) {
            SeatOperationResult result = new SeatOperationResult();
            result.success = false;
            result.message = message;
            return result;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public int getLockedCount() { return lockedCount; }

        @Override
        public String toString() {
            return success ? "成功(锁定" + lockedCount + "个座位)" : "失败(" + message + ")";
        }
    }

    /**
     * 座位验证结果
     */
    class SeatValidationResult {
        private boolean valid;
        private String errorMessage;

        public static SeatValidationResult ok() {
            SeatValidationResult result = new SeatValidationResult();
            result.valid = true;
            return result;
        }

        public static SeatValidationResult error(String errorMsg) {
            SeatValidationResult result = new SeatValidationResult();
            result.valid = false;
            result.errorMessage = errorMsg;
            return result;
        }

        public boolean isValid() { return valid; }
        public String getErrorMessage() { return errorMessage; }

        @Override
        public String toString() {
            return valid ? "验证通过" : "验证失败(" + errorMessage + ")";
        }
    }
}
