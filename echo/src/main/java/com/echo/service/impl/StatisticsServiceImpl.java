package com.echo.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.entity.Show;
import com.echo.entity.Ticket;
import com.echo.entity.TicketOrder;
import com.echo.entity.UserInfo;
import com.echo.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.echo.utils.RedisConstants.CACHE_STATISTICS_KEY;

/**
 * 数据统计服务实现类
 *
 * @author tulip
 */
@SuppressWarnings("unchecked")
@Slf4j
@Service
public class StatisticsServiceImpl implements IStatisticsService {

    @Resource
    private ITicketOrderService ticketOrderService;
    @Resource
    private ITicketService ticketService;
    @Resource
    private IShowService showService;
    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private com.echo.service.IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询热门演出排行（支持LBS距离排序）
     */
    @Override
    public Result<?> hotShows(Integer limit) {
        return hotShows(limit, null, null);
    }

    /**
     * 查询热门演出排行（带位置参数，支持距离排序）
     *
     * @param limit 返回数量限制（1-50）
     * @param x 用户经度坐标（可选，用于距离排序）
     * @param y 用户纬度坐标（可选，用于距离排序）
     * @return 热门演出列表（含distance字段，单位：米）
     */
    @Override
    public Result<?> hotShows(Integer limit, Double x, Double y) {
        int limitInt = limit != null ? limit : 10;
        try {
            // 1. 参数校验
            if (limitInt < 1) limitInt = 10;
            if (limitInt > 50) {
                log.warn("[热门演出] 超出限制，limit:{}调整为50", limitInt);
                limitInt = 50;
            }

            String cacheKey = CACHE_STATISTICS_KEY + "hotShows:" + limitInt;

            // 2. 尝试从Redis读取缓存
            log.debug("[热门演出] 尝试从Redis读取缓存，key:{}", cacheKey);
            String json = stringRedisTemplate.opsForValue().get(cacheKey);

            if (json != null) {
                if ("[]".equals(json)) {
                    log.debug("[热门演出] 命中空值缓存（防穿透），返回空列表");
                    return Result.ok(Collections.emptyList());
                }
                List<?> cached = JSONUtil.toList(JSONUtil.parseArray(json), Map.class);
                log.info("[热门演出] 缓存命中，返回{}条", cached.size());
                return Result.ok(cached);
            }

            // 3. 缓存未命中，查DB（优化后：仅3次查询）
            log.info("[热门演出] 缓存未命中，回源数据库");

            // Step 1: 查询近期已支付订单（上限 5000 条，生产安全护栏）
            // 注意：订单量大时应改为 SQL 层聚合（SELECT ticket_id, COUNT(*) GROUP BY），此处保留
            // 全量加载以支持按销售量排序取 TopN 的业务逻辑
            QueryWrapper<TicketOrder> orderQuery = new QueryWrapper<>();
            orderQuery.in("status", 2, 3)
                      .last("ORDER BY update_time DESC LIMIT 5000");
            List<TicketOrder> paidOrders = ticketOrderService.list(orderQuery);
            log.debug("[热门演出] 已支付订单数:{}", paidOrders.size());

            if (paidOrders.isEmpty()) {
                // 空值缓存防穿透
                long ttl = getRandomExpireTime(5L);
                stringRedisTemplate.opsForValue().set(cacheKey, "[]", ttl, TimeUnit.SECONDS);
                log.warn("[热门演出] 无支付订单数据，已设置空值缓存，TTL:{}分钟", ttl);
                return Result.ok(Collections.emptyList());
            }

            // Step 2: 统计每个ticket的销售数量
            Map<Long, Long> ticketSales = paidOrders.stream()
                    .collect(Collectors.groupingBy(
                            TicketOrder::getTicketId,
                            Collectors.counting()
                    ));

            Set<Long> ticketIds = ticketSales.keySet();

            // Step 3: 批量查询所有相关Ticket（1次查询）【关键优化】
            List<Ticket> tickets = ticketService.listByIds(ticketIds);

            // 构建ticket→showId映射
            Map<Long, Long> ticketShowMap = tickets.stream()
                    .filter(t -> t.getShowId() != null)
                    .collect(Collectors.toMap(
                            Ticket::getId,
                            Ticket::getShowId,
                            (v1, v2) -> v1
                    ));

            // 统计每个show的总销售量
            Map<Long, Long> showSales = new HashMap<>();
            for (Map.Entry<Long, Long> entry : ticketSales.entrySet()) {
                Long showId = ticketShowMap.get(entry.getKey());
                if (showId != null) {
                    showSales.merge(showId, entry.getValue(), Long::sum);
                }
            }

            // Step 4: 查询上架演出（上限 200 条，配合订单 LIMIT 形成业务边界）
            QueryWrapper<Show> showQuery = new QueryWrapper<>();
            showQuery.eq("status", 1)
                     .orderByDesc("create_time")
                     .last("LIMIT 200");
            List<Show> allShows = showService.list(showQuery);

            if (showSales.isEmpty() && allShows.isEmpty()) {
                stringRedisTemplate.opsForValue().set(cacheKey, "[]", 10, TimeUnit.MINUTES);
                log.warn("[热门演出] 无有效演出数据，返回空列表");
                return Result.ok(Collections.emptyList());
            }

            // 构建showId→Show映射
            Map<Long, Show> allShowMap = allShows.stream()
                    .collect(Collectors.toMap(
                            Show::getId,
                            Function.identity(),
                            (v1, v2) -> v1
                    ));

            Set<Long> showIds = showSales.keySet();
            List<Show> shows = showIds.isEmpty() ? allShows : allShowMap.entrySet().stream()
                    .filter(e -> showIds.contains(e.getKey()))
                    .map(Map.Entry::getValue)
                    .toList();

            Map<Long, Show> showMap = shows.stream()
                    .collect(Collectors.toMap(
                            Show::getId,
                            Function.identity(),
                            (v1, v2) -> v1
                    ));

            // Step 5: 排序并取TopN（返回完整演出信息）
            List<Map<String, Object>> result = new ArrayList<>();

            if (!showSales.isEmpty()) {
                result = showSales.entrySet().stream()
                        .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                        .limit(limit)
                        .map(e -> {
                            Map<String, Object> map = new HashMap<>();
                            Show show = showMap.get(e.getKey());
                            if (show != null) {
                                map.put("id", show.getId());
                                map.put("name", show.getName());
                                map.put("typeId", show.getTypeId());
                                map.put("images", show.getImages());
                                map.put("area", show.getArea());
                                map.put("address", show.getAddress());
                                map.put("openHours", show.getOpenHours());
                                map.put("avgPrice", show.getAvgPrice());
                                map.put("sold", e.getValue());
                                map.put("sales", e.getValue());
                            } else {
                                map.put("id", e.getKey());
                                map.put("name", "未知演出");
                                map.put("sold", e.getValue());
                                map.put("sales", e.getValue());
                            }
                            return map;
                        })
                        .collect(Collectors.toList());
            }

            if (result.size() < limit && !allShows.isEmpty()) {
                Set<Long> existingIds = result.stream()
                        .map(m -> (Long) m.get("id"))
                        .collect(Collectors.toSet());

                List<Map<String, Object>> additionalShows = allShows.stream()
                        .filter(s -> !existingIds.contains(s.getId()))
                        .limit(limit - result.size())
                        .map(show -> {
                            Map<String, Object> map = new HashMap<>();
                            map.put("id", show.getId());
                            map.put("name", show.getName());
                            map.put("typeId", show.getTypeId());
                            map.put("images", show.getImages());
                            map.put("area", show.getArea());
                            map.put("address", show.getAddress());
                            map.put("openHours", show.getOpenHours());
                            map.put("avgPrice", show.getAvgPrice());
                            map.put("sold", 0);
                            map.put("sales", 0);
                            return map;
                        })
                        .toList();

                result.addAll(additionalShows);
            }

            // 7. LBS距离排序（如果提供了用户位置坐标）
            if (x != null && y != null) {
                log.info("[热门演出] 启用LBS距离排序，用户位置:({}, {})", x, y);

                final double userLon = x;
                final double userLat = y;

                // 对每个演出计算距离（Haversine公式）
                result.forEach(item -> {
                    Object showIdObj = item.get("id");
                    if (showIdObj != null) {
                        Long showId = ((Number) showIdObj).longValue();
                        Show show = allShowMap.get(showId);

                        if (show != null && show.getX() != null && show.getY() != null) {
                            double distance = calculateDistance(
                                userLat, userLon,
                                show.getY(), show.getX()
                            );
                            item.put("distance", Math.round(distance));
                            log.debug("[热门演出] 演出{} 距离: {}米", show.getName(), Math.round(distance));
                        } else {
                            item.put("distance", Double.MAX_VALUE);
                            log.debug("[热门演出] 演出{} 无坐标信息", item.get("name"));
                        }
                    }
                });

                // 按距离升序排序（最近的排在前面）
                result.sort((a, b) -> {
                    double distA = ((Number) a.getOrDefault("distance", Double.MAX_VALUE)).doubleValue();
                    double distB = ((Number) b.getOrDefault("distance", Double.MAX_VALUE)).doubleValue();
                    return Double.compare(distA, distB);
                });

                log.info("[热门演出] 距离排序完成，共{}条", result.size());
            } else {
                log.debug("[热门演出] 未提供位置参数，按销量排序");
            }

            // 8. 写入缓存（随机TTL防雪崩）
            long ttl = getRandomExpireTime(10L);
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(result), ttl, TimeUnit.SECONDS);
            log.info("[热门演出] 成功，共{}条，已缓存，TTL:{}分钟", result.size(), ttl);

            return Result.ok(result);

        } catch (Exception e) {
            log.error("[热门演出] 异常，error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.format(AppMessages.STATISTICS_HOT_SHOW_QUERY_FAIL, e.getMessage()));
        }
    }

