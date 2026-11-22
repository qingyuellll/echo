/**
 * 订单接口定义
 * 统一前后端字段命名规范
 */

export interface Order {
  id: number | string        // 雪花算法生成的订单ID（作为订单号）
  userId: number             // 用户ID
  showId: number             // 演出ID
  ticketId: number           // 票种ID
  ticketTitle: string        // 票种标题（动态填充）
  quantity: number           // 购买数量
  payValue: number           // 总价 = quantity × 单价（单位：分）
  unitPrice?: number         // 单价（单位：分）
  usePoints: number          // 使用积分数量
  pointsDiscount: number     // 积分抵扣金额（单位：分）
  payType: number            // 支付方式：0=微信, 1=支付宝
  status: number             // 订单状态
  createTime: string         // 创建时间（ISO格式）
  payTime?: string           // 支付时间（ISO格式）
  useTime?: string           // 核销时间（ISO格式）
  refundTime?: string        // 退款时间（ISO格式）
  verificationCode?: string  // 核销码（JSON数组或单个字符串）

  // 关联信息（API动态填充）
  userName?: string          // 用户昵称
  userPhone?: string         // 用户手机号（脱敏）
  showName?: string          // 演出名称
  seats?: Array<SeatInfo>    // 座位信息（选座票）
  pending?: boolean          // 是否待处理（秒杀订单MQ异步中）
}

export interface SeatInfo {
  row: string                // 排号
  column: string             // 座位号
  area?: string              // 区域
  price?: number             // 该座位单价
}

/**
 * 订单状态枚举
 */
export const ORDER_STATUS = {
  RESERVED: 0,          // 待支付（普通票/选座票预占）
  SECKILL_RESERVED: 1,  // 秒杀预占（未支付）
  PAID: 2,              // 已支付
  USED: 3,              // 已核销
  CANCELLED: 4,         // 已取消
  REFUNDED: 5,          // 已退款
  REFUND_PENDING: 6,    // 申请退款中
  REJECTED: 7           // 已拒绝
} as const

export const ORDER_STATUS_TEXT: Record<number, string> = {
  [ORDER_STATUS.RESERVED]: '待支付',
  [ORDER_STATUS.SECKILL_RESERVED]: '秒杀预占',
  [ORDER_STATUS.PAID]: '已支付',
  [ORDER_STATUS.USED]: '已核销',
  [ORDER_STATUS.CANCELLED]: '已取消',
  [ORDER_STATUS.REFUNDED]: '已退款',
  [ORDER_STATUS.REFUND_PENDING]: '申请退款中',
  [ORDER_STATUS.REJECTED]: '已拒绝'
}
