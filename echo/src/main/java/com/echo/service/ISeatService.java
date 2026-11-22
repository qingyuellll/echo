package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.dto.Result;
import com.echo.dto.SeatBatchCreateDTO;
import com.echo.entity.Seat;

/**
 * 座位服务接口
 *
 * @author tulip
 */
public interface ISeatService extends IService<Seat> {
    Result<?> querySeatsByTicket(Long ticketId);
    Result<?> lockSeat(Long ticketId, Long seatId);
    Result<?> releaseSeat(Long ticketId, Long seatId);
    Result<?> batchCreateSeats(Long ticketId, SeatBatchCreateDTO dto);
}
