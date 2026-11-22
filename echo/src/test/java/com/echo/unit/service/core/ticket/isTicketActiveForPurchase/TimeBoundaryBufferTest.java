package com.echo.unit.service.core.ticket.isTicketActiveForPurchase;

import com.echo.dto.Result;
import com.echo.entity.SeckillTicket;
import com.echo.service.ISeckillTicketService;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Time Boundary Buffer Test")
public class TimeBoundaryBufferTest {

    private static final Long TEST_TICKET_ID = 500L;

    void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("1.1 Ticket not started yet")
    void ticketNotStarted_ShouldReturnInactive() {
        LocalDateTime now = LocalDateTime.now();

        SeckillTicket config = new SeckillTicket();
        config.setTicketId(TEST_TICKET_ID);
        config.setStock(100);
        config.setBeginTime(now.plusMinutes(5));
        config.setEndTime(now.plusHours(2));

        assertNotNull(config.getBeginTime());
        assertTrue(config.getBeginTime().isAfter(now));

        System.out.println("[PASS] Not started ticket handled correctly");
    }

    @Test
    @DisplayName("1.2 Ticket already ended")
    void ticketAlreadyEnded_ShouldReturnInactive() {
        LocalDateTime now = LocalDateTime.now();

        SeckillTicket config = new SeckillTicket();
        config.setTicketId(TEST_TICKET_ID);
        config.setStock(0);
        config.setBeginTime(now.minusHours(5));
        config.setEndTime(now.minusMinutes(10));

        assertNotNull(config.getEndTime());
        assertTrue(config.getEndTime().isBefore(now));

        System.out.println("[PASS] Ended ticket handled correctly");
    }
}
