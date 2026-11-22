package com.echo.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.transaction.annotation.Transactional;
import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.dto.CursorPageResult;
import com.echo.dto.UserDTO;
import com.echo.utils.CursorCodec;
import com.echo.entity.Show;
import com.echo.entity.ShowReview;
import com.echo.entity.User;
import com.echo.enums.NotificationType;
import com.echo.mapper.ShowMapper;
import com.echo.mapper.ShowReviewMapper;
import com.echo.message.NotificationMessage;
import com.echo.service.IFollowService;
import com.echo.service.IPointsService;
import com.echo.service.IShowReviewService;
import com.echo.service.IUserService;
import com.echo.utils.MqConstants;
import com.echo.utils.SystemConstants;
import com.echo.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;

import static com.echo.utils.BusinessConstants.*;
import static com.echo.utils.RedisConstants.*;

/**
 * 演出评测服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class ShowReviewServiceImpl extends ServiceImpl<ShowReviewMapper, ShowReview> implements IShowReviewService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    @Resource
    private IPointsService pointsService;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private ShowMapper showMapper;

    @Resource
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Resource
    private DefaultRedisScript<Long> likeReviewScript;

    /**
     * 发布评测（笔记）
     * <p>
     * 数据库操作：
     *   INSERT INTO tb_show_review (show_id, user_id, title, images, content, liked, comments, create_time, update_time)
     *   VALUES ({showId}, {userId}, {title}, {images}, {content}, 0, 0, now(), now())
     * <p>
     * Feed流推送：
     *   遍历关注者列表 → ZADD feed:{followerId} {timestamp} {reviewId}
     * <p>
     * 积分奖励：+3积分
     *   INSERT INTO tb_points_record (user_id, points, type, description, related_id, create_time)
     *   VALUES ({userId}, 3, 1, '发布评测', {reviewId}, now())
     *
     * @param review 评测内容
     * @return 评测ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> saveReview(ShowReview review) {
        // 验证评分（必填）
        if (review.getScore() == null || review.getScore() < 1 || review.getScore() > 5) {
            return Result.fail(AppMessages.SHOW_REVIEW_SCORE_INVALID);
        }

        // 允许纯评分：标题和内容都为空时只记录评分
        boolean hasTitle = review.getTitle() != null && !review.getTitle().isBlank();
        boolean hasContent = review.getContent() != null && !review.getContent().isBlank();

        // 如果有内容但没标题，要求填写标题
        if (hasContent && !hasTitle) {
            return Result.fail(AppMessages.REVIEW_TITLE_NOT_EMPTY);
        }

        // 校验演出是否存在
        if (review.getShowId() == null || review.getShowId() <= 0) {
            return Result.fail(AppMessages.SHOW_ID_NOT_EMPTY);
        }
        Show existShow = showMapper.selectById(review.getShowId());
        if (existShow == null) {
            return Result.fail(AppMessages.SHOW_NOT_EXIST);
        }

        UserDTO user = UserHolder.getUser();
        review.setUserId(user.getId());

        Long existingReviewId = query().eq("user_id", user.getId()).eq("show_id", review.getShowId()).one() != null ?
                query().eq("user_id", user.getId()).eq("show_id", review.getShowId()).one().getId() : null;
        if (existingReviewId != null) {
            log.warn("[发布评测] 用户已评测过该演出 - userId:{}, showId:{}, 已有评测ID:{}", user.getId(), review.getShowId(), existingReviewId);
            return Result.fail("您已评测过该演出，每个演出只能评测一次");
        }

        // 防御性处理：确保 title 和 content 不为 null（数据库允许 NULL，但统一用空字符串）
        if (review.getTitle() == null) {
            review.setTitle("");
        }
        if (review.getContent() == null) {
            review.setContent("");
        }

        // 纯评分模式：如果没有标题和内容，设置默认值
        if (!hasTitle) {
            log.info("[发布评测] 纯评分模式 - userId:{}, showId:{}, score:{}",
                    user.getId(), review.getShowId(), review.getScore());
        }

        boolean isSuccess = save(review);
        if (!isSuccess) {
            return Result.fail(AppMessages.REVIEW_SAVE_ERROR);
        }

        // 更新演出统计数据（评测数量 +1，重新计算平均分）
        try {
            transactionTemplate.execute(status -> {
                updateShowStatistics(review.getShowId());
                return null;
            });
        } catch (Exception e) {
            log.error("[发布评测] 更新演出统计失败 - showId:{}, error:{}",
                    review.getShowId(), e.getMessage(), e);
            // 统计更新失败不影响主流程
        }

        // 只有完整的评测才给积分和推送Feed流
        if (hasTitle) {
            pointsService.addPoints(user.getId(), 3, 1, POINTS_REVIEW, null);

            // 从 Redis Set 获取粉丝列表（避免DB查询，热点作者可能有大量粉丝）
            String fansKey = FOLLOW_FANS_KEY + user.getId();
            Set<String> fanIds = stringRedisTemplate.opsForSet().members(fansKey);
            if (fanIds != null && !fanIds.isEmpty()) {
                for (String fanIdStr : fanIds) {
                    Long fanId = Long.valueOf(fanIdStr);
                    // 限流：每个用户每小时最多收到一条同类型通知
                    String rateLimitKey = NOTIFICATION_RATE_LIMIT_KEY + "5:" + fanId;
                    String exists = stringRedisTemplate.opsForValue().get(rateLimitKey);
                    if (exists != null) {
                        continue;
                    }
                    stringRedisTemplate.opsForValue().set(rateLimitKey, "1",
                            NOTIFICATION_RATE_LIMIT_TTL, java.util.concurrent.TimeUnit.SECONDS);

                    stringRedisTemplate.opsForZSet().add(FEED_KEY + fanId, review.getId().toString(), System.currentTimeMillis());
                    NotificationMessage msg = NotificationMessage.of(fanId, NotificationType.REVIEW_FOLLOW, NOTIFICATION_FOLLOW_REVIEW_TITLE,
                            NOTIFICATION_FOLLOW_REVIEW_CONTENT_PREFIX + user.getNickName() + NOTIFICATION_FOLLOW_REVIEW_CONTENT_MID + review.getTitle(), review.getId());
                    rocketMQTemplate.asyncSend(MqConstants.NOTIFICATION_TOPIC,
                            MessageBuilder.withPayload(msg).build(),
                            new SendCallback() {
                                @Override
                                public void onSuccess(SendResult sendResult) {}
                                @Override
                                public void onException(Throwable e) {
                                    log.error("[通知] 关注动态通知发送失败，userId:{}", fanId, e);
                                }
                            });
                }
            }
        }

        return Result.ok(review.getId());
    }

    /**
     * 点赞/取消点赞评测
     * <p>
     * 使用Lua脚本保证原子性：
     *   Redis Lua: likeReview.lua
     *   KEYS[1] = review:liked:{reviewId}  → ZSet
     *   ARGV[1] = {userId}
     *   返回：1=点赞成功, 0=取消点赞成功
     * <p>
     * 逻辑：
     *   ZSCORE review:liked:{reviewId} {userId} → 判断是否已点赞
     *   已点赞 → ZREM review:liked:{reviewId} {userId} → 取消点赞
     *   未点赞 → ZADD review:liked:{reviewId} {timestamp} {userId} → 点赞
     * <p>
     * 数据库同步（异步/批量）：
     *   UPDATE tb_show_review SET liked = ZCARD(review:liked:{reviewId}) WHERE id = {reviewId}
     *
     * @param id 评测ID
     * @return 操作结果（1=点赞成功, 0=取消点赞成功）
     */
    @Override
    public Result<?> likeReview(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = REVIEW_LIKED_KEY + id;

        Long result = stringRedisTemplate.execute(
                likeReviewScript,
                Collections.singletonList(key),
                userId.toString(), String.valueOf(System.currentTimeMillis()));

        int delta = result.intValue();
        if (delta == 1) {
            update().setSql("liked = liked + 1").eq("id", id).update();
            pointsService.addPoints(userId, com.echo.utils.BusinessConstants.POINTS_LIKE_REWARD_VALUE, 1, com.echo.utils.BusinessConstants.POINTS_LIKE_REWARD, null);
            log.info("[点赞] 用户:{} 点赞了评论:{}", userId, id);
        } else if (delta == -1) {
            update().setSql("liked = liked - 1").eq("id", id).update();
            log.info("[取消点赞] 用户:{} 取消了评论:{} 的点赞", userId, id);
        }
        return Result.ok();
    }

    @Override
    public Result<?> queryReviewById(Long id) {
        ShowReview review = getById(id);
        if (review == null) {
            return Result.fail(AppMessages.REVIEW_NOT_EXIST);
        }
        queryReviewUser(review);
        isReviewLiked(review);
        return Result.ok(review);
    }

    @Override
    public Result<?> queryHotReview(Integer current, Integer size) {
        Page<ShowReview> page = query().orderByDesc("liked").page(new Page<>(current, size != null ? size : 10));
        List<ShowReview> records = page.getRecords();
        records.forEach(review -> { queryReviewUser(review); isReviewLiked(review); });
        return Result.ok(records, page.getTotal());
    }

    @Override
    public Result<?> queryLatestReviews(String cursor) {
        final int PAGE_SIZE = 10;
        Long lastId = 0L;

        if (cursor != null && !cursor.isEmpty()) {
            try {
                CursorCodec.CursorInfo cursorInfo = CursorCodec.decode(cursor);
                lastId = cursorInfo.getLastId();
            } catch (Exception e) {
                log.warn("[最新评测] 游标解码失败，使用默认值: {}", e.getMessage());
            }
        }

        QueryWrapper<ShowReview> wrapper = new QueryWrapper<>();
        if (lastId > 0) {
            wrapper.lt("id", lastId);
        }
        wrapper.orderByDesc("create_time");
        wrapper.last("LIMIT " + PAGE_SIZE);

        List<ShowReview> records = list(wrapper);
        records.forEach(review -> { queryReviewUser(review); isReviewLiked(review); });

        String nextCursor = null;
        boolean hasMore = false;

        if (records.size() == PAGE_SIZE) {
            ShowReview lastRecord = records.get(records.size() - 1);
            CursorCodec.CursorInfo cursorInfo = new CursorCodec.CursorInfo();
            cursorInfo.setLastId(lastRecord.getId());
            cursorInfo.setLastTime(lastRecord.getCreateTime() != null ?
                    lastRecord.getCreateTime().toString() : String.valueOf(System.currentTimeMillis()));
            nextCursor = CursorCodec.encode(cursorInfo);
            hasMore = true;
        }

        return Result.ok(CursorPageResult.of(records, nextCursor, hasMore));
    }

    @Override
    public Result<?> queryLatestCommentedReviews(String cursor) {
        final int PAGE_SIZE = 10;
        Long lastId = 0L;

        if (cursor != null && !cursor.isEmpty()) {
            try {
                CursorCodec.CursorInfo cursorInfo = CursorCodec.decode(cursor);
                lastId = cursorInfo.getLastId();
            } catch (Exception e) {
                log.warn("[最新评论] 游标解码失败，使用默认值: {}", e.getMessage());
            }
        }

        QueryWrapper<ShowReview> wrapper = new QueryWrapper<>();
        if (lastId > 0) {
            wrapper.lt("id", lastId);
        }
        wrapper.orderByDesc("update_time");
        wrapper.last("LIMIT " + PAGE_SIZE);

        List<ShowReview> records = list(wrapper);
        records.forEach(review -> { queryReviewUser(review); isReviewLiked(review); });

        String nextCursor = null;
        boolean hasMore = false;

        if (records.size() == PAGE_SIZE) {
            ShowReview lastRecord = records.get(records.size() - 1);
            CursorCodec.CursorInfo cursorInfo = new CursorCodec.CursorInfo();
            cursorInfo.setLastId(lastRecord.getId());
            cursorInfo.setLastTime(lastRecord.getUpdateTime() != null ?
                    lastRecord.getUpdateTime().toString() : String.valueOf(System.currentTimeMillis()));
            nextCursor = CursorCodec.encode(cursorInfo);
            hasMore = true;
        }

        return Result.ok(CursorPageResult.of(records, nextCursor, hasMore));
    }

    @Override
    public Result<?> queryRecommendedReviews(String cursor) {
        final int PAGE_SIZE = 15;
        Long lastId = 0L;

        if (cursor != null && !cursor.isEmpty()) {
            try {
                CursorCodec.CursorInfo cursorInfo = CursorCodec.decode(cursor);
                lastId = cursorInfo.getLastId();
            } catch (Exception e) {
                log.warn("[推荐评测] 游标解码失败，使用默认值: {}", e.getMessage());
            }
        }

        QueryWrapper<ShowReview> wrapper = new QueryWrapper<>();
        if (lastId > 0) {
            wrapper.lt("id", lastId);
        }
        wrapper.last("ORDER BY RAND() LIMIT " + PAGE_SIZE);

        List<ShowReview> records = list(wrapper);
        records.forEach(review -> { queryReviewUser(review); isReviewLiked(review); });

        String nextCursor = null;
        boolean hasMore = false;

        if (records.size() == PAGE_SIZE) {
            ShowReview lastRecord = records.get(records.size() - 1);
            CursorCodec.CursorInfo cursorInfo = new CursorCodec.CursorInfo();
            cursorInfo.setLastId(lastRecord.getId());
            cursorInfo.setLastTime(String.valueOf(System.currentTimeMillis()));
            nextCursor = CursorCodec.encode(cursorInfo);
            hasMore = true;
        }

        return Result.ok(CursorPageResult.of(records, nextCursor, hasMore));
    }

    @Override
    public Result<?> searchReviews(String keyword, Integer current) {
        log.info("[评测搜索] 开始搜索 - keyword:{}, current:{}",
                keyword != null && !keyword.isEmpty() ? keyword : "(空)", current);

        try {
            QueryWrapper<ShowReview> wrapper = new QueryWrapper<>();

            if (keyword != null && !keyword.trim().isEmpty()) {
                String trimmedKeyword = keyword.trim();

                wrapper.and(w -> w
                        .like("title", trimmedKeyword)
                        .or()
                        .like("content", trimmedKeyword)
                );

                log.info("[评测搜索] 使用关键词搜索:{}", trimmedKeyword);
            } else {
                log.info("[评测搜索] 无关键词，返回所有评测");
            }

            wrapper.orderByDesc("create_time");

            Page<ShowReview> pageResult = new Page<>(current, SystemConstants.MAX_PAGE_SIZE);
            page(pageResult, wrapper);

            List<ShowReview> records = pageResult.getRecords();

            if (records == null || records.isEmpty()) {
                log.info("[评测搜索] 无结果");
                return Result.ok(java.util.Collections.emptyList());
            }

            log.info("[评测搜索] 找到{}条记录，开始填充用户信息和点赞状态", records.size());

            for (ShowReview review : records) {
                try {
                    queryReviewUser(review);
                    isReviewLiked(review);
                } catch (Exception e) {
                    log.error("[评测搜索] 填充review信息异常，reviewId:{}, error:{}",
                            review.getId(), e.getMessage(), e);
                }
            }

            log.info("[评测搜索] 搜索完成 - total:{}, current:{}",
                    pageResult.getTotal(), pageResult.getCurrent());

            return Result.ok(records);
        } catch (Exception e) {
            log.error("[评测搜索] 异常 - error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.SHOW_REVIEW_SEARCH_FAIL);
        }
    }

    @Override
    public Result<?> queryMyReview(Integer current) {
        UserDTO user = UserHolder.getUser();
        Page<ShowReview> page = query().eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @Override
    public Result<?> queryReviewLiked(Long id) {
        log.debug("[查询点赞用户] 查询回顾:{} 的点赞用户", id);
        String key = REVIEW_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).toList();
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOs = userService.query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream().map(u -> BeanUtil.copyProperties(u, UserDTO.class)).toList();
        return Result.ok(userDTOs);
    }

    @Override
    public Result<?> queryReviewByUserId(Integer current, Long userId) {
        Page<ShowReview> page = query().eq("user_id", userId).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @Override
    public Result<?> queryReviewOfFollow(String cursor) {
        Long userId = UserHolder.getUser().getId();
        log.info("[Feed流查询] 用户:{} 查询关注回顾，cursor:{}", userId, cursor);
        String key = FEED_KEY + userId;

        Long maxTime = null;
        int offset = 0;

        if (cursor != null && !cursor.isEmpty()) {
            try {
                CursorCodec.CursorInfo cursorInfo = CursorCodec.decode(cursor);
                maxTime = Long.parseLong(cursorInfo.getLastTime());
                offset = 1;
            } catch (Exception e) {
                log.warn("[Feed流] 游标解码失败，使用默认值: {}", e.getMessage());
            }
        }

        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, maxTime != null ? maxTime : System.currentTimeMillis(), offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok(CursorPageResult.empty());
        }

        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            if (tuple == null || tuple.getValue() == null || tuple.getScore() == null) {
                continue;
            }
            ids.add(Long.parseLong(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }

        String idStr = StrUtil.join(",", ids);
        List<ShowReview> reviews = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (ShowReview review : reviews) { queryReviewUser(review); isReviewLiked(review); }

        String nextCursor = null;
        boolean hasMore = false;

        if (!reviews.isEmpty()) {
            ShowReview lastReview = reviews.get(reviews.size() - 1);
            CursorCodec.CursorInfo cursorInfo = new CursorCodec.CursorInfo();
            cursorInfo.setLastId(lastReview.getId());
            cursorInfo.setLastTime(String.valueOf(minTime));
            nextCursor = CursorCodec.encode(cursorInfo);
            hasMore = (reviews.size() >= 2);
        }

        return Result.ok(CursorPageResult.of(reviews, nextCursor, hasMore));
    }

    private void queryReviewUser(ShowReview review) {
        try {
            User user = userService.getById(review.getUserId());

            if (user == null) {
                log.warn("[查询评测作者] 用户不存在，userId:{}", review.getUserId());
                review.setUserName("未知用户");
                review.setUserIcon("/default-avatar.png");
                return;
            }

            String nickName = user.getNickName();
            String icon = user.getIcon();

            if (nickName == null || nickName.trim().isEmpty()) {
                nickName = "用户" + review.getUserId();
                log.info("[查询评测作者] 用户昵称为空，使用默认昵称，userId:{}", review.getUserId());
            }

            if (icon == null || icon.trim().isEmpty()) {
                icon = "/default-avatar.png";
                log.debug("[查询评测作者] 用户头像为空，使用默认头像，userId:{}", review.getUserId());
            }

            review.setUserName(nickName);
            review.setUserIcon(icon);
        } catch (Exception e) {
            log.error("[查询评测作者] 异常，reviewId:{}, userId:{}, error:{}",
                    review.getId(), review.getUserId(), e.getMessage(), e);
            review.setUserName("未知用户");
            review.setUserIcon("/default-avatar.png");
        }
    }

    private void isReviewLiked(ShowReview review) {
        try {
            UserDTO user = UserHolder.getUser();

            if (user == null) {
                review.setIsLike(false);
                return;
            }

            if (review.getId() == null) {
                log.warn("[查询点赞状态] reviewId为空");
                review.setIsLike(false);
                return;
            }

            String key = REVIEW_LIKED_KEY + review.getId();
            Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());

            boolean liked = score != null;
            review.setIsLike(liked);

            log.debug("[查询点赞状态] reviewId:{}, userId:{}, isLiked:{}",
                    review.getId(), user.getId(), liked);
        } catch (Exception e) {
            log.error("[查询点赞状态] 异常，reviewId:{}, error:{}",
                    review.getId() != null ? review.getId() : "null", e.getMessage(), e);
            review.setIsLike(false);
        }
    }

    @Override
    public Result<?> queryReviewsByShowId(Long showId, Integer current, Integer size) {
        log.info("[查询演出评测] showId:{}, current:{}, size:{}", showId, current, size);

        if (showId == null || showId <= 0) {
            log.warn("[查询演出评测] 演出ID无效 - showId:{}", showId);
            return Result.fail(AppMessages.SHOW_ID_INVALID);
        }

        try {
            Page<ShowReview> page = new Page<>(current, size);

            QueryWrapper<ShowReview> wrapper = new QueryWrapper<>();
            wrapper.eq("show_id", showId)
                   .orderByDesc("create_time");

            Page<ShowReview> resultPage = this.page(page, wrapper);

            List<ShowReview> records = resultPage.getRecords();

            if (records != null && !records.isEmpty()) {
                for (ShowReview review : records) {
                    queryReviewUser(review);  // 填充用户信息
                    isReviewLiked(review);     // 检查点赞状态
                }
            }

            log.info("[查询演出评测] 成功 - showId:{}, total:{}, currentRecords:{}",
                    showId, resultPage.getTotal(),
                    records != null ? records.size() : 0);

            return Result.ok(resultPage);
        } catch (Exception e) {
            log.error("[查询演出评测] 异常 - showId:{}, error:{}", showId, e.getMessage(), e);
            return Result.fail(AppMessages.format(AppMessages.SHOW_REVIEW_QUERY_FAIL, e.getMessage()));
        }
    }

    /**
     * 更新演出统计数据
     * <p>
     * 在发布/删除评测时调用，同步更新 tb_show 表的：
     * - comments: 评测总数
     * - score: 平均评分
     *
     * @param showId 演出ID
     */
    public void updateShowStatistics(Long showId) {
        if (showId == null || showId <= 0) {
            log.warn("[更新统计] 演出ID无效 - showId:{}", showId);
            return;
        }

        log.info("[更新统计] 开始更新演出统计 - showId:{}", showId);

        // 1. 查询该演出的所有评测数量和平均分
        QueryWrapper<ShowReview> wrapper = new QueryWrapper<>();
        wrapper.eq("show_id", showId)
               .select("COUNT(*) AS total", "AVG(score) AS avg_score");

        // 使用原生SQL查询统计数据（更高效）
        Map<String, Object> statistics = this.getMap(wrapper);

        if (statistics == null || statistics.isEmpty()) {
            log.warn("[更新统计] 未查询到统计数据 - showId:{}", showId);
            return;
        }

        // 2. 提取统计值
        long totalReviews = (statistics.get("total") != null) ?
                Long.parseLong(statistics.get("total").toString()) : 0L;
        double avgScore = (statistics.get("avg_score") != null) ?
                Double.parseDouble(statistics.get("avg_score").toString()) : 0.0;

        // 四舍五入到2位小数
        avgScore = Math.round(avgScore * 100.0) / 100.0;

        log.info("[更新统计] 统计结果 - showId:{}, totalReviews:{}, avgScore:{}",
                showId, totalReviews, avgScore);

        // 3. 更新 tb_show 表
        Show show = new Show();
        show.setId(showId);
        show.setComments((int) totalReviews);
        // score 字段在 Show 实体中是 1-5 整数，直接取整
        show.setScore((int) Math.round(avgScore));

        int updated = showMapper.updateById(show);

        if (updated > 0) {
            log.info("[更新统计] 演出统计更新成功 - showId:{}, comments:{}, score:{}",
                    showId, totalReviews, avgScore);
        } else {
            log.warn("[更新统计] 演出统计更新失败，可能演出不存在 - showId:{}", showId);
        }
    }

    @Override
    public Result<?> deleteReview(Long reviewId, Long userId) {
        log.info("[删除评测] 用户{}请求删除评测{}", userId, reviewId);

        ShowReview review = getById(reviewId);
        if (review == null) {
            log.warn("[删除评测] 评测不存在，reviewId:{}", reviewId);
            return Result.fail("评测不存在");
        }

        if (!review.getUserId().equals(userId)) {
            log.warn("[删除评测] 无权删除，userId:{}, reviewUserId:{}", userId, review.getUserId());
            return Result.fail("无权删除该评测");
        }

        boolean removed = removeById(reviewId);
        if (removed) {
            log.info("[删除评测] 删除成功，reviewId:{}", reviewId);
            stringRedisTemplate.delete("review:liked:" + reviewId);
            return Result.ok();
        } else {
            log.error("[删除评测] 删除失败，reviewId:{}", reviewId);
            return Result.fail("删除失败");
        }
    }
}
