package com.echo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.constants.AppMessages;
import com.echo.dto.PageResult;
import com.echo.dto.Result;
import com.echo.entity.OperationLog;
import com.echo.enums.OperationResultStatus;
import com.echo.mapper.OperationLogMapper;
import com.echo.service.IOperationLogService;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作日志服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class OperationLogServiceImpl
        extends ServiceImpl<OperationLogMapper, OperationLog>
        implements IOperationLogService {

    private static final int MAX_EXPORT_SIZE = 10000;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationLog logBatchStart(
            String operationType,
            String targetType,
            List<Long> targetIds,
            HttpServletRequest request) {

        Long operatorId = null;
        String operatorName = "未知操作者";

        if (UserHolder.getAdmin() != null) {
            operatorId = UserHolder.getAdmin().getId();
            operatorName = UserHolder.getAdmin().getName();
        } else if (UserHolder.getUser() != null) {
            operatorId = UserHolder.getUser().getId();
            operatorName = UserHolder.getUser().getNickName();
        }

        OperationLog operationLog = new OperationLog();
        operationLog.setOperatorId(operatorId);
        operationLog.setOperatorName(operatorName);
        operationLog.setOperationType(operationType);
        operationLog.setTargetType(targetType);

        if (targetIds != null && !targetIds.isEmpty()) {
            operationLog.setTargetIds(com.alibaba.fastjson.JSON.toJSONString(targetIds));
        }

        setClientInfo(operationLog, request);

        operationLog.setResultStatus(OperationResultStatus.IN_PROGRESS);
        operationLog.setSuccessCount(0);
        operationLog.setFailCount(0);
        operationLog.setRequestId(generateRequestId());

        this.save(operationLog);

        log.info("[操作日志] 批量操作开始 - logId:{}, type:{}, target:{}, count:{}",
                operationLog.getId(),
                operationType,
                targetType,
                targetIds != null ? targetIds.size() : 0);

        return operationLog;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void logBatchSuccess(Long logId, int successCount, int failCount, long durationMs) {
        OperationLog operationLog = this.getById(logId);
        if (operationLog == null) {
            log.warn("[操作日志] 日志记录不存在 - logId:{}", logId);
            return;
        }

        operationLog.setResultStatus(failCount == 0 ? OperationResultStatus.SUCCESS : OperationResultStatus.PARTIAL_SUCCESS);
        operationLog.setSuccessCount(successCount);
        operationLog.setFailCount(failCount);
        operationLog.setDurationMs(durationMs);

        this.updateById(operationLog);

        log.info("[操作日志] 批量操作成功 - logId:{}, success:{}, fail:{}, duration:{}ms",
                logId, successCount, failCount, durationMs);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void logBatchFailure(Long logId, String errorMessage, long durationMs) {
        OperationLog operationLog = this.getById(logId);
        if (operationLog == null) {
            log.warn("[操作日志] 日志记录不存在 - logId:{}", logId);
            return;
        }

        operationLog.setResultStatus(OperationResultStatus.FAIL);
        operationLog.setErrorMessage(errorMessage);
        operationLog.setDurationMs(durationMs);

        this.updateById(operationLog);

        log.warn("[操作日志] 批量操作失败 - logId:{}, error:{}, duration:{}ms",
                logId, errorMessage, durationMs);
    }

    @Override
    public Result<?> queryLogs(
            Long operatorId,
            String operationType,
            Integer resultStatus,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int current,
            int pageSize) {

        LambdaQueryWrapper<OperationLog> wrapper = buildQueryWrapper(operatorId, operationType, resultStatus, startTime, endTime);
        wrapper.orderByDesc(OperationLog::getCreateTime);

        Page<OperationLog> page = new Page<>(current, pageSize);
        Page<OperationLog> result = this.page(page, wrapper);

        PageResult<OperationLog> pageResult = PageResult.from(result);

        log.debug("[操作日志] 查询日志列表 - total:{}, current:{}", result.getTotal(), current);

        return Result.ok(pageResult);
    }

    @Override
    public Map<String, Object> getTodayStatistics() {
        try {
            Map<String, Object> stats = baseMapper.getTodayStatistics();

            if (stats == null || stats.isEmpty()) {
                return getDefaultStatistics();
            }

            long total = ((Number) stats.getOrDefault("todayCount", 0)).longValue();
            long success = ((Number) stats.getOrDefault("successCount", 0)).longValue();
            double successRate = total > 0 ? (double) success / total * 100 : 100.0;
            stats.put("successRate", Math.round(successRate * 100) / 100.0);

            return stats;

        } catch (Exception e) {
            log.error("[操作日志] 获取今日统计失败 - error:{}", e.getMessage(), e);
            return getDefaultStatistics();
        }
    }

    @Override
    public List<OperationLog> getRecentLogs(int limit) {
        try {
            return baseMapper.findRecentLogs(limit);
        } catch (Exception e) {
            log.error("[操作日志] 获取最近日志失败 - error:{}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<Map<String, Object>> getDailyTrend() {
        try {
            return baseMapper.getDailyTrend();
        } catch (Exception e) {
            log.error("[操作日志] 获取每日趋势失败 - error:{}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<Map<String, Object>> getHourlyPerformance() {
        try {
            return baseMapper.getHourlyPerformance();
        } catch (Exception e) {
            log.error("[操作日志] 获取每小时性能失败 - error:{}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public Result<?> exportLogs(Map<String, Object> params) {
        log.info("[操作日志-导出] 开始处理导出请求 - params:{}", params);

        Long operatorId = params.get("operatorId") != null ? Long.valueOf(params.get("operatorId").toString()) : null;
        String operationType = (String) params.get("operationType");
        Integer resultStatus = params.get("resultStatus") != null ? Integer.valueOf(params.get("resultStatus").toString()) : null;

        LocalDateTime startTime = null;
        LocalDateTime endTime = null;

        if (params.get("startTime") != null) {
            startTime = LocalDateTime.parse(params.get("startTime").toString());
        }
        if (params.get("endTime") != null) {
            endTime = LocalDateTime.parse(params.get("endTime").toString());
        }

        LambdaQueryWrapper<OperationLog> queryWrapper = buildQueryWrapper(operatorId, operationType, resultStatus, startTime, endTime);
        queryWrapper.orderByDesc(OperationLog::getCreateTime);

        List<OperationLog> logs = baseMapper.selectList(queryWrapper);

        if (logs == null || logs.isEmpty()) {
            log.warn("[操作日志-导出] 无符合条件的数据");
            return Result.fail("没有找到符合条件的操作日志");
        }

        if (logs.size() > MAX_EXPORT_SIZE) {
            log.warn("[操作日志-导出] 数据量超过限制 - 实际:{}, 限制:{}", logs.size(), MAX_EXPORT_SIZE);
            return Result.fail("导出数据量过大（" + logs.size() + "条），请缩小查询范围（最大支持" + MAX_EXPORT_SIZE + "条）");
        }

        log.info("[操作日志-导出] 查询到{}条记录，开始生成Excel", logs.size());

        cn.hutool.poi.excel.ExcelWriter writer = cn.hutool.poi.excel.ExcelUtil.getWriter(true);

        writer.addHeaderAlias("id", "日志ID");
        writer.addHeaderAlias("operatorId", "操作者ID");
        writer.addHeaderAlias("operatorName", "操作者名称");
        writer.addHeaderAlias("operationType", "操作类型");
        writer.addHeaderAlias("targetType", "目标类型");
        writer.addHeaderAlias("targetIds", "目标ID列表");
        writer.addHeaderAlias("targetCount", "目标数量");
        writer.addHeaderAlias("successCount", "成功数量");
        writer.addHeaderAlias("failCount", "失败数量");
        writer.addHeaderAlias("resultStatus", "结果状态");
        writer.addHeaderAlias("errorMessage", "错误信息");
        writer.addHeaderAlias("durationMs", "耗时(ms)");
        writer.addHeaderAlias("ipAddress", "IP地址");
        writer.addHeaderAlias("userAgent", "用户代理");
        writer.addHeaderAlias("createTime", "创建时间");

        writer.write(logs, true);

        String fileName = "操作日志导出_" + System.currentTimeMillis() + ".xlsx";
        String base64Data;
        int fileSize;

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            writer.flush(outputStream, true);
            base64Data = Base64.getEncoder().encodeToString(outputStream.toByteArray());
            fileSize = outputStream.size();

            log.info("[操作日志-导出] Excel生成成功 - 文件名:{}, 大小:{}bytes, 记录数:{}",
                    fileName, fileSize, logs.size());
        } catch (java.io.IOException e) {
            log.error("[操作日志-导出] Excel生成失败", e);
            return Result.fail("Excel文件生成失败：" + e.getMessage());
        } finally {
            writer.close();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("fileName", fileName);
        result.put("fileSize", fileSize);
        result.put("recordCount", logs.size());
        result.put("contentType", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        result.put("base64Data", base64Data);

        return Result.ok(result);
    }

    private LambdaQueryWrapper<OperationLog> buildQueryWrapper(
            Long operatorId,
            String operationType,
            Integer resultStatus,
            LocalDateTime startTime,
            LocalDateTime endTime) {

        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();

        if (operatorId != null) {
            wrapper.eq(OperationLog::getOperatorId, operatorId);
        }

        if (operationType != null && !operationType.isEmpty()) {
            wrapper.eq(OperationLog::getOperationType, operationType);
        }

        if (resultStatus != null) {
            wrapper.eq(OperationLog::getResultStatus, OperationResultStatus.fromCode(resultStatus));
        }

        if (startTime != null) {
            wrapper.ge(OperationLog::getCreateTime, startTime);
        }

        if (endTime != null) {
            wrapper.le(OperationLog::getCreateTime, endTime);
        }

        return wrapper;
    }

    private void setClientInfo(OperationLog log, HttpServletRequest request) {
        if (request != null) {
            log.setIpAddress(getClientIp(request));
            log.setUserAgent(request.getHeader("User-Agent"));
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }

        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }

        return ip;
    }

    private String generateRequestId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private Map<String, Object> getDefaultStatistics() {
        Map<String, Object> defaultStats = new HashMap<>();
        defaultStats.put("todayCount", 0);
        defaultStats.put("successCount", 0);
        defaultStats.put("failCount", 0);
        defaultStats.put("avgDuration", 0);
        defaultStats.put("successRate", 100.0);
        return defaultStats;
    }
}
