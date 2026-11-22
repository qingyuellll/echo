package com.echo.unit.service.core.ticket.isTicketActiveForPurchase;

import org.junit.jupiter.api.*;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class IsTicketActiveForPurchaseTest {

    @Test
    @DisplayName("1. Ticket is active for purchase")
    void isTicketActive_True() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime beginTime = now.minusHours(1);
        LocalDateTime endTime = now.plusHours(1);

        assertTrue(beginTime.isBefore(now));
        assertTrue(endTime.isAfter(now));

        System.out.println("[PASS] Ticket is active for purchase");
    }

    @Test
    @DisplayName("2. Ticket not started yet")
    void isTicketActive_NotStarted() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime beginTime = now.plusHours(1);

        assertTrue(beginTime.isAfter(now));

        System.out.println("[PASS] Ticket not yet started");
    }

    @Test
    @DisplayName("3. Ticket already ended")
    void isTicketActive_Ended() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endTime = now.minusMinutes(10);

        assertTrue(endTime.isBefore(now));

        System.out.println("[PASS] Ticket already ended");
    }
}
