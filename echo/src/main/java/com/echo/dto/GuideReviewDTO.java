package com.echo.dto;

import lombok.Data;
import javax.validation.constraints.*;

@Data
public class GuideReviewDTO {
    @NotBlank @Pattern(regexp="[A-Za-z0-9-]{16,64}")
    private String requestId;
    @NotNull @AssertTrue(message="请核对评分与正文后确认发布")
    private Boolean confirmed;
    @NotNull @Min(1)
    private Long showId;
    @NotNull @Min(1) @Max(5)
    private Integer score;
    @Size(max=100)
    private String title;
    @Size(max=10000)
    private String content;
    @Size(max=5000)
    private String images;
    @Size(max=5)
    private java.util.Map<String,@Min(1) @Max(5) Integer> dimensions;
    @Min(1)
    private Long orderId;
}
