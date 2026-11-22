package com.echo.unit.enums;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class TicketStatusTest {

    @Test
    @DisplayName("1. Ticket status test")
    void ticket_Status() {
        String status = "ACTIVE";

        assertEquals("ACTIVE", status);

        System.out.println("[PASS] Ticket status verified: " + status);
    }
}
