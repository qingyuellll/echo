package com.echo.dto;

import com.echo.constants.TicketMessages;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeatBatchCreateDTO {

    @NotEmpty(message = TicketMessages.AREAS_NOT_EMPTY)
    @Size(max = 10, message = TicketMessages.AREAS_MAX_SIZE)
    @Valid
    private List<AreaConfig> areas;

    public int calculateTotalSeats() {
        if (areas == null || areas.isEmpty()) {
            return 0;
        }
        return areas.stream()
                .mapToInt(area -> (area.getRows() != null ? area.getRows() : 0) *
                        (area.getCols() != null ? area.getCols() : 0))
                .sum();
    }

    public static SeatBatchCreateDTO of(AreaConfig... areaConfigs) {
        List<AreaConfig> areaList = new ArrayList<>();
        for (AreaConfig config : areaConfigs) {
            if (config != null) {
                areaList.add(config);
            }
        }
        return new SeatBatchCreateDTO(areaList);
    }

    public static SeatBatchCreateDTO empty() {
        return new SeatBatchCreateDTO(Collections.emptyList());
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AreaConfig {
        @NotBlank(message = TicketMessages.AREA_NAME_NOT_EMPTY)
        @Size(max = 20, message = TicketMessages.AREA_NAME_TOO_LONG)
        private String name;

        @NotNull(message = TicketMessages.ROWS_NOT_EMPTY)
        @Min(value = 1, message = TicketMessages.ROWS_MIN)
        @Max(value = 50, message = TicketMessages.ROWS_MAX)
        private Integer rows;

        @NotNull(message = TicketMessages.COLS_NOT_EMPTY)
        @Min(value = 1, message = TicketMessages.COLS_MIN)
        @Max(value = 100, message = TicketMessages.COLS_MAX)
        private Integer cols;

        @NotNull(message = TicketMessages.START_ROW_NOT_EMPTY)
        @Min(value = 0, message = TicketMessages.START_ROW_MIN)
        private Integer startRow;

        public int getSeatCount() {
            if (rows == null || cols == null) {
                return 0;
            }
            return rows * cols;
        }

        public static AreaConfig create(String name, int rows, int cols) {
            return new AreaConfig(name, rows, cols, 1);
        }

        public static AreaConfig create(String name, int rows, int cols, int startRow) {
            return new AreaConfig(name, rows, cols, startRow);
        }
    }
}
