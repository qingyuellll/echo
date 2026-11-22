package com.echo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.echo.constants.TicketMessages;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Data
@TableName("tb_review_like")
public class ReviewLike {

    @TableId(type = IdType.AUTO)
    private Long id;

    @NotNull(message = TicketMessages.REVIEW_LIKE_REVIEW_ID_NOT_EMPTY)
    private Long reviewId;

    @NotNull(message = TicketMessages.REVIEW_LIKE_USER_ID_NOT_EMPTY)
    private Long userId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    public static final String REDIS_KEY_PREFIX = "review:liked:";

    public static String buildRedisKey(Long reviewId) {
        return REDIS_KEY_PREFIX + reviewId;
    }
}
