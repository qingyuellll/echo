package com.echo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.echo.entity.Ticket;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 票种数据访问接口
 *
 * @author tulip
 */
public interface TicketMapper extends BaseMapper<Ticket> {

    List<Ticket> queryTicketOfShow(@Param("showId") Long showId);
}
