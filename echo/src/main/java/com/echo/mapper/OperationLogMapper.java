package com.echo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.echo.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 操作日志数据访问接口
 *
 * @author tulip
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {

    @Select("SELECT * FROM operation_log " +
            "WHERE admin_id = #{operatorId} " +
            "AND created_at BETWEEN #{start} AND #{end} " +
            "ORDER BY created_at DESC")
    List<OperationLog> findByOperatorIdAndTimeRange(
            @Param("operatorId") Long operatorId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    @Select("SELECT operation_type, COUNT(*) as count " +
            "FROM operation_log " +
            "WHERE created_at BETWEEN #{startTime} AND #{endTime} " +
            "GROUP BY operation_type")
    List<Map<String, Object>> countByOperationType(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    @Select("SELECT " +
            "COUNT(*) as todayCount, " +
            "SUM(CASE WHEN result_status IN (1, 3) THEN 1 ELSE 0 END) as successCount, " +
            "SUM(CASE WHEN result_status = 2 THEN 1 ELSE 0 END) as failCount, " +
            "AVG(duration_ms) as avgDuration " +
            "FROM operation_log " +
            "WHERE DATE(created_at) = CURDATE()")
    Map<String, Object> getTodayStatistics();

    @Select("SELECT * FROM operation_log ORDER BY created_at DESC LIMIT #{limit}")
    List<OperationLog> findRecentLogs(@Param("limit") int limit);

    @Select("SELECT DATE(created_at) as date, COUNT(*) as count " +
            "FROM operation_log " +
            "WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL 30 DAY) " +
            "GROUP BY DATE(created_at) " +
            "ORDER BY date ASC")
    List<Map<String, Object>> getDailyTrend();

    @Select("SELECT HOUR(created_at) as hour, COUNT(*) as count, " +
            "AVG(duration_ms) as avgDuration, " +
            "SUM(CASE WHEN result_status IN (1, 3) THEN 1 ELSE 0 END) as successCount, " +
            "SUM(CASE WHEN result_status = 2 THEN 1 ELSE 0 END) as failCount " +
            "FROM operation_log " +
            "WHERE DATE(created_at) = CURDATE() " +
            "GROUP BY HOUR(created_at) " +
            "ORDER BY hour ASC")
    List<Map<String, Object>> getHourlyPerformance();
}
