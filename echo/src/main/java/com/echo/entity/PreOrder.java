package com.echo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.echo.constants.OrderMessages;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.FutureOrPresent;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("tb_pre_order")
public class PreOrder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "temp_id", type = IdType.INPUT)
    @NotBlank(message = OrderMessages.TEMP_ID_NOT_EMPTY)
    @Size(min = 10, max = 50, message = OrderMessages.TEMP_ID_LENGTH_INVALID)
    @Schema(description = "临时ID(TEMP_格式)", requiredMode = Schema.RequiredMode.REQUIRED, example = "TEMP_1692736485920_1001")
    private String tempId;

    @NotNull(message = OrderMessages.USER_ID_NOT_EMPTY)
    @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "100")
    private Long userId;

    @NotNull(message = OrderMessages.TICKET_ID_NOT_EMPTY)
    @Schema(description = "票种ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "200")
    private Long ticketId;

    @Schema(description = "演出ID", example = "50")
    private Long showId;

    @TableField(exist = false)
    @Schema(description = "座位ID列表(MQ透传)", example = "[101, 102, 103]")
    private List<Long> seatIds;

    @NotNull(message = OrderMessages.QUANTITY_NOT_EMPTY)
    @Min(value = 1, message = OrderMessages.QUANTITY_MIN)
    @Max(value = 100, message = OrderMessages.QUANTITY_MAX)
    @Schema(description = "购买数量", example = "3")
    private Integer quantity;

    @NotNull(message = OrderMessages.PAY_VALUE_NOT_EMPTY)
    @Min(value = 0, message = OrderMessages.PAY_VALUE_NEGATIVE)
    @Schema(description = "冻结销售单价(分)", example = "9900")
    private Long payValue;

    @NotNull(message = OrderMessages.ACTUAL_VALUE_NOT_EMPTY)
    @Min(value = 0, message = OrderMessages.ACTUAL_VALUE_NEGATIVE)
    @Schema(description = "冻结原价(分)", example = "12800")
    private Long actualValue;

    @NotNull(message = OrderMessages.PRE_ORDER_STATUS_NOT_EMPTY)
    @Min(value = 0, message = OrderMessages.PRE_ORDER_STATUS_MIN)
    @Max(value = 4, message = OrderMessages.PRE_ORDER_STATUS_MAX)
    @Schema(description = "状态: 0=待支付, 4=已取消", example = "0")
    private Integer status;

    @NotNull(message = OrderMessages.CREATE_TIME_NOT_EMPTY)
    @Schema(description = "创建时间", example = "2025-01-01T10:00:00")
    private LocalDateTime createTime;

    @NotNull(message = OrderMessages.EXPIRE_TIME_NOT_EMPTY)
    @FutureOrPresent(message = OrderMessages.EXPIRE_TIME_PAST)
    @Schema(description = "过期时间", example = "2025-01-01T10:30:00")
    private LocalDateTime expireTime;

    public boolean isPendingPayment() {
        return status != null && status == 0;
    }

    public boolean isCancelled() {
        return status != null && status == 4;
    }

    public boolean isExpired() {
        return expireTime != null && expireTime.isBefore(LocalDateTime.now());
    }

    public long getRemainingSeconds() {
        if (expireTime == null) {
            return 0;
        }
        long seconds = java.time.Duration.between(LocalDateTime.now(), expireTime).getSeconds();
        return Math.max(seconds, 0);
    }
}
