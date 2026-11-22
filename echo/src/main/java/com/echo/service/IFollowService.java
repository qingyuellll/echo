package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.dto.Result;
import com.echo.entity.Follow;

/**
 * 用户关注服务接口
 *
 * @author tulip
 */
public interface IFollowService extends IService<Follow> {

    Result<?> follow(Long followUserId, Boolean isFollow);
    Result<?> isFollow(Long followUserId);

    Result<?> followCommons(Long id);

    Result<?> followCount(Long userId);
}
