package com.echo.dto;

import com.echo.constants.OrderMessages;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketOrderCreateDTO {

    @NotNull(message = OrderMessages.COUNT_NOT_EMPTY)
    @Min(value = 1, message = OrderMessages.COUNT_MIN)
    @Max(value = 10, message = OrderMessages.COUNT_MAX)
    private Integer count = 1;

    @NotEmpty(message = OrderMessages.SEAT_IDS_NOT_EMPTY)
    @Size(max = 10, message = OrderMessages.SEAT_IDS_MAX_SIZE)
    private List<Long> seatIds;

    public static TicketOrderCreateDTO fromSeatIds(List<Long> seatIds) {
        TicketOrderCreateDTO dto = new TicketOrderCreateDTO();
        dto.setSeatIds(seatIds);
        dto.setCount(seatIds != null ? seatIds.size() : 0);
        return dto;
    }

    public static TicketOrderCreateDTO singleSeat(Long seatId) {
        return fromSeatIds(Collections.singletonList(seatId));
    }

    public boolean isValid() {
        return count != null && count >= 1 && count <= 10
                && seatIds != null && !seatIds.isEmpty()
                && seatIds.size() == count;
    }

    public int getSelectedSeatCount() {
        return seatIds != null ? seatIds.size() : 0;
    }
}
