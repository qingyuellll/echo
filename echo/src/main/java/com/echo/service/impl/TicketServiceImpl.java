package com.echo.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.entity.SeckillTicket;
import com.echo.entity.Ticket;
import com.echo.enums.TicketType;
import com.echo.exception.BusinessException;
import com.echo.mapper.TicketMapper;
import com.echo.service.ISeckillTicketService;
import com.echo.service.ITicketOrderService;
import com.echo.service.ITicketService;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import static com.echo.utils.RedisConstants.*;


/**
 * 票种服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class TicketServiceImpl extends ServiceImpl<TicketMapper, Ticket> implements ITicketService {

    @Resource
    private ISeckillTicketService seckillTicketService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private com.echo.mapper.TicketOrderMapper ticketOrderMapper;
    @Lazy
    @Resource
    private ITicketOrderService ticketOrderService;

    private final Cache<Long, Ticket> localTicketCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .removalListener((key, value, cause) -> {
                if (cause.wasEvicted()) {
                    log.debug("[本地缓存] 票种详情缓存被淘汰，ticketId:{}", key);
                }
            })
            .build();

    public void invalidateLocalTicketCache(Long ticketId) {
        if (ticketId != null) {
            localTicketCache.invalidate(ticketId);
            log.info("[本地缓存] 已失效票种详情缓存，ticketId:{}", ticketId);
        }
    }

    public void invalidateAllLocalTicketCache() {
        localTicketCache.cleanUp();
        log.info("[本地缓存] 已清理所有本地缓存");
    }

    /**
     * 查询演出下所有票种（含秒杀信息+缓存穿透防护+异常降级）
     */
    @Override
    public Result<?> queryTicketOfShow(Long showId) {
        try {
            // 1. 参数校验
            if (showId == null || showId <= 0) {
                log.warn("[查询票种] 无效的showId:{}", showId);
                return Result.fail(AppMessages.SHOW_ID_INVALID);
            }

            String cacheKey = CACHE_TICKET_KEY + showId;

            // 2. 尝试从Redis读取缓存
            log.debug("[查询票种] 尝试从Redis读取缓存，key:{}", cacheKey);
            String json = stringRedisTemplate.opsForValue().get(cacheKey);

            if (json != null) {
                // 判断是否为空值缓存
                if ("[]".equals(json)) {
                    log.debug("[查询票种] 命中空值缓存（防穿透），返回空列表");
                    return Result.ok(Collections.emptyList());
                }

                // 正常缓存命中
                List<Ticket> cached = JSONUtil.toList(JSONUtil.parseArray(json), Ticket.class);
                log.info("[查询票种] 缓存命中，showId:{}, 共{}个票种", showId, cached.size());

                // 关键修复：即使缓存命中，也要为秒杀票同步Redis实时库存！
                refreshSeckillStockFromRedis(cached, "查询票种-缓存命中");

                // 为缓存中的所有票种设置isSeckill标记（前端需要此字段判断票种类型）
                setSeckillFlag(cached);


                setSoldCountAndRemainingStock(cached);

                return Result.ok(cached);
            }
            log.info("[查询票种] 缓存未命中，回源数据库，showId:{}", showId);
            List<Ticket> tickets = getBaseMapper().queryTicketOfShow(showId);

            // 为秒杀票从Redis获取实时库存（关键修复！）
            refreshSeckillStockFromRedis(tickets, "查询票种");

            // 4. 写入缓存（含空值缓存防穿透）
            long ttl = getRandomExpireTime();  // 基础30分钟 + 随机10分钟

            // 为所有票种设置isSeckill标记（关键修复）
            setSeckillFlag(tickets);


            setSoldCountAndRemainingStock(tickets);

            if (tickets == null || tickets.isEmpty()) {
                // 空值缓存，防止缓存穿透（TTL=5分钟）
                stringRedisTemplate.opsForValue().set(
                        cacheKey,
                        "[]",
                        5,
                        TimeUnit.MINUTES
                );
                log.warn("[查询票种] 演出无票种数据，已设置空值缓存（防穿透），showId:{}, TTL:5分钟", showId);
                return Result.ok(Collections.emptyList());
            } else {
                // 正常数据缓存（随机TTL防雪崩）
                stringRedisTemplate.opsForValue().set(
                        cacheKey,
                        JSONUtil.toJsonStr(tickets),
                        ttl,

                        TimeUnit.SECONDS
                );
                log.info("[查询票种] 成功，showId:{}, 共{}个票种，已缓存，TTL:{}分钟", showId, tickets.size(), ttl);
                return Result.ok(tickets);
            }

        } catch (Exception e) {
            log.error("[查询票种] 异常，showId:{}, error:{}", showId, e.getMessage(), e);

            // 降级：直接查DB返回（不经过Redis）
            try {
                log.warn("[查询票种] Redis异常，降级为直接查DB，showId:{}", showId);
                List<Ticket> fallbackList = getBaseMapper().queryTicketOfShow(showId);
                return Result.ok(fallbackList != null ? fallbackList : Collections.emptyList());
            } catch (Exception ex) {
                log.error("[查询票种] 降级也失败，showId:{}, error:{}", showId, ex.getMessage());
                return Result.fail(AppMessages.TICKET_QUERY_FAIL);
            }
        }
    }

    /**
     * 刷新演出票种缓存（Write-Through模式：立即更新Redis）
     * <p>
     * 优势：
     * - 用户端立即可见最新数据（无需等待下次查询重建缓存）
     * - 避免缓存击穿（不会出现删除后多线程同时查DB的情况）
     * - 数据一致性更好（DB和Redis几乎同步更新）
     *
     * @param showId 演出ID
     */
    public void refreshTicketCache(Long showId) {
        if (showId == null || showId <= 0) {
            log.warn("[刷新缓存] 无效的showId:{}", showId);
            return;
        }

        String cacheKey = CACHE_TICKET_KEY + showId;
        String lockKey = "lock:cache:refresh:" + showId;
        Boolean locked = false;

        try {
            locked = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);

            if (!Boolean.TRUE.equals(locked)) {
                String cachedData = stringRedisTemplate.opsForValue().get(cacheKey);
                if (cachedData != null) {
                    log.info("[刷新缓存] 其他线程正在刷新，跳过重复操作，showId:{}", showId);
                    return;
                }

                log.warn("[刷新缓存] 获取锁失败且缓存为空，可能存在缓存击穿风险，showId:{}. " +
                        "建议前端稍后重试或增加熔断机制", showId);

                throw new com.echo.exception.BusinessException(
                        "系统繁忙，缓存刷新中，请稍后重试",
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
            }

            log.debug("[刷新缓存] 成功获取分布式锁，key:{}, showId:{}", lockKey, showId);

            // 1. 从数据库查询最新数据（可能抛出异常）
            List<Ticket> latestTickets;
            try {
                latestTickets = getBaseMapper().queryTicketOfShow(showId);
            } catch (Exception dbEx) {
                log.error("[刷新缓存] 数据库查询异常，showId:{}, error:{}", showId, dbEx.getMessage(), dbEx);
                throw new BusinessException(AppMessages.format(AppMessages.DATABASE_QUERY_FAIL_PREFIX, dbEx.getMessage()), org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, dbEx);
            }

            // 2. 计算随机TTL（防雪崩）
            long ttl = getRandomExpireTime();

            // 3. 写入Redis缓存
            if (latestTickets == null || latestTickets.isEmpty()) {
                // 空数据：写入空值缓存（防穿透），TTL=5分钟
                stringRedisTemplate.opsForValue().set(
                        cacheKey,
                        "[]",
                        5,
                        TimeUnit.MINUTES
                );
                log.info("[刷新缓存] 演出无票种数据，写入空值缓存，showId:{}, TTL:5分钟", showId);
            } else {
                // 正常数据：写入完整列表，TTL=30-40分钟（随机）
                stringRedisTemplate.opsForValue().set(
                        cacheKey,
                        JSONUtil.toJsonStr(latestTickets),
                        ttl,
                        TimeUnit.SECONDS
                );
                log.info("[刷新缓存] 成功，showId:{}, 共{}个票种，已写入Redis，TTL:{}分钟",
                        showId, latestTickets.size(), ttl);
            }

        } catch (Exception e) {
            log.error("[刷新缓存] 异常，showId:{}, error:{}", showId, e.getMessage(), e);

            // 降级：尝试删除旧缓存（避免脏数据）
            try {
                stringRedisTemplate.delete(cacheKey);
                log.warn("[刷新缓存] 已降级为删除旧缓存，showId:{}", showId);
            } catch (Exception ex) {
                log.error("[刷新缓存] 降级也失败，showId:{}, error:{}", showId, ex.getMessage());
            }
        } finally {
            if (Boolean.TRUE.equals(locked)) {
                try {
                    stringRedisTemplate.delete(lockKey);
                    log.debug("[刷新缓存] 已释放分布式锁，key:{}", lockKey);
                } catch (Exception lockEx) {
                    log.warn("[刷新缓存] 释放锁失败（10秒后自动过期），key:{}, error:{}", lockKey, lockEx.getMessage());
                }
            }
        }
    }

    /**
     * 清除所有票种缓存（用于SQL变更后强制刷新）
     * <p>
     * 使用场景：
     * - SQL查询条件修改后（如添加status过滤）
     * - 数据库结构变更后
     * - 批量数据修复后
     *
     * NEW-23改进：使用SCAN替代KEYS（防止大键空间阻塞Redis）
     *
     * @return 清除的缓存数量
     */
    public int clearAllTicketCache() {
        try {
            // 使用SCAN命令遍历键空间（非阻塞式，适合生产环境）
            final java.util.List<String> ticketCacheKeys = new java.util.ArrayList<>();
            final int SCAN_BATCH_SIZE = 1000;  // 每次扫描最多返回的key数

            stringRedisTemplate.execute((org.springframework.data.redis.connection.RedisConnection connection) -> {
                var cursor = connection.scan(
                        org.springframework.data.redis.core.ScanOptions.scanOptions()
                                .match(CACHE_TICKET_KEY + "*")
                                .count(SCAN_BATCH_SIZE)
                                .build()
                );

                while (cursor.hasNext()) {
                    byte[] keyBytes = cursor.next();
                    ticketCacheKeys.add(new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8));
                }

                return null;
            });

            if (ticketCacheKeys.isEmpty()) {
                log.info("[清除所有票种缓存] 无需清除，当前无票种缓存");
                return 0;
            }

            // 分批删除（避免单次删除过多key导致阻塞）
            int totalDeleted = 0;
            final int DELETE_BATCH_SIZE = 500;

            for (int i = 0; i < ticketCacheKeys.size(); i += DELETE_BATCH_SIZE) {
                int endIdx = Math.min(i + DELETE_BATCH_SIZE, ticketCacheKeys.size());
                java.util.List<String> batchKeys = ticketCacheKeys.subList(i, endIdx);

                long batchDeleted = stringRedisTemplate.delete(batchKeys);
                totalDeleted += batchDeleted;

                log.debug("[清除所有票种缓存] 已删除批次 {}/{}，本批删除:{}个",
                        (i / DELETE_BATCH_SIZE) + 1,
                        (ticketCacheKeys.size() + DELETE_BATCH_SIZE - 1) / DELETE_BATCH_SIZE,
                        batchDeleted);
            }

            log.info("[清除所有票种缓存] 成功清除 {} 个票种缓存（使用SCAN非阻塞扫描）", totalDeleted);

            return totalDeleted;

        } catch (Exception e) {
            log.error("[清除所有票种缓存] 失败，error:{}", e.getMessage(), e);
            return -1;  // 错误码
        }
    }

    /**
     * 清理演出票种缓存（兼容壳方法）
     *
     * 设计说明：
     * - 此方法是历史遗留接口的兼容层（~12处调用方尚未迁移）
     * - 内部直接委托给 refreshTicketCache(showId) 执行实际清理逻辑
     * - 保留此方法可避免大规模重构带来的回归风险
     *
     * 迁移计划：
     * - 新代码统一使用 refreshTicketCache()
     * - 旧调用点逐步迁移（每次迭代处理2-3处）
     * - 当调用方降至0时，可安全删除此方法
     *
     * @param showId 演出ID
     */
    public void clearTicketCache(Long showId) {
        log.debug("[缓存兼容层] clearTicketCache → 委托给 refreshTicketCache, showId:{}", showId);
        refreshTicketCache(showId);
    }

    /**
     * 新增秒杀票种（事务管理+参数校验+日志增强）
     * <p>
     * 业务流程：
     * 1. 校验票种基本信息（标题、库存、时间等）
     * 2. 保存票种记录到tb_ticket表
     * 3. 创建秒杀配置到tb_seckill_ticket表
     * 4. 初始化Redis库存计数器
     * <p>
     * 注意事项：
     * - 整个流程在同一事务中，任一步骤失败则整体回滚
     * - Redis库存初始化在事务提交后执行（避免事务回滚但库存已写入的不一致问题）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addSeckillTicket(Ticket ticket) {
        String lockKey = null;
        Boolean locked = false;

        try {
            // 1. 参数校验
            if (ticket == null) {
                log.warn("[新增秒杀票] ticket对象为空");
                throw new IllegalArgumentException(AppMessages.TICKET_INFO_EMPTY);
            }

            if (ticket.getTitle() == null || ticket.getTitle().trim().isEmpty()) {
                log.warn("[新增秒杀票] 标题为空");
                throw new IllegalArgumentException(AppMessages.TICKET_TITLE_EMPTY);
            }

            if (ticket.getStock() == null || ticket.getStock() <= 0) {
                log.warn("[新增秒杀票] 库存无效，stock:{}", ticket.getStock());
                throw new IllegalArgumentException(AppMessages.TICKET_STOCK_MUST_GREATER_THAN_ZERO);
            }

            if (ticket.getShowId() == null || ticket.getShowId() <= 0) {
                log.warn("[新增秒杀票] 无效的showId:{}", ticket.getShowId());
                throw new IllegalArgumentException(AppMessages.SHOW_ID_INVALID);
            }

            if (ticket.getBeginTime() == null || ticket.getEndTime() == null) {
                log.warn("[新增秒杀票] 秒杀时间为空");
                throw new IllegalArgumentException(AppMessages.TICKET_SECKILL_TIME_REQUIRED);
            }

            if (ticket.getBeginTime().isAfter(ticket.getEndTime())) {
                log.warn("[新增秒杀票] 时间错误，beginTime:{} > endTime:{}", ticket.getBeginTime(), ticket.getEndTime());
                throw new IllegalArgumentException(AppMessages.TICKET_SECKILL_TIME_INVALID);
            }

            lockKey = "lock:seckill:add:" + ticket.getShowId();
            try {
                locked = stringRedisTemplate.opsForValue()
                        .setIfAbsent(lockKey, "1", 30, java.util.concurrent.TimeUnit.SECONDS);

                if (!Boolean.TRUE.equals(locked)) {
                    log.warn("[新增秒杀票] 获取分布式锁失败，可能存在并发操作，showId:{}", ticket.getShowId());
                    throw com.echo.exception.ServiceUnavailableException.ofServiceUnavailable("操作过于频繁，请稍后再试（锁竞争）");
                }

                log.info("[新增秒杀票] 成功获取分布式锁，key:{}, showId:{}", lockKey, ticket.getShowId());

                Long existingCount = getBaseMapper().selectCount(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Ticket>()
                                .eq("show_id", ticket.getShowId())
                                .eq("type", TicketType.SECKILL.getCode())
                                .eq("title", ticket.getTitle())
                );

                if (existingCount != null && existingCount > 0) {
                    log.warn("[新增秒杀票] 该演出已存在同名秒杀票，showId:{}, title:{}", ticket.getShowId(), ticket.getTitle());
                    throw new IllegalArgumentException("该演出已存在同名的秒杀票种，请勿重复添加");
                }

            } catch (Exception lockEx) {
                if (lockEx instanceof IllegalArgumentException || lockEx instanceof RuntimeException) {
                    throw lockEx;
                }
                log.error("[新增秒杀票] 获取锁或检查重复时异常，showId:{}", ticket.getShowId(), lockEx);
                throw com.echo.exception.ServiceUnavailableException.ofServiceUnavailable("系统繁忙，请稍后再试", lockEx);
            }

            log.info("[新增秒杀票] 开始添加秒杀门票，title:{}, showId:{}, stock:{}, time:{}~{}",
                    ticket.getTitle(),
                    ticket.getShowId(),
                    ticket.getStock(),
                    ticket.getBeginTime(),
                    ticket.getEndTime());

            // 2. 保存票种记录（事务内）
            boolean saveSuccess = save(ticket);
            if (!saveSuccess) {
                log.error("[新增秒杀票] 保存票种失败");
                throw new BusinessException(AppMessages.TICKET_SAVE_FAIL, org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
            }

            log.info("[新增秒杀票] 票种保存成功，id:{}", ticket.getId());

            // 3. 创建秒杀配置（事务内）
            SeckillTicket seckillTicket = new SeckillTicket();
            seckillTicket.setTicketId(ticket.getId());
            seckillTicket.setStock(ticket.getStock());
            seckillTicket.setBeginTime(ticket.getBeginTime());
            seckillTicket.setEndTime(ticket.getEndTime());

            boolean seckillSaveSuccess = seckillTicketService.save(seckillTicket);
            if (!seckillSaveSuccess) {
                log.error("[新增秒杀票] 保存秒杀配置失败");
                throw new BusinessException(AppMessages.TICKET_SECKILL_SAVE_FAIL, org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
            }

            log.info("[新增秒杀票] 秒杀配置保存成功");

            // 4. 初始化Redis库存（事务外：即使Redis操作失败也不影响主业务）

            try {
                long dynamicTtlHours = calculateDynamicTtlForSeckill(seckillTicket);

                stringRedisTemplate.opsForValue().set(
                        SECKILL_STOCK_KEY + ticket.getId(),
                        String.valueOf(ticket.getStock()),
                        dynamicTtlHours,
                        java.util.concurrent.TimeUnit.HOURS
                );
                log.info("[新增秒杀票] Redis库存初始化成功，key:{}, stock:{}, TTL:{}小时",
                        SECKILL_STOCK_KEY + ticket.getId(),
                        ticket.getStock(),
                        dynamicTtlHours);
            } catch (Exception redisEx) {
                log.warn("[新增秒杀票] Redis库存初始化失败，不影响主流程，error:{}", redisEx.getMessage());
                // 不抛出异常，允许事务正常提交（后续可通过补偿机制修复库存）
            }

            log.info("[新增成功] 秒杀门票创建完成，id:{}, title:{}", ticket.getId(), ticket.getTitle());

        } catch (IllegalArgumentException e) {
            log.error("[新增秒杀票] 参数校验失败", e);
            throw e;
        } catch (RuntimeException e) {
            log.error("[新增秒杀票] 业务异常，error:{}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("[新增秒杀票] 系统异常，error:{}", e.getMessage(), e);
            throw new BusinessException(AppMessages.format(AppMessages.TICKET_SECKILL_CREATE_FAIL_PREFIX, e.getMessage()), org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, e);
        } finally {
            if (Boolean.TRUE.equals(locked)) {
                try {
                    stringRedisTemplate.delete(lockKey);
                    log.info("[新增秒杀票] 已释放分布式锁，key:{}", lockKey);
                } catch (Exception lockEx) {
                    log.error("[新增秒杀票] 释放分布式锁失败（30秒后自动过期），key:{}, error:{}", lockKey, lockEx.getMessage());
                }
            }
        }
    }

    /**
     * 生成随机过期时间（防止缓存雪崩）
     *
     * @return 最终TTL（分钟）
     */
    private long getRandomExpireTime() {
        return com.echo.utils.RedisConstants.CACHE_TICKET_TTL + (long) (Math.random() * com.echo.utils.RedisConstants.CACHE_TICKET_RANDOM_TTL);
    }

    /**
     * 计算秒杀库存键的动态TTL（与SeckillTicketServiceImpl保持一致）
     *
     * 统一策略：
     * - 基于活动结束时间计算剩余时长 + 缓冲区
     * - 最小保障48小时（防止异常过期）
     * - 确保addSeckillTicket、syncSeckillStockToRedis、preloadSeckillStock三处TTL一致
     *
     * @param seckillTicket 秒杀票配置
     * @return TTL（小时）
     */
    private long calculateDynamicTtlForSeckill(com.echo.entity.SeckillTicket seckillTicket) {
        final long MIN_TTL_HOURS = 48L;  // 最小安全保障
        final long BUFFER_HOURS = 6L;    // 活动结束后缓冲

        try {
            java.time.LocalDateTime endTime = seckillTicket.getEndTime();

            if (endTime != null) {
                long remainingMillis = java.time.Duration.between(java.time.LocalDateTime.now(), endTime).toMillis();
                long remainingHours = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(remainingMillis) + BUFFER_HOURS;

                if (remainingHours > MIN_TTL_HOURS) {
                    log.debug("[动态TTL] 基于活动结束时间，ticketId:{}, TTL:{}小时",
                            seckillTicket.getTicketId(), remainingHours);
                    return remainingHours;
                } else if (remainingHours > 0) {
                    log.info("[动态TTL] 活动即将结束，使用最小TTL，ticketId:{}, TTL:{}小时",
                            seckillTicket.getTicketId(), MIN_TTL_HOURS);
                    return MIN_TTL_HOURS;
                }
            }

            log.info("[动态TTL] 无法获取结束时间或已结束，使用默认TTL，ticketId:{}, TTL:{}小时",
                    seckillTicket.getTicketId(), MIN_TTL_HOURS);
            return MIN_TTL_HOURS;

        } catch (Exception e) {
            log.warn("[动态TTL] 异常，使用默认TTL，ticketId:{}, error:{}",
                    seckillTicket.getTicketId(), e.getMessage());
            return MIN_TTL_HOURS;
        }
    }

    /**
     * 为票种列表设置isSeckill标记（前端需要此字段判断票种类型）
     *
     * @param tickets 票种列表
     */
    private void setSeckillFlag(List<Ticket> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return;
        }

        List<Long> seckillTicketIds = tickets.stream()
                .filter(t -> TicketType.isSeckill(t.getType()))
                .map(Ticket::getId)
                .collect(java.util.stream.Collectors.toList());

        java.util.Map<Long, SeckillTicket> seckillConfigMap = new java.util.HashMap<>();
        if (!seckillTicketIds.isEmpty()) {
            try {
                List<SeckillTicket> seckillConfigs = seckillTicketService.listByIds(seckillTicketIds);
                if (seckillConfigs != null) {
                    seckillConfigMap = seckillConfigs.stream()
                            .collect(java.util.stream.Collectors.toMap(SeckillTicket::getTicketId, config -> config, (a, b) -> a));
                }
                log.debug("[秒杀批量查询] 批量获取{}个秒杀配置，成功{}个", seckillTicketIds.size(), seckillConfigMap.size());
            } catch (Exception e) {
                log.warn("[秒杀批量查询] 批量查询失败，降级为逐个查询，error:{}", e.getMessage());
                for (Long ticketId : seckillTicketIds) {
                    try {
                        SeckillTicket config = seckillTicketService.getById(ticketId);
                        if (config != null) {
                            seckillConfigMap.put(ticketId, config);
                        }
                    } catch (Exception ex) {
                        log.warn("[秒杀降级查询] 单个查询也失败，ticketId:{}", ticketId);
                    }
                }
            }
        }

        for (Ticket t : tickets) {
            boolean isSeckill = TicketType.isSeckill(t.getType());
            t.setIsSeckill(isSeckill);

            if (isSeckill) {
                try {
                    SeckillTicket seckillTicket = seckillConfigMap.get(t.getId());
                    if (seckillTicket != null) {
                        Map<String, Object> extInfo = new java.util.HashMap<>();
                        extInfo.put("seckillBeginTime", seckillTicket.getBeginTime());
                        extInfo.put("seckillEndTime", seckillTicket.getEndTime());
                        extInfo.put("seckillScheduledStartTime", seckillTicket.getScheduledStartTime());
                        t.setExtInfo(extInfo);

                        t.setBeginTime(seckillTicket.getBeginTime());
                        t.setEndTime(seckillTicket.getEndTime());

                        LocalDateTime now = LocalDateTime.now();
                        boolean isActive = seckillTicket.getBeginTime() != null &&
                                seckillTicket.getEndTime() != null &&
                                !now.isBefore(seckillTicket.getBeginTime()) &&
                                !now.isAfter(seckillTicket.getEndTime());
                        t.setIsActive(isActive);

                        log.debug("[秒杀时间] ticketId:{}, beginTime:{}, endTime:{}, isActive:{}",
                                t.getId(), seckillTicket.getBeginTime(), seckillTicket.getEndTime(), isActive);
                    }
                } catch (Exception e) {
                    log.warn("[秒杀时间] 获取秒杀时间失败，ticketId:{}, error:{}", t.getId(), e.getMessage());
                }
            } else {
                t.setIsActive(t.getStatus() != null && t.getStatus() == 1);
            }
        }
    }

    /**
     * 计算票种的已售数量和剩余库存（核心业务逻辑）
     * <p>
     * 已售口径定义：订单状态 IN (2, 3)
     *   - status=2：已支付（含未核销的票）
     *   - status=3：已核销（已入场验票）
     * <p>
     * 排除的状态：
     *   - status=0：待支付（预订单）
     *   - status=1：秒杀预占（未支付）
     *   - status=4：已取消
     *   - status=5：已退款
     *   - status=6：申请退款中
     *
     * @param tickets 票种列表
     */
    private void setSoldCountAndRemainingStock(List<Ticket> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return;
        }

        try {
            List<Long> ticketIds = tickets.stream()
                    .map(Ticket::getId)
                    .filter(Objects::nonNull)
                    .toList();

            java.util.Map<Long, Integer> soldMap = new java.util.HashMap<>();

            if (!ticketIds.isEmpty()) {
                com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.TicketOrder> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
                wrapper.select("ticket_id", "COUNT(*) AS cnt")
                        .in("ticket_id", ticketIds)
                        .in("status", 2, 3)
                        .groupBy("ticket_id");

                List<java.util.Map<String, Object>> rows = ticketOrderMapper.selectMaps(wrapper);
                for (java.util.Map<String, Object> row : rows) {
                    Long tid = ((Number) row.get("ticket_id")).longValue();
                    Integer cnt = ((Number) row.get("cnt")).intValue();
                    soldMap.put(tid, cnt);
                }
            }

            for (Ticket ticket : tickets) {
                int soldCount = soldMap.getOrDefault(ticket.getId(), 0);
                ticket.setSoldCount(soldCount);

                if (!TicketType.isSeckill(ticket.getType())) {
                    int totalStock = ticket.getStock() != null ? ticket.getStock() : 0;
                    int remaining = totalStock - soldCount;
                    ticket.setRemainingStock(Math.max(0, remaining));
                } else {
                    if (ticket.getSeckillStock() != null) {
                        ticket.setRemainingStock(ticket.getSeckillStock());
                    }
                }

                log.debug("[库存统计] ticketId:{}, 总库存:{}, 已售:{}, 剩余:{}",
                    ticket.getId(), ticket.getStock(), soldCount, ticket.getRemainingStock());
            }
        } catch (Exception e) {
            log.error("[库存统计] 计算已售数量失败，使用默认值0: {}", e.getMessage(), e);
            for (Ticket ticket : tickets) {
                if (ticket.getSoldCount() == null) {
                    ticket.setSoldCount(0);
                }
                if (ticket.getRemainingStock() == null) {
                    ticket.setRemainingStock(ticket.getStock() != null ? ticket.getStock() : 0);
                }
            }
        }
    }

    /**
     * 为秒杀票从Redis获取实时库存（关键修复：保证数据实时性）
     *
     * @param tickets 票种列表
     * @param logPrefix 日志前缀（用于区分缓存命中/未命中场景）
     */
    private void refreshSeckillStockFromRedis(List<Ticket> tickets, String logPrefix) {
        if (tickets == null || tickets.isEmpty()) {
            return;
        }

        for (Ticket ticket : tickets) {
            if (TicketType.isSeckill(ticket.getType())) {
                try {
                    String redisStockKey = SECKILL_STOCK_KEY + ticket.getId();
                    String redisStockStr = stringRedisTemplate.opsForValue().get(redisStockKey);

                    if (redisStockStr != null && !redisStockStr.isEmpty()) {
                        int redisStock = Integer.parseInt(redisStockStr);
                        ticket.setSeckillStock(redisStock);
                        log.debug("[{}] 秒杀票库存已更新（Redis）, ticketId:{}, 库存:{}", logPrefix, ticket.getId(), redisStock);
                    } else {
                        log.warn("[{}] 秒杀票Redis库存为空，使用DB库存, ticketId:{}, DB库存:{}", logPrefix, ticket.getId(), ticket.getSeckillStock());
                    }
                } catch (NumberFormatException e) {
                    log.warn("[{}] 秒杀票Redis库存格式错误, ticketId:{}, error:{}", logPrefix, ticket.getId(), e.getMessage());
                } catch (Exception e) {
                    log.warn("[{}] 获取秒杀票Redis库存失败, ticketId:{}, error:{}", logPrefix, ticket.getId(), e.getMessage());
                }
            }
        }
    }

    // ==================== 票种状态验证（前后端双重防护） ====================

    /**
     * 查询票种状态（轻量级接口，用于前端并发安全验证）
     * <p>
     * 返回信息：
     * - status: 票种状态（0=下架, 1=上架, 2=停用）
     * - isSeckill: 是否秒杀票
     * - isActive: 是否处于"可售卖状态"（普通票上架后/秒杀开始后）
     * - beginTime: 秒杀开始时间（仅秒杀票有值）
     * - endTime: 秒杀结束时间（仅秒杀票有值）
     * - stock: 当前库存
     * <p>
     * 使用场景：
     * 1. 用户进入选座页面时
     * 2. 锁定座位前
     * 3. 创建订单前（最终验证）
     *
     * @param ticketId 票种ID
     * @return 票种状态信息
     */
    @Override
    public Result<?> checkTicketStatus(Long ticketId) {
        try {
            // 1. 参数校验
            if (ticketId == null || ticketId <= 0) {
                log.warn("[查询票种状态] 无效的ticketId:{}", ticketId);
                return Result.fail(AppMessages.ORDER_TICKET_NOT_EXIST);
            }

            // 2. 从数据库查询票种（确保数据最新，不使用缓存）
            Ticket ticket = getById(ticketId);
            if (ticket == null) {
                log.warn("[查询票种状态] 票种不存在，ticketId:{}", ticketId);
                return Result.fail(AppMessages.ORDER_TICKET_NOT_EXIST);
            }

            // 3. 构建状态返回对象（轻量级，只包含关键信息）
            java.util.Map<String, Object> statusInfo = new java.util.HashMap<>();
            statusInfo.put("id", ticket.getId());
            statusInfo.put("status", ticket.getStatus());  // 0下架/1上架/2停用
            statusInfo.put("isSeckill", TicketType.isSeckill(ticket.getType()));
            statusInfo.put("hasSeats", ticket.getHasSeats() != null ? ticket.getHasSeats() : false);

            // 4. 判断是否处于"可售卖状态"
            boolean isActive = isTicketActiveInternal(ticket);
            statusInfo.put("isActive", isActive);

            // 5. 如果是秒杀票，附加秒杀相关信息
            if (TicketType.isSeckill(ticket.getType())) {
                try {
                    SeckillTicket seckillTicket = seckillTicketService.getById(ticketId);
                    if (seckillTicket != null) {
                        statusInfo.put("beginTime", seckillTicket.getBeginTime());
                        statusInfo.put("endTime", seckillTicket.getEndTime());

                        // 从Redis获取实时库存
                        String redisStockStr = stringRedisTemplate.opsForValue()
                                .get(SECKILL_STOCK_KEY + ticketId);
                        if (redisStockStr != null && !redisStockStr.isEmpty()) {
                            statusInfo.put("stock", Integer.parseInt(redisStockStr));
                            statusInfo.put("seckillStock", Integer.parseInt(redisStockStr));
                        } else {
                            statusInfo.put("stock", seckillTicket.getStock());
                            statusInfo.put("seckillStock", seckillTicket.getStock());
                        }
                    }
                } catch (Exception e) {
                    log.warn("[查询票种状态] 获取秒杀信息失败，ticketId:{}, error:{}", ticketId, e.getMessage());
                }
            } else {
                // 普通票的库存
                statusInfo.put("stock", ticket.getStock() != null ? ticket.getStock() : 0);
            }

            log.info("[查询票种状态] 成功，ticketId:{}, status:{}, isActive:{}",
                    ticketId, ticket.getStatus(), isActive);

            return Result.ok(statusInfo);

        } catch (Exception e) {
            log.error("[查询票种状态] 异常，ticketId:{}, error:{}", ticketId, e.getMessage(), e);
            return Result.fail(AppMessages.TICKET_STATUS_QUERY_FAIL);
        }
    }

    @Override
    public Result<?> checkSeckillPurchased(Long ticketId) {
        try {
            // 1. 参数校验
            if (ticketId == null || ticketId <= 0) {
                log.warn("[检查秒杀购买] 无效的ticketId:{}", ticketId);
                return Result.fail(AppMessages.TICKET_ID_INVALID);
            }

            // 2. 获取当前用户
            com.echo.dto.UserDTO user = UserHolder.getUser();
            if (user == null) {
                log.warn("[检查秒杀购买] 用户未登录");
                return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
            }

            Long userId = user.getId();
            log.info("[检查秒杀购买] 开始检查 - ticketId:{}, userId:{}", ticketId, userId);

            // 3. 检查是否为秒杀票
            Ticket ticket = getById(ticketId);
            if (ticket == null) {
                log.warn("[检查秒杀购买] 票种不存在 - ticketId:{}", ticketId);
                return Result.fail(AppMessages.ORDER_TICKET_NOT_EXIST);
            }

            if (!TicketType.isSeckill(ticket.getType())) {
                log.info("[检查秒杀购买] 非秒杀票，无需检查 - ticketId:{}, type:{}", ticketId, ticket.getType());
                return Result.ok(java.util.Map.of("purchased", false, "message", AppMessages.TICKET_NOT_SECKILL));
            }

            // 4. 查询用户是否已购买过该秒杀票
            //
            // 原问题：
            //   .ge("status", 1) 会匹配状态 1(秒杀预占), 2(已支付), 3(已核销),
            //   4(已取消), 5(已退款), 6(申请退款中), 7(已拒绝)
            //   → 用户一旦取消或退款，永久无法再购买同一秒杀票
            //
            // 修复后：
            //   仅匹配有效占用状态：2(已支付), 3(已核销), 6(申请退款中)
            //   → 已取消(4)/已退款(5)/已拒绝(7)的用户可重新购买
            //   → 与退款链路 seckillRefundScript 的 SREM 行为一致（Redis放行则DB也放行）
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.TicketOrder> orderQuery =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            orderQuery.eq("user_id", userId)
                      .eq("ticket_id", ticketId)
                      .in("status", 2, 3, 6);  // 仅限：已支付/已核销/退款处理中

            long existingOrderCount = ticketOrderService.count(orderQuery);

            boolean purchased = existingOrderCount > 0;

            log.info("[检查秒杀购买] 检查完成 - ticketId:{}, userId:{}, purchased:{}, existingOrderCount:{}",
                    ticketId, userId, purchased, existingOrderCount);

            // 5. 返回结果
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("purchased", purchased);
            result.put("message", purchased ? "您已购买过该秒杀票" : "可以购买");

            return Result.ok(result);

        } catch (Exception e) {
            log.error("[检查秒杀购买] 异常 - ticketId:{}, error:{}", ticketId, e.getMessage(), e);
            return Result.fail(AppMessages.TICKET_CHECK_PURCHASE_FAIL);
        }
    }

    /**
     *
     * 包含所有票种信息、秒杀配置、实时库存等
     *
     * @param ticketId 票种ID
     * @return 票种完整信息
     */
    @Override
    public Result<?> getTicketDetail(Long ticketId) {
        try {
            // 1. 参数校验
            if (ticketId == null || ticketId <= 0) {
                log.warn("[查询票种详情] 无效的ticketId:{}", ticketId);
                return Result.fail(AppMessages.ORDER_TICKET_NOT_EXIST);
            }

            // 2. 本地缓存查询（热点数据优化）
            Ticket ticket = localTicketCache.getIfPresent(ticketId);
            if (ticket != null) {
                log.debug("[查询票种详情] 命中本地缓存，ticketId:{}", ticketId);
                return buildTicketDetailResponse(ticket);
            }

            // 3. 查询票种基本信息
            ticket = getById(ticketId);
            if (ticket == null) {
                log.warn("[查询票种详情] 票种不存在，ticketId:{}", ticketId);
                return Result.fail(AppMessages.ORDER_TICKET_NOT_EXIST);
            }

            localTicketCache.put(ticketId, ticket);

            // 3. 设置isSeckill标记
            ticket.setIsSeckill(TicketType.isSeckill(ticket.getType()));

            // 4. 如果是秒杀票，补充秒杀信息
            if (TicketType.isSeckill(ticket.getType())) {
                try {
                    SeckillTicket seckillTicket = seckillTicketService.getById(ticketId);
                    if (seckillTicket != null) {
                        ticket.setBeginTime(seckillTicket.getBeginTime());
                        ticket.setEndTime(seckillTicket.getEndTime());
                        ticket.setSeckillStock(seckillTicket.getStock());

                        // 从Redis获取实时库存（覆盖DB库存）
                        String redisStockStr = stringRedisTemplate.opsForValue()
                                .get(SECKILL_STOCK_KEY + ticketId);
                        if (redisStockStr != null && !redisStockStr.isEmpty()) {
                            ticket.setSeckillStock(Integer.parseInt(redisStockStr));
                        }
                    }
                } catch (Exception e) {
                    log.warn("[查询票种详情] 获取秒杀信息失败，ticketId:{}, error:{}", ticketId, e.getMessage());
                }
            }

            // 5. 判断并设置可售卖状态
            ticket.setIsActive(isTicketActiveInternal(ticket));

            // 6.  计算已售数量和剩余库存（修复：详情页也需要显示正确的库存信息）
            setSoldCountAndRemainingStock(List.of(ticket));

            log.info("[查询票种详情] 成功，ticketId:{}, title:{}, status:{}, isActive:{}, soldCount:{}",
                    ticketId, ticket.getTitle(), ticket.getStatus(), ticket.getIsActive(), ticket.getSoldCount());

            return Result.ok(ticket);

        } catch (Exception e) {
            log.error("[查询票种详情] 异常，ticketId:{}, error:{}", ticketId, e.getMessage(), e);
            return Result.fail(AppMessages.TICKET_DETAIL_QUERY_FAIL);
        }
    }

    private Result<?> buildTicketDetailResponse(Ticket ticket) {
        try {
            if (ticket == null) {
                return Result.fail(AppMessages.ORDER_TICKET_NOT_EXIST);
            }

            ticket.setIsSeckill(TicketType.isSeckill(ticket.getType()));

            if (TicketType.isSeckill(ticket.getType())) {
                try {
                    SeckillTicket seckillTicket = seckillTicketService.getById(ticket.getId());
                    if (seckillTicket != null) {
                        ticket.setBeginTime(seckillTicket.getBeginTime());
                        ticket.setEndTime(seckillTicket.getEndTime());
                        ticket.setSeckillStock(seckillTicket.getStock());

                        String redisStockStr = stringRedisTemplate.opsForValue()
                                .get(SECKILL_STOCK_KEY + ticket.getId());
                        if (redisStockStr != null) {
                            try {
                                int redisStock = Integer.parseInt(redisStockStr.trim());
                                ticket.setSeckillStock(redisStock);
                                log.debug("[查询票种详情] 使用Redis实时库存，ticketId:{}, redisStock:{}",
                                        ticket.getId(), redisStock);
                            } catch (NumberFormatException ex) {
                                log.warn("[查询票种详情] Redis库存格式异常:{}", redisStockStr);
                            }
                        }

                        setSoldCountAndRemainingStock(List.of(ticket));
                    }
                } catch (Exception e) {
                    log.warn("[查询票种详情] 查询秒杀信息异常（返回基础信息），error:{}", e.getMessage());
                }
            }

            setSoldCountAndRemainingStock(List.of(ticket));
            return Result.ok(ticket);

        } catch (Exception e) {
            log.error("[构建票种详情响应] 异常，error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.TICKET_DETAIL_QUERY_FAIL);
        }
    }

    /**
     * 验证票种是否可售卖（供其他Service调用，内部方法）
     * <p>
     * 判断逻辑：
     * 1. 必须存在且状态为1（上架）
     * 2. 普通票：上架即可售卖
     * 3. 秒杀票：必须在时间窗口内（beginTime <= now <= endTime）
     *
     * @param ticketId 票种ID
     * @return true=可售卖, false=不可售卖
     */

    /**
     * 统一同步秒杀库存到Redis（供Controller层调用）
     * <p>
     * 使用场景：
     * - 创建秒杀票后初始化Redis库存
     * - 编辑秒杀票时更新Redis库存
     * - 调整库存时同步Redis
     * - 批量生成座位后更新总库存
     * <p>
     * 设计原则：
     * - 所有涉及库存变更的操作都必须调用此方法
     * - 保证MySQL与Redis数据一致性
     * - Redis操作失败不影响主流程（允许后续补偿修复）
     *
     * @param ticketId 票种ID
     * @param stock    新的库存数量（来自tb_seckill_ticket.stock或计算值）
     */
    public void syncSeckillStockToRedis(Long ticketId, Integer stock) {
        if (ticketId == null || stock == null || stock < 0) {
            log.warn("[Redis同步] 参数无效，跳过同步 - ticketId:{}, stock:{}", ticketId, stock);
            return;
        }

        try {
            String redisKey = SECKILL_STOCK_KEY + ticketId;

            com.echo.entity.SeckillTicket seckillTicket = seckillTicketService.getById(ticketId);
            long dynamicTtlHours = (seckillTicket != null)
                    ? calculateDynamicTtlForSeckill(seckillTicket)
                    : 48L;  // 默认48小时（最小安全值）

            stringRedisTemplate.opsForValue().set(
                    redisKey,
                    String.valueOf(stock),
                    dynamicTtlHours,
                    java.util.concurrent.TimeUnit.HOURS
            );
            log.info("[Redis同步] 秒杀库存已同步 - key:{}, stock:{}, TTL:{}小时", redisKey, stock, dynamicTtlHours);
        } catch (Exception e) {
            log.error("[Redis同步] 同步失败（非致命，可通过补偿机制修复） - ticketId:{}, stock:{}, error:{}",
                    ticketId, stock, e.getMessage());
        }
    }

    /**
     * 清除秒杀票Redis库存（用于删除票种或重置场景）
     *
     * @param ticketId 票种ID
     */
    public void clearSeckillStockFromRedis(Long ticketId) {
        if (ticketId == null) return;

        try {
            String redisKey = SECKILL_STOCK_KEY + ticketId;
            stringRedisTemplate.delete(redisKey);
            log.info("[Redis清除] 秒杀库存已清除 - key:{}", redisKey);
        } catch (Exception e) {
            log.warn("[Redis清除] 清除失败（非致命） - ticketId:{}, error:{}", ticketId, e.getMessage());
        }
    }

    @Override
    public boolean isTicketActiveForPurchase(Long ticketId) {
        try {
            if (ticketId == null || ticketId <= 0) {
                return false;
            }

            Ticket ticket = getById(ticketId);
            if (ticket == null) {
                return false;
            }

            return isTicketActiveInternal(ticket);

        } catch (Exception e) {
            log.error("[验证票种可售卖状态] 异常，ticketId:{}, error:{}", ticketId, e.getMessage(), e);
            return false;  // 异常时默认不可售卖（安全优先）
        }
    }

    /**
     * 内部方法：判断票种是否处于"可售卖状态"
     *
     * @param ticket 票种对象
     * @return true=可售卖, false=不可售卖
     */
    private boolean isTicketActiveInternal(Ticket ticket) {
        if (ticket == null) {
            return false;
        }

        // 1. 必须先上架（status === 1）
        if (ticket.getStatus() == null || ticket.getStatus() != 1) {
            return false;
        }

        // 2. 普通票：上架即视为可售卖
        if (!TicketType.isSeckill(ticket.getType())) {
            return true;  // 已上架 = 正在销售
        }

        // 3. 秒杀票：必须检查时间窗口
        try {
            SeckillTicket seckillTicket = seckillTicketService.getById(ticket.getId());
            if (seckillTicket == null) {
                log.warn("[判断可售卖状态] 秒杀票配置不存在，ticketId:{}", ticket.getId());
                return false;
            }

            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            java.time.LocalDateTime beginTime = seckillTicket.getBeginTime();
            java.time.LocalDateTime endTime = seckillTicket.getEndTime();

            java.time.Duration bufferTime = java.time.Duration.ofSeconds(5);
            java.time.LocalDateTime bufferedBeginTime = beginTime != null ? beginTime.minus(bufferTime) : null;
            java.time.LocalDateTime bufferedEndTime = endTime != null ? endTime.plus(bufferTime) : null;

            if (bufferedBeginTime != null && now.isBefore(bufferedBeginTime)) {
                log.debug("[判断可售卖状态] 秒杀尚未开始（含{}秒缓冲），ticketId:{}, beginTime:{}, bufferedBeginTime:{}, now:{}",
                        bufferTime.getSeconds(), ticket.getId(), beginTime, bufferedBeginTime, now);
                return false;
            }

            if (bufferedEndTime != null && now.isAfter(bufferedEndTime)) {
                log.debug("[判断可售卖状态] 秒杀已结束（含{}秒缓冲），ticketId:{}, endTime:{}, bufferedEndTime:{}, now:{}",
                        bufferTime.getSeconds(), ticket.getId(), endTime, bufferedEndTime, now);
                return false;  // 已结束
            }

            String stockKey = SECKILL_STOCK_KEY + ticket.getId();
            String stockStr = null;
            try {
                stockStr = stringRedisTemplate.opsForValue().get(stockKey);
                if (stockStr == null) {
                    log.warn("[判断可售卖状态] Redis库存键缺失（可能未预热或已过期），ticketId:{}, stockKey:{}，采用fail-closed策略：默认不可售卖",
                            ticket.getId(), stockKey);
                    return false;
                }

                int currentStock = Integer.parseInt(stockStr.trim());
                if (currentStock <= 0) {
                    log.info("[判断可售卖状态] 秒杀票已售罄（Redis库存=0），ticketId:{}", ticket.getId());
                    return false;  //  已售罄
                }
            } catch (NumberFormatException e) {
                log.warn("[判断可售卖状态] Redis库存格式异常，ticketId:{}, stockStr:{}，采用fail-closed策略：默认不可售卖",
                        ticket.getId(), stockStr);
                return false;
            }

            return true;

        } catch (Exception e) {
            log.error("[判断可售卖状态] 校验秒杀时间失败，ticketId:{}, error:{}", ticket.getId(), e.getMessage(), e);
            return false;  // 异常时默认不可售卖
        }
    }
}
