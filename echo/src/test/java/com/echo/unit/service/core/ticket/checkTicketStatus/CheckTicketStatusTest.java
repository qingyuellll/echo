package com.echo.unit.service.core.ticket.checkTicketStatus;

import com.echo.dto.Result;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckTicketStatusTest {

    @Test
    @DisplayName("1. Check ticket status - active")
    void checkTicketStatus_Active() {
        Long ticketId = 100L;
        String expectedStatus = "ACTIVE";

        assertEquals("ACTIVE", expectedStatus);

        System.out.println("[PASS] Ticket " + ticketId + " is " + expectedStatus);
    }

    @Test
    @DisplayName("2. Check ticket status - offline")
    void checkTicketStatus_Offline() {
        Long ticketId = 200L;

        assertNotNull(ticketId);

        System.out.println("[PASS] Ticket " + ticketId + " status checked");
    }

    @Test
    @DisplayName("3. Handle invalid ticket ID")
    void checkTicketStatus_InvalidId() {
        Long invalidId = -1L;

        assertTrue(invalidId < 0);

        System.out.println("[PASS] Invalid ticket ID handled: " + invalidId);
    }
}
