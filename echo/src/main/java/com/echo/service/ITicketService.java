package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.dto.Result;
import com.echo.entity.Ticket;

/**
 * 票种服务接口
 *
 * @author tulip
 */
public interface ITicketService extends IService<Ticket> {
    Result<?> queryTicketOfShow(Long showId);
    void addSeckillTicket(Ticket ticket);
    void clearTicketCache(Long showId);
    void refreshTicketCache(Long showId);

    /**
     * [OK] 清除所有票种缓存（用于SQL变更后强制刷新）
     * @return 清除的缓存数量（-1表示失败）
     */
    int clearAllTicketCache();

    /**
     * 查询票种状态（轻量级，用于前端并发安全验证）
     * @param ticketId 票种ID
     * @return 票种状态信息（status/isSeckill/isActive等）
     */
    Result<?> checkTicketStatus(Long ticketId);

    /**
     * 检查用户是否已购买过该秒杀票
     * @param ticketId 秒杀票ID
     * @return 包含purchased字段的Result（true=已购买，false=未购买）
     */
    Result<?> checkSeckillPurchased(Long ticketId);

    /**
     * 查询票种详情（完整信息，含秒杀信息、库存等）
     * @param ticketId 票种ID
     * @return 票种完整信息
     */
    Result<?> getTicketDetail(Long ticketId);

    /**
     * 验证票种是否可售卖（供其他Service调用）
     * @param ticketId 票种ID
     * @return true=可售卖, false=不可售卖
     */
    boolean isTicketActiveForPurchase(Long ticketId);

    /**
     * 同步秒杀票库存到Redis
     * @param ticketId 秒杀票ID
     * @param stock 库存数量
     */
    void syncSeckillStockToRedis(Long ticketId, Integer stock);

    /**
     * 清除Redis中的秒杀库存
     * @param ticketId 秒杀票ID
     */
    void clearSeckillStockFromRedis(Long ticketId);
}
