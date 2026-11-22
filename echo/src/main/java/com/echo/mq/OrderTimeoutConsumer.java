package com.echo.mq;

import com.echo.entity.PreOrder;
import com.echo.entity.Ticket;
import com.echo.enums.PreOrderStatus;
import com.echo.monitor.OrderMetricsService;
import com.echo.service.IPreOrderService;
import com.echo.service.ISeatService;
import com.echo.service.ITicketService;
import com.echo.service.ITicketOrderService;
import com.echo.utils.MqConstants;
import com.echo.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 订单超时自动取消消费者(RocketMQ Clustering模式)
 * <p>
 * 消费延时消息，自动取消超时未支付订单并回滚库存和释放座位
 *
 * @author tulip
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rocketmq.enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = MqConstants.ORDER_TIMEOUT_TOPIC,
        consumerGroup = MqConstants.ORDER_TIMEOUT_CONSUMER_GROUP
)
public class OrderTimeoutConsumer implements RocketMQListener<Map<String, Object>>,
        RocketMQPushConsumerLifecycleListener {

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        // 消费失败最多重试3次（RocketMQ默认16次），耗尽后进入死信队列 %DLQ%+组名，由DlqAlertMonitor告警
        consumer.setMaxReconsumeTimes(3);
    }

    /** 预订单服务(查询/更新/删除预订单) */
    @Resource
    private IPreOrderService preOrderService;

    /** 座位服务(查询/释放座位) */
    @Resource
    private ISeatService seatService;

    /** 票种服务(查询票种信息判断是否秒杀票) */
    @Resource
    private ITicketService ticketService;

    /** 订单服务(普通票库存回滚) */
    @Resource
    private ITicketOrderService ticketOrderService;

    /** Redis字符串操作模板(秒杀库存回滚) */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 秒杀库存回滚Lua脚本(原子操作) */
    @Resource
    private DefaultRedisScript<Long> seckillRollbackScript;

    /** 订单指标监控服务(可选，用于记录超时处理指标) */
    @Resource
    private OrderMetricsService orderMetricsService;

    /**
     * 处理超时订单消息
     * <p>
     * 核心流程：三重防护校验 → 乐观锁取消 → 分策略回滚库存 → 释放座位 → 清理缓存
     *
     * @param message 超时订单消息(Map格式，包含tempId/orderId/userId/ticketId)
     */
    @Override
    public void onMessage(Map<String, Object> message) {
        long startTime = System.currentTimeMillis();

        String tempId = (String) message.get("tempId");
        String orderId = (String) message.get("orderId");
        Long userId = message.get("userId") != null ? Long.valueOf(message.get("userId").toString()) : null;
        Long ticketId = message.get("ticketId") != null ? Long.valueOf(message.get("ticketId").toString()) : null;

        log.info("[超时订单] 收到延时消息 - tempId:{}, userId:{}, ticketId:{}", tempId, userId, ticketId);

        if (tempId == null && orderId == null) {
            log.error("[超时订单] 消息缺少tempId和orderId字段，忽略");
            recordFailMetrics("unknown", "缺少tempId/orderId", startTime);
            return;
        }

        String preOrderId = (tempId != null) ? tempId : orderId;

        try {
            PreOrder preOrder = queryAndValidatePreOrder(preOrderId);
            if (preOrder == null) {
                recordSuccessMetrics(startTime, preOrderId, "订单不存在或已处理");
                return;
            }

            boolean claimed = cancelOrderByOptimisticLock(preOrderId);
            if (!claimed) {
                recordSuccessMetrics(startTime, preOrderId, "幂等拦截");
                return;
            }

            Integer quantity = resolveQuantity(preOrder);

            rollbackResources(ticketId, userId, preOrderId, quantity, preOrder);

            cleanupAfterCancellation(preOrderId);

            log.info("[超时订单] 处理完成 - preOrderId:{}, ticketId:{}", preOrderId, ticketId);
            recordSuccessMetrics(startTime, preOrderId, "成功");

        } catch (Exception e) {
            log.error("[超时订单] 处理异常 - preOrderId:{}, error:{}", preOrderId, e.getMessage(), e);
            recordFailMetrics(preOrderId, e.getMessage(), startTime);
        }
    }

    /**
     * 查询预订单并验证状态(三重防护的前两道)
     * <p>
     * 第一道：查询预订单是否存在
     * 第二道：验证状态是否为待支付
     *
     * @param preOrderId 预订单ID
     * @return 预订单实体(如果验证通过), null(如果验证失败)
     */
    private PreOrder queryAndValidatePreOrder(String preOrderId) {
        PreOrder preOrder = preOrderService.getById(preOrderId);

        if (preOrder == null) {
            log.info("[超时订单] 预订单不存在（已支付或已取消），跳过 - preOrderId:{}", preOrderId);
            return null;
        }

        if (PreOrderStatus.PENDING_PAYMENT.getCode() != preOrder.getStatus()) {
            log.info("[超时订单] 预订单状态非待支付（status={}），跳过 - preOrderId:{}",
                    preOrder.getStatus(), preOrderId);
            return null;
        }

        return preOrder;
    }

    /**
     * 使用乐观锁取消订单(三重防护的第三道)
     * <p>
     * 通过CAS条件更新保证并发安全：只有当status仍为PENDING_PAYMENT时才能更新为CANCELLED
     *
     * @param preOrderId 预订单ID
     * @return true=取消成功, false=已被其他线程处理(幂等拦截)
     */
    private boolean cancelOrderByOptimisticLock(String preOrderId) {
        boolean claimed = preOrderService.update()
                .set("status", PreOrderStatus.CANCELLED.getCode())
                .eq("temp_id", preOrderId)
                .eq("status", PreOrderStatus.PENDING_PAYMENT.getCode())
                .update();

        if (!claimed) {
            log.warn("[超时订单] 幂等拦截：预订单状态已被其他线程更新，跳过 - preOrderId:{}", preOrderId);
        }

        return claimed;
    }

    /**
     * 解析购买数量(默认为1)
     *
     * @param preOrder 预订单实体
     * @return 购买数量
     */
    private Integer resolveQuantity(PreOrder preOrder) {
        return preOrder.getQuantity() != null ? preOrder.getQuantity() : 1;
    }

    /**
     * 回滚资源(库存+座位)
     *
     * @param ticketId 票种ID
     * @param userId 用户ID
     * @param preOrderId 预订单ID
     * @param quantity 购买数量
     * @param preOrder 预订单实体
     */
    private void rollbackResources(Long ticketId, Long userId, String preOrderId,
                                   Integer quantity, PreOrder preOrder) {
        rollbackStockByTicketType(ticketId, userId, preOrderId, quantity);
        releaseSeats(preOrderId, preOrder);
    }

    /**
     * 根据票种类型回滚库存(秒杀票用Redis Lua，普通票用MySQL)
     *
     * @param ticketId 票种ID
     * @param userId 用户ID
     * @param preOrderId 预订单ID
     * @param quantity 购买数量
     */
    private void rollbackStockByTicketType(Long ticketId, Long userId, String preOrderId, Integer quantity) {
        if (ticketId == null) {
            log.warn("[超时订单] 无票种信息，跳过库存回滚 - preOrderId:{}", preOrderId);
            return;
        }

        Ticket ticket = ticketService.getById(ticketId);
        if (ticket == null) {
            log.warn("[超时订单] 票种不存在，跳过库存回滚 - ticketId:{}", ticketId);
            return;
        }

        boolean isSeckill = ticket.getType() != null && ticket.getType() == 1;

        if (isSeckill) {
            rollbackSeckillStock(ticketId, userId, preOrderId, quantity);
        } else {
            rollbackNormalStock(ticketId, quantity);
        }
    }

    /**
     * 回滚普通票MySQL库存
     *
     * @param ticketId 票种ID
     * @param quantity 购买数量
     */
    private void rollbackNormalStock(Long ticketId, Integer quantity) {
        try {
            boolean restored = ticketOrderService.restoreStockForExpiredOrder(ticketId, quantity);
            if (restored) {
                log.info("[超时订单] 普通票MySQL库存已恢复 - ticketId:{}, quantity:{}", ticketId, quantity);
            } else {
                log.error("[超时订单] 普通票MySQL库存恢复失败 - ticketId:{}, quantity:{}", ticketId, quantity);
            }
        } catch (Exception e) {
            log.error("[超时订单] 普通票库存回滚异常 - ticketId:{}, error:{}", ticketId, e.getMessage(), e);
        }
    }

    /**
     * 回滚秒杀票Redis库存(使用Lua脚本保证原子性)
     * <p>
     * Lua脚本执行逻辑：
     * 1. 库存量+quantity（INCRBY）
     * 2. 从已购用户集合移除当前用户（SREM）
     * 3. 删除待支付标记（DEL）
     *
     * @param ticketId 票种ID
     * @param userId 用户ID
     * @param preOrderId 预订单ID
     * @param quantity 购买数量
     */
    private void rollbackSeckillStock(Long ticketId, Long userId, String preOrderId, Integer quantity) {
        try {
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + ticketId;
            Boolean hasStockKey = stringRedisTemplate.hasKey(stockKey);

            if (Boolean.TRUE.equals(hasStockKey)) {
                String orderKey = RedisConstants.SECKILL_ORDER_KEY + ticketId;
                String pendingSeckillOrderKey = RedisConstants.PENDING_SECKILL_ORDER + preOrderId;

                List<String> keys = Arrays.asList(stockKey, orderKey, pendingSeckillOrderKey);
                Long result = stringRedisTemplate.execute(
                        seckillRollbackScript,
                        keys,
                        String.valueOf(userId != null ? userId : 0),
                        String.valueOf(quantity != null ? quantity : 1)
                );

                if (result != null && result == 0) {
                    log.info("[超时订单] 秒杀库存回滚成功 - ticketId:{}, userId:{}, quantity:{}, preOrderId:{}",
                            ticketId, userId, quantity, preOrderId);
                } else {
                    log.warn("[超时订单] 秒杀库存回滚返回异常值 - ticketId:{}, result:{}", ticketId, result);
                }
            }
        } catch (Exception e) {
            log.error("[超时订单] 秒杀库存回滚异常 - ticketId:{}, error:{}", ticketId, e.getMessage(), e);
        }
    }

    /**
     * 加载并释放占用的座位(优化为批量操作提升性能)
     *
     * @param preOrderId 预订单ID
     * @param preOrder 预订单实体
     */
    private void releaseSeats(String preOrderId, PreOrder preOrder) {
        List<Long> seatIds = loadSeatIdsFromDatabase(preOrder);

        if (seatIds == null || seatIds.isEmpty()) {
            log.info("[超时订单] 无占座预订单，跳过座位释放 - preOrderId:{}", preOrderId);
            return;
        }

        boolean success = batchReleaseSeats(seatIds, preOrderId);
        log.info("[超时订单] 座位释放完成 - 数量:{}, 结果:{}, preOrderId:{}",
                seatIds.size(), success ? "批量成功" : "降级逐个", preOrderId);
    }

    /**
     * 从数据库加载该预订单占用的座位ID列表
     *
     * @param preOrder 预订单实体
     * @return 座位ID列表(可能为空列表)
     */
    private List<Long> loadSeatIdsFromDatabase(PreOrder preOrder) {
        if (preOrder == null || preOrder.getTempId() == null) {
            return List.of();
        }

        try {
            List<com.echo.entity.Seat> occupiedSeats = seatService.list(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.Seat>()
                            .eq("pre_order_id", preOrder.getTempId())
                            .eq("status", 1)
            );

            if (occupiedSeats != null && !occupiedSeats.isEmpty()) {
                List<Long> seatIds = occupiedSeats.stream()
                        .map(com.echo.entity.Seat::getId)
                        .sorted()
                        .collect(java.util.stream.Collectors.toList());

                preOrder.setSeatIds(seatIds);
                return seatIds;
            }
        } catch (Exception e) {
            log.error("[数据库查询] 加载座位列表异常 - tempId:{}, error:{}",
                    preOrder.getTempId(), e.getMessage(), e);
        }

        return List.of();
    }

    /**
     * 批量释放座位(使用IN子句一次更新多条记录，性能优化)
     * <p>
     * 性能对比：
     * - 原方案：逐个更新，N个座位需要N次SQL
     * - 新方案：批量更新，N个座位只需1次SQL
     *
     * @param seatIds 座位ID列表
     * @param preOrderId 预订单ID
     * @return true=批量释放成功, false=批量失败(已降级为逐个释放)
     */
    private boolean batchReleaseSeats(List<Long> seatIds, String preOrderId) {
        try {
            boolean success = seatService.update()
                    .set("status", 0)
                    .set("pre_order_id", null)
                    .in("id", seatIds)
                    .eq("pre_order_id", preOrderId)
                    .update();

            log.debug("[超时订单] 批量释放座位 - 数量:{}, 结果:{}", seatIds.size(), success ? "成功" : "失败");
            return success;
        } catch (Exception e) {
            log.error("[超时订单] 批量释放座位异常，降级为逐个释放 - preOrderId:{}, error:{}", preOrderId, e.getMessage(), e);
            fallbackReleaseSeatsOneByOne(seatIds, preOrderId);
            return false;
        }
    }

    /**
     * 逐个释放座位(批量失败时的降级方案)
     * <p>
     * 容错机制：即使部分座位释放失败，也不影响整体流程
     *
     * @param seatIds 座位ID列表
     * @param preOrderId 预订单ID
     * @return 成功释放的数量
     */
    private int fallbackReleaseSeatsOneByOne(List<Long> seatIds, String preOrderId) {
        int releasedCount = 0;
        for (Long seatId : seatIds) {
            try {
                boolean updated = seatService.update()
                        .set("status", 0)
                        .set("pre_order_id", null)
                        .eq("id", seatId)
                        .eq("pre_order_id", preOrderId)
                        .update();

                if (updated) {
                    releasedCount++;
                    log.debug("[超时订单] 座位已释放(降级模式) - seatId:{}, preOrderId:{}", seatId, preOrderId);
                } else {
                    log.warn("[超时订单] 座位释放失败(降级模式) - seatId:{}, preOrderId:{}", seatId, preOrderId);
                }
            } catch (Exception ex) {
                log.error("[超时订单] 座位释放异常(降级模式) - seatId:{}, error:{}", seatId, ex.getMessage());
            }
        }
        return releasedCount;
    }

    /**
     * 取消后清理工作(删除预订单+清理Redis缓存)
     *
     * @param preOrderId 预订单ID
     */
    private void cleanupAfterCancellation(String preOrderId) {
        deletePreOrder(preOrderId);
        clearRedisCache(preOrderId);
    }

    /**
     * 删除预订单记录
     *
     * @param preOrderId 预订单ID
     */
    private void deletePreOrder(String preOrderId) {
        try {
            preOrderService.removeById(preOrderId);
        } catch (Exception e) {
            log.error("[超时订单] 删除预订单失败 - preOrderId:{}, error:{}", preOrderId, e.getMessage());
        }
    }

    /**
     * 清理Redis缓存(非阻塞设计，失败不影响主流程)
     *
     * @param preOrderId 预订单ID
     */
    private void clearRedisCache(String preOrderId) {
        try {
            String cacheKey = RedisConstants.PENDING_PRE_ORDER + preOrderId;
            stringRedisTemplate.delete(cacheKey);
        } catch (Exception cacheEx) {
            log.warn("[超时订单] Redis缓存清理失败（不影响主流程） - error:{}", cacheEx.getMessage());
        }
    }

    /**
     * 记录成功处理的监控指标
     *
     * @param startTime 开始时间戳
     * @param preOrderId 预订单ID
     * @param result 结果描述
     */
    private void recordSuccessMetrics(long startTime, String preOrderId, String result) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            orderMetricsService.recordOrderTimeoutSuccess(preOrderId, result, duration);
        } catch (Exception ignored) {
        }
    }

    /**
     * 记录失败处理的监控指标
     *
     * @param preOrderId 预订单ID
     * @param errorMessage 错误信息
     * @param startTime 开始时间戳
     */
    private void recordFailMetrics(String preOrderId, String errorMessage, long startTime) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            orderMetricsService.recordOrderTimeoutFail(preOrderId, errorMessage, duration);
        } catch (Exception ignored) {
        }
    }
}
