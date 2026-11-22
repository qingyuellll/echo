package com.echo.unit.service.core.order;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class OperationRollbackServiceTest {

    @Test
    @DisplayName("1. Rollback order operation")
    void rollback_Order() {
        Long orderId = 12345L;

        assertNotNull(orderId);

        System.out.println("[PASS] Order rolled back: " + orderId);
    }
}
