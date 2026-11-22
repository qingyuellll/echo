package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.dto.Result;
import com.echo.entity.ShowType;

/**
 * 演出类型服务接口
 *
 * @author tulip
 */
public interface IShowTypeService extends IService<ShowType> {
    Result<?> queryTypeList();
}
