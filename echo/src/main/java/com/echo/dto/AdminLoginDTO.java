package com.echo.dto;

import lombok.Data;

/**
 * 管理员登录数据传输对象
 *
 * @author tulip
 */
@Data
public class AdminLoginDTO {
    private String phone;
    private String code;
    private String password;
}
