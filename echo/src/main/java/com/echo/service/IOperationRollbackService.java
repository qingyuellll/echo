package com.echo.service;

import com.echo.dto.Result;
import com.echo.entity.OperationLog;

import java.util.List;

/**
 * 操作回滚服务接口
 *
 * @author tulip
 */
public interface IOperationRollbackService {

    /**
     * 回滚指定ID的操作日志
     *
     * @param logId 操作日志ID
     * @return 回滚结果
     */
    Result<?> rollbackOperation(Long logId);

    /**
     * 获取可回滚的操作列表（最近24小时内的成功/部分成功操作）
     *
     * @param targetType 目标实体类型（可选，null表示全部）
     * @return 可回滚的操作列表
     */
    List<OperationLog> getRollableOperations(String targetType);

    /**
     * 预览回滚影响范围
     *
     * @param logId 操作日志ID
     * @return 预览信息（包含目标数量、影响的实体等）
     */
    Result<?> previewRollback(Long logId);
}
