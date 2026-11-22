package com.echo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.echo.enums.NotificationType;
import com.echo.enums.ReadStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通知实体类
 * <p>
 * 对应数据库表：tb_notification
 * 通知类型：1=支付成功, 2=关注动态, 3=退款成功, 4=核销成功, 5=关注评测, 6=演出开售
 * 读取状态：0=未读, 1=已读
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_notification")
public class Notification implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID（自增）
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 接收通知的用户ID（tb_user.id）
     */
    private Long userId;

    /**
     * 通知类型：PAYMENT_SUCCESS=支付成功, FOLLOW_ACTIVITY=关注动态, REFUND_SUCCESS=退款成功,
     *          VERIFICATION_SUCCESS=核销成功, REVIEW_FOLLOW=关注评测, SHOW_ON_SALE=演出开售
     */
    private NotificationType type;

    /**
     * 通知标题
     */
    private String title;

    /**
     * 通知内容
     */
    private String content;

    /**
     * 是否已读：UNREAD=未读, READ=已读
     */
    private ReadStatus isRead;

    /**
     * 关联业务ID（如订单ID、演出ID等）
     */
    private Long relatedId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
