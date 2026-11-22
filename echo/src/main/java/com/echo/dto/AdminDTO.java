package com.echo.dto;

import com.echo.enums.AdminRoleLevel;
import com.echo.enums.AdminStatus;
import lombok.Data;

@Data
public class AdminDTO {
    private Long id;
    private Long userId;
    private String name;
    private AdminRoleLevel roleLevel;
    private String phone;
    private AdminStatus status;
}
