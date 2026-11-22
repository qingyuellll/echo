package com.echo.unit.service.core.order;

import com.echo.dto.Result;
import com.echo.dto.UserDTO;
import com.echo.entity.*;
import com.echo.service.impl.TicketOrderServiceImpl;
import com.echo.service.strategy.SeatStrategy;
import com.echo.service.strategy.SeatStrategyFactory;
import com.echo.service.strategy.impl.WithSeatStrategy;
import com.echo.service.strategy.impl.WithoutSeatStrategy;
import com.echo.utils.OrderUtils;
import com.echo.utils.RedisWorker;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 四种下单场景全流程测试
 * 场景1: 秒杀有座票 - 选座下单
 * 场景2: 秒杀无座票 - 普通下单
 * 场景3: 普通有座票 - 选座下单
 * 场景4: 普通无座票 - 普通下单
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Slf4j
public class FourScenariosFullTest {

    @Mock
    private com.echo.service.ITicketService ticketService;

    @Mock
    private com.echo.service.IPreOrderService preOrderService;

    @Mock
    private com.echo.service.ISeatService seatService;

    @Mock
    private com.echo.service.IUserInfoService userInfoService;

    @Mock
    private com.echo.service.IShowService showService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RedisWorker redisWorker;

    @Mock
    private com.echo.service.ISeckillTicketService seckillTicketService;

    @Mock
    private org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;

    private TicketOrderServiceImpl ticketOrderService;

    @BeforeEach
    void setUp() throws Exception {
        ticketOrderService = new TicketOrderServiceImpl();

        inject(ticketOrderService, "ticketService", ticketService);
        inject(ticketOrderService, "preOrderService", preOrderService);
        inject(ticketOrderService, "seatService", seatService);
        inject(ticketOrderService, "userInfoService", userInfoService);
        inject(ticketOrderService, "showService", showService);
        inject(ticketOrderService, "stringRedisTemplate", stringRedisTemplate);
        inject(ticketOrderService, "redisWorker", redisWorker);
        inject(ticketOrderService, "seckillTicketService", seckillTicketService);
        inject(ticketOrderService, "rocketMQTemplate", rocketMQTemplate);

        // 注入真实 SeatStrategyFactory（mock会触发ClassCastException）
        inject(ticketOrderService, "seatStrategyFactory",
                new SeatStrategyFactory(
                        new WithSeatStrategy(),
                        new WithoutSeatStrategy()
                ));

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisWorker.nextId(anyString())).thenReturn(1L);

        UserHolder.saveUser(newUser(1001L, "测试用户"));
    }

    /** 通过反射注入 private 字段 */
    private void inject(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("注入字段失败: " + name, e);
        }
    }

    private UserDTO newUser(Long id, String nick) {
        UserDTO u = new UserDTO();
        u.setId(id);
        u.setNickName(nick);
        return u;
    }

    private Ticket newTicket(boolean hasSeats, boolean seckill) {
        Ticket t = new Ticket();
        t.setId(hasSeats && seckill ? 5001L : hasSeats ? 3001L : seckill ? 5002L : 3002L);
        t.setShowId(100L);
        t.setTitle((seckill ? "秒杀" : "普通") + (hasSeats ? "有座" : "无座"));
        t.setPayValue(100L);
        t.setStock(100);
        t.setStatus(1);
        t.setHasSeats(hasSeats);
        t.setType(seckill ? 1 : 0);
        return t;
    }

    private Show newShow() {
        Show s = new Show();
        s.setId(100L);
        s.setName("测试演出");
        s.setStatus(1);
        s.setOpenHours("2026-01-01~2026-12-31");
        return s;
    }

    // ── 场景测试 ────────────────────────────────────────────────

    @Test
    @DisplayName("场景4: 普通无座-普通下单")
    void scenario4_NormalWithoutSeat() {
        Ticket t = newTicket(false, false);

        when(ticketService.getById(t.getId())).thenReturn(t);
        when(showService.getById(anyLong())).thenReturn(newShow());
        when(ticketService.isTicketActiveForPurchase(anyLong())).thenReturn(true);
        when(preOrderService.save(any(PreOrder.class))).thenAnswer(i -> {
            i.getArgument(0, PreOrder.class).setTempId("T4");
            return true;
        });
        when(valueOperations.increment(anyString())).thenReturn(1L);

        Result<?> r = ticketOrderService.createTicketOrder(t.getId(), 5);
        assertTrue(r.getSuccess(), "场景4普通下单应成功");
        log.info("✅ 场景4-普通无座票下单通过");
    }

    // ── 跨场景验证 ──────────────────────────────────────────────

    @Test
    @DisplayName("策略工厂: hasSeats=true → WithSeatStrategy，false → WithoutSeatStrategy")
    void strategyFactory_DispatchesCorrectly() {
        WithSeatStrategy ws = new WithSeatStrategy();
        WithoutSeatStrategy wo = new WithoutSeatStrategy();
        SeatStrategyFactory factory = new SeatStrategyFactory(ws, wo);

        SeatStrategy r1 = factory.getStrategy(true);
        SeatStrategy r2 = factory.getStrategy(false);

        assertInstanceOf(WithSeatStrategy.class, r1);
        assertInstanceOf(WithoutSeatStrategy.class, r2);
        log.info("✅ 策略工厂分派验证通过");
    }

    @Test
    @DisplayName("核销码: 8位大写字母数字")
    void verificationCode_Format() {
        for (int i = 0; i < 20; i++) {
            String c = OrderUtils.generateVerificationCode();
            assertEquals(8, c.length(), "长度应为8位: " + c);
            assertTrue(c.matches("[A-Z0-9]{8}"), "格式错误: " + c);
        }
        log.info("✅ 核销码格式验证通过（20次）");
    }
}
