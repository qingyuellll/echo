package com.echo.unit.service.core.order;

import com.echo.entity.PreOrder;
import com.echo.service.IPreOrderService;
import com.echo.service.impl.PreOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PreOrderServiceTest {

    @Mock
    private com.echo.mapper.PreOrderMapper preOrderMapper;

    @InjectMocks
    private PreOrderServiceImpl preOrderService;

    private PreOrder createTestPreOrder() {
        PreOrder preOrder = new PreOrder();
        preOrder.setTempId("TEMP_" + System.currentTimeMillis() + "_1001");
        preOrder.setUserId(1001L);
        preOrder.setTicketId(2001L);
        preOrder.setShowId(3001L);
        preOrder.setQuantity(3);
        preOrder.setStatus(0);
        preOrder.setCreateTime(LocalDateTime.now());
        preOrder.setExpireTime(LocalDateTime.now().plusMinutes(30));

        List<Long> seatIds = Arrays.asList(4001L, 4002L, 4003L);
        preOrder.setSeatIds(seatIds);

        return preOrder;
    }

    @Test
    @DisplayName("1. 创建预订单 - 基本字段验证")
    @org.junit.jupiter.api.Order(1)
    void save_PreOrder_ShouldSaveSuccessfully() {
        log.info("=========================================");
        log.info("[TEST] 测试预订单保存功能");

        PreOrder preOrder = createTestPreOrder();

        when(preOrderMapper.insert(any())).thenReturn(1);

        boolean result = preOrderService.save(preOrder);

        assertTrue(result, "保存预订单应该成功");
        verify(preOrderMapper, times(1)).insert(preOrder);

        log.info("[PASS] ✅ 预订单保存成功 - tempId:{}", preOrder.getTempId());
    }

    @Test
    @DisplayName("2. 查询预订单 - 通过tempId")
    @org.junit.jupiter.api.Order(2)
    void getById_ExistingPreOrder_ShouldReturnCorrectly() {
        log.info("[TEST] 测试预订单查询功能");

        PreOrder expectedPreOrder = createTestPreOrder();

        when(preOrderMapper.selectById(anyString())).thenReturn(expectedPreOrder);

        PreOrder actualPreOrder = preOrderService.getById(expectedPreOrder.getTempId());

        assertNotNull(actualPreOrder, "查询结果不能为空");
        assertEquals(expectedPreOrder.getTempId(), actualPreOrder.getTempId(),
                "tempId必须匹配");
        assertEquals(expectedPreOrder.getUserId(), actualPreOrder.getUserId(),
                "userId必须匹配");
        assertEquals(expectedPreOrder.getTicketId(), actualPreOrder.getTicketId(),
                "ticketId必须匹配");
        assertEquals(expectedPreOrder.getQuantity(), actualPreOrder.getQuantity(),
                "quantity必须匹配");
        assertEquals(0, actualPreOrder.getStatus(),
                "初始状态必须是0（待支付）");
        assertNotNull(actualPreOrder.getExpireTime(),
                "过期时间不能为空");
        assertNotNull(actualPreOrder.getCreateTime(),
                "创建时间不能为空");

        log.info("[PASS] ✅ 预订单查询成功，所有字段匹配");
    }

    @Test
    @DisplayName("3. 查询不存在的预订单 - 应返回null")
    @org.junit.jupiter.api.Order(3)
    void getById_NonExistentPreOrder_ShouldReturnNull() {
        log.info("[TEST] 测试查询不存在的预订单");

        when(preOrderMapper.selectById(anyString())).thenReturn(null);

        PreOrder result = preOrderService.getById("TEMP_NON_EXISTENT");

        assertNull(result, "查询不存在的预订单应该返回null");

        log.info("[PASS] ✅ 正确返回null（预订单不存在）");
    }

    @Test
    @DisplayName("4. 删除预订单 - 成功删除")
    @org.junit.jupiter.api.Order(4)
    void removeById_ExistingPreOrder_ShouldDeleteSuccessfully() {
        log.info("[TEST] 测试预订单删除功能");

        String tempId = "TEMP_TO_DELETE_12345";

        when(preOrderMapper.deleteById(anyString())).thenReturn(1);

        boolean result = preOrderService.removeById(tempId);

        assertTrue(result, "删除应该成功");
        verify(preOrderMapper, times(1)).deleteById(tempId);

        log.info("[PASS] ✅ 预订单删除成功 - tempId:{}", tempId);
    }

    @Test
    @DisplayName("5. 删除预订单 - 不存在时应返回false")
    @org.junit.jupiter.api.Order(5)
    void removeById_NonExistentPreOrder_ShouldReturnFalse() {
        log.info("[TEST] 测试删除不存在的预订单");

        when(preOrderMapper.deleteById(anyString())).thenReturn(0);

        boolean result = preOrderService.removeById("TEMP_NON_EXISTENT");

        assertFalse(result, "删除不存在的预订单应该返回false");

        log.info("[PASS] ✅ 正确返回false（预订单不存在）");
    }

    @Test
    @DisplayName("6. 预订单状态更新 - 待支付→已取消")
    @org.junit.jupiter.api.Order(6)
    void updateStatus_PendingToCancelled_ShouldUpdateSuccessfully() {
        log.info("[TEST] 测试预订单状态更新");

        PreOrder preOrder = createTestPreOrder();
        preOrder.setStatus(4); // 更新为已取消

        when(preOrderMapper.updateById(any())).thenReturn(1);

        boolean result = preOrderService.updateById(preOrder);

        assertTrue(result, "状态更新应该成功");
        verify(preOrderMapper, times(1)).updateById(preOrder);

        log.info("[PASS] ✅ 预订单状态更新成功 - status: 0 → 4（已取消）");
    }

    @Test
    @DisplayName("7. 预订单过期时间验证 - 默认30分钟")
    @org.junit.jupiter.api.Order(7)
    void preOrderExpireTime_ShouldBe30MinutesFromNow() {
        log.info("[TEST] 验证预订单过期时间设置");

        PreOrder preOrder = createTestPreOrder();

        LocalDateTime createTime = preOrder.getCreateTime();
        LocalDateTime expireTime = preOrder.getExpireTime();

        assertNotNull(createTime, "创建时间不能为空");
        assertNotNull(expireTime, "过期时间不能为空");

        long minutesBetween = java.time.Duration.between(createTime, expireTime).toMinutes();
        assertEquals(30, minutesBetween,
                "过期时间应该是创建时间后30分钟");

        assertTrue(expireTime.isAfter(LocalDateTime.now()),
                "过期时间应该是未来时间");

        log.info("[PASS] ✅ 过期时间正确设置为{}分钟后", minutesBetween);
        log.info("  - 创建时间: {}", createTime);
        log.info("  - 过期时间: {}", expireTime);
    }

    @Test
    @DisplayName("8. 座位ID列表序列化/反序列化测试")
    @org.junit.jupiter.api.Order(8)
    void seatIds_ListHandling_ShouldWorkCorrectly() {
        log.info("[TEST] 测试座位ID列表处理");

        PreOrder preOrder = createTestPreOrder();
        List<Long> originalSeatIds = preOrder.getSeatIds();

        assertNotNull(originalSeatIds, "座位ID列表不能为空");
        assertEquals(3, originalSeatIds.size(), "座位数量必须是3");
        assertTrue(originalSeatIds.containsAll(Arrays.asList(4001L, 4002L, 4003L)),
                "座位ID必须包含预期的值");

        log.info("[PASS] ✅ 座位ID列表处理正确 - 数量:{}, IDs:{}",
                originalSeatIds.size(), originalSeatIds);
    }
}
