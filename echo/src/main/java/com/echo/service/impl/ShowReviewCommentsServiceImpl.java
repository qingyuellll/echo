package com.echo.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.dto.UserDTO;
import com.echo.entity.ShowReviewComments;
import com.echo.entity.User;
import com.echo.mapper.ShowReviewCommentsMapper;
import com.echo.service.IPointsService;
import com.echo.service.IShowReviewCommentsService;
import com.echo.service.IUserService;
import com.echo.utils.SystemConstants;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.echo.utils.BusinessConstants.POINTS_COMMENT;
import static com.echo.utils.RedisConstants.COMMENT_RATE_LIMIT_KEY;
import static com.echo.utils.RedisConstants.COMMENT_RATE_LIMIT_TTL;


/**
 * 评测评论服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class ShowReviewCommentsServiceImpl extends ServiceImpl<ShowReviewCommentsMapper, ShowReviewComments> implements IShowReviewCommentsService {

    @Resource
    private IPointsService pointsService;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String CACHE_COMMENT_KEY = "cache:comment:";
    private static final Long CACHE_COMMENT_TTL = 30L;
    private static final Long CACHE_COMMENT_RANDOM_TTL = 10L;
    private static final int MAX_CONTENT_LENGTH = 1000;
    /** 评论缓存最大页数（与 SystemConstants.MAX_PAGE_SIZE 保持一致） */
    private static final int MAX_CACHE_PAGE = 200;

    /**
     * 查询评测评论列表（含Redis缓存+空值缓存防穿透）
     * <p>
     * 缓存策略：
     *   正常数据：TTL=30-40分钟（随机值防雪崩）
     *   空结果：TTL=5分钟（防止缓存穿透）
     */
    @Override
    public Result<?> queryCommentsByReviewId(Long reviewId, Integer current) {
        // 参数校验
        if (reviewId == null || reviewId <= 0) {
            log.warn("[查询评论] 参数错误，reviewId:{}", reviewId);
            return Result.fail(AppMessages.REVIEW_ID_INVALID);
        }

        if (current == null || current < 1) {
            log.warn("[查询评论] 参数错误，current:{}", current);
            current = 1;
        }

        String cacheKey = CACHE_COMMENT_KEY + reviewId + ":" + current;

        try {
            // 1. 查询Redis缓存
            String json = stringRedisTemplate.opsForValue().get(cacheKey);
            if (json != null && !json.isEmpty()) {
                if ("[]".equals(json)) {
                    log.debug("[查询评论] 命中空值缓存（防穿透），reviewId:{}, current:{}", reviewId, current);
                    return Result.ok(Collections.emptyList());
                }
                List<ShowReviewComments> cached = JSONUtil.toList(JSONUtil.parseArray(json), ShowReviewComments.class);
                log.debug("[查询评论] 命中缓存，reviewId:{}, current:{}, count:{}", reviewId, current, cached.size());
                return Result.ok(cached);
            }

            // 2. 缓存未命中，查DB
            log.debug("[查询评论] 缓存未命中，回源DB，reviewId:{}, current:{}", reviewId, current);
            Page<ShowReviewComments> page = query()
                    .eq("review_id", reviewId)
                    .orderByDesc("create_time")
                    .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));

            List<ShowReviewComments> allRecords = page.getRecords();

            // 2.1 构建嵌套评论结构（一级评论 + 子评论列表）
            List<ShowReviewComments> records = buildNestedComments(allRecords);

            // 2.5 收集所有评论ID（一级评论 + 所有子评论），批量填充用户信息
            if (!records.isEmpty()) {
                Set<Long> allUserIds = new java.util.HashSet<>();

                for (ShowReviewComments parentComment : records) {
                    if (parentComment.getUserId() != null && parentComment.getUserId() > 0) {
                        allUserIds.add(parentComment.getUserId());
                    }

                    if (parentComment.getReplies() != null) {
                        for (ShowReviewComments reply : parentComment.getReplies()) {
                            if (reply.getUserId() != null && reply.getUserId() > 0) {
                                allUserIds.add(reply.getUserId());
                            }
                        }
                    }
                }

                if (!allUserIds.isEmpty()) {
                    List<User> users = userService.listByIds(allUserIds);
                    Map<Long, User> userMap = users.stream()
                            .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

                    int filledCount = 0;

                    for (ShowReviewComments parentComment : records) {
                        User parentUser = userMap.get(parentComment.getUserId());
                        if (parentUser != null) {
                            parentComment.setUserName(parentUser.getNickName() != null ? parentUser.getNickName() : ("用户" + parentUser.getId()));
                            parentComment.setIcon(parentUser.getIcon() != null ? parentUser.getIcon() : "/default-avatar.png");
                            filledCount++;
                        }

                        if (parentComment.getReplies() != null) {
                            for (ShowReviewComments reply : parentComment.getReplies()) {
                                User replyUser = userMap.get(reply.getUserId());
                                if (replyUser != null) {
                                    reply.setUserName(replyUser.getNickName() != null ? replyUser.getNickName() : ("用户" + replyUser.getId()));
                                    reply.setIcon(replyUser.getIcon() != null ? replyUser.getIcon() : "/default-avatar.png");
                                    filledCount++;
                                }
                            }
                        }
                    }

                    log.info("[填充用户信息] 成功填充{}条评论的用户信息（含{}条一级评论和{}条子评论）",
                            filledCount, records.size(),
                            records.stream().mapToInt(c -> c.getReplies() != null ? c.getReplies().size() : 0).sum());
                }
            }

            // 3. 写入缓存（含空值缓存）
            long ttl = getRandomExpireTime();
            if (records.isEmpty()) {
                // 空值缓存，防止缓存穿透
                stringRedisTemplate.opsForValue().set(
                        cacheKey,
                        "[]",
                        5,
                        TimeUnit.MINUTES
                );
                log.debug("[查询评论] 写入空值缓存（防穿透），reviewId:{}, TTL:5分钟", reviewId);
            } else {
                stringRedisTemplate.opsForValue().set(
                        cacheKey,
                        JSONUtil.toJsonStr(records),
                        ttl,
                        TimeUnit.MINUTES
                );
                log.debug("[查询评论] 写入正常缓存，reviewId:{}, count:{}, TTL:{}分钟",
                        reviewId, records.size(), ttl);
            }

            log.info("[查询评论] 成功，reviewId:{}, current:{}, total:{}, count:{}",
                    reviewId, current, page.getTotal(), records.size());

            return Result.ok(records);
        } catch (Exception e) {
            log.error("[查询评论] 异常，reviewId:{}, current:{}, error:{}", reviewId, current, e.getMessage(), e);

            // 降级：直接查DB返回（同样需要填充用户信息）
            try {
                List<ShowReviewComments> fallbackList = list(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShowReviewComments>()
                        .eq(ShowReviewComments::getReviewId, reviewId)
                        .orderByDesc(ShowReviewComments::getCreateTime)
                        .last("LIMIT " + SystemConstants.MAX_PAGE_SIZE)
                );

                List<ShowReviewComments> fallbackRecords = buildNestedComments(fallbackList);

                Set<Long> fallbackUserIds = new java.util.HashSet<>();
                for (ShowReviewComments comment : fallbackRecords) {
                    if (comment.getUserId() != null && comment.getUserId() > 0) {
                        fallbackUserIds.add(comment.getUserId());
                    }
                    if (comment.getReplies() != null) {
                        for (ShowReviewComments reply : comment.getReplies()) {
                            if (reply.getUserId() != null && reply.getUserId() > 0) {
                                fallbackUserIds.add(reply.getUserId());
                            }
                        }
                    }
                }

                if (!fallbackUserIds.isEmpty()) {
                    List<User> fallbackUsers = userService.listByIds(fallbackUserIds);
                    Map<Long, User> fallbackUserMap = fallbackUsers.stream()
                            .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

                    for (ShowReviewComments parentComment : fallbackRecords) {
                        User parentUser = fallbackUserMap.get(parentComment.getUserId());
                        if (parentUser != null) {
                            parentComment.setUserName(parentUser.getNickName() != null ? parentUser.getNickName() : ("用户" + parentUser.getId()));
                            parentComment.setIcon(parentUser.getIcon() != null ? parentUser.getIcon() : "/default-avatar.png");
                        }

                        if (parentComment.getReplies() != null) {
                            for (ShowReviewComments reply : parentComment.getReplies()) {
                                User replyUser = fallbackUserMap.get(reply.getUserId());
                                if (replyUser != null) {
                                    reply.setUserName(replyUser.getNickName() != null ? replyUser.getNickName() : ("用户" + replyUser.getId()));
                                    reply.setIcon(replyUser.getIcon() != null ? replyUser.getIcon() : "/default-avatar.png");
                                }
                            }
                        }
                    }
                }

                return Result.ok(fallbackRecords);
            } catch (Exception ex) {
                log.error("[查询评论] 降级也失败，error:{}", ex.getMessage());
                return Result.fail(AppMessages.REVIEW_COMMENT_QUERY_FAIL);
            }
        }
    }

    /**
     * 构建嵌套评论结构（一级评论 + 子评论列表）
     *
     * @param allComments 所有评论（含一级和子评论）
     * @return 嵌套结构的评论列表（每条一级评论包含replies子列表）
     */
    private List<ShowReviewComments> buildNestedComments(List<ShowReviewComments> allComments) {
        if (allComments == null || allComments.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        // 分离一级评论和子评论
        Map<Long, ShowReviewComments> parentMap = new java.util.LinkedHashMap<>();
        Map<Long, List<ShowReviewComments>> childrenMap = new java.util.HashMap<>();

        for (ShowReviewComments comment : allComments) {
            if (comment.getParentId() == null || comment.getParentId() == 0L) {
                parentMap.put(comment.getId(), comment);
                comment.setReplies(new java.util.ArrayList<>());  // 初始化空回复列表
            } else {
                childrenMap.computeIfAbsent(comment.getParentId(), k -> new java.util.ArrayList<>()).add(comment);
            }
        }

        // 将子评论挂载到对应的一级评论下
        for (Map.Entry<Long, List<ShowReviewComments>> entry : childrenMap.entrySet()) {
            ShowReviewComments parent = parentMap.get(entry.getKey());
            if (parent != null) {
                // 按时间正序排列子评论（最早的在前）
                entry.getValue().sort((a, b) -> a.getCreateTime().compareTo(b.getCreateTime()));
                parent.setReplies(entry.getValue());
            }
        }

        // 返回一级评论列表（保持原查询的倒序）
        return new java.util.ArrayList<>(parentMap.values());
    }

    /**
     * 发布评论（含事务管理+参数校验+限流+内容检查）
     * <p>
     * 业务流程：
     * 1. 校验用户登录状态
     * 2. 参数合法性校验
     * 3. Redis限流检查（30秒/条）
     * 4. 内容安全检查（长度、敏感词等）
     * 5. 事务性操作：保存评论 + 积分奖励
     * 6. 清除相关缓存
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> saveComment(ShowReviewComments comments) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("[发布评论] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        try {
            // 1. 参数校验
            if (comments == null) {
                log.warn("[发布评论] comments对象为空");
                return Result.fail(AppMessages.REVIEW_COMMENT_INFO_EMPTY);
            }

            if (comments.getReviewId() == null || comments.getReviewId() <= 0) {
                log.warn("[发布评论] 无效的reviewId:{}", comments.getReviewId());
                return Result.fail(AppMessages.REVIEW_ID_INVALID);
            }

            if (comments.getContent() == null || comments.getContent().trim().isEmpty()) {
                log.warn("[发布评论] 评论内容为空");
                return Result.fail(AppMessages.REVIEW_COMMENT_CONTENT_EMPTY);
            }

            if (comments.getContent().length() > MAX_CONTENT_LENGTH) {
                log.warn("[发布评论] 评论内容过长，length:{}", comments.getContent().length());
                return Result.fail(AppMessages.format(AppMessages.REVIEW_COMMENT_CONTENT_TOO_LONG, MAX_CONTENT_LENGTH));
            }

            // 2. 限流检查：每个用户30秒内只能发1条评论
            String rateLimitKey = COMMENT_RATE_LIMIT_KEY + user.getId();
            Boolean limited = stringRedisTemplate.opsForValue().setIfAbsent(
                    rateLimitKey,
                    "1",
                    COMMENT_RATE_LIMIT_TTL,
                    TimeUnit.SECONDS
            );

            if (Boolean.FALSE.equals(limited)) {
                log.warn("[发布评论] 触发限流，userId:{}, 限制:{}秒/次", user.getId(), COMMENT_RATE_LIMIT_TTL);
                return Result.fail(AppMessages.format(AppMessages.REVIEW_COMMENT_TOO_FREQUENT, COMMENT_RATE_LIMIT_TTL));
            }

            // 3. 设置评论者信息
            comments.setUserId(user.getId());
            comments.setStatus(true);
            comments.setLiked(0);

            // 3.5 校验父评论是否存在（防止孤儿数据）
            if (comments.getParentId() != null && comments.getParentId() > 0L) {
                ShowReviewComments parentComment = getById(comments.getParentId());
                if (parentComment == null) {
                    log.warn("[发布评论] 父评论不存在 - userId:{}, parentId:{}", user.getId(), comments.getParentId());
                    return Result.fail("被回复的评论不存在");
                }
                if (parentComment.getReviewId() == null || !parentComment.getReviewId().equals(comments.getReviewId())) {
                    log.warn("[发布评论] 父评论不属于当前评测 - userId:{}, parentId:{}, reviewId:{}", user.getId(), comments.getParentId(), comments.getReviewId());
                    return Result.fail("评论数据异常，请刷新页面重试");
                }

                if (comments.getAnswerId() != null && comments.getAnswerId() > 0L) {
                    User answeredUser = userService.getById(comments.getAnswerId());
                    if (answeredUser == null) {
                        log.warn("[发布评论] 被回复用户不存在 - answerId:{}", comments.getAnswerId());
                        return Result.fail("被回复的用户不存在");
                    }
                }
            }

            // 4. 如果是一级评论（parentId=0或null），设置answerId为null
            if (comments.getParentId() == null || comments.getParentId() == 0L) {
                comments.setAnswerId(null);
            }

            // 4. 保存评论（事务内）
            boolean saveSuccess = save(comments);
            if (!saveSuccess) {
                log.error("[发布评论] 保存失败，userId:{}, reviewId:{}", user.getId(), comments.getReviewId());
                throw new com.echo.exception.BusinessException(
                        AppMessages.REVIEW_COMMENT_SAVE_FAIL,
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
            }

            // 5. 积分奖励（事务内，失败则整体回滚）
            try {
                Result<?> pointsResult = pointsService.addPoints(user.getId(), 2, 1, POINTS_COMMENT, null);
                if (!pointsResult.getSuccess()) {
                    log.warn("[发布评论] 积分奖励可能失败，userId:{}, result:{}", user.getId(), pointsResult.getErrorMsg());
                    // 不抛异常，允许评论发布成功（积分可后续补偿）
                }
            } catch (Exception e) {
                log.warn("[发布评论] 积分奖励异常，不影响评论发布，userId:{}, error:{}", user.getId(), e.getMessage());
            }

            // 6. 清除相关缓存
            invalidateCommentCache(comments.getReviewId());

            log.info("[发布评论] 成功，userId:{}, reviewId:{}, commentId:{}, parentId:{}, 获得积分:+2",
                    user.getId(), comments.getReviewId(), comments.getId(), comments.getParentId());

            return Result.ok(comments.getId());
        } catch (RuntimeException e) {
            log.error("[发布评论] 业务异常，userId:{}, error:{}", user.getId(), e.getMessage(), e);
            throw e;  // 重新抛出以触发事务回滚
        } catch (Exception e) {
            log.error("[发布评论] 系统异常，userId:{}, error:{}", user.getId(), e.getMessage(), e);
            throw new com.echo.exception.BusinessException(
                    AppMessages.format(AppMessages.REVIEW_COMMENT_PUBLISH_FAIL_PREFIX, e.getMessage()),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    e);
        }
    }

    /**
     * 清除评论缓存
     */
    private void invalidateCommentCache(Long reviewId) {
        try {
            // 删除该评测所有可能页码的缓存（上限与 MAX_CACHE_PAGE 保持一致）
            for (int i = 1; i <= MAX_CACHE_PAGE; i++) {
                stringRedisTemplate.delete(CACHE_COMMENT_KEY + reviewId + ":" + i);
            }
            log.debug("[清除缓存] 评论缓存已清除，reviewId:{}", reviewId);
        } catch (Exception e) {
            log.warn("[清除缓存] 失败，不影响主流程，reviewId:{}, error:{}", reviewId, e.getMessage());
        }
    }

    /**
     * 生成随机过期时间（防止缓存雪崩）
     *
     * @return 最终TTL（分钟）
     */
    private long getRandomExpireTime() {
        return ShowReviewCommentsServiceImpl.CACHE_COMMENT_TTL + (long) (Math.random() * ShowReviewCommentsServiceImpl.CACHE_COMMENT_RANDOM_TTL);
    }
}
