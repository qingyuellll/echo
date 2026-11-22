package com.echo.service.strategy.impl;

import com.echo.entity.PreOrder;
import com.echo.entity.Ticket;
import com.echo.entity.TicketOrder;
import com.echo.service.strategy.SeatStrategy;
import com.echo.utils.OrderUtils;
import com.echo.utils.RedisWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 无座票座位处理策略实现
 *
 * @author tulip
 */
@Slf4j
@Component
public class WithoutSeatStrategy implements SeatStrategy {

    @Resource
    private RedisWorker redisWorker;

    private static final String STRATEGY_TYPE = "WITHOUT_SEAT";
    private static final String DESCRIPTION = "无座票策略（无需选座）";

    @Override
    public String getStrategyType() {
        return STRATEGY_TYPE;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    /**
     * 验证：无座票无需验证座位
     */
    @Override
    public SeatValidationResult validateSeats(Long ticketId, List<Long> seatIds, int quantity) {
        if (seatIds != null && !seatIds.isEmpty()) {
            log.warn("[无座票验证] [WARN] 传入了座位ID但该票种为无座票，忽略座位参数");
        }
        log.info("[无座票验证] [OK] 通过（无座票无需选座）");
        return SeatValidationResult.ok();
    }

    /**
     * 锁定：无座票无需锁定座位
     */
    @Override
    public SeatOperationResult lockSeats(TicketOrder order, List<Long> seatIds) {
        log.info("[无座票锁定] [OK] 跳过（无座票无需锁座）- orderId:{}", order.getId());
        return SeatOperationResult.success(0);
    }

    /**
     * 释放：无座票无需释放座位
     */
    @Override
    public int releaseSeats(TicketOrder order) {
        log.info("[无座票释放] [OK] 跳过（无座票无需释放）- orderId:{}", order.getId());
        return 0;
    }

    /**
     * 支付时构建子订单（无座票）
     * <p>
     * 核心流程：
     * - 循环 quantity 次，每次生成一个独立子订单
     * - 每个子订单包含：真正订单号、核销码、积分抵扣信息
     * - 无需锁座操作（无座票没有座位概念）
     *
     * @param preOrder      预订单（包含购买数量quantity）
     * @param ticket        票种信息
     * @param pointsToUse   使用积分数量
     * @param discountAmount 积分抵扣金额
     * @return 已构建完成的子订单列表
     */
    @Override
    public List<TicketOrder> buildSubOrdersOnPayment(PreOrder preOrder, Ticket ticket, int pointsToUse, long discountAmount) {
        int quantity = (preOrder.getQuantity() != null) ? preOrder.getQuantity() : 1;
        
        List<TicketOrder> subOrders = new ArrayList<>();
        
        log.info("[无座票支付拆单] 开始构建子订单 - tempId:{}, 数量:{}, 冻结票价:{}",
                preOrder.getTempId(), quantity, preOrder.getPayValue());
        
        for (int i = 0; i < quantity; i++) {
            // Step1: 生成真正订单号（雪花算法）
            long realOrderId = redisWorker.nextId("order");

            // Step2: 构建子订单
            TicketOrder subOrder = new TicketOrder();
            subOrder.setId(realOrderId);
            subOrder.setUserId(preOrder.getUserId());
            subOrder.setTicketId(preOrder.getTicketId());
            subOrder.setShowId(preOrder.getShowId());
            subOrder.setStatus(2);  // 已支付
            subOrder.setQuantity(1);
            subOrder.setSubOrderIndex((i + 1) + "/" + quantity);  // 序列号：1/3, 2/3, 3/3
            subOrder.setPayType(1);  // 模拟支付
            subOrder.setPayTime(LocalDateTime.now());
            subOrder.setCreateTime(LocalDateTime.now());
            subOrder.setUpdateTime(LocalDateTime.now());
            subOrder.setPayValue(preOrder.getPayValue() != null ? preOrder.getPayValue() : 0L);
            subOrder.setActualValue(preOrder.getActualValue() != null ? preOrder.getActualValue() : 0L);

            // Step3: 设置积分抵扣（仅第一张票扣积分）
            if (i == 0) {
                subOrder.setPointsUsed(pointsToUse);
                subOrder.setDiscountAmount(discountAmount);
            } else {
                subOrder.setPointsUsed(0);
                subOrder.setDiscountAmount(0L);
            }

            // Step4: 生成核销码（同一循环内生成）
            String verificationCode = OrderUtils.generateVerificationCode();
            subOrder.setVerificationCode(verificationCode);
            
            subOrders.add(subOrder);
            
            log.info("[无座票支付拆单] [OK] 子订单创建成功 - orderId:{}, 核销码:{}, 序号:{}/{}",
                    realOrderId, verificationCode, (i + 1), quantity);
        }

        log.info("[无座票支付拆单] [OK] 全部完成 - tempId:{}, 共构建{}个子订单",
                preOrder.getTempId(), subOrders.size());
        
        return subOrders;
    }
}
