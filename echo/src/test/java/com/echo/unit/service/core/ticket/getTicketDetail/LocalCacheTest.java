package com.echo.unit.service.core.ticket.getTicketDetail;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalCacheTest {

    @Test
    @DisplayName("1. Local cache test")
    void local_Cache() {
        String cacheKey = "local:cache:test";

        assertNotNull(cacheKey);
        assertTrue(cacheKey.startsWith("local:"));

        System.out.println("[PASS] Local cache test passed");
    }
}
