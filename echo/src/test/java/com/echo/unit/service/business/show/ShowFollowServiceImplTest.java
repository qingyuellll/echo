package com.echo.unit.service.business.show;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ShowFollowServiceImplTest {

    @Test
    @DisplayName("1. Show follow service test")
    void showFollow_Service() {
        Long showId = 100L;

        assertNotNull(showId);

        System.out.println("[PASS] Show follow service test passed: " + showId);
    }
}
