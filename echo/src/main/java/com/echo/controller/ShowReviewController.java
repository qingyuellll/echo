package com.echo.controller;

import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.entity.ShowReview;
import com.echo.service.IShowReviewService;
import com.echo.utils.UserHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
/**
 * 演出评测（笔记）接口
 * <p>
 * 核心接口：
 * - POST   /show-review                  发布完整评测（+3积分，需标题+内容+评分1-5）
 * - DELETE /show-review/{id}            删除评测（仅作者可删除）
 * - POST   /show-review/like/{id}       点赞/取消点赞（+1积分/次）
 * - GET    /show-review/list            评测列表（分页，兼容旧接口）
 * - GET    /show-review/{id}            评测详情
 * - GET    /show-review/my              我的评测
 * - GET    /show-review/feed            关注Feed流
 * <p>
 * 点赞机制：
 *   Redis ZSet: review:liked:{reviewId} → {userId: timestamp}
 *   Lua脚本原子操作：ZSCORE判断 → ZADD点赞/ZREM取消
 * <p>
 * Feed流推送：
 *   发布评测 → 遍历关注者 → ZADD feed:{fanId} {timestamp} {reviewId}
 *   查询Feed → ZREVRANGEBYSCORE feed:{userId} {lastId} 0 LIMIT 0 {pageSize}
 */
@Slf4j
@RestController
@RequestMapping("/show-review")
public class ShowReviewController {

    @Resource
    private IShowReviewService showReviewService;

