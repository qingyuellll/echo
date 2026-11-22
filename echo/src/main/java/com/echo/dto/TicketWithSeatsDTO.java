package com.echo.dto;

import com.echo.constants.DTOMessages;
import com.echo.dto.base.AuditableDTO;
import com.echo.entity.Ticket;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TicketWithSeatsDTO extends AuditableDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int MAX_TOTAL_SEATS = 10000;

    public static final int MAX_AREAS = 10;

    /**
     * 幂等键（必填）
     * <p>
     * 创建票种并生成座位是一次多表写入，网络重试会重复建票。客户端为每次逻辑创建
     * 生成一个 UUID，服务端以它为键缓存结果，同一个键的重复请求直接返回首次结果。
     */
    @NotBlank(message = DTOMessages.IDEMPOTENCY_KEY_NOT_EMPTY)
    private String idempotencyKey;

    @NotNull(message = DTOMessages.TICKET_WITH_SEATS_TICKET_NULL)
    @Valid
    private Ticket ticket;

    @Valid
    private SeatBatchCreateDTO seatConfig;

    public static TicketWithSeatsDTO of(Ticket ticket, SeatBatchCreateDTO seatConfig) {
        return TicketWithSeatsDTO.builder()
                .ticket(ticket)
                .seatConfig(seatConfig)
                .idempotencyKey(generateIdempotencyKey())
                .build();
    }

    public static TicketWithSeatsDTO of(Ticket ticket, SeatBatchCreateDTO seatConfig, String sourceChannel) {
        return TicketWithSeatsDTO.builder()
                .ticket(ticket)
                .seatConfig(seatConfig)
                .idempotencyKey(generateIdempotencyKey())
                .sourceChannel(sourceChannel)
                .build();
    }

    public static TicketWithSeatsDTO of(Ticket ticket, SeatBatchCreateDTO seatConfig, String sourceChannel, String remark) {
        return TicketWithSeatsDTO.builder()
                .ticket(ticket)
                .seatConfig(seatConfig)
                .idempotencyKey(generateIdempotencyKey())
                .sourceChannel(sourceChannel)
                .remark(remark)
                .build();
    }

    public static TicketWithSeatsDTO withoutSeats(Ticket ticket) {
        return TicketWithSeatsDTO.builder()
                .ticket(ticket)
                .idempotencyKey(generateIdempotencyKey())
                .build();
    }

    public boolean requiresSeats() {
        return ticket != null && Boolean.TRUE.equals(ticket.getHasSeats());
    }

    /**
     * 判断幂等键格式是否合法（必须为 36 位 UUID 字符串）
     *
     * @return true=格式合法
     */
    public boolean isIdempotencyKeyValid() {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            return false;
        }
        try {
            UUID.fromString(idempotencyKey);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isValid() {
        if (ticket == null) {
            return false;
        }
        if (ticket.getShowId() == null || ticket.getTitle() == null || ticket.getTitle().trim().isEmpty()) {
            return false;
        }
        if (requiresSeats()) {
            if (seatConfig == null || seatConfig.getAreas() == null || seatConfig.getAreas().isEmpty()) {
                return false;
            }
            int totalSeats = calculateTotalSeats();
            if (totalSeats > MAX_TOTAL_SEATS) {
                return false;
            }
        }
        if (!isIdempotencyKeyValid()) {
            return false;
        }
        if (getSourceChannel() == null || getSourceChannel().trim().isEmpty()) {
            return false;
        }
        return true;
    }

    public String getValidationError() {
        if (ticket == null) {
            return DTOMessages.TICKET_WITH_SEATS_TICKET_NULL;
        }
        if (ticket.getShowId() == null) {
            return DTOMessages.TICKET_WITH_SEATS_SHOW_ID_NULL;
        }
        if (ticket.getTitle() == null || ticket.getTitle().trim().isEmpty()) {
            return DTOMessages.TICKET_WITH_SEATS_TITLE_EMPTY;
        }
        if (requiresSeats()) {
            if (seatConfig == null) {
                return DTOMessages.TICKET_WITH_SEATS_SEAT_CONFIG_REQUIRED;
            }
            if (seatConfig.getAreas() == null || seatConfig.getAreas().isEmpty()) {
                return DTOMessages.TICKET_WITH_SEATS_AREAS_EMPTY;
            }
            int totalSeats = calculateTotalSeats();
            if (totalSeats > MAX_TOTAL_SEATS) {
                return DTOMessages.format(DTOMessages.TICKET_WITH_SEATS_TOTAL_SEATS_EXCEED, totalSeats, MAX_TOTAL_SEATS);
            }
        }
        if (!isIdempotencyKeyValid()) {
            if (getIdempotencyKey() == null || getIdempotencyKey().trim().isEmpty()) {
                return "幂等键不能为空";
            }
            return DTOMessages.TICKET_WITH_SEATS_IDEMPOTENCY_KEY_INVALID;
        }
        if (getSourceChannel() == null || getSourceChannel().trim().isEmpty()) {
            return DTOMessages.TICKET_WITH_SEATS_SOURCE_CHANNEL_INVALID;
        }
        return null;
    }

    public int calculateTotalSeats() {
        if (seatConfig == null) {
            return 0;
        }
        return seatConfig.calculateTotalSeats();
    }

    public int getAreaCount() {
        if (seatConfig == null || seatConfig.getAreas() == null) {
            return 0;
        }
        return seatConfig.getAreas().size();
    }

    public boolean isSeckillTicket() {
        return ticket != null && Integer.valueOf(1).equals(ticket.getType());
    }

    public String getTicketTitle() {
        return ticket != null ? ticket.getTitle() : null;
    }

    public Long getShowId() {
        return ticket != null ? ticket.getShowId() : null;
    }

    @Override
    public String toString() {
        return String.format("TicketWithSeatsDTO{title='%s', showId=%d, hasSeats=%b, areas=%d, totalSeats=%d, sourceChannel='%s', idempotencyKey='%s'}",
                getTicketTitle(),
                getShowId(),
                requiresSeats(),
                getAreaCount(),
                calculateTotalSeats(),
                getSourceChannel(),
                getIdempotencyKey() != null && getIdempotencyKey().length() > 8 
                    ? getIdempotencyKey().substring(0, 8) + "..." 
                    : getIdempotencyKey());
    }
}
