package com.echo.service;

import com.echo.dto.Result;

/**
 * 数据统计服务接口
 *
 * @author tulip
 */
public interface IStatisticsService {

    Result<?> hotShows(Integer limit);

    Result<?> hotShows(Integer limit, Double x, Double y);

    Result<?> salesTrend(Integer days);

    Result<?> userProfile();

    Result<?> dashboard();
}
