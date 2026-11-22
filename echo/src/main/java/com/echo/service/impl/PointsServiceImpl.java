package com.echo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.dto.UserDTO;
import com.echo.entity.PointsRecord;
import com.echo.entity.UserInfo;
import com.echo.entity.Coupon;
import com.echo.enums.CouponStatus;
import com.echo.mapper.PointsRecordMapper;
import com.echo.mapper.CouponMapper;
import com.echo.service.IPointsService;
import com.echo.service.IUserInfoService;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.echo.utils.BusinessConstants.*;
import static com.echo.utils.RedisConstants.CACHE_POINTS_BALANCE_KEY;
import static com.echo.utils.RedisConstants.CACHE_USER_INFO_KEY;
import static com.echo.utils.RedisConstants.POINTS_LOTTERY_KEY;

/**
 * 积分服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class PointsServiceImpl extends ServiceImpl<PointsRecordMapper, PointsRecord> implements IPointsService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int DAILY_LOTTERY_LIMIT = 3;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private CouponMapper couponMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> addPoints(Long userId, Integer points, Integer type, String description, Long relatedId) {
        try {
            UserInfo userInfo = getOrCreateUserInfo(userId);
            boolean updated = userInfoService.update()
                    .eq("user_id", userId)
                    .setSql("credits = IFNULL(credits, 0) + " + points)
                    .set("update_time", LocalDateTime.now())
                    .update();
            if (!updated) {
                log.error("[积分] 原子更新失败，userId:{}", userId);
                throw new com.echo.exception.BusinessException(
                        AppMessages.POINTS_ADD_FAIL,
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
            }
            userInfo.setCredits((userInfo.getCredits() != null ? userInfo.getCredits() : 0) + points);

            PointsRecord record = new PointsRecord();
            record.setUserId(userId);
            record.setPoints(points);
            record.setType(type);
            record.setDescription(description);
            record.setRelatedId(relatedId);
            record.setCreateTime(LocalDateTime.now());
            save(record);

            invalidatePointsCache(userId);

            log.info("[积分] 用户{}积分变动: {}{}, 当前:{}", userId,
                    points > 0 ? "+" : "", points, userInfo.getCredits());
            return Result.ok();
        } catch (Exception e) {
            log.error("[积分] 添加失败，userId:{}, error:{}", userId, e.getMessage(), e);
            throw new com.echo.exception.BusinessException(
                    AppMessages.POINTS_ADD_FAIL,
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> lottery() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        String lockKey = String.format("lock:points:lottery:%d", user.getId());
        String countKey = String.format("%s%d:%s", POINTS_LOTTERY_KEY, user.getId(),
                LocalDateTime.now().format(DATE_FORMAT));

        try {
            Boolean locked = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));

            if (Boolean.FALSE.equals(locked)) {
                return Result.fail(AppMessages.OPERATION_TOO_FREQUENT);
            }

            String countStr = stringRedisTemplate.opsForValue().get(countKey);
            int todayCount = countStr != null ? Integer.parseInt(countStr) : 0;

            if (todayCount >= DAILY_LOTTERY_LIMIT) {
                return Result.fail(AppMessages.POINTS_LOTTERY_LIMIT);
            }

            int[] prizes = {200, 100, 50, 10};
            double[] probabilities = {0.05, 0.15, 0.30, 0.50};
            String[] descriptions = {POINTS_LOTTERY_WIN_1, POINTS_LOTTERY_WIN_2,
                                     POINTS_LOTTERY_WIN_3, POINTS_LOTTERY_CONSOLE};

            int prizeIndex = selectPrize(probabilities);
            int rewardPoints = prizes[prizeIndex];
            String prizeDesc = descriptions[prizeIndex];

            // 原子操作：扣减成本 + 发放奖励（单条SQL保证原子性）
            int netChange = rewardPoints - com.echo.utils.BusinessConstants.POINTS_LOTTERY_COST_VALUE;
            boolean updated = userInfoService.update()
                    .eq("user_id", user.getId())
                    .ge("credits", com.echo.utils.BusinessConstants.POINTS_LOTTERY_COST_VALUE)  // 校验余额充足
                    .setSql("credits = credits + " + netChange)  // 扣减成本并发放奖励（净变化）
                    .update();

            if (!updated) {
                return Result.fail(AppMessages.POINTS_NOT_ENOUGH);
            }

            savePointsRecord(user.getId(), -com.echo.utils.BusinessConstants.POINTS_LOTTERY_COST_VALUE, POINTS_LOTTERY_COST);
            savePointsRecord(user.getId(), rewardPoints, prizeDesc);

            long secondsUntilMidnight = getSecondsUntilMidnight();
            stringRedisTemplate.opsForValue().set(
                    countKey,
                    String.valueOf(todayCount + 1),
                    secondsUntilMidnight,
                    TimeUnit.SECONDS
            );

            invalidatePointsCache(user.getId());

            // 查询扣减+奖励后剩余积分（userInfo 已在前面被修改，不能直接使用）
            UserInfo updatedUserInfo = userInfoService.getById(user.getId());
            int remainingCredits = updatedUserInfo != null && updatedUserInfo.getCredits() != null ? updatedUserInfo.getCredits() : 0;

            log.info("[抽奖] 成功，userId:{}, 消耗{}积分, 获得{}积分({}), 剩余:{}, 今日{}/{}次",
                    user.getId(), com.echo.utils.BusinessConstants.POINTS_LOTTERY_COST_VALUE, rewardPoints, prizeDesc,
                    remainingCredits, todayCount + 1, DAILY_LOTTERY_LIMIT);

            Map<String, Object> result = new HashMap<>();
            result.put("prize", prizeDesc);
            result.put("rewardPoints", rewardPoints);
            result.put("remainingPoints", remainingCredits);
            result.put("todayCount", todayCount + 1);
            result.put("dailyLimit", DAILY_LOTTERY_LIMIT);

            return Result.ok(result);
        } catch (Exception e) {
            log.error("[抽奖] 失败，userId:{}, error:{}", user.getId(), e.getMessage(), e);
            return Result.fail(AppMessages.POINTS_LOTTERY_FAIL);
        } finally {
            try {
                stringRedisTemplate.delete(lockKey);
            } catch (Exception e) {
                log.warn("[抽奖] 释放锁失败，key:{}", lockKey);
            }
        }
    }

    private static final int COUPON_COST = 100;
    private static final int DAILY_COUPON_LIMIT = 5;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> exchangeCoupon() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        String lockKey = String.format("lock:points:coupon:%d", user.getId());
        String countKey = String.format("points:coupon:%d:%s", user.getId(),
                LocalDateTime.now().format(DATE_FORMAT));

        try {
            Boolean locked = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));

            if (Boolean.FALSE.equals(locked)) {
                return Result.fail(AppMessages.OPERATION_TOO_FREQUENT);
            }

            String countStr = stringRedisTemplate.opsForValue().get(countKey);
            int todayCount = countStr != null ? Integer.parseInt(countStr) : 0;

            if (todayCount >= DAILY_COUPON_LIMIT) {
                return Result.fail("今日兑换次数已达上限（最多5次/天）");
            }

            // 先创建优惠券对象（内存中，尚未插入数据库）
            Coupon coupon = new Coupon();
            coupon.setUserId(user.getId());
            coupon.setName("10元优惠券");
            coupon.setDiscountAmount(1000L);  // 10元 = 1000分
            coupon.setMinOrderAmount(5000L);  // 最低消费50元 = 5000分
            coupon.setStatus(CouponStatus.UNUSED);  // 未使用状态
            coupon.setExpireTime(LocalDateTime.now().plusDays(30));  // 30天有效期
            coupon.setCreateTime(LocalDateTime.now());

            // 插入优惠券（必须在扣减积分之前，确保优惠券数据有效性）
            int couponInsertResult = couponMapper.insert(coupon);
            if (couponInsertResult <= 0 || coupon.getId() == null) {
                log.error("[兑换优惠券] 优惠券插入失败，userId:{}", user.getId());
                throw new RuntimeException("优惠券生成失败，请重试");
            }

            // 原子性扣减积分（参照lottery()方法的最佳实践）
            // 使用单条SQL完成余额校验+扣减，减少事务持锁时间
            boolean deducted = userInfoService.update()
                    .eq("user_id", user.getId())
                    .ge("credits", COUPON_COST)
                    .setSql("credits = credits - " + COUPON_COST)
                    .update();

            if (!deducted) {
                // 积分不足时回滚优惠券（抛出异常触发事务回滚）
                throw new RuntimeException("积分不足，兑换优惠券需要" + COUPON_COST + "积分");
            }

            // 保存积分变动记录（用于对账和审计）
            savePointsRecord(user.getId(), -COUPON_COST,
                    String.format("积分兑换优惠券-消耗%d积分-优惠券ID:%d", COUPON_COST, coupon.getId()));

            log.info("[兑换优惠券成功] userId:{}, couponId:{}, 扣减积分:{}, 有效期至:{}",
                    user.getId(), coupon.getId(), COUPON_COST, coupon.getExpireTime());

            long secondsUntilMidnight = getSecondsUntilMidnight();
            stringRedisTemplate.opsForValue().set(
                    countKey,
                    String.valueOf(todayCount + 1),
                    secondsUntilMidnight,
                    TimeUnit.SECONDS
            );

            invalidatePointsCache(user.getId());

            UserInfo updatedUserInfo = userInfoService.getById(user.getId());
            int remainingCredits = updatedUserInfo != null && updatedUserInfo.getCredits() != null ? updatedUserInfo.getCredits() : 0;

            log.info("[兑换优惠券] 成功，userId:{}, 消耗{}积分, 剩余:{}, 今日{}/{}次",
                    user.getId(), COUPON_COST, remainingCredits, todayCount + 1, DAILY_COUPON_LIMIT);

            Map<String, Object> result = new HashMap<>();
            result.put("couponValue", 10);
            result.put("minPurchase", 50);
            result.put("validDays", 30);
            result.put("costPoints", COUPON_COST);
            result.put("remainingPoints", remainingCredits);
            result.put("todayCount", todayCount + 1);
            result.put("dailyLimit", DAILY_COUPON_LIMIT);

            return Result.ok(result);
        } catch (Exception e) {
            log.error("[兑换优惠券] 失败，userId:{}, error:{}", user.getId(), e.getMessage(), e);
            return Result.fail("兑换失败，请稍后重试");
        } finally {
            try {
                stringRedisTemplate.delete(lockKey);
            } catch (Exception e) {
                log.warn("[兑换优惠券] 释放锁失败，key:{}", lockKey);
            }
        }
    }

    @Override
    public Result<?> queryMyPoints() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        try {
            String cacheKey = CACHE_POINTS_BALANCE_KEY + user.getId();

            log.info("[查询积分] 开始查询 - userId:{}, 将清除缓存", user.getId());

            stringRedisTemplate.delete(cacheKey);

            Map<String, Object> result = buildPointsResult(user.getId());

            log.info("[查询积分] 查询完成 - userId:{}, points:{}, todayCount:{}, limit:{}",
                    user.getId(), result.get("points"), result.get("todayCount"), result.get("dailyLimit"));

            return Result.ok(result);
        } catch (Exception e) {
            log.error("[查询积分] 异常，userId:{}, error:{}", user.getId(), e.getMessage(), e);

            try {
                Map<String, Object> fallbackResult = buildPointsResult(user.getId());
                return Result.ok(fallbackResult);
            } catch (Exception ex) {
                log.error("[查询积分] 降级也失败，error:{}", ex.getMessage());
                return Result.fail(AppMessages.POINTS_QUERY_FAIL);
            }
        }
    }

    @Override
    public Result<?> queryMyPointsRecords(Integer current) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        current = (current != null && current >= 1) ? current : 1;

        try {
            QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
            wrapper.eq("user_id", user.getId())
                  .orderByDesc("create_time");

            Page<PointsRecord> page = page(new Page<>(current, 20), wrapper);

            log.info("[积分记录] 查询成功，userId:{}, current:{}, total:{}",
                    user.getId(), current, page.getTotal());

            return Result.ok(page);
        } catch (Exception e) {
            log.error("[积分记录] 查询失败，userId:{}, error:{}", user.getId(), e.getMessage(), e);
            return Result.fail(AppMessages.POINTS_RECORD_QUERY_FAIL);
        }
    }

    @Override
    public Result<?> queryLotteryRecords(Integer current) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        current = (current != null && current >= 1) ? current : 1;

        try {
            QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
            wrapper.eq("user_id", user.getId())
                  .eq("type", 4)
                  .orderByDesc("create_time");

            Page<PointsRecord> page = page(new Page<>(current, 50), wrapper);

            log.info("[抽奖记录] 查询成功，userId:{}, current:{}, total:{}",
                    user.getId(), current, page.getTotal());

            return Result.ok(page);
        } catch (Exception e) {
            log.error("[抽奖记录] 查询失败，userId:{}, error:{}", user.getId(), e.getMessage(), e);
            return Result.fail(AppMessages.POINTS_LOTTERY_RECORD_QUERY_FAIL);
        }
    }

    private Map<String, Object> buildPointsResult(Long userId) {
        String cacheKey = CACHE_POINTS_BALANCE_KEY + userId;

        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null && !cachedJson.isEmpty()) {
            log.debug("[查询积分] 命中缓存，userId:{}", userId);
            Map<String, Object> cachedResult = parseJsonToMap(cachedJson);
            appendTodayLotteryInfo(cachedResult, userId);
            return cachedResult;
        }

        UserInfo userInfo = userInfoService.getById(userId);
        Map<String, Object> result = createPointsMap(userInfo);
        appendTodayLotteryInfo(result, userId);

        stringRedisTemplate.opsForValue().set(
                cacheKey,
                mapToJson(result),
                5,
                TimeUnit.MINUTES
        );

        log.debug("[查询积分] DB查询并缓存，userId:{}, points:{}", userId, result.get("points"));
        return result;
    }

    private void appendTodayLotteryInfo(Map<String, Object> result, Long userId) {
        try {
            String countKey = String.format("%s%d:%s", POINTS_LOTTERY_KEY, userId,
                    LocalDateTime.now().format(DATE_FORMAT));

            String countStr = stringRedisTemplate.opsForValue().get(countKey);
            int todayCount = countStr != null ? Integer.parseInt(countStr) : 0;

            log.info("[查询积分] 今日抽奖次数 - userId:{}, key:{}, count:{}, limit:{}",
                    userId, countKey, todayCount, DAILY_LOTTERY_LIMIT);

            result.put("todayCount", todayCount);
            result.put("dailyLimit", DAILY_LOTTERY_LIMIT);
        } catch (Exception e) {
            log.warn("[查询积分] 获取今日抽奖次数失败: userId:{}, error:{}", userId, e.getMessage());
            result.put("todayCount", 0);
            result.put("dailyLimit", DAILY_LOTTERY_LIMIT);
        }
    }

    private void savePointsRecord(Long userId, int points, String description) {
        PointsRecord record = new PointsRecord();
        record.setUserId(userId);
        record.setPoints(points);
        record.setType(4);
        record.setDescription(description);
        record.setCreateTime(LocalDateTime.now());
        save(record);
    }

    private UserInfo getOrCreateUserInfo(Long userId) {
        UserInfo userInfo = userInfoService.getById(userId);
        if (userInfo == null) {
            userInfo = new UserInfo();
            userInfo.setUserId(userId);
            userInfo.setCredits(0);
            userInfo.setCreditScore(100);
            userInfo.setCreateTime(LocalDateTime.now());
            userInfo.setUpdateTime(LocalDateTime.now());
            userInfoService.save(userInfo);
            log.info("[用户信息] 创建新用户积分账户，userId:{}", userId);
        }
        return userInfo;
    }

    private Map<String, Object> createPointsMap(UserInfo userInfo) {
        Map<String, Object> map = new HashMap<>();
        map.put("points", userInfo != null && userInfo.getCredits() != null ? userInfo.getCredits() : 0);
        map.put("creditScore", userInfo != null && userInfo.getCreditScore() != null ? userInfo.getCreditScore() : 100);
        return map;
    }

    private String mapToJson(Map<String, Object> map) {
        return com.alibaba.fastjson.JSON.toJSONString(map);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonToMap(String json) {
        return com.alibaba.fastjson.JSON.parseObject(json, Map.class);
    }

    private void invalidatePointsCache(Long userId) {
        try {
            stringRedisTemplate.delete(CACHE_POINTS_BALANCE_KEY + userId);
            // 【P2修复】积分变动同样影响用户信息缓存（积分/信誉分展示），一并失效，避免脏读
            stringRedisTemplate.delete(CACHE_USER_INFO_KEY + userId);
            log.debug("[清除缓存] 积分缓存与用户信息缓存已删除，userId:{}", userId);
        } catch (Exception e) {
            log.warn("[清除缓存] 失败，不影响主流程，userId:{}, error:{}", userId, e.getMessage());
        }
    }

    private int selectPrize(double[] probabilities) {
        Random random = new Random();
        double rand = random.nextDouble();
        double cumulative = 0.0;

        for (int i = 0; i < probabilities.length; i++) {
            cumulative += probabilities[i];
            if (rand < cumulative) {
                return i;
            }
        }
        return probabilities.length - 1;
    }

    private long getSecondsUntilMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime midnight = now.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        return Duration.between(now, midnight).getSeconds();
    }
}
