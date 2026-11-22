/**
 * 票种接口定义
 * 统一前后端字段命名规范
 */
export interface Ticket {
  id: number
  showId: number
  title: string               // 票种标题（如：早鸟票、VIP票）
  subTitle?: string           // 副标题（如：限时优惠）
  rules?: string              // 购票规则说明
  payValue: number            // 支付金额（单位：分）
  actualValue: number         // 实际价值/原价（单位：分）
  type: number                // 票种类型：0=普通票, 1=秒杀票
  status: number              // 状态：0=下架, 1=上架
  hasSeats: boolean           // 是否需要选座
  stock: number               // 剩余可卖库存（动态变化）
  totalStock?: number         // 总库存（固定不变）
  soldCount?: number          // 已售数量（运行时计算）
  remainingStock?: number     // 剩余库存（运行时计算：totalStock - soldCount）
  locked: number              // 锁定库存（秒杀预占数量）
  isSeckill: boolean          // 是否秒杀票（运行时计算：type === 1）

  // 秒杀票专用字段（来自 tb_seckill_ticket 表）
  seckillStock?: number       // 秒杀剩余库存（来自Redis实时数据）
  seckillTotalStock?: number  // 秒杀总库存（来自tb_seckill_ticket.stock）
  seckillSoldCount?: number   // 秒杀已售数量（计算值：seckillTotalStock - seckillStock）
  beginTime?: string          // 秒杀开始时间（ISO格式，兼容后端返回的多种字段名）
  endTime?: string            // 秒杀结束时间（ISO格式，兼容后端返回的多种字段名）
  seckillBeginTime?: string   // 秒杀开始时间（前端期望的字段名，可能与beginTime相同）
  seckillEndTime?: string     // 秒杀结束时间（前端期望的字段名，可能与endTime相同）

  // 扩展信息（后端可能返回的额外数据）
  extInfo?: {
    seckillBeginTime?: string
    seckillEndTime?: string
    [key: string]: any
  }

  // 活动状态
  isActive?: boolean          // 是否处于活跃状态（用于UI显示）

  // 定时发布
  scheduledPublishTime?: string  // 定时上架时间（ISO格式）

  // 座位信息（仅hasSeats=true时有值）
  seatCount?: number          // 座位总数
  availableSeats?: number     // 可用座位数（status=0的座位）

  // 关联信息（管理端API返回）
  showName?: string           // 所属演出名称

  // 时间戳
  createTime?: string         // 创建时间（ISO格式）
  updateTime?: string         // 更新时间（ISO格式）
}

/**
 * 票种选择结果（用于事件传递）
 */
export interface TicketSelectionResult {
  orderId?: number | string
  ticketId: number
  ticketTitle: string         // 票种标题
  quantity: number
  unitPrice: number           // 单价（payValue）
  totalPrice: number          // 总价
  needSeatSelection: boolean
  isSeckill: boolean
  isShowEnded?: boolean       // 演出是否已结束（用于支付页面提示）
}
