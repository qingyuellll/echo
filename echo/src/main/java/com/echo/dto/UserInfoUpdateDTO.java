package com.echo.dto;

import com.echo.constants.UserMessages;
import com.echo.dto.base.AuditableDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.Size;
import java.time.LocalDate;


@Data
@EqualsAndHashCode(callSuper = true)
public class UserInfoUpdateDTO extends AuditableDTO {

    @Size(max = 50, message = UserMessages.CITY_TOO_LONG)
    private String city;

    @Size(max = 200, message = UserMessages.INTRODUCE_TOO_LONG)
    private String introduce;

    private Boolean gender;

    private LocalDate birthday;

    private String icon;
}
