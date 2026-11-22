package com.echo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.echo.entity.PreOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 预订单数据访问接口
 *
 * @author tulip
 */
@Mapper
public interface PreOrderMapper extends BaseMapper<PreOrder> {
}
