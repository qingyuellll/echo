/**
 * API 工具函数类型声明
 * 对应 src/utils/api.js 中的 export const api = {...}
 */

declare namespace ApiModule {
  interface Api {
    reviewInsights(showId: string): Promise<any>
    guideOrderFollowUp(orderId: string): Promise<any>
    guideCalendar(orderId: string): Promise<any>
    guideCheckout(ticketId: string | number): Promise<any>
    guideCreateOrder(ticketId: string | number, body: object): Promise<any>
    guideRequestStatus(action: string, requestId: string): Promise<any>
    guideReviewContext(showId: string | number): Promise<any>
    guideReviewDraft(body: object): Promise<any>
    guidePublishReview(body: object): Promise<any>
    // ===== HTTP基础 =====
    get<T = any>(url: string, params?: Record<string, any>): Promise<any>
    post<T = any>(url: string, data?: Record<string, any>): Promise<any>
    put<T = any>(url: string, data?: Record<string, any>): Promise<any>
    delete<T = any>(url: string): Promise<any>

    // ===== 用户认证 =====
    sendCode(phone: string): Promise<any>
    login(phone: string, password: string, options?: object): Promise<any>
    register(data: object): Promise<any>
    logout(): Promise<any>
    refreshToken(): Promise<any>

    // ===== 用户信息 =====
    getCurrentUser(): Promise<any>
    updateProfile(data: object): Promise<any>
    updatePassword(data: object): Promise<any>

    // ===== 演出 =====
    getShows(params?: object): Promise<any>
    getShowDetail(id: number): Promise<any>
    getMyFollows(current?: number): Promise<any>
    getHotShows(limit?: number, location?: { x: number; y: number }): Promise<any>

    // ===== 票务核心 =====
    getTicketsByShow(showId: number): Promise<any>
    getTicketsByShowId(showId: number): Promise<any>

    /**
     * 获取票种详情（含秒杀信息、库存、是否可售卖等）
     */
    getTicketDetail(ticketId: number): Promise<any>

    /**
     * 检查票种状态（轻量级接口，仅返回status字段）
     */
    checkTicketStatus(ticketId: number): Promise<any>

    /**
     * 检查用户是否已购买过该秒杀票
     */
    checkSeckillPurchased(ticketId: number): Promise<any>

    /**
     * 批量检查用户已购买的秒杀票（用于UI禁用已购票种）
     * 返回格式: { data: { purchasedIds: number[] } }
     */
    checkSeckillPurchasedBatch(ticketIds: number[]): Promise<{ data: { purchasedIds: number[] } }>

    /**
     * 获取座位列表
     */
    getSeatsByTicket(ticketId: number): Promise<any>

    /**
     * 锁定座位（防并发）
     */
    lockSeat(ticketId: number, seatId: number): Promise<any>

    /**
     * 释放座位
     */
    releaseSeat(ticketId: number, seatId: number): Promise<any>

    // ===== 订单创建 =====
    /**
     * 创建普通订单或选座订单
     */
    createOrder(
      ticketId: number,
      options?: {
        count?: number
        seats?: number[]
        seatIds?: number[]
      }
    ): Promise<any>

    /**
     * 秒杀下单（支持选座）
     * @param ticketId - 秒杀票种ID
     * @param seatIds - 座位ID列表（选座票必填）
     * @param options - 额外选项
     */
    seckillOrder(
      ticketId: number,
      seatIds?: number[],
      options?: object
    ): Promise<any>

    /**
     * 秒杀下单并等待排队结果（入队 + 轮询到终态）
     * @param ticketId - 秒杀票种ID
     * @param seatIds - 座位ID列表（选座票必填）
     * @param options - 轮询参数（timeoutMs / intervalMs）
     * @returns {tempId: string, expireTime?: string, message?: string}
     */
    seckillOrderAndWait(
      ticketId: number,
      seatIds?: number[],
      options?: { timeoutMs?: number; intervalMs?: number }
    ): Promise<{ tempId: string; expireTime?: string; message?: string }>

    // ===== 订单管理 =====
    getOrder(orderId: number): Promise<any>
    getOrders(params?: { page?: number; size?: number }): Promise<any>
    getOrderDetail(id: number): Promise<any>
    cancelOrder(id: number): Promise<any>
    payOrder(id: number, usePoints?: boolean): Promise<any>

    // ===== 收藏/关注/评价 =====
    favoriteShow(id: number): Promise<any>
    followShow(id: number): Promise<any>
    publishReview(data: object): Promise<any>
    likeReview(id: number): Promise<any>

    // ===== 抽奖/积分 =====
    lottery(): Promise<any>
    getLotteryRecords(): Promise<any>
    dailySign(): Promise<any>
    exchangeCoupon(couponId: number): Promise<any>

    // ===== 文件上传 =====
    uploadFile(file: File): Promise<any>

    // ===== 工具函数 =====
    formatPrice(price: number | null | undefined): string
    parseImages(images: any): any[]
    getImageUrl(image: any): string
  }
}

declare const api: ApiModule.Api
declare function formatPrice(price: number | null | undefined): string

export { api, formatPrice }
