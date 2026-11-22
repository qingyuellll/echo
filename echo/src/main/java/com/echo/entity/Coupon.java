package com.echo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.echo.enums.CouponStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 优惠券实体类（活跃使用中）
 *
 * 业务场景：
 * - 用户通过积分兑换优惠券（PointsServiceImpl.exchangeCoupon）
 * - 优惠券记录持久化到tb_coupon表
 * - 支持状态流转：未使用(0) → 已使用(1) / 已过期(2)
 *
 * 对应数据库表：tb_coupon
 * 状态枚举：
 * - 0 = 未使用（可正常使用）
 * - 1 = 已使用（订单核销）
 * - 2 = 已过期（超过有效期）
 *
 * 金额单位：分
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_coupon")
public class Coupon implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID（自增）
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID（tb_user.id）
     */
    private Long userId;

    /**
     * 优惠券名称（如：10元优惠券）
     */
    private String name;

    /**
     * 优惠金额（单位：分，如 1000 = 10元）
     */
    private Long discountAmount;

    /**
     * 最低消费金额（单位：分，满多少可用）
     */
    private Long minOrderAmount;

    /**
     * 状态：UNUSED=未使用, USED=已使用, EXPIRED=已过期
     */
    private CouponStatus status;

    /**
     * 过期时间
     */
    private LocalDateTime expireTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
