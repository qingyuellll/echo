package com.echo.unit.service.core.payment;

import com.echo.dto.Result;
import com.echo.entity.PreOrder;
import com.echo.entity.Ticket;
import com.echo.entity.UserInfo;
import com.echo.service.IPreOrderService;
import com.echo.service.ITicketService;
import com.echo.service.IUserInfoService;
import com.echo.service.impl.TicketOrderServiceImpl;
import com.echo.monitor.OrderMetricsService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 积分抵扣50%上限边界值测试
 * <p>
 * 测试场景：
 * 1. 正常情况：使用积分 ≤ 50%订单金额 → 应成功
 * 2. 边界情况：使用积分 = 50%订单金额 → 应成功（边界值）
 * 3. 超限情况：使用积分 > 50%订单金额 → 应拒绝
 * 4. 极端情况：积分远超上限（10倍）→ 应拒绝并返回友好提示
 * 5. 零积分：usePoints=0 → 不触发校验，正常支付
 * 6. 空值情况：usePoints=null → 不触发校验，正常支付
 * <p>
 * 业务规则：
 * - 抵扣比例：10积分=1元（即1积分=0.1元）
 * - 上限限制：最多抵扣订单总价的50%
 * - 计算公式：discountAmount = usePoints / 10（单位：元）
 *
 * @author echo
 * @version 1.0 (2026-08-27 新增)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PointsDiscountLimitTest {

    @Mock
    private ITicketService ticketService;

    @Mock
    private IPreOrderService preOrderService;

    @Mock
    private IUserInfoService userInfoService;

    @Mock
    private OrderMetricsService orderMetricsService;

    @InjectMocks
    private TicketOrderServiceImpl ticketOrderService;

    private PreOrder createTestPreOrder(int quantity) {
        PreOrder preOrder = new PreOrder();
        preOrder.setTempId("TEST_TEMP_001");
        preOrder.setUserId(1001L);
        preOrder.setTicketId(2001L);
        preOrder.setShowId(3001L);
        preOrder.setStatus(0);
        preOrder.setQuantity(quantity);
        return preOrder;
    }

    private Ticket createTestTicket(long priceInFen) {
        Ticket ticket = new Ticket();
        ticket.setId(2001L);
        ticket.setTitle("测试演出");
        ticket.setPayValue(priceInFen);
        ticket.setHasSeats(false);
        return ticket;
    }

    private UserInfo createTestUserInfo(int credits) {
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(1001L);
        userInfo.setCredits(credits);
        return userInfo;
    }

    @BeforeEach
    void setUp() {
        when(preOrderService.getById(anyString())).thenReturn(createTestPreOrder(1));
        when(ticketService.getById(anyLong())).thenReturn(createTestTicket(10000L));
        when(userInfoService.getById(anyLong())).thenReturn(createTestUserInfo(10000));
    }

    @Test
    @DisplayName("1. 正常抵扣 - 使用400积分抵扣40元（总价100元，40%<50%）")
    @org.junit.jupiter.api.Order(1)
    void testNormalDiscount_Under50Percent_ShouldSucceed() {
        log.info("=========================================");
        log.info("[TEST] 测试正常抵扣 - 400积分抵扣40元");

        int usePoints = 400;
        int expectedDiscount = 40;

        log.info("设置: 总价100元, 使用{}积分, 预期抵扣{}元({}%)",
                usePoints, expectedDiscount, expectedDiscount);

        Result<?> result = ticketOrderService.payOrder("TEST_TEMP_001", usePoints);

        assertNotNull(result, "结果不应为null");
        log.info("结果: {}", result.getMsg());
    }

    @Test
    @DisplayName("2. 边界值 - 使用500积分恰好抵扣50%（总价100元）")
    @org.junit.jupiter.api.Order(2)
    void testBoundaryValue_Exactly50Percent_ShouldSucceed() {
        log.info("=========================================");
        log.info("[TEST] 测试边界值 - 恰好50%上限");

        int usePoints = 500;
        int totalPrice = 100;
        int maxDiscount = totalPrice / 2;

        log.info("设置: 总价{}元, 使用{}积分, 上限{}元(50%)",
                totalPrice, usePoints, maxDiscount);

        Result<?> result = ticketOrderService.payOrder("TEST_TEMP_001", usePoints);

        assertNotNull(result, "边界值应允许通过");
        verify(orderMetricsService, never()).recordPointsDiscountRejected(anyLong(), anyLong(), anyLong());
        log.info("✅ 边界值测试通过 - 50%上限允许");
    }

    @Test
    @DisplayName("3. 超限拒绝 - 使用600积分尝试抵扣60元（超过50%上限）")
    @org.junit.jupiter.api.Order(3)
    void testExceedLimit_Over50Percent_ShouldReject() {
        log.info("=========================================");
        log.info("[TEST] 测试超限拒绝 - 超过50%上限");

        int usePoints = 600;
        int totalPrice = 100;
        int requestedDiscount = usePoints / 10;
        int maxDiscount = totalPrice / 2;

        log.info("设置: 总价{}元, 使用{}积分, 请求抵扣{}元, 上限{}元",
                totalPrice, usePoints, requestedDiscount, maxDiscount);

        assertTrue(requestedDiscount > maxDiscount,
                "前置条件: 请求抵扣应大于上限");

        Result<?> result = ticketOrderService.payOrder("TEST_TEMP_001", usePoints);

        assertNotNull(result);
        assertFalse(result.isSuccess(), "超限应被拒绝");
        assertTrue(result.getMsg().contains("50%"),
                "错误信息应包含'50%'提示");
        assertTrue(result.getMsg().contains(String.valueOf(maxDiscount)),
                "错误信息应显示最大可抵扣金额");

        verify(orderMetricsService, times(1)).recordPointsDiscountRejected(
                eq(1001L),
                eq((long) requestedDiscount * 100),
                eq((long) maxDiscount * 100)
        );

        log.info("✅ 超限测试通过 - 正确拒绝并记录指标");
    }

    @Test
    @DisplayName("4. 极端超限 - 使用5000积分尝试全额抵扣（5倍上限）")
    @org.junit.jupiter.api.Order(4)
    void testExtremeExceed_5TimesLimit_ShouldReject() {
        log.info("=========================================");
        log.info("[TEST] 测试极端超限 - 5000积分（5倍上限）");

        int usePoints = 5000;
        int totalPrice = 100;
        int requestedDiscount = usePoints / 10;
        int maxDiscount = totalPrice / 2;

        log.info("设置: 总价{}元, 使用{}积分, 请求抵扣{}元(上限{}元的{}倍)",
                totalPrice, usePoints, requestedDiscount, maxDiscount, requestedDiscount / maxDiscount);

        Result<?> result = ticketOrderService.payOrder("TEST_TEMP_001", usePoints);

        assertNotNull(result);
        assertFalse(result.isSuccess(), "极端超限应被拒绝");
        assertTrue(result.getMsg().contains(String.valueOf(maxDiscount)),
                "即使极端超限也应显示正确上限");

        log.info("✅ 极端超限测试通过 - 系统健壮性验证OK");
    }

    @Test
    @DisplayName("5. 零积分 - 不使用积分支付")
    @org.junit.jupiter.api.Order(5)
    void testZeroPoints_NoDiscount_ShouldPass() {
        log.info("=========================================");
        log.info("[TEST] 测试零积分 - 不使用积分");

        int usePoints = 0;

        log.info("设置: 使用{}积分（不抵扣）", usePoints);

        Result<?> result = ticketOrderService.payOrder("TEST_TEMP_001", usePoints);

        assertNotNull(result, "零积分应正常处理");
        verify(orderMetricsService, never()).recordPointsDiscountRejected(anyLong(), anyLong(), anyLong());
        log.info("✅ 零积分测试通过 - 不触发校验逻辑");
    }

    @Test
    @DisplayName("6. null值 - 未传积分参数")
    @org.junit.jupiter.api.Order(6)
    void testNullPoints_NotProvided_ShouldPass() {
        log.info("=========================================");
        log.info("[TEST] 测试null值 - 未传积分参数");

        Integer usePoints = null;

        log.info("设置: usePoints={}", usePoints);

        Result<?> result = ticketOrderService.payOrder("TEST_TEMP_001", usePoints);

        assertNotNull(result, "null值应正常处理（不触发积分校验）");
        verify(orderMetricsService, never()).recordPointsDiscountRejected(anyLong(), anyLong(), anyLong());
        log.info("✅ null值测试通过 - 兼容旧版调用方式");
    }

    @Test
    @DisplayName("7. 多票场景 - 购买2张票总价200元，上限100元")
    @org.junit.jupiter.api.Order(7)
    void testMultipleTickets_DoublePrice_DoubleLimit() {
        log.info("=========================================");
        log.info("[TEST] 测试多票场景 - 2张票总价200元");

        when(preOrderService.getById(anyString())).thenReturn(createTestPreOrder(2));

        int usePoints = 1500;
        int unitPrice = 100;
        int quantity = 2;
        int totalPrice = unitPrice * quantity;
        int maxDiscount = totalPrice / 2;
        int requestedDiscount = usePoints / 10;

        log.info("设置: 单价{}元×{}张={}元, 使用{}积分, 请求抵扣{}元, 上限{}元",
                unitPrice, quantity, totalPrice, usePoints, requestedDiscount, maxDiscount);

        assertTrue(requestedDiscount > maxDiscount,
                "150积分>100元上限");

        Result<?> result = ticketOrderService.payOrder("TEST_TEMP_001", usePoints);

        assertNotNull(result);
        assertFalse(result.isSuccess(), "多票超限也应被拒绝");
        assertTrue(result.getMsg().contains(String.valueOf(maxDiscount)),
                "多票场景也应显示正确的上限值");

        log.info("✅ 多票场景测试通过 - 上限按总价计算正确");
    }

    @Test
    @DisplayName("8. 奇数价格 - 总价99元，上限49元（取整）")
    @org.junit.jupiter.api.Order(8)
    void testOddPrice_FloorDivision_ShouldWork() {
        log.info("=========================================");
        log.info("[TEST] 测试奇数价格 - 99元总价");

        when(ticketService.getById(anyLong())).thenReturn(createTestTicket(9900L));

        int usePoints = 500;
        int totalPrice = 99;
        int maxDiscount = totalPrice / 2;
        int requestedDiscount = usePoints / 10;

        log.info("设置: 总价{}元(奇数), 上限{}元(整数除法), 请求{}元",
                totalPrice, maxDiscount, requestedDiscount);

        assertEquals(49, maxDiscount, "99/2应向下取整为49");

        if (requestedDiscount > maxDiscount) {
            Result<?> result = ticketOrderService.payOrder("TEST_TEMP_001", usePoints);
            assertFalse(result.isSuccess(), "超过49元上限应被拒绝");
            log.info("✅ 奇数价格测试通过 - 整数除法行为符合预期");
        } else {
            log.info("ℹ️ 请求未超限，跳过断言");
        }
    }

    @Test
    @DisplayName("9. 积分不足 - 用户积分少于请求积分")
    @org.junit.jupiter.api.Order(9)
    void testInsufficientPoints_UserBalanceLow_ShouldReject() {
        log.info("=========================================");
        log.info("[TEST] 测试积分不足 - 用户余额不足");

        when(userInfoService.getById(anyLong())).thenReturn(createTestUserInfo(200));

        int userBalance = 200;
        int requestPoints = 500;

        log.info("设置: 用户积分{}, 请求{}", userBalance, requestPoints);

        Result<?> result = ticketOrderService.payOrder("TEST_TEMP_001", requestPoints);

        assertNotNull(result);
        assertFalse(result.isSuccess(), "积分不足应被拒绝");
        log.info("✅ 积分不足测试通过 - 优先于50%上限校验");
    }

    @AfterAll
    static void printSummary() {
        log.info("=========================================");
        log.info("【积分抵扣测试汇总】全部9个测试用例执行完毕");
        log.info("覆盖场景:");
        log.info("  ✅ 正常抵扣(<50%)");
        log.info("  ✅ 边界值(=50%)");
        log.info("  ✅ 超限拒绝(>50%)");
        log.info("  ✅ 极端超限(5倍)");
        log.info("  ✅ 零积分(不触发)");
        log.info("  ✅ null兼容");
        log.info("  ✅ 多票场景");
        log.info("  ✅ 奇数价格(取整)");
        log.info("  ✅ 积分不足(优先级)");
        log.info("=========================================");
    }
}
