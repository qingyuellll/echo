package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.entity.SeckillTicket;

/**
 * 秒杀票服务接口
 *
 * @author tulip
 */
public interface ISeckillTicketService extends IService<SeckillTicket> {

    /**
     * 将单个秒杀票的库存预热到Redis（SETNX原子写入，动态TTL）。
     * 调用方：启动初始化(SeckillDataInitializer)、周期守卫(SeckillStockGuard)。
     * 秒杀入口在活动期间保持对MySQL零访问，回源预热统一收敛到本方法。
     *
     * @param ticketId 票种ID
     */
    void preloadSeckillStock(Long ticketId);
}