    @PostMapping
    public Result<?> saveReview(@RequestBody ShowReview review) {
        // 统一登录校验（与其他接口风格一致）
        com.echo.dto.UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.warn("[发布评测] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        log.info("[Controller] 收到发布评测请求 - userId:{}, showId:{}, title:{}, score:{}, content length:{}",
                user.getId(),
                review != null ? review.getShowId() : null,
                review != null && review.getTitle() != null ?
                        (review.getTitle().length() > 20 ?
                                review.getTitle().substring(0, 20) + "..." :
                                review.getTitle()) : "(空)",
                review != null ? review.getScore() : null,
                review != null && review.getContent() != null ? review.getContent().length() : 0);

        if (review == null) {
            log.warn("[Controller] 评测对象为空");
            return Result.fail(AppMessages.REVIEW_INFO_EMPTY);
        }

        // 验证评分（必填）
        if (review.getScore() == null || review.getScore() < 1 || review.getScore() > 5) {
            log.warn("[Controller] 评分无效 - score:{}", review.getScore());
            return Result.fail(AppMessages.SHOW_REVIEW_SCORE_INVALID);
        }

        // 允许纯评分：如果标题和内容都为空，只记录评分
        boolean hasTitle = review.getTitle() != null && !review.getTitle().trim().isEmpty();
        boolean hasContent = review.getContent() != null && !review.getContent().trim().isEmpty();

        if (!hasTitle && !hasContent) {
            log.info("[Controller] 纯评分模式 - 仅记录评分: showId:{}, score:{}",
                    review.getShowId(), review.getScore());
        } else {
            // 如果有标题或内容，验证标题
            if (!hasTitle) {
                log.warn("[Controller] 有内容但标题为空");
                return Result.fail(AppMessages.REVIEW_TITLE_REQUIRED);
            }
        }

        if (review.getShowId() == null || review.getShowId() <= 0) {
            log.warn("[Controller] 演出ID无效，showId:{}", review.getShowId());
            return Result.fail(AppMessages.REVIEW_SHOW_REQUIRED);
        }

        try {
            Result<?> result = showReviewService.saveReview(review);

            if (result.getSuccess()) {
                Object data = result.getData();
                int pointsEarned = (data instanceof Number) ? ((Number) data).intValue() : 0;
                log.info("[Controller] 评测发布成功 - reviewId:{}, 获得积分:+{}", result.getData(), pointsEarned);
            } else {
                log.warn("[Controller] 评测发布失败 - error:{}", result.getErrorMsg());
            }

            return result;
        } catch (Exception e) {
            log.error("[Controller] 发布评测异常 - error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.CONTROLLER_REVIEW_PUBLISH_FAIL_PREFIX + e.getMessage());
        }
    }

    @PutMapping("/like/{id}")
    public Result<?> likeReview(@PathVariable("id") Long id) {
        return showReviewService.likeReview(id);
    }

    @Operation(summary = "删除评测", description = "用户删除自己发布的评测（仅作者可删除）")
    @DeleteMapping("/{id:\\d+}")
    public Result<?> deleteReview(@PathVariable("id") Long id) {
        com.echo.dto.UserDTO user = com.echo.utils.UserHolder.getUser();
        if (user == null) {
            log.warn("[删除评测] 用户未登录");
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        log.info("[删除评测] 开始处理删除请求 - reviewId:{}, userId:{}", id, user.getId());
        return showReviewService.deleteReview(id, user.getId());
    }

    @Operation(summary = "查询评测列表（兼容接口）", description = "查询所有评测列表，推荐使用 /show-review/of/show 或 /show-review/latest 获取更精确结果")
    @GetMapping("/list")
    public Result<?> queryReviewList(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        log.debug("[Controller] 查询评测列表（兼容接口），current:{}, size:{}", current, size);
        return showReviewService.queryLatestReviews(null);
    }

    @GetMapping("/{id:\\d+}")
    public Result<?> queryReviewById(@PathVariable("id") Long id) {
        return showReviewService.queryReviewById(id);
    }

    @GetMapping("/hot")
    public Result<?> queryHotReview(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        return showReviewService.queryHotReview(current, size);
    }

    @GetMapping("/latest")
    public Result<?> queryLatestReviews(
            @RequestParam(value = "cursor", required = false) String cursor) {
        return showReviewService.queryLatestReviews(cursor);
    }

    @GetMapping("/latest-commented")
    public Result<?> queryLatestCommentedReviews(
            @RequestParam(value = "cursor", required = false) String cursor) {
        return showReviewService.queryLatestCommentedReviews(cursor);
    }

    @GetMapping("/recommended")
    public Result<?> queryRecommendedReviews(
            @RequestParam(value = "cursor", required = false) String cursor) {
        return showReviewService.queryRecommendedReviews(cursor);
    }

    @GetMapping("/search")
    public Result<?> searchReviews(
            @Parameter(description = "搜索关键词（标题/内容/用户昵称）")
            @RequestParam(value = "keyword", required = false) String keyword,

            @Min(value = 1, message = "页码必须大于0")
            @Parameter(description = "页码", example = "1")
            @RequestParam(value = "current", defaultValue = "1") Integer current) {

        log.info("[Controller] 搜索评测 - keyword:{}, current:{}",
                keyword != null && !keyword.isEmpty() ? keyword : "(空)", current);

        try {
            Result<?> result = showReviewService.searchReviews(keyword, current);

            if (result.getData() instanceof java.util.List<?> list) {
                log.info("[Controller] 搜索结果 - count:{}", list.size());
            }

            return result;
        } catch (Exception e) {
            log.error("[Controller] 搜索评测异常 - error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.SHOW_REVIEW_SEARCH_FAIL);
        }
    }

    @GetMapping("/of/me")
    public Result<?> queryMyReview(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return showReviewService.queryMyReview(current);
    }

    @GetMapping("/likes/{id}")
    public Result<?> queryReviewLiked(@PathVariable("id") Long id) {
        return showReviewService.queryReviewLiked(id);
    }

    @GetMapping("/of/user")
    public Result<?> queryReviewByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        return showReviewService.queryReviewByUserId(current, id);
    }

    @GetMapping("/of/follow")
    public Result<?> queryReviewOfFollow(
            @RequestParam(value = "cursor", required = false) String cursor) {
        return showReviewService.queryReviewOfFollow(cursor);
    }

    @Operation(summary = "根据演出ID查询评测", description = "查询某个演出的所有评测，支持分页。用于演出详情页展示该演出的观众评价。")
    @GetMapping("/of/show")
    public Result<?> queryReviewsByShowId(
            @NotNull(message = "演出ID不能为空")
            @Min(value = 1, message = "演出ID必须大于0")
            @Parameter(description = "演出ID", required = true)
            @RequestParam("showId") Long showId,

            @Min(value = 1, message = "页码必须大于0")
            @Parameter(description = "页码", example = "1")
            @RequestParam(value = "current", defaultValue = "1") Integer current,

            @Min(value = 1, message = "每页条数必须大于0")
            @Max(value = 50, message = "每页条数不能超过50")
            @Parameter(description = "每页条数", example = "10")
            @RequestParam(value = "size", defaultValue = "10") Integer size) {

        log.info("[Controller] 根据演出ID查询评测 - showId:{}, current:{}, size:{}",
                showId, current, size);

        try {
            Result<?> result = showReviewService.queryReviewsByShowId(showId, current, size);

            if (result.getData() instanceof com.baomidou.mybatisplus.extension.plugins.pagination.Page<?> page) {
                log.info("[Controller] 演出评测查询成功 - showId:{}, total:{}, records:{}",
                        showId, page.getTotal(), page.getRecords().size());
            }

            return result;
        } catch (Exception e) {
            log.error("[Controller] 查询演出评测异常 - showId:{}, error:{}", showId, e.getMessage(), e);
            return Result.fail(AppMessages.CONTROLLER_QUERY_FAIL_PREFIX + e.getMessage());
        }
    }
}
