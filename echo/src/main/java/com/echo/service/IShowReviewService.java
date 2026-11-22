package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.dto.Result;
import com.echo.entity.ShowReview;

/**
 * 演出评测服务接口
 *
 * @author tulip
 */
public interface IShowReviewService extends IService<ShowReview> {
    Result<?> saveReview(ShowReview review);
    Result<?> likeReview(Long id);
    Result<?> queryReviewById(Long id);
    Result<?> queryHotReview(Integer current, Integer size);
    Result<?> queryLatestReviews(String cursor);
    Result<?> queryLatestCommentedReviews(String cursor);
    Result<?> queryRecommendedReviews(String cursor);
    Result<?> searchReviews(String keyword, Integer current);
    Result<?> queryMyReview(Integer current);
    Result<?> queryReviewLiked(Long id);
    Result<?> queryReviewByUserId(Integer current, Long userId);
    Result<?> queryReviewOfFollow(String cursor);
    Result<?> queryReviewsByShowId(Long showId, Integer current, Integer size);
    Result<?> deleteReview(Long reviewId, Long userId);
}
