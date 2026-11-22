package com.echo.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.dto.UserDTO;
import com.echo.entity.Show;
import com.echo.enums.ShowStatus;
import com.echo.mapper.ShowMapper;
import com.echo.mapper.TicketOrderMapper;
import com.echo.message.CacheInvalidateMessage;
import com.echo.service.IShowService;
import com.echo.utils.*;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.echo.utils.RedisConstants.*;

/**
 * 演出服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class ShowServiceImpl extends ServiceImpl<ShowMapper, Show> implements IShowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private Cache<String, Show> showLocalCache;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private org.springframework.context.ApplicationContext applicationContext;
    @Lazy
    @Resource
    private com.echo.service.ITicketOrderService ticketOrderService;
    @Lazy
    @Resource
    private TicketOrderMapper ticketOrderMapper;

    /**
     * 延迟双删-④：二次删除的RocketMQ延时级别（RocketMQ固定18个级别，3=10秒）。
     * 10秒窗口覆盖常规主从延迟（原500ms定值扛不住大事务/跨机房延迟）；
     * 极端延迟场景由缓存TTL（演出30±10分钟）兜底，需要更强保证则引入Canal订阅binlog。
     */
    @org.springframework.beans.factory.annotation.Value("${echo.cache.double-delete-delay-level:3}")
    private int doubleDeleteDelayLevel;

    /**
     * 查询演出详情（含三级缓存）
     * <p>
     * 查询SQL：
     *   SELECT v.*, sv.stock, sv.begin_time, sv.end_time
     *   FROM tb_show v
     *   LEFT JOIN tb_seckill_ticket sv ON v.id = sv.ticket_id
     *   WHERE v.id = {id}
     * <p>
     * 缓存查询流程：
     * 1. Caffeine本地缓存: cache:show:{id}
     * 2. Redis分布式缓存: cache:show:{id}
     * 3. MySQL数据库: tb_show
     *
     * @param id 演出ID
     * @return 演出详情（含票种列表）
     */
    @Override
    public Result<?> listShows() {
        log.debug("[演出列表] 查询所有演出（兼容接口）");
        List<Show> shows = this.list(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Show>()
                .eq(Show::getStatus, ShowStatus.ONLINE.getCode())
                .orderByDesc(Show::getCreateTime));
        fillShowSoldBatch(shows);
        return Result.ok(shows);
    }

    @Override
    public Result<?> queryById(Long id, boolean userOnly) {
        if (id == null || id <= 0) {
            log.warn("[演出查询] 参数错误，id:{}", id);
            return Result.fail(AppMessages.SHOW_ID_INVALID);
        }

        log.debug("[演出查询] 开始两级缓存查询，id:{}, userOnly:{}", id, userOnly);
        String cacheKey = CACHE_SHOW_KEY + id;

        Show show = showLocalCache.getIfPresent(cacheKey);
        if (show != null) {
            if (userOnly && !ShowStatus.isOnline(show.getStatus())) {
                log.debug("[用户端过滤] 演出已下架 - id:{}, status:{}", id, show.getStatus());
                return Result.fail("该演出已下架");
            }
            log.debug("[L1命中] Caffeine本地缓存命中，id:{}", id);
            return Result.ok(show);
        }

        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(json)) {
            show = JSONUtil.toBean(json, Show.class);
            if (userOnly && !ShowStatus.isOnline(show.getStatus())) {
                log.debug("[用户端过滤] 演出已下架（Redis缓存） - id:{}, status:{}", id, show.getStatus());
                return Result.fail("该演出已下架");
            }
            log.debug("[L2命中] Redis缓存命中，id:{}, 回填L1", id);
            showLocalCache.put(cacheKey, show);
            return Result.ok(show);
        }

        // json 为 null 表示缓存未命中（需查DB）；json 为 "" 表示空值缓存命中（演出不存在）
        if (json != null) {
            return Result.fail(AppMessages.SHOW_NOT_EXIST);
        }

        // 缓存完全未命中：进入互斥锁保护的缓存重建，防止热点Key失效瞬间大量请求穿透到数据库（缓存击穿）
        try {
            show = cacheClient.queryWithMutex(CACHE_SHOW_KEY, id, Show.class,
                    this::loadShowFromDb, CACHE_SHOW_TTL, CACHE_SHOW_RANDOM_TTL, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[演出查询] 缓存重建等待被中断，id:{}", id);
            return Result.fail(AppMessages.SYSTEM_BUSY);
        }

        if (show == null) {
            // queryWithMutex返回null有两种来源：空值缓存已写入（演出不存在）或重建等待超时（系统繁忙）
            String cachedAfterRebuild = stringRedisTemplate.opsForValue().get(cacheKey);
            if ("".equals(cachedAfterRebuild)) {
                log.warn("[演出不存在] 未找到演出信息，id:{}", id);
                return Result.fail(AppMessages.SHOW_NOT_EXIST);
            }
            log.warn("[演出查询] 缓存重建等待超时，id:{}", id);
            return Result.fail(AppMessages.SYSTEM_BUSY);
        }

        if (userOnly && !ShowStatus.isOnline(show.getStatus())) {
            log.warn("[用户端过滤] 演出已下架（数据库） - id:{}, status:{}", id, show.getStatus());
            return Result.fail("该演出已下架");
        }

        log.debug("[DB回源] 缓存重建完成，id:{}，回填L1", id);
        showLocalCache.put(cacheKey, show);
        return Result.ok(show);
    }

    /**
     * 数据库回源并组装缓存数据（互斥锁内的缓存重建逻辑，仅持锁线程执行）
     * <p>
     * 由 CacheClient.queryWithMutex 调用：持锁线程查询数据库并写入Redis（随机TTL防雪崩），
     * 其他等待线程在锁释放后直接命中缓存返回。
     */
    private Show loadShowFromDb(Long id) {
        Show show = getById(id);
        if (show == null) {
            return null;
        }
        fillSoldCount(show, id);
        ensureAvgPrice(show, id);
        return show;
    }

    /**
     * 实时计算已售数量（直接使用 show_id 字段查询订单表）
     */
    private void fillSoldCount(Show show, Long id) {
        try {
            Long soldCount = ticketOrderService.count(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.TicketOrder>()
                    .eq("show_id", id)
                    .in("status", 2, 3)  // 2=已支付, 3=已核销
            );
            show.setSold(soldCount.intValue());
            log.info("[演出查询] 已售数量计算完成 - showId:{}, sold:{}", id, soldCount);
        } catch (Exception e) {
            log.warn("[演出查询] 计算已售数量失败，使用数据库默认值 - showId:{}, error:{}", id, e.getMessage());
        }
    }

    /**
     * 平均票价兜底：管理员未预计算（或新演出）时懒加载计算一次并持久化
     */
    private void ensureAvgPrice(Show show, Long id) {
        if (show.getAvgPrice() == null || show.getAvgPrice() == 0) {
            log.info("[平均票价-兜底] 演出{}的avgPrice为空，触发懒加载计算", id);
            calculateAndSaveAvgPrice(id);
        } else {
            log.debug("[平均票价-命中] 演出{}的avgPrice已预计算: {}分（直接使用）", id, show.getAvgPrice());
        }
    }

    public void saveShow2Redis(Long id, Long expireSeconds) {
        log.info("[缓存预热] 开始将演出写入Redis，id:{}, TTL:{}秒", id, expireSeconds);
        Show show = getById(id);
        if (show == null) {
            log.warn("[预热失败] 演出不存在，id:{}", id);
            return;
        }
        // 【修复】预热格式改为直接存 Show 对象，与 queryById 读取格式保持一致
        // 原代码写入 RedisData 导致 queryById 读 Show 时反序列化失败
        String jsonStr = JSONUtil.toJsonStr(show);
        stringRedisTemplate.opsForValue().set(CACHE_SHOW_KEY + id, jsonStr, expireSeconds, TimeUnit.SECONDS);
        log.info("[预热成功] 演出已缓存到Redis，key:{}, dataLength:{}字节", CACHE_SHOW_KEY + id, jsonStr.length());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> update(Show show) {
        Long id = show.getId();
        log.info("[演出更新] 开始延迟双删+MQ广播流程，id:{}", id);

        if (id == null) {
            log.error("[更新失败] 演出ID不能为空");
            return Result.fail(AppMessages.SHOW_ID_NOT_EMPTY);
        }

        Show existingShow = getById(id);
        if (existingShow == null) {
            log.error("[更新失败] 演出不存在，id:{}", id);
            return Result.fail(AppMessages.SHOW_NOT_EXIST);
        }

        if (show.getName() != null) existingShow.setName(show.getName());
        if (show.getTypeId() != null) existingShow.setTypeId(show.getTypeId());
        if (show.getImages() != null) existingShow.setImages(show.getImages());
        if (show.getDescription() != null) existingShow.setDescription(show.getDescription());
        if (show.getOpenTime() != null) existingShow.setOpenTime(show.getOpenTime());
        if (show.getEndTime() != null) existingShow.setEndTime(show.getEndTime());
        if (show.getStatus() != null) existingShow.setStatus(show.getStatus());
        if (show.getAvgPrice() != null) existingShow.setAvgPrice(show.getAvgPrice());
        if (show.getArea() != null) existingShow.setArea(show.getArea());
        if (show.getAddress() != null) existingShow.setAddress(show.getAddress());

        try {
            String redisKey = CACHE_SHOW_KEY + id;
            stringRedisTemplate.delete(redisKey);
            log.debug("[延迟双删-①] 第一次删除Redis缓存，key:{}", redisKey);

            boolean dbSuccess = updateById(existingShow);
            if (!dbSuccess) {
                log.error("[更新失败] 数据库更新失败，id:{}", id);
                return Result.fail(AppMessages.SHOW_UPDATE_FAIL);
            }
            log.debug("[延迟双删-②] 数据库更新成功，id:{}", id);

            CacheInvalidateMessage message = CacheInvalidateMessage.of(CACHE_SHOW_KEY, id);
            rocketMQTemplate.syncSend(MqConstants.CACHE_INVALIDATE_TOPIC, message);
            log.info("[延迟双删-③] RocketMQ消息已发送，通知所有节点删除本地与Redis缓存，showId:{}", id);

            // [延迟双删-④] 二次删除改用RocketMQ延时消息（替代原Thread.sleep(500)硬编码）：
            // - 10秒窗口远大于500ms，覆盖大事务/跨机房等较高的主从延迟场景
            // - 消息持久化在broker，应用在窗口内重启不丢删除任务（原CompletableFuture方案会丢）
            // - 消费失败自动重试，且不占用任何业务线程
            rocketMQTemplate.syncSend(MqConstants.CACHE_INVALIDATE_TOPIC,
                    org.springframework.messaging.support.MessageBuilder.withPayload(message).build(),
                    3000, doubleDeleteDelayLevel);
            log.info("[延迟双删-④] 延时删除消息已发送，delayLevel:{}（对应约10秒），showId:{}",
                    doubleDeleteDelayLevel, id);

            return Result.ok();
        } catch (Exception e) {
            log.error("[更新异常] 演出更新发生异常，id:{}, error:{}", id, e.getMessage(), e);
            throw new com.echo.exception.BusinessException(
                    AppMessages.SHOW_UPDATE_FAIL,
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    e);
        }
    }

    /**
     * 按演出类型查询列表（支持LBS距离排序）
     * <p>
     * 数据库表：tb_show
     * 查询SQL示例：
     *   -- 无坐标时（普通分页查询）
     *   SELECT * FROM tb_show WHERE type_id = ? AND status = 1 LIMIT ?, ?
     * <p>
     *   -- 有坐标时（全量查出后按距离排序）
     *   SELECT * FROM tb_show WHERE type_id = ? AND status = 1
     * <p>
     * 距离计算算法：Haversine公式（球面距离）
     * - 使用Redis GEO存储演出的经纬度坐标
     * - 根据用户位置(x,y)逐个计算每个演出的距离
     * - 返回结果按距离从近到远排序
     *
     * @param typeId 演出类型ID（1:演唱会 2:话剧 3:展览...）
     * @param pageQuery 分页参数（页码与每页条数）
     * @param x 用户经度（可选，为null时不启用距离排序）
     * @param y 用户纬度（可选，为null时不启用距离排序）
     * @return 分页的演出列表（含距离信息）
     */
    @Override
    public Result<?> queryShowByType(Integer typeId, com.echo.dto.PageQuery pageQuery, Double x, Double y) {
        int current = pageQuery.getCurrent();
        int size = pageQuery.getSize();

        if (x == null || y == null) {
            Page<Show> page = query()
                    .eq("type_id", typeId)
                    .eq("status", ShowStatus.ONLINE.getCode())
                    .page(new Page<>(current, size));
            fillShowSoldBatch(page.getRecords());
            Map<String, Object> result = new HashMap<>();
            result.put("records", page.getRecords());
            result.put("total", page.getTotal());
            result.put("current", page.getCurrent());
            result.put("size", page.getSize());
            result.put("pages", page.getPages());
            return Result.ok(result);
        }

        List<Show> allShows = query()
                .eq("type_id", typeId)
                .eq("status", ShowStatus.ONLINE.getCode())
                .list();

        if (allShows.isEmpty()) {
            Map<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("records", Collections.emptyList());
            emptyResult.put("total", 0L);
            emptyResult.put("current", current);
            emptyResult.put("size", size);
            emptyResult.put("pages", 0);
            return Result.ok(emptyResult);
        }

        fillShowSoldBatch(allShows);

        String key = SHOW_GEO_KEY + typeId;
        final double userLng = x;
        final double userLat = y;

        List<Show> sortedShows = allShows.stream()
                .peek(show -> calculateAndSetShowDistance(show, key, userLat, userLng))
                .sorted(this::compareByDistance)
                .toList();

        int total = sortedShows.size();
        int totalPages = (int) Math.ceil((double) total / size);
        int from = (current - 1) * size;
        int to = Math.min(current * size, total);

        List<Show> pagedShows = from < to ? sortedShows.subList(from, to) : Collections.emptyList();

        Map<String, Object> result = new HashMap<>();
        result.put("records", pagedShows);
        result.put("total", (long) total);
        result.put("current", current);
        result.put("size", size);
        result.put("pages", totalPages);

        log.info("[分类+距离] 类型ID:{}, 总数:{}, 当前页:{}, 每页:{}, 范围:{}-{}",
                typeId, total, current, size, from, to);

        return Result.ok(result);
    }

    @Override
    public Result<?> queryShowByName(String name, Long id, com.echo.dto.PageQuery pageQuery, boolean userOnly) {
        QueryWrapper<Show> wrapper = new QueryWrapper<>();

        if (id != null && id > 0) {
            wrapper.eq("id", id);
        } else if (name != null && !name.isBlank()) {
            wrapper.like("name", name);
        }

        // 用户端强制过滤：仅返回上架演出（status=1）
        if (userOnly) {
            wrapper.eq("status", ShowStatus.ONLINE.getCode());
            log.debug("[用户端查询] 强制过滤上架演出 - name:{}, id:{}", name, id);
        }

        // 使用 PageQuery 的排序参数
        String sortBy = pageQuery.getSortBy();
        String sortOrder = pageQuery.getSortOrder();

        if (sortBy != null && !sortBy.isBlank()) {
            boolean asc = "asc".equalsIgnoreCase(sortOrder);
            switch (sortBy) {
                case "id":
                    wrapper.orderBy(true, asc, "id");
                    break;
                case "name":
                    wrapper.orderBy(true, asc, "name");
                    break;
                case "typeId":
                    wrapper.orderBy(true, asc, "type_id");
                    break;
                case "status":
                    wrapper.orderBy(true, asc, "status");
                    break;
                case "createTime":
                    wrapper.orderBy(true, asc, "create_time");
                    break;
                case "updateTime":
                    wrapper.orderBy(true, asc, "update_time");
                    break;
                default:
                    wrapper.orderByDesc("create_time");
                    break;
            }
        } else {
            wrapper.orderByDesc("create_time");
        }

        // 使用 PageQuery 创建分页对象
        Page<Show> page = page(pageQuery.toTypedPage(), wrapper);
        fillShowSoldBatch(page.getRecords());

        // 使用 PageResult 统一返回格式
        return Result.ok(com.echo.dto.PageResult.from(page));
    }

    @Override
    public Result<?> loadShowGeoData() {
        log.info("[GEO加载] 开始将演出坐标加载到Redis GEO索引");
        List<Show> showList = list();
        if (showList.isEmpty()) return Result.fail(AppMessages.SHOW_NO_DATA);

        Map<Long, List<Show>> typeShowMap = new HashMap<>();
        for (Show show : showList) {
            if (show.getX() == null || show.getY() == null) continue;
            typeShowMap.computeIfAbsent(show.getTypeId(), k -> new ArrayList<>()).add(show);
        }

        int totalCount = 0;
        for (Map.Entry<Long, List<Show>> entry : typeShowMap.entrySet()) {
            String geoKey = SHOW_GEO_KEY + entry.getKey();
            stringRedisTemplate.delete(geoKey);
            for (Show show : entry.getValue()) {
                stringRedisTemplate.opsForGeo().add(geoKey, new Point(show.getX(), show.getY()), show.getId().toString());
                totalCount++;
            }
        }
        log.info("[GEO加载完成] 共加载{}个演出坐标到Redis", totalCount);
        return Result.ok(AppMessages.format(AppMessages.SHOW_GEO_LOAD_SUCCESS, totalCount));
    }

    @Override
    public Result<?> toggleFavorite(Long showId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("[收藏/取消收藏] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        if (showId == null || showId <= 0) {
            log.warn("[收藏/取消收藏] 参数错误，showId:{}", showId);
            return Result.fail(AppMessages.SHOW_ID_INVALID);
        }

        try {
            String key = SHOW_FAVORITE_KEY + user.getId();
            Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, showId.toString());

            if (Boolean.TRUE.equals(isMember)) {
                stringRedisTemplate.opsForSet().remove(key, showId.toString());
                log.info("[取消收藏] userId:{}, showId:{}", user.getId(), showId);
                return Result.ok(false);
            } else {
                stringRedisTemplate.opsForSet().add(key, showId.toString());
                log.info("[收藏成功] userId:{}, showId:{}", user.getId(), showId);
                return Result.ok(true);
            }
        } catch (Exception e) {
            log.error("[收藏失败] userId:{}, showId:{}, error:{}", user.getId(), showId, e.getMessage(), e);
            return Result.fail(AppMessages.FAVORITE_ADD_FAIL);
        }
    }

    @Override
    public Result<?> queryMyFavorites(Integer current) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("[查询收藏列表] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        if (current == null || current < 1) {
            log.warn("[查询收藏列表] 参数错误，current:{}", current);
            current = 1;
        }

        String key = SHOW_FAVORITE_KEY + user.getId();
        Long total = stringRedisTemplate.opsForSet().size(key);
        if (total == null || total == 0) {
            return Result.ok(Collections.emptyList());
        }

        int pageSize = 10;
        int page = current;
        int start = (page - 1) * pageSize;
        int end = start + pageSize - 1;

        Set<String> memberSet = stringRedisTemplate.opsForSet().members(key);
        if (memberSet == null || memberSet.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<String> showIdStrs = new ArrayList<>(memberSet);

        List<Show> shows = showIdStrs.stream()
                .skip(start)
                .limit(pageSize)
                .map(id -> {
                    return cacheClient.queryWithPassThrough(CACHE_SHOW_KEY, Long.valueOf(id), Show.class, this::getById, CACHE_SHOW_TTL, TimeUnit.SECONDS);
                })
                .filter(Objects::nonNull)
                .toList();

        return Result.ok(shows);
    }

    @Override
    public Result<?> scrollShows(com.echo.dto.PageQuery pageQuery) {
        log.info("[游标分页] 开始查询演出列表 - size:{}, cursorMode:{}", 
                pageQuery.getSize(), pageQuery.isCursorMode());

        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Show> wrapper = 
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();

        // 只查询上架演出
        wrapper.eq("status", com.echo.enums.ShowStatus.ONLINE.getCode());

        // 应用排序（默认按创建时间降序）
        String sortBy = pageQuery.getSortBy();
        String sortOrder = pageQuery.getSortOrder();

        if (sortBy != null && !sortBy.isBlank()) {
            boolean asc = "asc".equalsIgnoreCase(sortOrder);
            switch (sortBy) {
                case "id":
                    wrapper.orderBy(true, asc, "id");
                    break;
                case "name":
                    wrapper.orderBy(true, asc, "name");
                    break;
                case "createTime":
                default:
                    wrapper.orderBy(true, asc, "create_time");
                    break;
            }
        } else {
            wrapper.orderByDesc("create_time");
        }

        // 游标分页逻辑
        if (pageQuery.isCursorMode()) {
            try {
                // 解码游标
                com.echo.utils.CursorCodec.CursorInfo cursorInfo = pageQuery.decodeCursor();

                // 使用游标信息构建WHERE条件（基于ID和时间戳）
                if (cursorInfo.getLastId() != null) {
                    wrapper.and(w -> w
                            .lt("id", cursorInfo.getLastId())
                            .or()
                            .eq("id", cursorInfo.getLastId())
                            .lt("create_time", cursorInfo.getLastTime())
                    );
                }

                log.debug("[游标分页] 应用游标条件 - lastId:{}", cursorInfo.getLastId());
            } catch (Exception e) {
                log.error("[游标分页] 游标解码失败 - error:{}", e.getMessage());
                return Result.fail(com.echo.constants.AppMessages.PARAM_ERROR);
            }
        }

        // 查询数据（多查一条，用于判断是否有更多数据）
        int pageSize = pageQuery.getSize();
        List<Show> records = list(wrapper.last("LIMIT " + (pageSize + 1)));

        // 处理结果
        boolean hasMore = records.size() > pageSize;
        if (hasMore) {
            records = records.subList(0, pageSize);  // 截取实际需要的数量
        }

        // 填充已售数量
        fillShowSoldBatch(records);

        // 构建下一页游标
        String nextCursor = null;
        if (hasMore && !records.isEmpty()) {
            Show lastRecord = records.get(records.size() - 1);
            com.echo.utils.CursorCodec.CursorInfo nextCursorInfo = new com.echo.utils.CursorCodec.CursorInfo();
            nextCursorInfo.setLastId(lastRecord.getId());
            nextCursorInfo.setLastTime(lastRecord.getCreateTime() != null ? 
                    lastRecord.getCreateTime().toString() : "");
            nextCursor = com.echo.utils.CursorCodec.encode(nextCursorInfo);

            log.debug("[游标分页] 生成下一页游标 - lastId:{}, cursor:{}", 
                    lastRecord.getId(), nextCursor);
        }

        // 返回游标分页结果
        log.info("[游标分页] 查询完成 - 返回{}条记录, hasMore:{}", records.size(), hasMore);
        return Result.ok(com.echo.dto.CursorPageResult.of(records, nextCursor, hasMore));
    }

    /**
     * Haversine公式计算球面距离
     * <p>
     * 算法原理：
     * 1. 将经纬度差值转换为弧度
     * 2. 使用半正矢（haversin）函数计算两点间的圆心角
     * 3. 距离 = 地球半径 × 圆心角
     * <p>
     * 精度：误差 < 0.5%，适用于全球范围
     *
     */
    private void calculateAndSetShowDistance(Show show, String key, double userLat, double userLng) {
        try {
            List<Point> positions = stringRedisTemplate.opsForGeo().position(key, show.getId().toString());
            if (positions != null && !positions.isEmpty()) {
                Point position = positions.get(0);
                double distance = calculateDistance(userLat, userLng, position.getY(), position.getX());
                show.setDistance(distance);
            } else {
                show.setDistance(-1.0);
            }
        } catch (Exception e) {
            show.setDistance(-1.0);
        }
    }

    private int compareByDistance(Show s1, Show s2) {
        if (s1.getDistance() < 0 && s2.getDistance() < 0) return 0;
        if (s1.getDistance() < 0) return 1;
        if (s2.getDistance() < 0) return -1;
        return Double.compare(s1.getDistance(), s2.getDistance());
    }

    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        final double EARTH_RADIUS_METERS = 6371000;  // 地球平均半径（米）

        double dLat = Math.toRadians(lat2 - lat1);  // 纬度差（弧度）
        double dLng = Math.toRadians(lng2 - lng1);  // 经度差（弧度）

        // Haversine公式核心：sin²(Δlat/2) + cos(lat1)×cos(lat2)×sin²(Δlng/2)
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLng / 2) * Math.sin(dLng / 2);

        // 计算圆心角（弧度）
        double centralAngle = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        // 距离 = 地球半径 × 圆心角
        return EARTH_RADIUS_METERS * centralAngle;
    }

    /**
     * 计算并保存演出的平均票价（懒加载兜底）
     * <p>
     * 触发时机：
     * - 用户查询演出详情时，如果avgPrice为null或0
     * <p>
     * 计算逻辑：基于该演出所有上架票种的payValue求平均值
     *
     * @param showId 演出ID
     */
    private void calculateAndSaveAvgPrice(Long showId) {
        if (showId == null || showId <= 0) {
            return;
        }

        try {
            com.echo.mapper.TicketMapper ticketMapper =
                applicationContext.getBean(com.echo.mapper.TicketMapper.class);

            // 查询该演出所有上架的票种
            List<com.echo.entity.Ticket> tickets = ticketMapper.selectList(
                new QueryWrapper<com.echo.entity.Ticket>()
                    .eq("show_id", showId)
                    .eq("status", 1)
                    .isNotNull("pay_value")
                    .gt("pay_value", 0)
            );

            long avgPriceCalculated = 0;

            if (!tickets.isEmpty()) {
                long totalPrice = 0;
                int validTicketCount = 0;

                for (com.echo.entity.Ticket ticket : tickets) {
                    totalPrice += ticket.getPayValue();
                    validTicketCount++;
                }

                if (validTicketCount > 0) {
                    avgPriceCalculated = totalPrice / validTicketCount;
                }
            }

            log.info("[平均票价-懒加载] 演出{}的平均票价: {}分 (基于{}个票种)",
                    showId, avgPriceCalculated, tickets.size());

            // 更新DB（持久化）
            com.echo.entity.Show showUpdate = new com.echo.entity.Show();
            showUpdate.setId(showId);
            showUpdate.setAvgPrice(avgPriceCalculated);
            updateById(showUpdate);

            log.info("[平均票价-懒加载] 已持久化到数据库 - showId:{}, avgPrice:{}分",
                    showId, avgPriceCalculated);

            // 【修复】avgPrice 变更后清除 Redis 和 Caffeine 本地缓存
            // 注意：这里直接调 updateById，绕过了上层 ShowServiceImpl.update() 的缓存失效逻辑，
            // 需手动清除缓存以保证数据一致性
            try {
                String redisKey = CACHE_SHOW_KEY + showId;
                stringRedisTemplate.delete(redisKey);
                log.info("[平均票价-懒加载] 已清除 Redis 缓存 - showId:{}", showId);

                // 通过 RocketMQ 广播通知所有节点清除本地 Caffeine 缓存
                CacheInvalidateMessage message = CacheInvalidateMessage.of(CACHE_SHOW_KEY, showId);
                rocketMQTemplate.syncSend(MqConstants.CACHE_INVALIDATE_TOPIC, message);
                log.info("[平均票价-懒加载] RocketMQ 广播已发送，Caffeine 本地缓存将失效 - showId:{}", showId);
            } catch (Exception cacheEx) {
                log.warn("[平均票价-懒加载] 缓存清理失败（不影响DB更新）- showId:{}, error:{}",
                        showId, cacheEx.getMessage());
            }

        } catch (Exception e) {
            log.error("[平均票价-懒加载] 失败 - showId:{}, error:{}", showId, e.getMessage(), e);
        }
    }

    /**
     * 批量填充演出已售数量（单一数据源：实时查询 tb_ticket_order）
     * <p>
     * 口径：status IN (2, 3) —— 已支付 + 已核销
     * 策略：一次 SELECT ... GROUP BY show_id 批量查出，内存 merge 到 Show 列表
     *
     * @param shows 待填充的演出列表
     */
    private void fillShowSoldBatch(List<Show> shows) {
        if (shows == null || shows.isEmpty()) return;

        try {
            List<Long> showIds = shows.stream()
                    .map(Show::getId)
                    .filter(Objects::nonNull)
                    .toList();

            if (showIds.isEmpty()) return;

            QueryWrapper<com.echo.entity.TicketOrder> wrapper = new QueryWrapper<>();
            wrapper.select("show_id", "COUNT(*) AS cnt")
                    .in("show_id", showIds)
                    .in("status", 2, 3)
                    .groupBy("show_id");

            List<Map<String, Object>> rows = ticketOrderMapper.selectMaps(wrapper);

            Map<Long, Integer> soldMap = new HashMap<>();
            for (Map<String, Object> row : rows) {
                Long sid = ((Number) row.get("show_id")).longValue();
                Integer cnt = ((Number) row.get("cnt")).intValue();
                soldMap.put(sid, cnt);
            }

            for (Show show : shows) {
                show.setSold(soldMap.getOrDefault(show.getId(), 0));
            }

            log.debug("[演出已售数] 批量填充完成 - 共{}个演出", shows.size());
        } catch (Exception e) {
            log.warn("[演出已售数] 批量填充异常，sold 保持 DB 值 - error:{}", e.getMessage());
        }
    }
}
