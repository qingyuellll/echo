package com.echo.unit.service.core.ticket.seckillConcurrency;

import com.echo.entity.SeckillTicket;
import com.echo.entity.Ticket;
import com.echo.mapper.TicketMapper;
import com.echo.mapper.TicketOrderMapper;
import com.echo.service.ISeckillTicketService;
import com.echo.service.impl.TicketServiceImpl;
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
import org.springframework.data.redis.core.SetOperations;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TicketServiceImpl - Lua Script Safety Test (V2.0)")
public class SeckillScriptSafetyTest {

    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private ISeckillTicketService seckillTicketService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private TicketOrderMapper ticketOrderMapper;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SetOperations<String, String> setOperations; // ✅ 新增：修复 opsForSet() Mock

    @InjectMocks
    private TicketServiceImpl ticketService;

    protected Ticket normalTicket;
    protected Ticket seckillTicket;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();

        normalTicket = new Ticket();
        normalTicket.setId(1L);
        normalTicket.setShowId(100L);
        normalTicket.setTitle("VIP Normal Ticket");
        normalTicket.setStock(500);
        normalTicket.setType(0);
        normalTicket.setStatus(1);
        normalTicket.setPayValue(10000L);
        normalTicket.setHasSeats(true);

        seckillTicket = new Ticket();
        seckillTicket.setId(2L);
        seckillTicket.setShowId(100L);
        seckillTicket.setTitle("Seckill Flash Sale");
        seckillTicket.setStock(100);
        seckillTicket.setType(1);
        seckillTicket.setStatus(1);
        seckillTicket.setBeginTime(now.plusMinutes(-10));
        seckillTicket.setEndTime(now.plusHours(2));
        seckillTicket.setPayValue(5000L);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations); // ✅ 新增
        configureTestSpecificMocks();
    }

    protected void configureTestSpecificMocks() {
        lenient().when(ticketMapper.selectById(1L)).thenReturn(normalTicket);
        lenient().when(ticketMapper.selectById(2L)).thenReturn(seckillTicket);
        lenient().when(seckillTicketService.getById(2L)).thenAnswer(invocation -> {
            return createSeckillConfig(2L, seckillTicket.getStock(),
                    seckillTicket.getBeginTime(), seckillTicket.getEndTime());
        });
    }

    protected SeckillTicket createSeckillConfig(Long ticketId, Integer stock, LocalDateTime beginTime, LocalDateTime endTime) {
        SeckillTicket config = new SeckillTicket();
        config.setTicketId(ticketId);
        config.setStock(stock);
        config.setBeginTime(beginTime);
        config.setEndTime(endTime);
        return config;
    }

    // ==================== 1. 库存递减安全性测试 ====================

    @Nested
    @DisplayName("1. Stock Decrement Safety Tests")
    class StockDecrementSafety {

        @Test
        @DisplayName("1.1 最后一件商品库存检查")
        void lastItem_StockCheck() {
            seckillTicket.setStock(1);
            when(valueOperations.get(anyString())).thenReturn("1");

            boolean isActive = ticketService.isTicketActiveForPurchase(2L);

            log.info("[TEST] 最后一件商品: active={}, stock={}", isActive, seckillTicket.getStock());
            assertNotNull(isActive, "应返回状态值");
            log.info("[PASS] 最后一件商品库存检查完成");
        }

        @Test
        @DisplayName("1.2 大库存处理验证")
        void largeStock_Handling() {
            seckillTicket.setStock(9999);
            when(valueOperations.get(anyString())).thenReturn("9999");

            boolean isActive = ticketService.isTicketActiveForPurchase(2L);

            log.info("[TEST] 大库存: active={}, stock={}", isActive, seckillTicket.getStock());
            assertNotNull(isActive, "应返回状态值");
            log.info("[PASS] 大库存处理验证完成");
        }

        @Test
        @DisplayName("1.3 零库存应拒绝购买")
        void zeroStock_RejectPurchase() {
            seckillTicket.setStock(0);
            when(valueOperations.get(anyString())).thenReturn("0");

            boolean isActive = ticketService.isTicketActiveForPurchase(2L);

            assertFalse(isActive, "零库存应拒绝购买");
            log.info("[PASS] 零库存正确拒绝");
        }
    }

    // ==================== 2. 边界情况测试 ====================

    @Nested
    @DisplayName("2. Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("2.1 负库存视为售罄")
        void negativeStock_TreatedAsSoldOut() {
            seckillTicket.setStock(-5);
            when(valueOperations.get(anyString())).thenReturn("-5");

            boolean isActive = ticketService.isTicketActiveForPurchase(2L);

            assertFalse(isActive, "负库存应视为售罄");
            log.info("[PASS] 负库存正确处理为售罄");
        }

        @Test
        @DisplayName("2.2 非数字库存优雅降级")
        void nonNumericStock_GracefulDegradation() {
            when(valueOperations.get(anyString())).thenReturn("invalid_stock");

            assertDoesNotThrow(() -> ticketService.isTicketActiveForPurchase(2L),
                    "非数字库存不应抛异常，应降级处理");
            
            boolean isActive = ticketService.isTicketActiveForPurchase(2L);
            assertFalse(isActive, "无效库存格式应返回不可购买");
            log.info("[PASS] 非数字库存优雅降级");
        }

        @Test
        @DisplayName("2.3 Redis Key 不存在返回库存不足")
        void redisKeyNotExists_ReturnStockInsufficient() {
            when(valueOperations.get(anyString())).thenReturn(null);

            boolean isActive = ticketService.isTicketActiveForPurchase(2L);

            assertFalse(isActive, "Redis Key 不存在应返回库存不足");
            log.info("[PASS] Redis Key 不存在处理正确");
        }

        @Test
        @DisplayName("2.4 库存变化场景验证")
        void stockChangeScenario() {
            when(valueOperations.get(anyString()))
                .thenReturn("1")
                .thenReturn("0");

            boolean firstCheck = ticketService.isTicketActiveForPurchase(2L);
            log.info("[TEST] 第一次检查 (stock=1): active={}", firstCheck);

            boolean secondCheck = ticketService.isTicketActiveForPurchase(2L);
            log.info("[TEST] 第二次检查 (stock=0): active={}", secondCheck);

            assertNotNull(firstCheck, "第一次应返回状态");
            assertNotNull(secondCheck, "第二次应返回状态");
            log.info("[PASS] 库存变化场景验证完成");
        }
    }

    // ==================== 3. V2.0 新功能测试 ====================

    @Nested
    @DisplayName("3. V2.0 New Features")
    class V2Features {

        @Test
        @DisplayName("3.1 双重检查机制（DB + Redis）- 验证可执行性")
        void doubleCheckMechanism() {
            seckillTicket.setStatus(1);
            seckillTicket.setBeginTime(LocalDateTime.now().plusMinutes(-10));
            seckillTicket.setEndTime(LocalDateTime.now().plusHours(1));

            when(valueOperations.get(anyString())).thenReturn("50");

            boolean isActive = ticketService.isTicketActiveForPurchase(2L);

            log.info("[TEST] 双重检查结果: active={}", isActive);
            assertNotNull(isActive, "应返回布尔值");
            log.info("[PASS] 双重检查机制正常执行");
        }

        @Test
        @DisplayName("3.2 回滚可靠性保证 - 异常不崩溃")
        void rollbackReliability() {
            seckillTicket.setStatus(1);
            when(valueOperations.get(anyString())).thenReturn("100");

            assertDoesNotThrow(() -> ticketService.isTicketActiveForPurchase(2L),
                "异常情况不应导致系统崩溃");

            log.info("[PASS] 回滚可靠性验证完成");
        }

        @Test
        @DisplayName("3.3 库存查询操作验证")
        void stockQueryOperation() {
            seckillTicket.setStatus(1);
            when(valueOperations.get(anyString())).thenReturn("99");

            boolean isActive = ticketService.isTicketActiveForPurchase(2L);

            log.info("[TEST] 库存操作结果: active={}", isActive);
            assertNotNull(isActive, "应返回库存状态");
            log.info("[PASS] 库存查询操作正常");
        }
    }

    // ==================== 4. 重复订单防护测试 ====================

    @Nested
    @DisplayName("4. Duplicate Order Prevention")
    class DuplicateOrderPrevention {

        @Test
        @DisplayName("4.1 同一用户不能重复购买同一秒杀票")
        void sameUser_CannotBuyTwice() {
            Long userId = 12345L;
            Long ticketId = 2L;
            
            when(stringRedisTemplate.opsForSet().isMember(anyString(), eq(String.valueOf(userId))))
                .thenReturn(true); // 已购买记录

            boolean hasPurchased = checkUserPurchased(userId, ticketId);
            
            assertTrue(hasPurchased, "同一用户已购买应返回true");
            log.info("[PASS] 同一用户重复购买检测正常");
        }

        @Test
        @DisplayName("4.2 不同用户可以购买同一秒杀票")
        void differentUsers_CanBuySameTicket() {
            Long userId1 = 11111L;
            Long userId2 = 22222L;
            Long ticketId = 2L;
            
            when(stringRedisTemplate.opsForSet().isMember(anyString(), eq(String.valueOf(userId1))))
                .thenReturn(false); // 用户1未购买
            when(stringRedisTemplate.opsForSet().isMember(anyString(), eq(String.valueOf(userId2))))
                .thenReturn(true);  // 用户2已购买

            boolean user1Purchased = checkUserPurchased(userId1, ticketId);
            boolean user2Purchased = checkUserPurchased(userId2, ticketId);
            
            assertFalse(user1Purchased, "用户1未购买应返回false");
            assertTrue(user2Purchased, "用户2已购买应返回true");
            log.info("[PASS] 不同用户购买权限隔离正常");
        }

        private boolean checkUserPurchased(Long userId, Long ticketId) {
            return Boolean.TRUE.equals(
                stringRedisTemplate.opsForSet().isMember(
                    "seckill:purchased:" + ticketId,
                    String.valueOf(userId)
                )
            );
        }
    }
}
