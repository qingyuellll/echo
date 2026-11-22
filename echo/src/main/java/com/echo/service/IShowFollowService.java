package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.dto.Result;
import com.echo.entity.ShowFollow;

/**
 * 演出关注服务接口
 *
 * @author tulip
 */
public interface IShowFollowService extends IService<ShowFollow> {

    Result<?> followShow(Long showId);

    Result<?> unfollowShow(Long showId);

    Result<?> isFollowed(Long showId);

    Result<?> queryMyFollowedShows(Integer current);
}
