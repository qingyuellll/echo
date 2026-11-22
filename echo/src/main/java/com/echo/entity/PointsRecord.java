package com.echo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.echo.constants.TicketMessages;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_points_record")
public class PointsRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @NotNull(message = TicketMessages.POINTS_USER_ID_NOT_EMPTY)
    private Long userId;

    @NotNull(message = TicketMessages.POINTS_VALUE_NOT_EMPTY)
    @Min(value = -99999, message = TicketMessages.POINTS_VALUE_MIN)
    @Max(value = 99999, message = TicketMessages.POINTS_VALUE_MAX)
    private Integer points;

    @NotNull(message = TicketMessages.POINTS_TYPE_NOT_EMPTY)
    @Min(value = 1, message = TicketMessages.POINTS_TYPE_MIN)
    @Max(value = 4, message = TicketMessages.POINTS_TYPE_MAX)
    private Integer type;

    @Size(max = 200, message = TicketMessages.POINTS_DESCRIPTION_TOO_LONG)
    private String description;

    private Long relatedId;

    private LocalDateTime createTime;

    public static final int TYPE_INCOME = 1;
    public static final int TYPE_EXPENSE = 2;
    public static final int TYPE_REFUND = 3;
    public static final int TYPE_LOTTERY = 4;
}
