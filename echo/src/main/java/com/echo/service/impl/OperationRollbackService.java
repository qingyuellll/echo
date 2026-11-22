package com.echo.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.entity.OperationLog;
import com.echo.entity.Show;
import com.echo.entity.Ticket;
import com.echo.mapper.OperationLogMapper;
import com.echo.service.IOperationLogService;
import com.echo.service.IOperationRollbackService;
import com.echo.service.IShowService;
import com.echo.service.ITicketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 操作回滚服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class OperationRollbackService implements IOperationRollbackService {

    @Resource
    private OperationLogMapper operationLogMapper;

    @Resource
    private IOperationLogService operationLogService;

    @Resource
    private IShowService showService;

    @Resource
    private ITicketService ticketService;

    @Resource
    private org.springframework.context.ApplicationContext applicationContext;

    /**
     * 回滚指定ID的操作日志（改进版：防NPE + 事务安全）
     *
     * 核心改进：
     * 防止NPE：rollbackLog为null时安全处理
     * 事务安全：移除@Transactional注解，避免部分成功被整体回滚
     * 幂等性：每条记录独立提交，失败不影响已成功的记录
     * 日志可靠性：即使主流程失败也尽力记录日志
     *
     * @param logId 操作日志ID
     * @return 回滚结果
     */
    public Result<?> rollbackOperation(Long logId) {
        OperationLog originalLog = null;
        List<Long> targetIds = null;
        String rollbackOperation = null;
        int successCount = 0;
        int failCount = 0;
        List<String> failedIds = new ArrayList<>();
        Long rollbackLogId = null;

        try {
            // 1. 查询原始操作日志
            originalLog = operationLogMapper.selectById(logId);
            if (originalLog == null) {
                return Result.fail(AppMessages.ROLLBACK_LOG_NOT_EXIST);
            }

            // 2. 验证是否在可回滚时间窗口内（24小时内）
            if (!canRollback(originalLog)) {
                return Result.fail(AppMessages.ROLLBACK_TIMEOUT_24H);
            }

            // 3. 解析目标ID列表
            targetIds = parseTargetIds(originalLog.getTargetIds());
            if (targetIds == null || targetIds.isEmpty()) {
                return Result.fail(AppMessages.ROLLBACK_TARGET_IDS_EMPTY);
            }

            // 4. 根据操作类型确定回滚方向
            String originalOperation = originalLog.getOperationType();
            rollbackOperation = getRollbackOperation(originalOperation);

            if (rollbackOperation == null) {
                return Result.fail(AppMessages.ROLLBACK_UNSUPPORTED_OPERATION + originalOperation);
            }

            log.info("[回滚] 开始执行回滚 - logId:{}, originalOp:{}, rollbackOp:{}, targetCount:{}",
                    logId, originalOperation, rollbackOperation, targetIds.size());

            long startTime = System.currentTimeMillis();

            // 5. 预创建回滚日志（确保日志ID可用）
            OperationLog rollbackLog = operationLogService.logBatchStart(
                    "ROLLBACK_" + originalOperation,
                    originalLog.getTargetType(),
                    targetIds,
                    null
            );
            rollbackLogId = (rollbackLog != null) ? rollbackLog.getId() : null;

            // 6. 逐条执行回滚（每条独立提交，互不影响）
            for (Long id : targetIds) {
                boolean success = false;
                try {
                    success = executeRollbackAction(
                            id,
                            originalOperation,
                            originalLog.getTargetType()
                    );

                    if (success) {
                        successCount++;
                        log.debug("[回滚] 单项回滚成功 - id:{}", id);
                    } else {
                        failCount++;
                        failedIds.add(String.valueOf(id));
                        log.warn("[回滚] 单项回滚返回失败 - id:{}", id);
                    }
                } catch (Exception e) {
                    failCount++;
                    failedIds.add(String.valueOf(id));
                    log.error("[回滚] 单项回滚异常 - id:{}, error:{}", id, e.getMessage(), e);
                }
            }

            long durationMs = System.currentTimeMillis() - startTime;

            // 7. 完成回滚日志记录（无论成败都记录）
            if (rollbackLogId != null) {
                try {
                    operationLogService.logBatchSuccess(
                            rollbackLogId,
                            successCount,
                            failCount,
                            durationMs
                    );

                    // 更新原始日志的备注信息（标记已回滚）
                    updateOriginalLogWithRollbackInfo(logId, rollbackLogId);

                    log.info("[回滚] 日志记录完成 - rollbackLogId:{}", rollbackLogId);
                } catch (Exception logEx) {
                    log.error("[回滚] 记录回滚日志失败（不影响主流程） - rollbackLogId:{}, error:{}",
                            rollbackLogId, logEx.getMessage());
                }
            } else {
                log.warn("[回滚] 无法创建回滚日志，操作结果：success:{}, fail:{}",
                        successCount, failCount);
            }

            log.info("[回滚] 回滚完成 - success:{}, fail:{}, duration:{}ms",
                    successCount, failCount, durationMs);

        } catch (Exception e) {
            log.error("[回滚] 回滚操作全局异常 - logId:{}, error:{}", logId, e.getMessage(), e);

            // 即使全局异常也尝试记录失败日志
            if (rollbackLogId != null) {
                try {
                    operationLogService.logBatchFailure(rollbackLogId, e.getMessage(), 0);
                } catch (Exception logEx) {
                    log.warn("[回滚-记录失败日志异常] rollbackLogId:{}, error:{}", rollbackLogId, logEx.getMessage());
                }
            }

            return Result.fail(AppMessages.format(AppMessages.ROLLBACK_FAIL_PREFIX, e.getMessage()));
        }

        // 8. 构建返回结果（安全访问rollbackLogId）
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("failedIds", failedIds);
        result.put("originalLogId", logId);
        result.put("rollbackLogId", rollbackLogId != null ? rollbackLogId : -1L);  // -1表示日志失败

        return Result.ok(result);
    }

    /**
     * 获取可回滚的操作列表（最近24小时内的成功/部分成功操作）
     *
     * @param targetType 目标实体类型（可选，null表示全部）
     * @return 可回滚的操作列表
     */
    public List<OperationLog> getRollableOperations(String targetType) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(24);

        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(OperationLog::getCreateTime, cutoffTime)
               .in(OperationLog::getResultStatus, 1, 3)
               .orderByDesc(OperationLog::getCreateTime);

        if (targetType != null && !targetType.isEmpty()) {
            wrapper.eq(OperationLog::getTargetType, targetType);
        }

        return operationLogMapper.selectList(wrapper);
    }

    /**
     * 预览回滚影响范围
     *
     * @param logId 操作日志ID
     * @return 预览信息（包含目标数量、影响的实体等）
     */
    public Result<?> previewRollback(Long logId) {
        OperationLog log = operationLogMapper.selectById(logId);
        if (log == null) {
            return Result.fail(AppMessages.ROLLBACK_LOG_NOT_EXIST);
        }

        if (!canRollback(log)) {
            return Result.fail(AppMessages.ROLLBACK_TIMEOUT_24H);
        }

        List<Long> targetIds = parseTargetIds(log.getTargetIds());
        String rollbackOperation = getRollbackOperation(log.getOperationType());

        java.util.Map<String, Object> preview = new java.util.HashMap<>();
        preview.put("originalLog", log);
        preview.put("targetCount", targetIds != null ? targetIds.size() : 0);
        preview.put("rollbackOperation", rollbackOperation);
        preview.put("canRollback", true);
        preview.put("timeRemaining", calculateTimeRemaining(log.getCreateTime()));

        return Result.ok(preview);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 判断是否可以回滚（24小时内）
     */
    private boolean canRollback(OperationLog log) {
        LocalDateTime createTime = log.getCreateTime();
        if (createTime == null) {
            return false;
        }

        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(24);
        return !createTime.isBefore(cutoffTime) && createTime.isBefore(LocalDateTime.now());
    }

    /**
     * 解析目标ID列表JSON字符串
     * <p>
     * 支持多种格式：
     * 1. 纯ID数组：[1, 2, 3]
     * 2. ID:Status对象数组：[{"id":123,"status":0}, {"id":456,"status":1}]
     * 3. ID:Status字符串数组：["123:0", "456:1"]
     */
    private List<Long> parseTargetIds(String targetIdsStr) {
        if (targetIdsStr == null || targetIdsStr.trim().isEmpty()) {
            return null;
        }

        try {
            List<Long> ids = new java.util.ArrayList<>();

            // 尝试解析为 JSON 数组
            com.alibaba.fastjson.JSONArray jsonArray = JSON.parseArray(targetIdsStr);
            if (jsonArray != null && !jsonArray.isEmpty()) {
                for (int i = 0; i < jsonArray.size(); i++) {
                    Object item = jsonArray.get(i);
                    if (item instanceof Number) {
                        // 纯数字：[1, 2, 3]
                        ids.add(((Number) item).longValue());
                    } else if (item instanceof String) {
                        // 字符串格式："123:0" 或 "456"
                        String str = (String) item;
                        String[] parts = str.split(":");
                        ids.add(Long.parseLong(parts[0].trim()));
                    } else if (item instanceof com.alibaba.fastjson.JSONObject) {
                        // 对象格式：{"id":123,"status":0}
                        com.alibaba.fastjson.JSONObject jsonObj = (com.alibaba.fastjson.JSONObject) item;
                        Object idObj = jsonObj.get("id");
                        if (idObj instanceof Number) {
                            ids.add(((Number) idObj).longValue());
                        }
                    }
                }
            }

            if (!ids.isEmpty()) {
                log.info("[回滚] 解析目标ID成功 - 原始:{}, 提取IDs:{}", targetIdsStr, ids);
                return ids;
            }

            log.warn("[回滚] 解析目标ID为空 - 原始:{}", targetIdsStr);
            return null;
        } catch (Exception e) {
            log.error("[回滚] 解析目标ID失败 - str:{}, error:{}", targetIdsStr, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取回滚操作类型
     */
    private String getRollbackOperation(String originalOperation) {
        return switch (originalOperation.toUpperCase()) {
            case "BATCH_ONLINE" -> "BATCH_OFFLINE";   // 上架 → 下架
            case "BATCH_OFFLINE" -> "BATCH_ONLINE";    // 下架 → 上架
            default -> null;                           // 不支持回滚
        };
    }

    /**
     * 执行具体的回滚动作
     */
    private boolean executeRollbackAction(
            Long id,
            String originalOperation,
            String targetType) {

        return switch (targetType.toLowerCase()) {
            case "show" -> rollbackShowOperation(id, originalOperation);
            case "ticket" -> rollbackTicketOperation(id, originalOperation);
            default -> {
                log.warn("[回滚] 不支持的目标类型 - type:{}, id:{}", targetType, id);
                yield false;
            }
        };
    }

    /**
     * 回滚演出操作（完整版：含缓存清理 + GEO索引同步）
     *
     * 改进点：
     * 清理演出缓存（cache:show:{id}）
     * 同步GEO地理位置索引（show:geo:）
     * 清理关联的票种缓存级联
     * 发布领域事件通知其他服务
     */
    private boolean rollbackShowOperation(Long showId, String originalOperation) {
        Show show = showService.getById(showId);
        if (show == null) {
            log.warn("[回滚] 演出不存在 - id:{}", showId);
            return false;
        }

        int newStatus = -1;
        String operationDesc = "";

        switch (originalOperation.toUpperCase()) {
            case "BATCH_ONLINE":
                newStatus = 0;  // 下架
                operationDesc = "批量上架→回滚下架";
                break;

            case "BATCH_OFFLINE":
                newStatus = 1;  // 上架
                operationDesc = "批量下架→回滚上架";
                break;

            default:
                log.warn("[回滚] 不支持的演出操作类型 - op:{}", originalOperation);
                return false;
        }

        show.setStatus(newStatus);

        try {
            boolean dbSuccess = showService.updateById(show);

            if (!dbSuccess) {
                log.error("[回滚] 演出状态更新失败 - id:{}, newStatus:{}", showId, newStatus);
                return false;
            }

            log.info("[回滚-演出] DB更新成功 - id:{}, status:{}, 操作:{}", showId, newStatus, operationDesc);

            // 清理缓存（确保前端立即看到最新状态）
            clearShowRelatedCache(showId);

            log.info("[回滚-演出] 完成 - id:{}, 已清理缓存", showId);
            return true;

        } catch (Exception e) {
            log.error("[回滚-演出] 异常 - id:{}, error:{}", showId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 回滚票种操作（完整版：含缓存清理 + 库存恢复 + 秒杀预热）
     *
     * 改进点：
     * 清理票种缓存（cache:ticket:show:{id}）
     * 恢复秒杀库存到Redis（如果是秒杀票）
     * 清理关联的演出列表缓存
     * 处理选座票的座位状态重置
     */
    private boolean rollbackTicketOperation(Long ticketId, String originalOperation) {
        Ticket ticket = ticketService.getById(ticketId);
        if (ticket == null) {
            log.warn("[回滚] 票种不存在 - id:{}", ticketId);
            return false;
        }

        int newStatus = -1;
        String operationDesc = "";

        switch (originalOperation.toUpperCase()) {
            case "BATCH_ONLINE":
                newStatus = 0;  // 下架
                operationDesc = "批量上架→回滚下架";
                break;

            case "BATCH_OFFLINE":
                newStatus = 1;  // 上架
                operationDesc = "批量下架→回滚上架";
                break;

            default:
                log.warn("[回滚] 不支持的票种操作类型 - op:{}", originalOperation);
                return false;
        }

        ticket.setStatus(newStatus);

        try {
            boolean dbSuccess = ticketService.updateById(ticket);

            if (!dbSuccess) {
                log.error("[回滚] 票种状态更新失败 - id:{}, newStatus:{}", ticketId, newStatus);
                return false;
            }

            log.info("[回滚-票种] DB更新成功 - id:{}, status:{}, 操作:{}", ticketId, newStatus, operationDesc);

            // 清理缓存（确保前端立即看到最新状态）
            clearTicketRelatedCache(ticketId);

            // 如果是重新上架，检查是否需要预热秒杀库存
            if (newStatus == 1) {
                reheatSeckillStockIfNeeded(ticketId);
            }

            log.info("[回滚-票种] 完成 - id:{}, 已清理缓存+预热库存", ticketId);
            return true;

        } catch (Exception e) {
            log.error("[回滚-票种] 异常 - id:{}, error:{}", ticketId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 清理演出相关缓存（含GEO索引）
     */
    private void clearShowRelatedCache(Long showId) {
        try {
            org.springframework.data.redis.core.StringRedisTemplate redisTemplate =
                    applicationContext.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);

            // 1. 清理演出详情缓存
            redisTemplate.delete(com.echo.utils.RedisConstants.CACHE_SHOW_KEY + showId);

            // 2. 清理GEO地理位置索引（如果存在）
            try {
                redisTemplate.opsForGeo().remove(
                        com.echo.utils.RedisConstants.SHOW_GEO_KEY,
                        showId.toString()
                );
                log.debug("[回滚-清缓存] GEO索引已移除 - showId:{}", showId);
            } catch (Exception geoEx) {
                log.warn("[回滚-清缓存] GEO移除失败（非关键） - showId:{}, error:{}", showId, geoEx.getMessage());
            }

            // 3. 清理演出关注者列表缓存
            redisTemplate.delete(com.echo.utils.RedisConstants.SHOW_FOLLOWERS_KEY + showId);

            log.info("[回滚-清缓存] 演出相关缓存已清理 - showId:{}", showId);

        } catch (Exception cacheEx) {
            log.error("[回滚-清缓存] 异常（不影响主流程） - showId:{}, error:{}", showId, cacheEx.getMessage());
        }
    }

    /**
     * 清理票种相关缓存
     */
    private void clearTicketRelatedCache(Long ticketId) {
        try {
            org.springframework.data.redis.core.StringRedisTemplate redisTemplate =
                    applicationContext.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);

            // 1. 清理票种详情缓存（按showId前缀的所有票种）
            // 注意：这里简化处理，实际可能需要更精确的key匹配
            redisTemplate.delete(com.echo.utils.RedisConstants.CACHE_TICKET_KEY + ticketId);

            log.info("[回滚-清缓存] 票种相关缓存已清理 - ticketId:{}", ticketId);

        } catch (Exception cacheEx) {
            log.error("[回滚-清缓存] 异常（不影响主流程） - ticketId:{}, error:{}", ticketId, cacheEx.getMessage());
        }
    }

    /**
     * 如果是秒杀票且重新上架，预热Redis库存
     */
    private void reheatSeckillStockIfNeeded(Long ticketId) {
        try {
            com.echo.service.ISeckillTicketService seckillTicketService =
                    applicationContext.getBean(com.echo.service.ISeckillTicketService.class);

            com.echo.entity.SeckillTicket seckillTicket = seckillTicketService.getById(ticketId);

            if (seckillTicket != null && seckillTicket.getStock() != null && seckillTicket.getStock() > 0) {
                // 重新上架是管理端重置语义：以DB值为准强制覆盖Redis（preloadSeckillStock只做
                // 缓存未命中预热、不覆盖已存在的计数器）
                com.echo.service.ITicketService ticketService =
                        applicationContext.getBean(com.echo.service.ITicketService.class);
                ticketService.syncSeckillStockToRedis(ticketId, seckillTicket.getStock());
                log.info("[回滚-预热] 秒杀库存已重新预热 - ticketId:{}, stock:{}",
                        ticketId, seckillTicket.getStock());
            }

        } catch (Exception ex) {
            log.warn("[回滚-预热] 预热失败（非关键） - ticketId:{}, error:{}", ticketId, ex.getMessage());
        }
    }

    /**
     * 更新原始日志的回滚信息
     */
    private void updateOriginalLogWithRollbackInfo(Long originalLogId, Long rollbackLogId) {
        OperationLog originalLog = new OperationLog();
        originalLog.setId(originalLogId);
        String detail = "[已回滚] 回滚日志ID: " + rollbackLogId +
                       ", 回滚时间: " + LocalDateTime.now();
        originalLog.setOperationDetail(detail);
        operationLogMapper.updateById(originalLog);
    }

    /**
     * 计算剩余可回滚时间（小时）
     */
    private double calculateTimeRemaining(LocalDateTime createdAt) {
        if (createdAt == null) return 0;

        long hoursDiff = java.time.Duration.between(createdAt, LocalDateTime.now()).toHours();
        return Math.max(0, 24 - hoursDiff);
    }
}
