package com.echo.dto;

import com.echo.constants.AuthMessages;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import javax.validation.constraints.Pattern;

@Data
public class LoginFormDTO {
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = AuthMessages.PHONE_FORMAT_INVALID)
    private String phone;

    private String code;

    @Length(min = 6, max = 20, message = AuthMessages.PASSWORD_LENGTH_INVALID)
    private String password;
}
