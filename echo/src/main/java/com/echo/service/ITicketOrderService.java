package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.dto.Result;
import com.echo.entity.TicketOrder;

import java.util.List;

/**
 * 票务订单服务接口
 *
 * @author tulip
 */
public interface ITicketOrderService extends IService<TicketOrder> {
    Result<?> seckillTicket(Long ticketId, @org.springframework.web.bind.annotation.RequestParam(required = false) List<Long> seatIds);
    Result<?> getSeckillQueueStatus(Long ticketId);
    void consumeSeckillQueueBatch();
    Result<?> createTicketOrder(Long ticketId, Integer quantity);
    Result<?> createSeatOrder(Long ticketId, List<Long> seatIds, Integer count);
    Result<?> payOrder(String tempId, Integer usePoints);
    Result<?> getOrderById(String orderId);
    Result<?> verifyOrder(String verificationCode);
    Result<?> checkVerificationStatus(Long orderId, Integer ticketIndex);
    Result<?> queryMyOrders(Integer status, Integer current, Integer size);
    Result<?> cancelOrder(String orderId);
    Result<?> refundOrder(Long orderId);

    /**
     * 管理员审批退款（不校验用户登录身份和订单归属）
     */
    Result<?> adminRefundOrder(Long orderId);

    /**
     * 批量检查用户已购买的秒杀票
     * @param ticketIds 秒杀票ID列表
     * @return 已购买的秒杀票ID集合
     */
    Result<?> checkSeckillPurchasedBatch(List<Long> ticketIds);

    /**
     * 根据ID查询票种信息（供状态机使用）
     */
    com.echo.entity.Ticket getTicketById(Long ticketId);

    /**
     * 恢复过期预订单的库存（供定时任务调用）
     * @param ticketId 票种ID
     * @param quantity 需恢复的数量
     * @return 是否恢复成功
     */
    boolean restoreStockForExpiredOrder(Long ticketId, Integer quantity);
}
