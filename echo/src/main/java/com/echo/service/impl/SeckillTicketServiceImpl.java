package com.echo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.entity.SeckillTicket;
import com.echo.mapper.SeckillTicketMapper;
import com.echo.service.ISeckillTicketService;
import com.echo.utils.RedisConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀票服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class SeckillTicketServiceImpl extends ServiceImpl<SeckillTicketMapper, SeckillTicket> implements ISeckillTicketService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 预热单个秒杀票到Redis（动态TTL策略）
     *
     * 核心改进：
     *  动态TTL：根据活动结束时间设置库存过期时间（而非固定24h）
     *  最小TTL保障：即使活动信息异常也保证至少48小时有效
     *  库存一致性：SETNX原子写入——并发预热在同一瞬间只有一个请求成功建key，
     *             消除"hasKey判断与set写入两步之间"的覆盖窗口；key已存在时只续期TTL，
     *             活动期间Redis计数器是唯一真相（Lua原子扣减/退款回补），
     *             DB库存要等结算后才变动，用DB值覆盖Redis会造成超卖；
     *             管理端强制重置请走 syncSeckillStockToRedis
     *  订单集合保护：保留已有购买记录防止重复购买
     *
     * @param ticketId 票种ID
     */
    public void preloadSeckillStock(Long ticketId) {
        try {
            SeckillTicket seckillTicket = this.getOne(
                new LambdaQueryWrapper<SeckillTicket>()
                    .eq(SeckillTicket::getTicketId, ticketId)
            );

            if (seckillTicket == null) {
                log.warn("[秒杀预热] 秒杀票不存在，ticketId:{}", ticketId);
                return;
            }

            if (seckillTicket.getStock() == null || seckillTicket.getStock() <= 0) {
                log.warn("[秒杀预热] 秒杀票库存为0或未设置，ticketId:{}", ticketId);
                return;
            }

            String stockKey = RedisConstants.SECKILL_STOCK_KEY + ticketId;
            String orderKey = RedisConstants.SECKILL_ORDER_KEY + ticketId;

            long dynamicTtlHours = calculateDynamicTtl(seckillTicket);

            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                stockKey,
                seckillTicket.getStock().toString(),
                dynamicTtlHours,
                TimeUnit.HOURS
            );

            if (Boolean.TRUE.equals(acquired)) {
                // SETNX原子写入：并发预热在同一瞬间只有一个请求成功建key，
                // 消除"hasKey判断与set写入两步之间"的窗口，
                // 该窗口会让迟到的预热请求把已扣减的库存计数刷回DB旧值造成超卖
                log.info("[秒杀预热成功] SETNX原子写入 - ticketId:{}, 库存:{}, TTL:{}小时, key:{}",
                    ticketId, seckillTicket.getStock(), dynamicTtlHours, stockKey);
            } else {
                // key已存在：活动进行中，Redis计数器是唯一真相，只续期TTL，绝不覆盖。
                // DB库存滞后于Redis（结算后才变动），用DB值覆盖会把已扣减的库存刷回去造成超卖
                String currentStock = stringRedisTemplate.opsForValue().get(stockKey);
                if (currentStock == null || !currentStock.equals(seckillTicket.getStock().toString())) {
                    log.warn("[秒杀库存不一致（不覆盖）] ticketId:{}, Redis库存:{}, DB库存:{}。" +
                            "若确认需以DB为准，请走管理端 syncSeckillStockToRedis",
                            ticketId, currentStock, seckillTicket.getStock());
                }
                // 库存值保持不动，续期TTL（防止长活动期间过期）
                stringRedisTemplate.expire(stockKey, dynamicTtlHours, TimeUnit.HOURS);
                log.debug("[秒杀库存续期] ticketId:{}, 续期至{}小时", ticketId, dynamicTtlHours);
            }

            Boolean orderKeyExists = stringRedisTemplate.hasKey(orderKey);
            if (!Boolean.TRUE.equals(orderKeyExists)) {
                stringRedisTemplate.opsForSet().add(orderKey, "__INITIALIZED__");
                stringRedisTemplate.expire(orderKey, dynamicTtlHours, TimeUnit.HOURS);
                log.info("[秒杀订单集合初始化] 创建空的购买记录集合，key:{}, TTL:{}小时", orderKey, dynamicTtlHours);
            } else {
                long currentOrderSize = stringRedisTemplate.opsForSet().size(orderKey);
                stringRedisTemplate.expire(orderKey, dynamicTtlHours, TimeUnit.HOURS);
                log.info("[秒杀订单集合已存在] 保留已有购买记录并续期，key:{}, 当前购买人数:{}, TTL:{}小时",
                        orderKey, currentOrderSize, dynamicTtlHours);
            }

        } catch (Exception e) {
            log.error("[秒杀预热失败] ticketId:{}, error:{}", ticketId, e.getMessage(), e);
        }
    }

    /**
     * 动态计算库存键TTL（基于活动结束时间）
     *
     * 策略：
     * 1. 优先使用 endTime - now（活动剩余时间 + 缓冲区）
     * 2. 如果 endTime 为空或已过期，使用最小安全TTL（48小时）
     * 3. 确保TTL >= 48小时，避免频繁过期导致的热点问题
     *
     * @param seckillTicket 秒杀票实体
     * @return TTL（小时）
     */
    private long calculateDynamicTtl(SeckillTicket seckillTicket) {
        final long MIN_TTL_HOURS = 48L;  // 最小TTL保障
        final long BUFFER_HOURS = 6L;     // 活动结束后缓冲时间

        try {
            java.time.LocalDateTime endTime = seckillTicket.getEndTime();

            if (endTime != null) {
                long remainingMillis = java.time.Duration.between(java.time.LocalDateTime.now(), endTime).toMillis();
                long remainingHours = TimeUnit.MILLISECONDS.toHours(remainingMillis) + BUFFER_HOURS;

                if (remainingHours > MIN_TTL_HOURS) {
                    log.debug("[动态TTL计算] 基于活动结束时间，ticketId:{}, 剩余:{}小时, 最终TTL:{}小时",
                            seckillTicket.getTicketId(), remainingHours - BUFFER_HOURS, remainingHours);
                    return remainingHours;
                } else if (remainingHours > 0) {
                    log.info("[动态TTL计算] 活动即将结束，使用最小TTL保障，ticketId:{}, 剩余:{}小时, 最终TTL:{}小时",
                            seckillTicket.getTicketId(), remainingHours - BUFFER_HOURS, MIN_TTL_HOURS);
                    return MIN_TTL_HOURS;
                }
            }

            log.info("[动态TTL计算] 无法获取结束时间或活动已结束，使用默认TTL，ticketId:{}, TTL:{}小时",
                    seckillTicket.getTicketId(), MIN_TTL_HOURS);
            return MIN_TTL_HOURS;

        } catch (Exception e) {
            log.warn("[动态TTL计算] 异常，使用默认TTL，ticketId:{}, error:{}", seckillTicket.getTicketId(), e.getMessage());
            return MIN_TTL_HOURS;
        }
    }

    /**
     * 预热所有秒杀票到Redis（系统启动时调用）
     *
     * 改进：使用动态TTL而非固定24h
     */
    public void preloadAllSeckillTickets() {
        try {
            List<SeckillTicket> allTickets = this.list(new LambdaQueryWrapper<>());

            if (allTickets == null || allTickets.isEmpty()) {
                log.info("[批量预热] 没有找到秒杀票");
                return;
            }

            int successCount = 0;
            for (SeckillTicket ticket : allTickets) {
                if (ticket.getStock() != null && ticket.getStock() > 0) {
                    long dynamicTtlHours = calculateDynamicTtl(ticket);
                    String stockKey = RedisConstants.SECKILL_STOCK_KEY + ticket.getTicketId();
                    stringRedisTemplate.opsForValue().set(
                        stockKey,
                        ticket.getStock().toString(),
                        dynamicTtlHours,
                        TimeUnit.HOURS
                    );
                    successCount++;
                }
            }

            log.info("[批量预热完成] 总计:{}个秒杀票，成功预热:{}个（均使用动态TTL）", allTickets.size(), successCount);

        } catch (Exception e) {
            log.error("[批量预热失败] error:{}", e.getMessage(), e);
        }
    }

    /**
     * 刷新指定秒杀票的Redis库存（管理员修改库存后调用）
     *
     * 核心策略：DB + Redis 双写保证一致性
     *
     * 执行流程：
     *   Step 1: 更新数据库库存（确保DB是权威数据源）
     *   Step 2: 同步Redis缓存（使用较长TTL避免频繁预热）
     *   Step 3: 记录操作日志（便于审计和问题排查）
     *
     * @param ticketId 票种ID
     * @param newStock 新库存数量
     */
    public void refreshSeckillStock(Long ticketId, Integer newStock) {
        try {
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + ticketId;

            if (newStock == null || newStock < 0) {
                log.warn("[刷新库存] 无效库存值，ticketId:{}, newStock:{}", ticketId, newStock);
                return;
            }

            // Step 1: 更新数据库（双写策略：DB优先）
            SeckillTicket seckillTicket = this.getOne(
                new LambdaQueryWrapper<SeckillTicket>()
                    .eq(SeckillTicket::getTicketId, ticketId)
            );

            if (seckillTicket != null) {
                // 只有当DB中的库存与新值不同时才更新（避免无效写操作）
                if (!newStock.equals(seckillTicket.getStock())) {
                    seckillTicket.setStock(newStock);
                    boolean dbUpdated = this.updateById(seckillTicket);
                    if (dbUpdated) {
                        log.info("[刷新库存-DB更新成功] ticketId:{}, 旧库存:{} → 新库存:{}",
                                ticketId, seckillTicket.getStock(), newStock);
                    } else {
                        log.error("[刷新库存-DB更新失败] ticketId:{}, newStock:{}", ticketId, newStock);
                        return;  // DB更新失败则不更新Redis，保持一致
                    }
                }
            }

            // Step 2: 同步Redis缓存（使用动态TTL）
            long dynamicTtlHours = (seckillTicket != null)
                    ? calculateDynamicTtl(seckillTicket)
                    : 48L;  // 默认48小时

            stringRedisTemplate.opsForValue().set(
                stockKey,
                newStock.toString(),
                dynamicTtlHours,
                TimeUnit.HOURS
            );

            log.info("[刷新库存成功] ticketId:{}, 新库存:{}, TTL:{}小时, key:{}, DB+Redis双写完成",
                    ticketId, newStock, dynamicTtlHours, stockKey);

        } catch (Exception e) {
            log.error("[刷新库存失败] ticketId:{}, error:{}", ticketId, e.getMessage(), e);
        }
    }

    /**
     * 仅同步Redis缓存（DB已由调用方更新）
     *
     * 使用场景：
     * - AdminServiceImpl.updateSeckillTicket() 中，DB已通过updateById()更新
     * - 其他已知DB已是最新的场景
     *
     * 与 refreshSeckillStock() 的区别：
     * - refreshSeckillStock(): DB + Redis 双写（完整一致性保证）
     * - refreshSeckillStockToRedis(): 仅更新Redis（DB已由外部更新）
     *
     * @param ticketId 票种ID
     * @param newStock 新库存数量（应与DB中的值一致）
     */
    public void refreshSeckillStockToRedis(Long ticketId, Integer newStock) {
        try {
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + ticketId;

            if (newStock == null || newStock < 0) {
                log.warn("[刷新Redis库存] 无效库存值, ticketId:{}, newStock:{}", ticketId, newStock);
                return;
            }

            SeckillTicket seckillTicket = this.getOne(
                new LambdaQueryWrapper<SeckillTicket>()
                    .eq(SeckillTicket::getTicketId, ticketId)
            );

            long dynamicTtlHours = (seckillTicket != null)
                    ? calculateDynamicTtl(seckillTicket)
                    : 48L;

            stringRedisTemplate.opsForValue().set(
                stockKey,
                newStock.toString(),
                dynamicTtlHours,
                TimeUnit.HOURS
            );

            log.info("[刷新Redis库存成功] ticketId:{}, 新库存:{}, TTL:{}小时, key:{}",
                    ticketId, newStock, dynamicTtlHours, stockKey);

        } catch (Exception e) {
            log.error("[刷新Redis库存失败] ticketId:{}, error:{}", ticketId, e.getMessage(), e);
        }
    }

    /**
     * 获取Redis中当前库存（用于调试和管理）
     *
     * @param ticketId 票种ID
     * @return 当前Redis库存，如果不存在返回null
     */
    public Integer getRedisStock(Long ticketId) {
        try {
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + ticketId;
            String stockStr = stringRedisTemplate.opsForValue().get(stockKey);

            if (stockStr == null) {
                return null;
            }

            return Integer.parseInt(stockStr);
        } catch (NumberFormatException e) {
            log.error("[获取Redis库存] 格式错误，ticketId:{}", ticketId);
            return null;
        }
    }
}
