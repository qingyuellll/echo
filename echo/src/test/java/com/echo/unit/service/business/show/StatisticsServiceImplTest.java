package com.echo.unit.service.business.show;

import com.echo.dto.Result;
import com.echo.service.ITicketOrderService;
import com.echo.service.ITicketService;
import com.echo.service.IUserInfoService;
import com.echo.service.IShowService;
import com.echo.service.impl.StatisticsServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


/**
 * StatisticsServiceImpl Test
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatisticsServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ITicketOrderService ticketOrderService;

    @Mock
    private ITicketService ticketService;

    @Mock
    private IShowService showService;

    @Mock
    private IUserInfoService userInfoService;

    @InjectMocks
    private StatisticsServiceImpl statisticsService;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        // Mock
        when(ticketOrderService.list(any())).thenReturn(Collections.emptyList());
        when(ticketService.listByIds(any())).thenReturn(Collections.emptyList());
        when(showService.listByIds(any())).thenReturn(Collections.emptyList());
        when(userInfoService.list(any())).thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName(" - null")
    void hotShows_ResultNotNull() {
        Result<?> result = statisticsService.hotShows(10);

        assertNotNull(result, "null");
        System.out.println("[PASS] -");
    }

    @Test
    @DisplayName("?- null")
    void salesTrend_ResultNotNull() {
        Result<?> result = statisticsService.salesTrend(7);

        assertNotNull(result, "null");
        System.out.println("[PASS] ?");
    }

    @Test
    @DisplayName(" - null")
    void userProfile_ResultNotNull() {
        Result<?> result = statisticsService.userProfile();

        assertNotNull(result, "null");
        System.out.println("[PASS] -");
    }
}
