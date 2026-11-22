package com.echo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.echo.entity.SeckillTicket;
import com.echo.entity.Show;
import com.echo.entity.Ticket;
import com.echo.enums.NotificationType;
import com.echo.enums.ShowStatus;
import com.echo.enums.TicketStatus;
import com.echo.message.CacheInvalidateMessage;
import com.echo.service.IScheduledPublishService;
import com.echo.service.ISeckillTicketService;
import com.echo.service.IShowService;
import com.echo.service.ITicketService;
import com.echo.utils.MqConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.echo.utils.RedisConstants.*;

/**
 * 定时发布服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class ScheduledPublishServiceImpl implements IScheduledPublishService {

    @Resource
    private ITicketService ticketService;

    @Resource
    private ISeckillTicketService seckillTicketService;

    @Resource
    private IShowService showService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private org.redisson.api.RedissonClient redissonClient;

    /** 定时任务全局分布式锁 Key（多实例防并发重复执行） */
    private static final String SCHEDULED_PUBLISH_LOCK_KEY = "lock:scheduled:publish";
    /** 锁持有时间（秒），超过后自动释放，防止持锁节点宕机导致死锁 */
    private static final long LOCK_HOLD_SECONDS = 60L;
    /** 锁等待时间（秒），获取锁失败后最多等待这么久 */
    private static final long LOCK_WAIT_SECONDS = 5L;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkAndExecuteScheduledPublish() {
        RLock lock = redissonClient.getLock(SCHEDULED_PUBLISH_LOCK_KEY);
        boolean locked = false;

        try {
            // 【修复】获取分布式锁，多实例部署时只有一个节点执行定时任务
            locked = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_HOLD_SECONDS, TimeUnit.SECONDS);

            if (!locked) {
                log.debug("[定时发布] 未获取到分布式锁，跳过本次执行（其他节点正在处理）");
                return;
            }

            log.info("[定时发布] 已获取分布式锁，开始检查待发布任务...");
            LocalDateTime now = LocalDateTime.now();

            publishTickets(now);
            activateSeckillActivities(now);
            deactivateExpiredSeckillActivities(now);
            publishShows(now);
            log.info("[定时发布] 所有任务检查完成");

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("[定时发布] 获取锁被中断", ie);
        } catch (Exception e) {
            log.error("[定时发布] 执行任务失败", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                    log.debug("[定时发布] 分布式锁已释放");
                } catch (Exception unlockEx) {
                    log.warn("[定时发布] 释放锁失败: {}", unlockEx.getMessage());
                }
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeImmediately() {
        log.info("[手动触发] 立即执行所有待发布任务");
        checkAndExecuteScheduledPublish();
    }

    /**
     * 票种定时上架
     */
    private void publishTickets(LocalDateTime now) {
        QueryWrapper<Ticket> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", TicketStatus.OFFLINE.getCode())
                .isNotNull("scheduled_publish_time")
                .le("scheduled_publish_time", now);

        long count = ticketService.count(queryWrapper);
        if (count > 0) {
            UpdateWrapper<Ticket> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("status", TicketStatus.OFFLINE.getCode())
                    .isNotNull("scheduled_publish_time")
                    .le("scheduled_publish_time", now)
                    .set("status", TicketStatus.ONLINE.getCode())
                    .set("update_time", now);

            boolean success = ticketService.update(updateWrapper);
            if (success) {
                log.info("[票种定时上架] 成功上架 {} 个票种", count);

                // 【修复】清除所有被上架票种关联的演出缓存
                try {
                    List<Ticket> publishedTickets = ticketService.list(queryWrapper);
                    for (Ticket ticket : publishedTickets) {
                        if (ticket.getShowId() != null) {
                            invalidateTicketCache(ticket.getShowId());
                        }
                    }
                    log.info("[票种定时上架] 已清理 {} 个票种的关联演出缓存", publishedTickets.size());
                } catch (Exception cacheEx) {
                    log.warn("[票种定时上架] 缓存清理失败（不影响主流程）: {}", cacheEx.getMessage());
                }
            } else {
                log.warn("[票种定时上架] 更新失败");
            }
        } else {
            log.debug("[票种定时上架] 无需上架的票种");
        }
    }

    /**
     * 秒杀活动定时开始
     */
    private void activateSeckillActivities(LocalDateTime now) {
        QueryWrapper<SeckillTicket> queryWrapper = new QueryWrapper<>();
        queryWrapper.isNotNull("scheduled_start_time")
                .le("scheduled_start_time", now);

        long count = seckillTicketService.count(queryWrapper);
        if (count > 0) {
            UpdateWrapper<SeckillTicket> updateWrapper = new UpdateWrapper<>();
            updateWrapper.isNotNull("scheduled_start_time")
                    .le("scheduled_start_time", now)
                    .set("begin_time", now)
                    .set("scheduled_start_time", null)
                    .set("update_time", now);

            boolean success = seckillTicketService.update(updateWrapper);
            if (success) {
                log.info("[秒杀定时激活] 成功激活 {} 个秒杀活动", count);

                // 【修复】清除激活的秒杀票关联的演出缓存
                try {
                    List<SeckillTicket> activatedSeckills = seckillTicketService.list(queryWrapper);
                    for (SeckillTicket seckill : activatedSeckills) {
                        Ticket ticket = ticketService.getById(seckill.getTicketId());
                        if (ticket != null && ticket.getShowId() != null) {
                            invalidateTicketCache(ticket.getShowId());
                        }
                    }
                    log.info("[秒杀定时激活] 已清理 {} 个秒杀票的关联演出缓存", activatedSeckills.size());
                } catch (Exception cacheEx) {
                    log.warn("[秒杀定时激活] 缓存清理失败（不影响主流程）: {}", cacheEx.getMessage());
                }
            } else {
                log.warn("[秒杀定时激活] 更新失败");
            }
        } else {
            log.debug("[秒杀定时激活] 无需激活的秒杀活动");
        }
    }

    /**
     * 秒杀活动过期自动下架
     * <p>
     * 检查所有已结束的秒杀票（endTime <= now），自动将其状态改为下架（status=0）
     * 确保过期的秒杀票不再对用户可见和可购买
     */
    private void deactivateExpiredSeckillActivities(LocalDateTime now) {
        // 1. 查找所有已过期的秒杀活动
        QueryWrapper<SeckillTicket> seckillQuery = new QueryWrapper<>();
        seckillQuery.isNotNull("end_time")
                .le("end_time", now);

        long expiredSeckillCount = seckillTicketService.count(seckillQuery);

        if (expiredSeckillCount > 0) {
            log.info("[秒杀自动下架] 发现 {} 个已过期的秒杀活动", expiredSeckillCount);

            // 2. 获取所有过期秒杀的 ticketId 列表（用于后续清理缓存）
            List<SeckillTicket> expiredSeckills = seckillTicketService.list(seckillQuery);
            List<Long> expiredTicketIds = expiredSeckills.stream()
                    .map(SeckillTicket::getTicketId)
                    .collect(java.util.stream.Collectors.toList());

            // 3. 批量下架这些票种（status=1 → status=0）
            UpdateWrapper<Ticket> ticketUpdateWrapper = new UpdateWrapper<>();
            ticketUpdateWrapper.in("id", expiredTicketIds)
                    .eq("status", TicketStatus.ONLINE.getCode())
                    .set("status", TicketStatus.OFFLINE.getCode())
                    .set("update_time", now);

            boolean updateSuccess = ticketService.update(ticketUpdateWrapper);

            if (updateSuccess) {
                log.info("[秒杀自动下架] 成功下架 {} 个过期秒杀票种, ticketIds: {}",
                        expiredTicketIds.size(), expiredTicketIds);
            } else {
                log.warn("[秒杀自动下架] 批量更新返回 false（可能已全部下架或无匹配记录）");
            }

            // 4. 【修复】真正清理相关缓存：清除 Redis + 广播 Caffeine 失效
            try {
                for (Long ticketId : expiredTicketIds) {
                    Ticket ticket = ticketService.getById(ticketId);
                    if (ticket != null && ticket.getShowId() != null) {
                        Long showId = ticket.getShowId();

                        // 清除 Redis 演出票种缓存
                        String ticketCacheKey = CACHE_TICKET_KEY + showId;
                        stringRedisTemplate.delete(ticketCacheKey);
                        log.debug("[秒杀自动下架] 已清除 Redis 票种缓存 - showId:{}, key:{}",
                                showId, ticketCacheKey);

                        // 清除 Redis 演出详情缓存
                        String showCacheKey = CACHE_SHOW_KEY + showId;
                        stringRedisTemplate.delete(showCacheKey);
                        log.debug("[秒杀自动下架] 已清除 Redis 演出详情缓存 - showId:{}, key:{}",
                                showId, showCacheKey);

                        // 通过 RocketMQ 广播通知所有节点清除本地 Caffeine 缓存
                        CacheInvalidateMessage msg = CacheInvalidateMessage.of(CACHE_SHOW_KEY, showId);
                        rocketMQTemplate.syncSend(MqConstants.CACHE_INVALIDATE_TOPIC, msg);
                        log.debug("[秒杀自动下架] RocketMQ 广播已发送，Caffeine 本地缓存将失效 - showId:{}",
                                showId);
                    }
                }
                log.info("[秒杀自动下架] 缓存清理完成，ticketIds: {}", expiredTicketIds);
            } catch (Exception cacheEx) {
                log.warn("[秒杀自动下架] 缓存清理失败（不影响主流程）: {}", cacheEx.getMessage());
            }

        } else {
            log.debug("[秒杀自动下架] 无需下架的过期秒杀活动");
        }
    }

    /**
     * 演出定时发布（完整版：含GEO索引重建 + 关注者通知）
     *
     * 核心改进：
     *  更新status为已发布（status=1）
     *  清理Redis缓存 + 广播Caffeine失效
     *  重建GEO地理位置索引（与手动上架路径一致）
     *  发送开售通知给关注者（替代空TODO）
     *  失败隔离：每个操作独立try-catch，互不影响
     */
    private void publishShows(LocalDateTime now) {
        QueryWrapper<Show> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", ShowStatus.OFFLINE.getCode())
                .isNotNull("scheduled_publish_time")
                .le("scheduled_publish_time", now);

        long count = showService.count(queryWrapper);
        if (count > 0) {
            UpdateWrapper<Show> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("status", ShowStatus.OFFLINE.getCode())
                    .isNotNull("scheduled_publish_time")
                    .le("scheduled_publish_time", now)
                    .set("status", ShowStatus.ONLINE.getCode())
                    .set("update_time", now);

            boolean success = showService.update(updateWrapper);
            if (success) {
                log.info("[演出定时发布] 成功发布 {} 个演出", count);

                // 获取已发布的演出列表（用于后续GEO和通知处理）
                List<Show> publishedShows = showService.list(queryWrapper);

                // 【步骤1】清理缓存（Redis + Caffeine广播）
                try {
                    for (Show show : publishedShows) {
                        if (show.getId() != null) {
                            invalidateShowCache(show.getId());
                        }
                    }
                    log.info("[演出定时发布] 已清理 {} 个演出的缓存", publishedShows.size());
                } catch (Exception cacheEx) {
                    log.warn("[演出定时发布] 缓存清理失败（不影响主流程）: {}", cacheEx.getMessage());
                }

                // 【步骤2】重建GEO地理位置索引（关键！确保LBS查询可用）
                try {
                    rebuildGeoIndexForShows(publishedShows);
                    log.info("[演出定时发布] 已重建 {} 个演出的GEO索引", publishedShows.size());
                } catch (Exception geoEx) {
                    log.error("[演出定时发布] GEO索引重建失败（影响LBS查询）: {}", geoEx.getMessage(), geoEx);
                }

                // 【步骤3】发送开售通知给关注者（核心业务逻辑）
                try {
                    notifyFollowersForPublishedShows(publishedShows, now);
                    log.info("[演出定时发布] 已发送关注者通知，共{}个演出", publishedShows.size());
                } catch (Exception notifyEx) {
                    log.error("[演出定时发布] 关注者通知失败: {}", notifyEx.getMessage(), notifyEx);
                }

            } else {
                log.warn("[演出定时发布] 更新失败");
            }
        } else {
            log.debug("[演出定时发布] 无需发布的演出");
        }
    }

    /**
     * 清理单个演出的缓存（Redis + Caffeine广播）
     *
     * @param showId 演出ID
     */
    private void invalidateShowCache(Long showId) {
        if (showId == null) return;

        // 清除 Redis 演出详情缓存
        String showCacheKey = CACHE_SHOW_KEY + showId;
        stringRedisTemplate.delete(showCacheKey);
        log.debug("[缓存清理] 已清除 Redis 演出缓存 - showId:{}, key:{}", showId, showCacheKey);

        // 广播 Caffeine 缓存失效（多节点同步）
        CacheInvalidateMessage msg = CacheInvalidateMessage.of(CACHE_SHOW_KEY, showId);
        rocketMQTemplate.syncSend(MqConstants.CACHE_INVALIDATE_TOPIC, msg);
        log.debug("[缓存清理] RocketMQ 广播已发送 - showId:{}", showId);
    }

    /**
     * 批量重建GEO地理位置索引（与AdminServiceImpl.online()保持一致）
     *
     * 核心逻辑：
     * 1. 遍历已发布的演出
     * 2. 提取经纬度信息（longitude, latitude）
     * 3. 写入Redis GEO集合（show:geo:）
     * 4. 支持后续LBS位置查询（附近演出推荐）
     *
     * @param shows 已发布的演出列表
     */
    private void rebuildGeoIndexForShows(List<Show> shows) {
        if (shows == null || shows.isEmpty()) return;

        int successCount = 0;
        int skipCount = 0;

        for (Show show : shows) {
            try {
                Double longitude = show.getX();
                Double latitude = show.getY();

                // 仅对有地理位置信息的演出建立索引
                if (longitude != null && latitude != null &&
                    longitude != 0.0 && latitude != 0.0) {

                    stringRedisTemplate.opsForGeo().add(
                            SHOW_GEO_KEY,
                            new RedisGeoCommands.GeoLocation<>(
                                    String.valueOf(show.getId()),
                                    new org.springframework.data.geo.Point(latitude, longitude)
                            )
                    );

                    successCount++;
                    log.debug("[GEO重建] 索引添加成功 - showId:{}, 经纬度:({},{})",
                            show.getId(), latitude, longitude);
                } else {
                    skipCount++;
                    log.debug("[GEO重建] 跳过无位置信息的演出 - showId:{}, x:{}, y:{}",
                            show.getId(), longitude, latitude);
                }
            } catch (Exception e) {
                log.warn("[GEO重建] 单条索引添加失败 - showId:{}, error:{}", show.getId(), e.getMessage());
            }
        }

        log.info("[GEO重建] 完成 - 总数:{}, 成功:{}, 跳过(无位置):{}",
                shows.size(), successCount, skipCount);
    }

    /**
     * 发送开售通知给关注者（完整实现，替代空TODO）
     *
     * 业务场景：
     * 用户A关注了演出X → 演出X定时上架 → 系统自动推送"您关注的演出已开售"
     *
     * 实现策略：
     * 1. 查询每个演出的关注者列表（Redis Set: show:followers:{showId}）
     * 2. 构建通知消息（使用统一的消息模板）
     * 3. 通过RocketMQ异步发送（高吞吐、削峰填谷）
     * 4. 记录通知统计日志
     *
     * @param publishedShows 已发布的演出列表
     * @param now 当前时间
     */
    private void notifyFollowersForPublishedShows(List<Show> publishedShows, LocalDateTime now) {
        if (publishedShows == null || publishedShows.isEmpty()) return;

        int totalNotifiedUsers = 0;
        int totalShowsProcessed = 0;

        for (Show show : publishedShows) {
            try {
                Long showId = show.getId();
                if (showId == null) continue;

                // 查询关注者ID列表（从Redis Set获取）
                String followersKey = SHOW_FOLLOWERS_KEY + showId;
                java.util.Set<String> followerIds = stringRedisTemplate.opsForSet().members(followersKey);

                if (followerIds == null || followerIds.isEmpty()) {
                    log.debug("[通知关注者] 演出无关注者 - showId:{}", showId);
                    continue;
                }

                totalShowsProcessed++;

                for (String followerIdStr : followerIds) {
                    try {
                        Long userId = Long.parseLong(followerIdStr);

                        // 构建通知消息体
                        com.echo.message.NotificationMessage notification = com.echo.message.NotificationMessage.builder()
                                .userId(userId)
                                .type(NotificationType.PAYMENT_SUCCESS)  // 支付成功通知类型
                                .title("🎭 您关注的演出已开售")
                                .content(String.format(
                                        "《%s》现已正式开售，快去抢购吧！",
                                        show.getName() != null ? show.getName() : "精彩演出"
                                ))
                                .relatedId(showId)
                                .createTime(LocalDateTime.now())
                                .build();

                        // 通过RocketMQ异步发送通知
                        rocketMQTemplate.convertAndSend(
                                "notification-topic",
                                notification
                        );

                        totalNotifiedUsers++;

                    } catch (NumberFormatException nfe) {
                        log.warn("[通知关注者] 关注者ID格式异常 - showId:{}, followerId:{}",
                                showId, followerIdStr);
                    } catch (Exception notifyEx) {
                        log.error("[通知关注者] 单条通知发送失败 - showId:{}, userId:{}, error:{}",
                                showId, followerIdStr, notifyEx.getMessage());
                    }
                }

                log.info("[通知关注者] 演出通知完成 - showId:{}, name:{}, 关注者数:{}, 已通知:{}人",
                        showId, show.getName(), followerIds.size(), totalNotifiedUsers);

            } catch (Exception showEx) {
                log.error("[通知关注者] 演出通知处理异常 - showId:{}, error:{}",
                        show.getId(), showEx.getMessage());
            }
        }

        log.info("[通知关注器] 定时发布通知统计 - 处理演出数:{}, 总通知用户数:{}",
                totalShowsProcessed, totalNotifiedUsers);
    }

    /**
     * 清除演出关联的票种缓存（内部方法）
     *
     * @param showId 演出ID
     */
    private void invalidateTicketCache(Long showId) {
        if (showId == null) {
            return;
        }
        try {
            // 清除 Redis 演出票种缓存
            String ticketCacheKey = CACHE_TICKET_KEY + showId;
            stringRedisTemplate.delete(ticketCacheKey);

            // 清除 Redis 演出详情缓存
            String showCacheKey = CACHE_SHOW_KEY + showId;
            stringRedisTemplate.delete(showCacheKey);

            // 广播 Caffeine 缓存失效
            CacheInvalidateMessage msg = CacheInvalidateMessage.of(CACHE_SHOW_KEY, showId);
            rocketMQTemplate.syncSend(MqConstants.CACHE_INVALIDATE_TOPIC, msg);

            log.debug("[缓存清理] 已清理演出缓存 - showId:{}", showId);
        } catch (Exception e) {
            log.warn("[缓存清理] 清理失败 - showId:{}, error:{}", showId, e.getMessage());
        }
    }

}
