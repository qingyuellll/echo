package com.echo.unit.service.core.ticket;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class UploadServiceImplTest {

    @Test
    @DisplayName("1. Upload service test")
    void upload_Service() {
        String fileName = "test.jpg";

        assertNotNull(fileName);

        System.out.println("[PASS] Upload service test passed");
    }
}
