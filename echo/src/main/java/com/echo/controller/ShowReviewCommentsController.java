package com.echo.controller;

import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.entity.ShowReviewComments;
import com.echo.service.IShowReviewCommentsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;


/**
 * 评测评论管理控制器
 *
 * 提供演出评测的评论发布、查询、删除等互动功能接口
 *
 * @author tulip
 */
@Tag(name = "评测评论管理")
@Slf4j
@Validated
@RestController
@RequestMapping("/show-review-comments")
public class ShowReviewCommentsController {

    @Resource
    private IShowReviewCommentsService showReviewCommentsService;

    @Operation(summary = "查询评测评论列表", description = "分页查询指定评测的一级评论，按时间倒序排列")
    @GetMapping("/of/{reviewId}")
    public Result<?> queryCommentsByReviewId(
            @NotNull(message = "评测ID不能为空")
            @Min(value = 1, message = "评测ID必须大于0")
            @Parameter(description = "评测ID", required = true)
            @PathVariable("reviewId") Long reviewId,

            @Min(value = 1, message = "页码必须大于0")
            @Parameter(description = "页码", example = "1")
            @RequestParam(value = "current", defaultValue = "1") Integer current) {

        log.debug("[Controller] 查询评测评论，reviewId:{}, current:{}", reviewId, current);
        return showReviewCommentsService.queryCommentsByReviewId(reviewId, current);
    }

    @Operation(summary = "发布评论", description = "发布一级评论或子评论，成功后获得+2积分奖励。请求体示例：{\"reviewId\": 11, \"parentId\": 0, \"answerId\": null, \"content\": \"这条评测写得太好了！\"}")
    @PostMapping("/save")
    public Result<?> saveComment(
            @Parameter(description = "评论信息", required = true)
            @RequestBody ShowReviewComments comments) {

        log.info("[Controller] 收到发布评论请求 - reviewId:{}, parentId:{}, answerId:{}, content:{}",
                comments != null ? comments.getReviewId() : null,
                comments != null ? comments.getParentId() : null,
                comments != null ? comments.getAnswerId() : null,
                comments != null && comments.getContent() != null ?
                        (comments.getContent().length() > 30 ?
                                comments.getContent().substring(0, 30) + "..." :
                                comments.getContent()) : "(空)");

        if (comments == null) {
            log.warn("[Controller] 评论对象为空");
            return Result.fail(AppMessages.REVIEW_COMMENT_INFO_EMPTY);
        }

        try {
            Result<?> result = showReviewCommentsService.saveComment(comments);

            if (result.getSuccess()) {
                log.info("[Controller] 评论发布成功 - commentId:{}", result.getData());
            } else {
                log.warn("[Controller] 评论发布失败 - error:{}", result.getErrorMsg());
            }

            return result;
        } catch (Exception e) {
            log.error("[Controller] 发布评论异常 - error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.REVIEW_COMMENT_PUBLISH_FAIL_PREFIX + e.getMessage());
        }
    }
}
