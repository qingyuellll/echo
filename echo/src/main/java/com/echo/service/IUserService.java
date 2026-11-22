package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.dto.ChangePasswordDTO;
import com.echo.dto.LoginFormDTO;
import com.echo.dto.Result;
import com.echo.entity.User;

import javax.servlet.http.HttpSession;

/**
 * 用户服务接口
 *
 * @author tulip
 */
public interface IUserService extends IService<User> {

    /**
     * 发送短信验证码
     */
    Result<?> sendCode(String phone, HttpSession session);

    /**
     * 用户登录（验证码/密码登录）
     */
    Result<?> login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 用户登出
     */
    Result<?> logout();

    /**
     * 根据用户ID查询用户DTO（脱敏后返回）
     */
    Result<?> queryUserById(Long userId);

    Result<?> sign();

    Result<?> signCount();

    /**
     * 修改密码（短信验证码校验）
     */
    Result<?> changePassword(ChangePasswordDTO dto);

    /**
     * 发送修改密码验证码（发送到当前登录用户手机号）
     */
    Result<?> sendPasswordCode();
}
