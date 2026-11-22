package com.echo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.echo.constants.TicketMessages;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("tb_review")
public class Review {
    @TableId(type = IdType.AUTO)
    private Long id;

    @NotNull(message = TicketMessages.REVIEW_SHOW_ID_NOT_EMPTY)
    private Long showId;

    @NotNull(message = TicketMessages.REVIEW_USER_ID_NOT_EMPTY)
    private Long userId;

    @NotNull(message = TicketMessages.REVIEW_ORDER_ID_NOT_EMPTY)
    private Long orderId;

    @NotNull(message = TicketMessages.REVIEW_SCORE_NOT_EMPTY)
    @Min(value = 1, message = TicketMessages.REVIEW_SCORE_MIN)
    @Max(value = 5, message = TicketMessages.REVIEW_SCORE_MAX)
    private Integer score;

    @Size(max = 1000, message = TicketMessages.REVIEW_CONTENT_TOO_LONG)
    private String content;

    @Size(max = 2000, message = TicketMessages.REVIEW_IMAGES_TOO_LONG)
    private String images;

    @NotNull(message = TicketMessages.REVIEW_STATUS_NOT_EMPTY)
    @Min(value = 0, message = TicketMessages.REVIEW_STATUS_MIN)
    @Max(value = 2, message = TicketMessages.REVIEW_STATUS_MAX)
    private Integer status;

    @Min(value = 0, message = TicketMessages.LIKE_COUNT_NEGATIVE)
    private Integer likeCount;

    private Integer isAnonymous;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private String userName;

    @TableField(exist = false)
    private String userAvatar;

    @TableField(exist = false)
    private Boolean isLiked;

    @TableField(exist = false)
    private List<String> imageList;

    public static final int STATUS_HIDDEN = 0;
    public static final int STATUS_VISIBLE = 1;
    public static final int STATUS_PENDING_REVIEW = 2;

    public boolean isVisible() {
        return status != null && status == STATUS_VISIBLE;
    }

    boolean isHidden() {
        return status != null && status == STATUS_HIDDEN;
    }

    boolean isPendingReview() {
        return status != null && status == STATUS_PENDING_REVIEW;
    }

    public boolean isAnonymous() {
        return isAnonymous != null && isAnonymous == 1;
    }

    public List<String> getImageList() {
        if (images == null || images.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        try {
            return com.alibaba.fastjson.JSON.parseArray(images, String.class);
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }
}
