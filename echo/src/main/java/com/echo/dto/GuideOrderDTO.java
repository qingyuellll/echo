package com.echo.dto;

import lombok.Data;
import javax.validation.constraints.*;
import java.util.ArrayList;
import java.util.List;

@Data
public class GuideOrderDTO {
    @NotBlank @Pattern(regexp="[A-Za-z0-9-]{16,64}")
    private String requestId;
    @NotNull @AssertTrue(message="请确认订单信息后提交")
    private Boolean confirmed;
    @NotNull @Min(1) @Max(10)
    private Integer count;
    @NotNull @Min(0)
    private Long expectedUnitPrice;
    @NotNull @Size(max=10)
    private List<@NotNull @Min(1) Long> seatIds = new ArrayList<>();
}
