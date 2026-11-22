package com.echo.unit.enums;

import com.echo.enums.TicketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TicketType Enum Test")
class TicketTypeTest {

    @Test
    @DisplayName("1. Test enum values and descriptions")
    void testEnumValuesAndDescriptions() {
        assertEquals(0, TicketType.NORMAL.getCode());
        assertEquals("普通票", TicketType.NORMAL.getDescription());

        assertEquals(1, TicketType.SECKILL.getCode());
        assertEquals("秒杀票", TicketType.SECKILL.getDescription());
    }

    @Test
    @DisplayName("2. fromCode() - valid input")
    void testFromCode_NormalInput() {
        assertEquals(TicketType.NORMAL, TicketType.fromCode(0));
        assertEquals(TicketType.SECKILL, TicketType.fromCode(1));
    }

    @Test
    @DisplayName("3. fromCode() - null input")
    void testFromCode_NullInput() {
        assertNull(TicketType.fromCode(null));
    }

    @Test
    @DisplayName("4. fromCode() - invalid code")
    void testFromCode_InvalidCode() {
        assertNull(TicketType.fromCode(-1));
        assertNull(TicketType.fromCode(2));
        assertNull(TicketType.fromCode(999));
    }

    @Test
    @DisplayName("5. isSeckill() - seckill type returns true")
    void testIsSeckill_True() {
        assertTrue(TicketType.isSeckill(1));
    }

    @Test
    @DisplayName("6. isSeckill() - normal type returns false")
    void testIsSeckill_False() {
        assertFalse(TicketType.isSeckill(0));
    }

    @Test
    @DisplayName("7. isSeckill() - null input returns false")
    void testIsSeckill_NullInput() {
        assertFalse(TicketType.isSeckill(null));
    }

    @Test
    @DisplayName("8. isSeckill() - invalid code returns false")
    void testIsSeckill_InvalidCode() {
        assertFalse(TicketType.isSeckill(-1));
        assertFalse(TicketType.isSeckill(2));
    }
}
