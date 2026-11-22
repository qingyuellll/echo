package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.dto.Result;
import com.echo.entity.OperationLog;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 操作日志服务接口
 *
 * @author tulip
 */
public interface IOperationLogService extends IService<OperationLog> {

    /**
     * 记录批量操作开始
     *
     * @param operationType 操作类型 (BATCH_ONLINE/BATCH_OFFLINE等)
     * @param targetType 目标实体类型 (show/ticket/order/user)
     * @param targetIds 目标ID列表
     * @param request HTTP请求对象（用于获取IP等信息）
     * @return 创建的日志记录（状态为"进行中"）
     */
    OperationLog logBatchStart(
            String operationType,
            String targetType,
            List<Long> targetIds,
            HttpServletRequest request
    );

    /**
     * 更新日志为成功结果
     *
     * @param logId 日志ID
     * @param successCount 成功数量
     * @param failCount 失败数量
     * @param durationMs 耗时(毫秒)
     */
    void logBatchSuccess(
            Long logId,
            int successCount,
            int failCount,
            long durationMs
    );

    /**
     * 更新日志为失败结果
     *
     * @param logId 日志ID
     * @param errorMessage 错误信息
     * @param durationMs 耗时(毫秒)
     */
    void logBatchFailure(
            Long logId,
            String errorMessage,
            long durationMs
    );

    /**
     * 分页查询操作日志
     *
     * @param operatorId 操作者ID（可选，null表示全部）
     * @param operationType 操作类型（可选）
     * @param resultStatus 结果状态（可选）
     * @param startTime 开始时间（可选）
     * @param endTime 结束时间（可选）
     * @param current 页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    Result<?> queryLogs(
            Long operatorId,
            String operationType,
            Integer resultStatus,
            java.time.LocalDateTime startTime,
            java.time.LocalDateTime endTime,
            int current,
            int pageSize
    );

    /**
     * 获取今日统计数据
     *
     * @return 统计数据Map
     */
    Map<String, Object> getTodayStatistics();

    /**
     * 获取最近N条操作日志
     *
     * @param limit 数量限制
     * @return 日志列表
     */
    List<OperationLog> getRecentLogs(int limit);

    /**
     * 获取每日操作趋势（最近30天）
     *
     * @return 趋势数据 List<Map<date, count>>
     */
    List<Map<String, Object>> getDailyTrend();

    /**
     * 获取每小时性能数据（最近24小时）
     *
     * @return 性能数据 List<Map<hour, avgDuration>>
     */
    List<Map<String, Object>> getHourlyPerformance();

    /**
     * 导出操作日志（Excel/CSV格式）
     *
     * @param params 查询参数（同queryLogs）
     * @return 文件下载URL或Base64编码的数据
     */
    Result<?> exportLogs(Map<String, Object> params);
}
