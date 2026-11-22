package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.dto.Result;
import com.echo.entity.PointsRecord;

/**
 * 积分服务接口
 *
 * @author tulip
 */
public interface IPointsService extends IService<PointsRecord> {

    Result<?> addPoints(Long userId, Integer points, Integer type, String description, Long relatedId);

    Result<?> exchangeCoupon();

    Result<?> lottery();

    Result<?> queryMyPoints();

    Result<?> queryMyPointsRecords(Integer current);

    Result<?> queryLotteryRecords(Integer current);
}
