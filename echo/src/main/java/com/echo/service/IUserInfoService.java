package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.dto.Result;
import com.echo.dto.UserInfoUpdateDTO;
import com.echo.entity.UserInfo;

/**
 * 用户信息服务接口
 *
 * @author tulip
 */
public interface IUserInfoService extends IService<UserInfo> {

    /**
     * 根据用户ID查询用户详细信息（过滤敏感字段）
     *
     * @param userId 用户ID
     * @return 脱敏后的用户详细信息
     */
    Result<?> getUserInfoById(Long userId);

    /**
     * 更新当前登录用户的个人资料
     *
     * @param updateDTO 要更新的资料字段
     * @return 更新后的用户信息
     */
    Result<?> updateUserInfo(UserInfoUpdateDTO updateDTO);
}
