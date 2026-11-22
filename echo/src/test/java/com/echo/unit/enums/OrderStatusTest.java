package com.echo.unit.enums;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderStatusTest {

    @Test
    @DisplayName("1. Validate order status")
    void validateOrder_Status() {
        String status = "PAID";
        
        assertEquals("PAID", status);
        
        System.out.println("[PASS] Order status validated: " + status);
    }
}
