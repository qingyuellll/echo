package com.echo.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 超时订单延时消息
 * <p>
 * 订单创建后发送到 RocketMQ 延时队列，延迟 N 分钟后消费者检查订单状态，
 * 若订单仍未支付，则取消订单并释放库存
 *
 * @author tulip
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderTimeoutMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主订单ID(第一个订单，用于兼容旧逻辑) */
    private Long orderId;

    /** 门票ID(用于回滚库存) */
    private Long ticketId;

    /** 子订单ID列表(选座下单时每个座位一个订单，为空则只取消主订单) */
    private List<Long> subOrderIds;

    /**
     * 创建超时订单消息(普通票/秒杀票)
     *
     * @param orderId 主订单ID
     * @param ticketId 门票ID
     * @return 超时订单消息实例
     */
    public static OrderTimeoutMessage of(Long orderId, Long ticketId) {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("orderId 必须为正整数");
        }
        if (ticketId == null || ticketId <= 0) {
            throw new IllegalArgumentException("ticketId 必须为正整数");
        }

        return OrderTimeoutMessage.builder()
                .orderId(orderId)
                .ticketId(ticketId)
                .build();
    }
}
