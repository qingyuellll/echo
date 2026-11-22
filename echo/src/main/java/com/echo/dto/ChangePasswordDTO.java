package com.echo.dto;

import lombok.Data;

/**
 * 修改密码数据传输对象
 *
 * @author tulip
 */
@Data
public class ChangePasswordDTO {
    private String oldPassword;
    private String newPassword;
    private String code;
}
