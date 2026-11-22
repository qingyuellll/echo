package com.echo.dto.base;

import com.echo.constants.DTOMessages;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.util.UUID;

/**
 * 审计追踪基类
 * <p>
 * 承载"谁在什么渠道做了这次操作"所需的追踪字段，供需要写操作日志的请求体继承。
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li>{@code sourceChannel}：操作来源渠道，审计日志的必填维度</li>
 *   <li>{@code remark}：操作备注，可选，长度上限 {@value #REMARK_MAX_LENGTH}</li>
 * </ul>
 * <p>
 * <b>幂等键不在本类中。</b>幂等键只对"会重复提交造成副作用"的创建类接口有意义，
 * 目前仅 {@code POST /admin/ticket/with-seats} 消费它，因此由
 * {@link com.echo.dto.TicketWithSeatsDTO} 自行声明为必填字段。
 * 更新类接口（如用户资料修改）本身幂等，不要求客户端生成幂等键。
 *
 * @author tulip
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AuditableDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 备注长度上限 */
    public static final int REMARK_MAX_LENGTH = 500;

    /** 操作来源渠道（如 web-user、web-admin） */
    @NotBlank(message = DTOMessages.SOURCE_CHANNEL_NOT_EMPTY)
    private String sourceChannel;

    /** 操作备注（可选，用于审计日志） */
    @Size(max = REMARK_MAX_LENGTH, message = DTOMessages.REMARK_TOO_LONG)
    private String remark;

    /**
     * 生成一个幂等键
     * <p>
     * 需要幂等语义的请求体在缺省时用它补齐，保证键格式统一为 UUID 字符串。
     *
     * @return 36 位标准 UUID 字符串
     */
    public static String generateIdempotencyKey() {
        return UUID.randomUUID().toString();
    }
}
