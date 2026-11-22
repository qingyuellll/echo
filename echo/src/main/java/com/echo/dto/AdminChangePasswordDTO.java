package com.echo.dto;

import lombok.Data;

@Data
public class AdminChangePasswordDTO {
    private String oldPassword;
    private String newPassword;
    private String code;
}
