package com.echo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.entity.PreOrder;
import com.echo.mapper.PreOrderMapper;
import com.echo.service.IPreOrderService;
import org.springframework.stereotype.Service;

/**
 * 预订单服务实现类
 *
 * @author tulip
 */
@Service
public class PreOrderServiceImpl extends ServiceImpl<PreOrderMapper, PreOrder> implements IPreOrderService {
}
