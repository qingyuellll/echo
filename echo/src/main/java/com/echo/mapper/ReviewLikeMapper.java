package com.echo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.echo.entity.ReviewLike;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 评测点赞数据访问接口
 *
 * @author tulip
 */
@Mapper
public interface ReviewLikeMapper extends BaseMapper<ReviewLike> {

    @Select("SELECT COUNT(*) > 0 FROM tb_review_like WHERE review_id = #{reviewId} AND user_id = #{userId}")
    boolean isLiked(@Param("reviewId") Long reviewId, @Param("userId") Long userId);

    @Delete("DELETE FROM tb_review_like WHERE review_id = #{reviewId} AND user_id = #{userId}")
    void deleteByReviewAndUser(@Param("reviewId") Long reviewId, @Param("userId") Long userId);
}