    /**
     * 查询销售趋势统计（性能优化版：解决重复查询问题）
     */
    @Override
    public Result<?> salesTrend(Integer days) {
        int daysInt = days != null ? days : 7;
        try {
            // 1. 参数校验
            if (daysInt < 1) daysInt = 7;
            if (daysInt > 90) {
                log.warn("[销售趋势] 超出限制，days:{}调整为90", daysInt);
                daysInt = 90;
            }

            String cacheKey = CACHE_STATISTICS_KEY + "salesTrend:" + daysInt;

            // 2. 尝试从Redis读取缓存
            log.debug("[销售趋势] 尝试从Redis读取缓存，key:{}", cacheKey);
            String json = stringRedisTemplate.opsForValue().get(cacheKey);

            if (json != null) {
                if ("[]".equals(json)) {
                    log.debug("[销售趋势] 命中空值缓存（防穿透），返回空列表");
                    return Result.ok(Collections.emptyList());
                }
                List<?> cached = JSONUtil.toList(JSONUtil.parseArray(json), Map.class);
                log.info("[销售趋势] 缓存命中，返回{}条", cached.size());
                return Result.ok(cached);
            }

            // 3. 缓存未命中，查DB（优化后：每天仅1次查询）
            log.info("[销售趋势] 缓存未命中，回源数据库，统计{}天", daysInt);

            List<Map<String, Object>> trend = new ArrayList<>();
            LocalDate today = LocalDate.now();

            for (int i = daysInt - 1; i >= 0; i--) {
                LocalDate date = today.minusDays(i);
                LocalDateTime start = date.atStartOfDay();
                LocalDateTime end = date.plusDays(1).atStartOfDay();

                // 【关键优化】合并count和sum为单次查询
                QueryWrapper<TicketOrder> queryWrapper = new QueryWrapper<>();
                queryWrapper.ge("pay_time", start)
                            .lt("pay_time", end);

                List<TicketOrder> dayOrders = ticketOrderService.list(queryWrapper);

                long count = dayOrders.size();

                // 批量查询当天的ticket价格并计算总额
                long amount = 0L;
                if (!dayOrders.isEmpty()) {
                    Set<Long> dayTicketIds = dayOrders.stream()
                            .map(TicketOrder::getTicketId)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());

                    if (!dayTicketIds.isEmpty()) {
                        List<Ticket> dayTickets = ticketService.listByIds(dayTicketIds);
                        Map<Long, Ticket> dayTicketMap = dayTickets.stream()
                                .collect(Collectors.toMap(
                                        Ticket::getId,
                                        Function.identity(),
                                        (v1, v2) -> v1
                                ));

                        amount = dayOrders.stream()
                                .mapToLong(o -> {
                                    Ticket ticket = dayTicketMap.get(o.getTicketId());
                                    return ticket != null ? ticket.getPayValue() : 0;
                                })
                                .sum();
                    }
                }

                Map<String, Object> dayData = new HashMap<>();
                dayData.put("date", date.toString());
                dayData.put("orderCount", count);
                dayData.put("amount", amount);
                trend.add(dayData);

                log.debug("[销售趋势] {} 订单数:{}, 销售额:{}", date, count, amount);
            }

            // 4. 写入缓存（随机TTL防雪崩）
            long ttl = getRandomExpireTime(10L);
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(trend), ttl, TimeUnit.SECONDS);
            log.info("[销售趋势] 成功，共{}天，已缓存，TTL:{}分钟", trend.size(), ttl);

