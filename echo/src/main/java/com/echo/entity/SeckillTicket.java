package com.echo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.echo.constants.TicketMessages;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;


@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seckill_ticket")
public class SeckillTicket implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = TicketMessages.SECKILL_TICKET_ID_NOT_EMPTY)
    @TableId(value = "ticket_id", type = IdType.INPUT)
    private Long ticketId;

    @Min(value = 0, message = TicketMessages.SECKILL_STOCK_NEGATIVE)
    @Max(value = 100000, message = TicketMessages.SECKILL_STOCK_MAX)
    @JsonProperty("seckillStock")
    private Integer stock;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @NotNull(message = TicketMessages.SECKILL_BEGIN_TIME_NOT_EMPTY)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime beginTime;

    @NotNull(message = TicketMessages.SECKILL_END_TIME_NOT_EMPTY)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime scheduledStartTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    public boolean isActive() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime actualStart = scheduledStartTime != null ? scheduledStartTime : beginTime;
        return !now.isBefore(actualStart) && !now.isAfter(endTime);
    }

    public boolean isUpcoming() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime actualStart = scheduledStartTime != null ? scheduledStartTime : beginTime;
        return now.isBefore(actualStart);
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(endTime);
    }

    public boolean hasStock() {
        return stock != null && stock > 0;
    }

    public LocalDateTime getActualStartTime() {
        return scheduledStartTime != null ? scheduledStartTime : beginTime;
    }

    public long getRemainingSeconds() {
        if (endTime == null) {
            return 0;
        }
        long seconds = java.time.Duration.between(LocalDateTime.now(), endTime).getSeconds();
        return Math.max(seconds, 0);
    }
}
