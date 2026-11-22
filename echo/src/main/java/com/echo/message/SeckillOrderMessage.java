package com.echo.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 秒杀预订单消息
 * <p>
 * 用户秒抢成功后发送到 RocketMQ，消费者接收后确认预订单有效性，
 * 等待用户支付完成最终建单和库存扣减
 *
 * @author tulip
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeckillOrderMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 预订单临时ID */
    private String tempId;

    /** 用户ID */
    private Long userId;

    /** 秒杀票ID */
    private Long ticketId;

    /**
     * 创建秒杀预订单消息
     *
     * @param tempId  预订单临时ID
     * @param userId  用户ID
     * @param ticketId 秒杀票ID
     * @return 秒杀预订单消息实例
     */
    public static SeckillOrderMessage of(String tempId, Long userId, Long ticketId) {
        if (tempId == null || tempId.isBlank()) {
            throw new IllegalArgumentException("tempId 不能为空");
        }
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId 必须为正整数");
        }
        if (ticketId == null || ticketId <= 0) {
            throw new IllegalArgumentException("ticketId 必须为正整数");
        }

        return SeckillOrderMessage.builder()
                .tempId(tempId)
                .userId(userId)
                .ticketId(ticketId)
                .build();
    }
}