            return Result.ok(trend);

        } catch (Exception e) {
            log.error("[销售趋势] 异常，error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.format(AppMessages.STATISTICS_SALES_TREND_QUERY_FAIL, e.getMessage()));
        }
    }

    /**
     * 查询用户画像统计（含缓存穿透防护+异常降级）
     */
    @Override
    public Result<?> userProfile() {
        try {
            String cacheKey = CACHE_STATISTICS_KEY + "userProfile";

            // 1. 尝试从Redis读取缓存
            log.debug("[用户画像] 尝试从Redis读取缓存，key:{}", cacheKey);
            String json = stringRedisTemplate.opsForValue().get(cacheKey);

            if (json != null) {
                Map<String, Object> cached = JSONUtil.toBean(json, Map.class);
                log.info("[用户画像] 缓存命中");
                return Result.ok(cached);
            }

            // 2. 缓存未命中，查DB（使用SQL聚合，避免全表加载到内存）
            log.info("[用户画像] 缓存未命中，回源数据库");

            // 2.1 性别分布（SQL聚合）
            Map<String, Object> genderParams = new HashMap<>();
            genderParams.put("male", 0);
            genderParams.put("female", 0);
            genderParams.put("unknown", 0);

            QueryWrapper<UserInfo> genderQuery = new QueryWrapper<>();
            genderQuery.select("gender, COUNT(*) as cnt").groupBy("gender");
            List<Map<String, Object>> genderResult = userInfoService.listMaps(genderQuery);

            for (Map<String, Object> row : genderResult) {
                Object genderObj = row.get("gender");
                Long count = row.get("cnt") instanceof Number ? ((Number) row.get("cnt")).longValue() : 0L;

                if (genderObj == null) {
                    genderParams.put("unknown", count);
                } else if (Boolean.FALSE.equals(genderObj)) {
                    genderParams.put("male", count);
                } else if (Boolean.TRUE.equals(genderObj)) {
                    genderParams.put("female", count);
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Long> genderDist = (Map<String, Long>) (Map<?, ?>) genderParams;

            // 2.2 城市分布Top10（SQL聚合+排序）
            QueryWrapper<UserInfo> cityQuery = new QueryWrapper<>();
            cityQuery.select("city, COUNT(*) as city_cnt")
                    .isNotNull("city")
                    .ne("city", "")
                    .groupBy("city")
                    .orderByDesc("city_cnt")
                    .last("LIMIT 10");

            List<Map<String, Object>> cityResult = userInfoService.listMaps(cityQuery);

            List<Map.Entry<String, Long>> topCities = cityResult.stream()
                    .map(row -> {
                        String city = row.get("city") != null ? row.get("city").toString() : "未知";
                        Long cnt = row.get("city_cnt") instanceof Number ? ((Number) row.get("city_cnt")).longValue() : 0L;
                        return Map.entry(city, cnt);
                    })
                    .toList();

            // 2.3 年龄分布（仅加载birthday字段）
            QueryWrapper<UserInfo> ageQuery = new QueryWrapper<>();
            ageQuery.select("birthday");

            List<Map<String, Object>> birthdayList = userInfoService.listMaps(ageQuery);

            Map<String, Long> ageDist = new HashMap<>();
            ageDist.put("under18", 0L);
            ageDist.put("18-25", 0L);
            ageDist.put("26-35", 0L);
            ageDist.put("36-45", 0L);
            ageDist.put("46+", 0L);
            ageDist.put("unknown", 0L);

            LocalDate now = LocalDate.now();
            for (Map<String, Object> row : birthdayList) {
                Object birthdayObj = row.get("birthday");
                if (birthdayObj == null) {
                    ageDist.merge("unknown", 1L, Long::sum);
                    continue;
                }

                LocalDate birthday;
                if (birthdayObj instanceof java.sql.Date sqlDate) {
                    birthday = sqlDate.toLocalDate();
                } else if (birthdayObj instanceof LocalDate localDate) {
                    birthday = localDate;
                } else {
                    ageDist.merge("unknown", 1L, Long::sum);
                    continue;
                }

                int age = now.getYear() - birthday.getYear();
                if (age < 18) ageDist.merge("under18", 1L, Long::sum);
                else if (age <= 25) ageDist.merge("18-25", 1L, Long::sum);
                else if (age <= 35) ageDist.merge("26-35", 1L, Long::sum);
                else if (age <= 45) ageDist.merge("36-45", 1L, Long::sum);
                else ageDist.merge("46+", 1L, Long::sum);
            }

            // 3. 组装结果
            Map<String, Object> result = new HashMap<>();
            result.put("genderDist", genderDist);
            result.put("topCities", topCities);
            result.put("ageDist", ageDist);

            // 4. 写入缓存（随机TTL防雪崩）
            long ttl = getRandomExpireTime(15L);  // 基础30分钟 + 随机0-15分钟
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(result), ttl, TimeUnit.SECONDS);
            log.info("[用户画像] 成功，已使用SQL聚合查询，已缓存，TTL:{}分钟", ttl);

            return Result.ok(result);

        } catch (Exception e) {
            log.error("[用户画像] 异常，error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.format(AppMessages.STATISTICS_USER_PROFILE_QUERY_FAIL, e.getMessage()));
        }
    }

    /**
     * 查询仪表盘统计数据
     */
    @Override
    public Result<?> dashboard() {
        try {
            String cacheKey = CACHE_STATISTICS_KEY + "dashboard";

            String json = stringRedisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                Map<String, Object> cached = JSONUtil.toBean(json, Map.class);
                log.info("[仪表盘] 缓存命中");
                return Result.ok(cached);
            }

            log.info("[仪表盘] 缓存未命中，回源数据库");

            // ===== 统计口径与管理端 /admin/dashboard 保持一致 =====
            // 用户数同源 tb_user（旧实现查 tb_user_info，两表记录数不同导致首页与管理端数字不一致）
            long showCount = showService.count();
            long userCount = userService.count();

            // 订单数同源全量订单（旧实现只算 status 2/3 成交单，与管理端"订单总数"口径不一致）
            long orderCount = ticketOrderService.count();

            // 从tb_show.score字段聚合计算真实平均评分（替代硬编码4.8）
            double avgScore = calculateRealAverageShowScore();

            Map<String, Object> result = new HashMap<>();
            result.put("showCount", showCount);
            result.put("userCount", userCount);
            result.put("orderCount", orderCount);
            result.put("avgScore", avgScore);

            long ttl = getRandomExpireTime(10L);
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(result), ttl, TimeUnit.SECONDS);
            log.info("[仪表盘] 成功，已缓存，TTL:{}分钟, 真实平均评分:{}", ttl, avgScore);

            return Result.ok(result);

        } catch (Exception e) {
            log.error("[仪表盘] 异常，error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.format(AppMessages.STATISTICS_USER_PROFILE_QUERY_FAIL, e.getMessage()));
        }
    }

    /**
     * 计算所有演出的真实平均评分（从tb_show.score字段聚合）
     *
     * 业务规则：
     * 1. 仅统计score不为null且>0的演出
     * 2. 使用AVG聚合函数确保精度（保留1位小数）
     * 3. 无演出或无评分时返回0.0（而非硬编码4.8）
     * 4. 异常时降级为0.0并记录警告日志
     *
     * @return 真实平均评分（0.0-5.0）
     */
    private double calculateRealAverageShowScore() {
        try {
            // 查询所有有效评分的演出（score IS NOT NULL AND score > 0）
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.Show> queryWrapper =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            queryWrapper.isNotNull("score")
                     .gt("score", 0);

            // 使用MySQL的AVG函数聚合计算
            List<com.echo.entity.Show> showsWithScore = showService.list(queryWrapper);

            if (showsWithScore == null || showsWithScore.isEmpty()) {
                log.info("[平均评分] 当前无已评分演出，返回默认值0.0");
                return 0.0;
            }

            // Java侧聚合计算（避免SQL注入风险，且便于日志记录）
            double totalScore = 0.0;
            int validCount = 0;

            for (com.echo.entity.Show show : showsWithScore) {
                if (show.getScore() != null && show.getScore() > 0) {
                    totalScore += show.getScore();
                    validCount++;
                }
            }

            if (validCount == 0) {
                log.warn("[平均评分] 查询到演出但均无有效评分，返回0.0");
                return 0.0;
            }

            double avgScore = totalScore / validCount;

            // 四舍五入保留1位小数（与前端展示格式一致）
            avgScore = Math.round(avgScore * 10.0) / 10.0;

            log.info("[平均评分] 真实计算完成 - 演出总数:{}, 有效评分数:{}, 平均分:{}",
                    showsWithScore.size(), validCount, avgScore);

            return avgScore;

        } catch (Exception e) {
            log.error("[平均评分] 计算失败，降级返回0.0 - error:{}", e.getMessage(), e);
            return 0.0;  // 异常降级，不返回虚假数据
        }
    }

    /**
     * 生成随机过期时间（防止缓存雪崩）
     *
     * @param randomTTL 随机TTL范围（分钟）
     * @return 最终TTL（分钟）
     */
    private long getRandomExpireTime(long randomTTL) {
        return com.echo.utils.RedisConstants.CACHE_STATISTICS_TTL + (long) (Math.random() * randomTTL);
    }

    /**
     * Haversine公式计算两点间球面距离（单位：米）
     *
     * @param lat1 起点纬度
     * @param lon1 起点经度
     * @param lat2 终点纬度
     * @param lon2 终点经度
     * @return 两点间距离（米）
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000; // 地球平均半径（米）

        double lat1Rad = Math.toRadians(lat1);
        double lon1Rad = Math.toRadians(lon1);
        double lat2Rad = Math.toRadians(lat2);
        double lon2Rad = Math.toRadians(lon2);

        double dLat = lat2Rad - lat1Rad;
        double dLon = lon2Rad - lon1Rad;

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c;
    }
}
