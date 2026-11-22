package com.echo.message;

import cn.hutool.core.util.StrUtil;
import com.echo.enums.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户通知消息
 * <p>
 * 通过 RocketMQ 异步发送用户通知，支持多种通知类型（支付成功、退款成功等）
 *
 * @author tulip
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 目标用户ID */
    private Long userId;

    /** 通知类型 */
    private NotificationType type;

    /** 通知标题 */
    private String title;

    /** 通知内容 */
    private String content;

    /** 关联业务ID(用于幂等去重和跳转详情) */
    private Long relatedId;

    /** 消息创建时间 */
    private LocalDateTime createTime;

    /**
     * 创建通知消息
     *
     * @param userId 目标用户ID
     * @param type 通知类型
     * @param title 通知标题
     * @param content 通知内容
     * @param relatedId 关联业务ID
     * @return 通知消息实例
     */
    public static NotificationMessage of(Long userId, NotificationType type, String title, String content, Long relatedId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId 必须为正整数");
        }
        if (type == null) {
            throw new IllegalArgumentException("type 不能为空");
        }
        if (StrUtil.isBlank(title)) {
            throw new IllegalArgumentException("title 不能为空");
        }

        return NotificationMessage.builder()
                .userId(userId)
                .type(type)
                .title(title)
                .content(content)
                .relatedId(relatedId)
                .createTime(LocalDateTime.now())
                .build();
    }
}
