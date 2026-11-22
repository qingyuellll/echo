package com.echo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 票务订单实体类
 * <p>
 * 对应数据库表：tb_ticket_order
 * 订单状态：0=待支付, 1=未支付（秒杀预占）, 2=已支付, 3=已核销, 4=已取消, 5=已退款, 6=申请退款中
 * 支付方式：1=模拟支付
 * 金额单位：分
 * <p>
 * ✅ 标准字段命名规范（Strict Mode v2.0）
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_ticket_order")
public class TicketOrder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 订单ID（雪花算法生成，非自增）
     * MySQL: bigint, 雪花算法格式: 时间戳(41bit) + 机器ID(10bit) + 序列号(12bit)
     * <p>
     * ✅ 标准字段：作为订单号使用（禁止使用 orderNo）
     */
    @TableId(value = "id", type = IdType.INPUT)
    @Schema(description = "订单ID(雪花算法，作为订单号)", requiredMode = Schema.RequiredMode.REQUIRED, example = "1234567890123456789")
    private Long id;

    /**
     * 用户ID（tb_user.id）✅ 标准字段
     */
    @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "100")
    private Long userId;

    /** 票种ID（tb_ticket.id）[标准字段] */
    @Schema(description = "票种ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "200")
    private Long ticketId;

    /**
     * 演出ID（tb_show.id）✅ 标准字段
     * <p>
     * 数据库字段：tb_ticket_order.show_id
     * 来源：下单时从 tb_ticket.show_id 复制过来
     * 用途：已售统计、退款回退、按演出查询订单
     */
    @Schema(description = "演出ID(来自Ticket.showId)", requiredMode = Schema.RequiredMode.REQUIRED, example = "50")
    private Long showId;

    /** 购票数量（默认1张）[标准字段] */
    @Schema(description = "购票数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    private Integer quantity;

    /**
     * 使用积分数量
     */
    @Schema(description = "使用积分数量", example = "500")
    private Integer usePoints;

    /**
     * 积分抵扣金额（单位：分）
     */
    @Schema(description = "积分抵扣金额(单位:分)", example = "5000")
    private Integer pointsDiscount;

    /**
     * 支付方式：1=模拟支付
     */
    @Schema(description = "支付方式: 1=模拟支付", example = "1")
    private Integer payType;

    /**
     * 订单状态 ✅ 标准字段
     */
    @Schema(
        description = "订单状态: 0=待支付(普通/选座), 1=秒杀预占(待支付), 2=已支付, 3=已核销, 4=已取消, 5=已退款, 6=申请退款中",
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "2"
    )
    private Integer status;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间", example = "2025-01-01T10:00:00")
    private LocalDateTime createTime;

    /**
     * 支付时间
     */
    @Schema(description = "支付时间", example = "2025-01-01T10:05:00")
    private LocalDateTime payTime;

    /**
     * 核销时间（入场验票时间）
     */
    @Schema(description = "核销时间", example = "2025-06-01T19:30:00")
    private LocalDateTime useTime;

    /**
     * 退款时间
     */
    @Schema(description = "退款时间", example = "2025-05-31T10:00:00")
    private LocalDateTime refundTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间", example = "2025-01-01T10:05:00")
    private LocalDateTime updateTime;

    // ========== 以下为非数据库字段（查询时动态填充） ==========

    /**
     * 核销码（8位大写字母数字，支付成功后生成）
     * 存储：数据库 tb_ticket_order.verification_code + Redis（双写）
     * Redis Key: verify:code:{code} → orderId, TTL: 30天
     */
    @Schema(description = "核销码(8位)", example = "A1B2C3D4")
    private String verificationCode;

    /**
     * 子订单序号（用于多张票拆分场景）
     * 格式：当前序号/总数量（如 1/3, 2/3, 3/3）
     * 含义：第几张票 / 共购买多少张
     * <p>
     * 使用场景：
     * - 用户一次购买3张票 → 拆分为3个独立订单
     * - 订单1: subOrderIndex = "1/3"
     * - 订单2: subOrderIndex = "2/3"
     * - 订单3: subOrderIndex = "3/3"
     * <p>
     * 前端展示：购买数量统一显示为1，但通过此字段标识归属关系
     * <p>
     * 数据库字段：tb_ticket_order.sub_order_index（VARCHAR(10)）
     */
    @Schema(description = "子订单序号(格式: 当前/总数，如 1/3)", example = "1/3")
    private String subOrderIndex;

    /** 用户昵称——非数据库字段，来自 tb_user.nick_name [标准字段] */
    @TableField(exist = false)
    @Schema(description = "用户昵称(来自User.nickName)", example = "张三")
    private String userName;

    /**
     * 手机号——非数据库字段，来自 tb_user.phone
     */
    @TableField(exist = false)
    @Schema(description = "用户手机号(脱敏)", example = "138****8000")
    private String userPhone;

    /**
     * 票种标题——非数据库字段，来自 tb_ticket.title ✅ 标准字段（禁止使用 ticketName）
     */
    @TableField(exist = false)
    @Schema(description = "票种标题(来自Ticket.title)", example = "VIP票")
    private String ticketTitle;

    /**
     * 演出名称——非数据库字段，来自 tb_show.name
     */
    @TableField(exist = false)
    @Schema(description = "演出名称(来自Show.name)", example = "周杰伦演唱会")
    private String showName;

    /**
     * 支付金额/总价——数据库字段 [标准字段]（已持久化）
     * 含义：下单时的实际支付金额（快照价格，不受后续票种调价影响）
     * 计算时机：创建订单时计算并保存
     * 计算公式：quantity × Ticket.payValue
     */
    @TableField(exist = false)
    @Schema(description = "支付总价(单位:分)，下单时的快照价格", example = "100000")
    private Long payValue;

    /**
     * 实际价值——非数据库字段（运行时计算）
     * 含义：下单时的原价总额（用于显示优惠幅度）
     * 计算公式：quantity × Ticket.actualValue
     */
    @TableField(exist = false)
    @Schema(description = "实际价值(单位:分)，下单时的快照原价", example = "160000")
    private Long actualValue;

    /**
     * 使用的积分数——非数据库字段，支付时动态计算
     */
    @TableField(exist = false)
    @Schema(description = "使用积分数", example = "500")
    private Integer pointsUsed;

    /**
     * 积分抵扣金额（分）——非数据库字段，10积分=1元=100分
     */
    @TableField(exist = false)
    @Schema(description = "积分抵扣金额(单位:分)", example = "5000")
    private Long discountAmount;

    /**
     * 座位信息列表——非数据库字段，选座订单时动态填充
     * 格式：[{id, area, rowNum, colNum, seatLabel}, ...]
     */
    @TableField(exist = false)
    @Schema(description = "座位信息列表(选座订单)")
    private List<Map<String, Object>> seatInfoList;
}
