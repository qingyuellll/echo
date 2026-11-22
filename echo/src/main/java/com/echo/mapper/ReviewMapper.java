package com.echo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.echo.entity.Review;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 评测数据访问接口
 *
 * @author tulip
 */
@Mapper
public interface ReviewMapper extends BaseMapper<Review> {

    IPage<Review> selectReviewPageWithUser(
        Page<Review> page,
        @Param("showId") Long showId,
        @Param("showStatus") Integer showStatus
    );

    @Select("SELECT COUNT(*) FROM ${tablePrefix}review WHERE show_id = #{showId} AND status = 1")
    int countByShowId(@Param("showId") Long showId, @Param("tablePrefix") String tablePrefix);

    @Select("SELECT COALESCE(ROUND(AVG(score), 2), 0.00) FROM ${tablePrefix}review WHERE show_id = #{showId} AND status = 1")
    Double calculateAvgScore(@Param("showId") Long showId, @Param("tablePrefix") String tablePrefix);

    @Select("SELECT COUNT(*) > 0 FROM ${tablePrefix}review WHERE order_id = #{orderId} AND user_id = #{userId}")
    boolean existsByOrderAndUser(@Param("orderId") Long orderId, @Param("userId") Long userId, @Param("tablePrefix") String tablePrefix);
}
