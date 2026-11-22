package com.echo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.echo.constants.SystemMessages;
import com.echo.enums.OperationResultStatus;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("admin_id")
    private Long operatorId;

    @TableField("admin_name")
    private String operatorName;

    @NotBlank(message = SystemMessages.OPERATION_TYPE_NOT_EMPTY)
    @Size(max = 50, message = SystemMessages.OPERATION_TYPE_TOO_LONG)
    private String operationType;

    @Size(max = 50, message = SystemMessages.TARGET_TYPE_TOO_LONG)
    private String targetType;

    private String targetIds;

    private String operationDetail;

    @NotNull(message = SystemMessages.RESULT_STATUS_NOT_EMPTY)
    private OperationResultStatus resultStatus;

    @Min(value = 0, message = SystemMessages.SUCCESS_COUNT_MIN)
    private Integer successCount;

    @Min(value = 0, message = SystemMessages.FAIL_COUNT_MIN)
    private Integer failCount;

    @Size(max = 2000, message = SystemMessages.ERROR_MESSAGE_TOO_LONG)
    private String errorMessage;

    @Size(max = 50, message = SystemMessages.IP_ADDRESS_TOO_LONG)
    private String ipAddress;

    @Size(max = 500, message = SystemMessages.USER_AGENT_TOO_LONG)
    private String userAgent;

    @Size(max = 32, message = SystemMessages.REQUEST_ID_TOO_LONG)
    private String requestId;

    @Min(value = 0, message = SystemMessages.DURATION_NEGATIVE)
    private Long durationMs;

    /** 表列为 created_at（非默认蛇形 create_time），需显式映射 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
