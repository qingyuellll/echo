package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.dto.Result;
import com.echo.entity.ShowReviewComments;

/**
 * 评测评论服务接口
 *
 * @author tulip
 */
public interface IShowReviewCommentsService extends IService<ShowReviewComments> {
    Result<?> queryCommentsByReviewId(Long reviewId, Integer current);
    Result<?> saveComment(ShowReviewComments comments);
}
