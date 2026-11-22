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

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seat")
public class Seat implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @NotNull(message = TicketMessages.SEAT_TICKET_ID_NOT_EMPTY)
    private Long ticketId;

    @Size(max = 20, message = TicketMessages.AREA_NAME_TOO_LONG_20)
    private String area;

    @NotNull(message = TicketMessages.ROW_NUM_NOT_EMPTY)
    @Min(value = 1, message = TicketMessages.ROW_NUM_MIN)
    @Max(value = 100, message = TicketMessages.ROW_NUM_MAX)
    private Integer rowNum;

    @NotNull(message = TicketMessages.COL_NUM_NOT_EMPTY)
    @Min(value = 1, message = TicketMessages.COL_NUM_MIN)
    @Max(value = 100, message = TicketMessages.COL_NUM_MAX)
    private Integer colNum;

    @NotNull(message = TicketMessages.SEAT_STATUS_NOT_EMPTY)
    @Min(value = 0, message = TicketMessages.SEAT_STATUS_MIN)
    @Max(value = 2, message = TicketMessages.SEAT_STATUS_MAX)
    private Integer status;

    private Long orderId;

    @Size(max = 64, message = TicketMessages.PRE_ORDER_ID_TOO_LONG)
    private String preOrderId;

    public static final int STATUS_AVAILABLE = 0;
    public static final int STATUS_LOCKED = 1;
    public static final int STATUS_SOLD = 2;

    public boolean isAvailable() {
        return status != null && status == STATUS_AVAILABLE;
    }

    public boolean isLocked() {
        return status != null && status == STATUS_LOCKED;
    }

    public boolean isSold() {
        return status != null && status == STATUS_SOLD;
    }

    public boolean isOccupied() {
        return isLocked() || isSold();
    }

    public String getDisplayName() {
        if (area == null) {
            return rowNum + "排" + colNum + "座";
        }
        return area + " " + rowNum + "排" + colNum + "座";
    }
}
