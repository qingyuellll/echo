/**
 * API 工具函数类型声明
 * 用于 TypeScript 类型检查
 * 对应 src/utils/api.js 中的 export const api = {...}
 */

declare namespace Api {
  // ===== 用户认证 =====
  function sendCode(phone: string): Promise<any>
  function login(phone: string, password: string, options?: object): Promise<any>
  function register(data: object): Promise<any>
  function logout(): Promise<any>
  function refreshToken(): Promise<any>

  // ===== 用户信息 =====
  function getCurrentUser(): Promise<any>
  function updateProfile(data: object): Promise<any>
  function updatePassword(data: object): Promise<any>

  // ===== 演出 =====
  function getShows(params?: object): Promise<any>
  function getShowDetail(id: number): Promise<any>
  function getMyFollows(current?: number): Promise<any>

  // ===== 票务核心 =====
  function getTicketsByShow(showId: number): Promise<any>

  /**
   * 获取票种详情（含秒杀信息、库存、是否可售卖等）
   */
  function getTicketDetail(ticketId: number): Promise<any>

  /**
   * 检查票种状态（轻量级接口，仅返回status字段）
   */
  function checkTicketStatus(ticketId: number): Promise<any>

  /**
   * 检查用户是否已购买过该秒杀票
   */
  function checkSeckillPurchased(ticketId: number): Promise<any>

  /**
   * 批量检查用户已购买的秒杀票（用于UI禁用已购票种）
   */
  function checkSeckillPurchasedBatch(ticketIds: number[]): Promise<{ purchasedIds: number[] }>

  /**
   * 获取座位列表
   */
  function getSeatsByTicket(ticketId: number): Promise<any>

  /**
   * 锁定座位（防并发）
   */
  function lockSeat(ticketId: number, seatId: number): Promise<any>

  /**
   * 释放座位
   */
  function releaseSeat(ticketId: number, seatId: number): Promise<any>

  // ===== 订单创建 =====
  /**
   * 创建普通订单或选座订单
   */
  function createOrder(
    ticketId: number,
    data?: {
      count?: number
      seats?: number[]
      seatIds?: number[]
    }
  ): Promise<any>

  /**
   * 秒杀下单（支持选座）
   * @param ticketId - 秒杀票种ID
   * @param seatIds - 座位ID列表（选座票必填，默认空数组）
   * @param options - 额外选项
   */
  function seckillOrder(
    ticketId: number,
    seatIds?: number[],
    options?: object
  ): Promise<any>

  /**
   * 秒杀下单并等待排队结果（入队 + 轮询到终态）
   * @returns {tempId: string, expireTime?: string, message?: string}
   */
  function seckillOrderAndWait(
    ticketId: number,
    seatIds?: number[],
    options?: { timeoutMs?: number; intervalMs?: number }
  ): Promise<{ tempId: string; expireTime?: string; message?: string }>

  // ===== 订单管理 =====
  function getOrders(params?: { page?: number; size?: number }): Promise<any>
  function getOrderDetail(id: number): Promise<any>
  function cancelOrder(id: number): Promise<any>
  function payOrder(id: number, usePoints?: boolean): Promise<any>

  // ===== 收藏/关注/评价 =====
  function favoriteShow(id: number): Promise<any>
  function followShow(id: number): Promise<any>
  function publishReview(data: object): Promise<any>
  function likeReview(id: number): Promise<any>

  // ===== 抽奖/积分 =====
  function lottery(): Promise<any>
  function getLotteryRecords(): Promise<any>
  function dailySign(): Promise<any>
  function exchangeCoupon(couponId: number): Promise<any>

  // ===== 文件上传 =====
  function uploadFile(file: File): Promise<any>

  // ===== 工具函数 =====
  function formatPrice(price: number | null | undefined): string
}

declare const api: typeof Api
export { api }
