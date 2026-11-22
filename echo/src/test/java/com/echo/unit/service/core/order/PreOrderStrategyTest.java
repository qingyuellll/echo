package com.echo.unit.service.core.order;

import com.echo.entity.PreOrder;
import com.echo.entity.Ticket;
import com.echo.entity.TicketOrder;
import com.echo.service.strategy.SeatStrategy;
import com.echo.service.strategy.impl.WithSeatStrategy;
import com.echo.service.strategy.impl.WithoutSeatStrategy;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PreOrderStrategyTest {

    @Mock
    private com.echo.service.ISeatService seatService;

    @Mock
    private com.echo.utils.RedisWorker redisWorker;

    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private WithSeatStrategy withSeatStrategy;

    @InjectMocks
    private WithoutSeatStrategy withoutSeatStrategy;

    private PreOrder createTestPreOrder(boolean withSeats) {
        PreOrder preOrder = new PreOrder();
        preOrder.setTempId("TEMP_1234567890_1001");
        preOrder.setUserId(1001L);
        preOrder.setTicketId(2001L);
        preOrder.setShowId(3001L);
        preOrder.setStatus(0);
        preOrder.setCreateTime(LocalDateTime.now());
        preOrder.setExpireTime(LocalDateTime.now().plusMinutes(30));

        if (withSeats) {
            List<Long> seatIds = new ArrayList<>();
            seatIds.add(4001L);
            seatIds.add(4002L);
            seatIds.add(4003L);
            preOrder.setSeatIds(seatIds);
            preOrder.setQuantity(3);
        } else {
            preOrder.setQuantity(2);
        }

        return preOrder;
    }

    private Ticket createTestTicket(boolean hasSeats) {
        Ticket ticket = new Ticket();
        ticket.setId(2001L);
        ticket.setTitle("测试演出票");
        ticket.setHasSeats(hasSeats);
        ticket.setPayValue(10000L); // 100元（单位：分）
        return ticket;
    }

    @Test
    @DisplayName("1. WithSeatStrategy - 有座票拆单：生成3个子订单")
    @org.junit.jupiter.api.Order(1)
    void withSeatStrategy_BuildSubOrdersOnPayment_ShouldCreate3SubOrders() {
        log.info("=========================================");
        log.info("[TEST] 开始测试有座票支付时拆单逻辑");

        PreOrder preOrder = createTestPreOrder(true);
        Ticket ticket = createTestTicket(true);

        when(redisWorker.nextId("order")).thenReturn(5001L, 5002L, 5003L);

        when(seatService.getById(anyLong())).thenAnswer(invocation -> {
            Long seatId = invocation.getArgument(0);
            com.echo.entity.Seat seat = new com.echo.entity.Seat();
            seat.setId(seatId);
            seat.setStatus(1); // 预占状态
            seat.setPreOrderId(preOrder.getTempId());
            return seat;
        });

        when(seatService.updateById(any())).thenReturn(true);

        List<TicketOrder> subOrders = withSeatStrategy.buildSubOrdersOnPayment(
                preOrder, ticket, 100, 50L);

        assertNotNull(subOrders);
        assertEquals(3, subOrders.size(), "应该生成3个子订单（对应3个座位）");

        log.info("[PASS] ✅ 成功生成{}个子订单", subOrders.size());

        for (int i = 0; i < subOrders.size(); i++) {
            TicketOrder subOrder = subOrders.get(i);

            log.info("[验证] 子订单{}/{} - orderId:{}, 核销码:{}",
                    (i + 1), subOrders.size(),
                    subOrder.getId(), subOrder.getVerificationCode());

            assertNotNull(subOrder.getId(), "子订单ID不能为空");
            assertEquals(1001L, subOrder.getUserId(), "用户ID必须匹配");
            assertEquals(2001L, subOrder.getTicketId(), "票种ID必须匹配");
            assertEquals(3001L, subOrder.getShowId(), "演出ID必须匹配");
            assertEquals(2, subOrder.getStatus(), "状态必须是已支付(2)");
            assertEquals(1, subOrder.getQuantity(), "每张子订单数量必须是1");
            assertNotNull(subOrder.getVerificationCode(), "核销码不能为空");
            assertTrue(subOrder.getVerificationCode().length() == 8,
                    "核销码长度必须是8位");

            String expectedIndex = (i + 1) + "/3";
            assertEquals(expectedIndex, subOrder.getSubOrderIndex(),
                    "子订单序号格式错误");
        }

        log.info("[PASS] ✅ 所有子订单字段验证通过");
        verify(redisWorker, times(3)).nextId("order");
        verify(seatService, times(3)).getById(anyLong());
        verify(seatService, times(3)).updateById(any());
    }

    @Test
    @DisplayName("2. WithSeatStrategy - 积分抵扣：仅第一张票扣积分")
    @org.junit.jupiter.api.Order(2)
    void withSeatStrategy_PointsDeduction_ShouldOnlyApplyToFirstTicket() {
        log.info("[TEST] 测试积分抵扣逻辑 - 仅第一张扣积分");

        PreOrder preOrder = createTestPreOrder(true);
        Ticket ticket = createTestTicket(true);

        when(redisWorker.nextId("order")).thenReturn(6001L, 6002L, 6003L);
        when(seatService.getById(anyLong())).thenAnswer(invocation -> {
            com.echo.entity.Seat seat = new com.echo.entity.Seat();
            seat.setId(invocation.getArgument(0));
            seat.setStatus(1); // 预占状态
            seat.setPreOrderId(preOrder.getTempId());
            return seat;
        });
        when(seatService.updateById(any())).thenReturn(true);

        int pointsToUse = 200; // 使用200积分，抵扣20元
        long discountAmount = 20L;

        List<TicketOrder> subOrders = withSeatStrategy.buildSubOrdersOnPayment(
                preOrder, ticket, pointsToUse, discountAmount);

        assertEquals(pointsToUse, subOrders.get(0).getPointsUsed(),
                "第一张票应该扣除所有积分");
        assertEquals(discountAmount, subOrders.get(0).getDiscountAmount(),
                "第一张票应该包含折扣金额");

        for (int i = 1; i < subOrders.size(); i++) {
            assertEquals(0, subOrders.get(i).getPointsUsed(),
                    "第" + (i + 1) + "张票不应该扣积分");
            assertEquals(0L, subOrders.get(i).getDiscountAmount(),
                    "第" + (i + 1) + "张票不应该有折扣");
        }

        log.info("[PASS] ✅ 积分抵扣逻辑正确 - 仅第一张票扣{}积分", pointsToUse);
    }

    @Test
    @DisplayName("3. WithoutSeatStrategy - 无座票拆单：生成2个子订单")
    @org.junit.jupiter.api.Order(3)
    void withoutSeatStrategy_BuildSubOrdersOnPayment_ShouldCreate2SubOrders() {
        log.info("=========================================");
        log.info("[TEST] 开始测试无座票支付时拆单逻辑");

        PreOrder preOrder = createTestPreOrder(false);
        Ticket ticket = createTestTicket(false);

        when(redisWorker.nextId("order")).thenReturn(7001L, 7002L);

        List<TicketOrder> subOrders = withoutSeatStrategy.buildSubOrdersOnPayment(
                preOrder, ticket, 50, 25L);

        assertNotNull(subOrders);
        assertEquals(2, subOrders.size(), "应该生成2个子订单（购买数量=2）");

        log.info("[PASS] ✅ 成功生成{}个子订单", subOrders.size());

        for (int i = 0; i < subOrders.size(); i++) {
            TicketOrder subOrder = subOrders.get(i);

            log.info("[验证] 子订单{}/{} - orderId:{}, 核销码:{}",
                    (i + 1), subOrders.size(),
                    subOrder.getId(), subOrder.getVerificationCode());

            assertNotNull(subOrder.getId(), "子订单ID不能为空");
            assertEquals(1001L, subOrder.getUserId(), "用户ID必须匹配");
            assertEquals(2001L, subOrder.getTicketId(), "票种ID必须匹配");
            assertEquals(3001L, subOrder.getShowId(), "演出ID必须匹配");
            assertEquals(2, subOrder.getStatus(), "状态必须是已支付(2)");
            assertEquals(1, subOrder.getQuantity(), "每张子订单数量必须是1");
            assertNotNull(subOrder.getVerificationCode(), "核销码不能为空");
            assertEquals(10000L, subOrder.getPayValue(), "票价必须匹配");

            String expectedIndex = (i + 1) + "/2";
            assertEquals(expectedIndex, subOrder.getSubOrderIndex(),
                    "子订单序号格式错误");
        }

        log.info("[PASS] ✅ 所有无座票子订单字段验证通过");
        verify(redisWorker, times(2)).nextId("order");
    }

    @Test
    @DisplayName("4. WithoutSeatStrategy - 无座票不调用锁座方法")
    @org.junit.jupiter.api.Order(4)
    void withoutSeatStrategy_ShouldNotCallLockSeats() {
        log.info("[TEST] 验证无座票策略不会调用锁座相关方法");

        PreOrder preOrder = createTestPreOrder(false);
        Ticket ticket = createTestTicket(false);

        when(redisWorker.nextId("order")).thenReturn(8001L, 8002L);

        withoutSeatStrategy.buildSubOrdersOnPayment(preOrder, ticket, 0, 0L);

        verify(seatService, never()).getById(anyLong());
        verify(seatService, never()).updateById(any());

        log.info("[PASS] ✅ 无座票策略未调用任何锁座方法（符合预期）");
    }

    @Test
    @DisplayName("5. WithSeatStrategy - 座位锁定失败应抛出异常")
    @org.junit.jupiter.api.Order(5)
    void withSeatStrategy_LockSeatFailure_ShouldThrowException() {
        log.info("[TEST] 测试座位锁定失败时的异常处理");

        PreOrder preOrder = createTestPreOrder(true);
        Ticket ticket = createTestTicket(true);

        when(redisWorker.nextId("order")).thenReturn(9001L, 9002L, 9003L);
        when(seatService.getById(anyLong())).thenAnswer(invocation -> {
            com.echo.entity.Seat seat = new com.echo.entity.Seat();
            seat.setId(invocation.getArgument(0));
            seat.setStatus(1); // 预占状态
            seat.setPreOrderId(preOrder.getTempId());
            return seat;
        });
        when(seatService.updateById(any())).thenReturn(false); // 锁定失败

        assertThrows(RuntimeException.class, () -> {
            withSeatStrategy.buildSubOrdersOnPayment(preOrder, ticket, 0, 0L);
        }, "座位锁定失败应该抛出RuntimeException");

        log.info("[PASS] ✅ 座位锁定失败正确抛出异常");
    }

    @Test
    @DisplayName("6. 策略对比 - 有座票vs无座票的核销码生成")
    @org.junit.jupiter.api.Order(6)
    void compareStrategies_VerificationCodesShouldBeUnique() {
        log.info("[TEST] 对比两种策略生成的核销码唯一性");

        PreOrder preOrderWithSeats = createTestPreOrder(true);
        PreOrder preOrderWithoutSeats = createTestPreOrder(false);
        Ticket ticketWithSeats = createTestTicket(true);
        Ticket ticketWithoutSeats = createTestTicket(false);

        when(redisWorker.nextId("order"))
                .thenReturn(10001L, 10002L, 10003L, 11001L, 11002L);
        when(seatService.getById(anyLong())).thenAnswer(invocation -> {
            com.echo.entity.Seat seat = new com.echo.entity.Seat();
            seat.setId(invocation.getArgument(0));
            seat.setStatus(1); // 预占状态
            seat.setPreOrderId(preOrderWithSeats.getTempId());
            return seat;
        });
        when(seatService.updateById(any())).thenReturn(true);

        List<TicketOrder> seatOrders = withSeatStrategy.buildSubOrdersOnPayment(
                preOrderWithSeats, ticketWithSeats, 0, 0L);
        List<TicketOrder> nonSeatOrders = withoutSeatStrategy.buildSubOrdersOnPayment(
                preOrderWithoutSeats, ticketWithoutSeats, 0, 0L);

        List<String> allCodes = new ArrayList<>();
        seatOrders.forEach(order -> allCodes.add(order.getVerificationCode()));
        nonSeatOrders.forEach(order -> allCodes.add(order.getVerificationCode()));

        long uniqueCount = allCodes.stream().distinct().count();
        assertEquals(allCodes.size(), uniqueCount,
                "所有核销码必须是唯一的");

        log.info("[PASS] ✅ 共生成{}个唯一核销码", uniqueCount);
        allCodes.forEach(code -> log.info("  - 核销码: {}", code));
    }
}
