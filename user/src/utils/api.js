const BASE_URL = import.meta.env?.VITE_API_BASE_URL || '/api'

let isRedirectingToLogin = false

class HttpError extends Error {
  constructor(message, { url, method, statusCode, responseBody } = {}) {
    super(message)
    this.name = 'HttpError'
    this.url = url
    this.method = method
    this.statusCode = statusCode
    this.response = responseBody ? { data: responseBody } : null
    this.isHttpError = true
  }
}

class BusinessError extends Error {
  constructor(message, { code, data } = {}) {
    super(message)
    this.name = 'BusinessError'
    this.code = code
    this.data = data
    this.isBusinessError = true
  }
}

// 注意：下单等非幂等POST请求严禁在此类工具层做自动重试——
// 重试一次就是一次重复提交，会消耗令牌桶与Redis判重资源；
// 网络失败应由用户显式重试或走业务侧的状态查询接口

async function request(url, options = {}, _retryCount = 0) {
  const token = sessionStorage.getItem('token')
  const MAX_CSRF_RETRY = 1  // CSRF Token刷新后最多重试1次

  const CSRF_TOKEN_EXPIRY = 3600000  // CSRF Token有效期为1小时（单位：毫秒）
  const csrfToken = localStorage.getItem('csrfToken') || ''
  const csrfTokenTime = parseInt(localStorage.getItem('csrfTokenTime') || '0')
  const csrfTokenValid = csrfToken && (Date.now() - csrfTokenTime < CSRF_TOKEN_EXPIRY)

  if (csrfToken && !csrfTokenValid) {
    console.warn('[API] CSRF Token已过期，清除并等待服务端刷新')
    localStorage.removeItem('csrfToken')
    localStorage.removeItem('csrfTokenTime')
  }

  const activeCsrfToken = csrfTokenValid ? csrfToken : ''
  const method = (options.method || 'GET').toUpperCase()

  const defaultHeaders = {}

  if (!(options.body instanceof FormData)) {
    defaultHeaders['Content-Type'] = 'application/json'
  }

  if (token) {
    defaultHeaders['Authorization'] = `Bearer ${token}`
  }

  if (activeCsrfToken && ['POST', 'PUT', 'DELETE', 'PATCH'].includes(method)) {
    defaultHeaders['X-CSRF-Token'] = activeCsrfToken
  }

  const timeout = options.timeout || 15000
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), timeout)

  const config = {
    ...options,
    signal: controller.signal,
    headers: {
      ...defaultHeaders,
      ...options.headers
    }
  }

  delete config.timeout

  try {
    const startTime = Date.now()
    const response = await fetch(`${BASE_URL}${url}`, config)
    clearTimeout(timeoutId)
    const duration = Date.now() - startTime

    const logUrl = maskSensitiveUrl(url)
    console.log(`[API] ${method} ${logUrl} (${duration}ms)`)

    // ✅ 新增：CSRF 403 自动重试机制
    if (response.status === 403 && _retryCount < MAX_CSRF_RETRY && ['POST', 'PUT', 'DELETE', 'PATCH'].includes(method)) {
      const errorBody = await response.json().catch(() => null)

      if (errorBody && errorBody.errorMsg?.includes('CSRF')) {
        console.warn(`[API] ⚠️ CSRF验证失败 (${_retryCount + 1}/${MAX_CSRF_RETRY})，自动刷新Token并重试...`, logUrl)

        clearTimeout(timeoutId)

        // 尝试刷新CSRF Token
        try {
          await api.refreshCsrfToken()

          // 使用新Token重新发起请求（递归调用，_retryCount+1）
          return await request(url, options, _retryCount + 1)
        } catch (refreshError) {
          console.error('[API] ❌ CSRF Token刷新失败:', refreshError)
          throw new HttpError(errorBody.errorMsg || 'CSRF验证失败，请刷新页面重试', {
            url: logUrl,
            method,
            statusCode: 403,
            responseBody: errorBody
          })
        }
      }
    }

    if (response.status === 401) {
      console.warn('[API] 401 Unauthorized - URL:', logUrl)

      const publicPaths = ['/user/', '/show/', '/follow/', '/show-review/']
      const privateSubPaths = ['/${id}', '/info', '/me', '/password', '/sign', '/orders', '/my-orders']

      const isPublicApi = publicPaths.some(path => {
        if (!url.startsWith(path)) return false
        return !privateSubPaths.some(subPath => url.includes(subPath))
      })

      if (!isPublicApi && !isRedirectingToLogin) {
        isRedirectingToLogin = true
        sessionStorage.removeItem('token')
        sessionStorage.removeItem('user')

        setTimeout(() => {
          const returnPath = window.location.pathname + window.location.search
          window.location.assign('/login?redirect=' + encodeURIComponent(returnPath.startsWith('/login') ? '/' : returnPath))
          isRedirectingToLogin = false
        }, 100)
      }

      throw new HttpError('未授权访问，请先登录', {
        url: logUrl,
        method,
        statusCode: 401
      })
    }

    if (!response.ok) {
      let errorBody = null
      try {
        errorBody = await response.json()
      } catch (_) {}

      let errorMsg = `请求失败 (${response.status})`

      if (errorBody) {
        // 错误字段优先级（与后端Result.errorMsg保持一致）：
        // 1. errorMsg - 后端业务异常标准字段
        // 2. message - Spring框架默认异常字段
        // 3. msg - 兼容其他可能的响应格式
        // 4. 默认值 - HTTP状态码描述
        errorMsg = errorBody.errorMsg || errorBody.message || errorBody.msg || errorMsg
      }

      console.error(`[API] ${response.status} Error - URL:`, logUrl, 'Response:', errorBody || response.statusText)

      throw new HttpError(errorMsg, {
        url: logUrl,
        method,
        statusCode: response.status,
        responseBody: errorBody
      })
    }

    const data = await response.json()

    // 优先从响应头获取最新的 CSRF Token（CsrfInterceptor 每次验证通过后会刷新）
    let updatedCsrfToken = null
    try {
      const headerCsrfToken = response.headers.get('X-CSRF-Token')
      if (headerCsrfToken) {
        updatedCsrfToken = headerCsrfToken
        console.log('[API] 从响应头获取新CSRF Token:', headerCsrfToken.substring(0, 8) + '...')
      }
    } catch (headerError) {
      console.debug('[API] 读取响应头失败（非关键错误）:', headerError.message)
    }

    // 如果响应头中没有，再尝试从响应体获取（兼容登录接口等特殊情况）
    if (!updatedCsrfToken && data.csrfToken) {
      updatedCsrfToken = data.csrfToken
    }

    // 更新本地存储的 CSRF Token
    if (updatedCsrfToken) {
      localStorage.setItem('csrfToken', updatedCsrfToken)
      localStorage.setItem('csrfTokenTime', Date.now().toString())
      console.log('[API] CSRF Token已更新:', updatedCsrfToken.substring(0, 8) + '...', '有效期为1小时')
    }

    if (data.success === false || data.success === 0 || data.code && data.code !== 200) {
      const businessErrorMsg = data.errorMsg || data.message || data.msg || '业务逻辑错误'
      console.warn(`[API] 业务错误 - URL:`, logUrl, 'Message:', businessErrorMsg)

      throw new BusinessError(businessErrorMsg, {
        code: data.errorCode || data.code,
        data: data.data
      })
    }

    return data

  } catch (error) {
    clearTimeout(timeoutId)

    if (error.name === 'AbortError') {
      console.error(`[API] 请求超时 (>${timeout}ms):`, url)
      throw new HttpError('请求超时，请检查网络连接后重试', { url, method, statusCode: 408 })
    }

    if (error.message.includes('Unexpected end of JSON') ||
        error.message.includes('JSON.parse')) {
      console.error('[API] JSON解析失败 - URL:', url, error)
      throw new Error('服务器响应异常，请稍后重试')
    }
    throw error
  }
}

function maskSensitiveUrl(url) {
  return url
    .replace(/phone=\d{11}/g, 'phone=***')
    .replace(/password=[^&]*/g, 'password=***')
    .replace(/verificationCode=[A-Za-z0-9]{8}/g, 'verificationCode=***')
    .replace(/credits=\d+/g, 'credits=***')
    .replace(/token=[^&]{20,}/g, 'token=***')
}

export const api = {
  /**
   * 预加载/刷新 CSRF Token
   * 在页面加载或Token过期时调用，确保后续写操作有有效的CSRF Token
   *
   * 工作原理：
   * - 调用 GET /user/me 接口（轻量级，必定返回用户信息）
   * - 后端 CsrfInterceptor 会在响应头中放入新的 X-CSRF-Token
   * - 前端 request() 函数会自动从响应头提取并保存到 localStorage
   *
   * @returns {Promise<boolean>} 是否成功获取新Token
   */
  async refreshCsrfToken() {
    try {
      const tokenBefore = localStorage.getItem('csrfToken')?.substring(0, 12) || '(空)'
      console.log('[API] 🔄 开始刷新CSRF Token... 当前Token:', tokenBefore)

      // 调用 GET /user/me 触发后端生成新Token
      const res = await request('/user/me', { method: 'GET' })

      if (res) {
        const tokenAfter = localStorage.getItem('csrfToken')?.substring(0, 12) || '(空)'
        const tokenTime = new Date(parseInt(localStorage.getItem('csrfTokenTime') || '0')).toLocaleTimeString()

        if (tokenAfter !== '(空)' && tokenAfter !== tokenBefore) {
          console.log('[API] ✅ CSRF Token刷新成功！')
          console.log('   - 新Token:', tokenAfter + '...')
          console.log('   - 更新时间:', tokenTime)
          return true
        } else {
          console.warn('[API] ⚠️ Token未更新，可能后端未返回新Token或用户未登录')
          return false
        }
      }

      return false
    } catch (error) {
      console.error('[API] ❌ CSRF Token刷新失败:', error.message)
      return false
    }
  },

  /**
   * 确保CSRF Token有效（如果无效则自动刷新）
   * 在执行重要写操作前调用
   * @returns {Promise<void>}
   */
  async ensureCsrfToken() {
    const CSRF_TOKEN_EXPIRY = 3600000  // 1小时
    const csrfToken = localStorage.getItem('csrfToken') || ''
    const csrfTokenTime = parseInt(localStorage.getItem('csrfTokenTime') || '0')
    const isValid = csrfToken && (Date.now() - csrfTokenTime < CSRF_TOKEN_EXPIRY)

    if (!isValid) {
      console.log('[API] CSRF Token无效或不存在，自动刷新...')
      await this.refreshCsrfToken()
    } else {
      console.log('[API] ✅ CSRF Token有效，剩余有效期:', Math.round((CSRF_TOKEN_EXPIRY - (Date.now() - csrfTokenTime)) / 60000), '分钟')
    }
  },

  // ===== 用户认证 =====
  sendCode(phone) {
    return request(`/user/code?phone=${encodeURIComponent(phone)}`, {
      method: 'POST'
    })
  },

  /**
   * 发送设置/修改密码的短信验证码（需登录态）
   */
  sendPasswordCode() {
    return request('/user/password/code', { method: 'POST' })
  },

  /**
   * 设置/修改密码
   * 后端规则：未设置过密码 → 只需 newPassword + code；
   *          已设置过密码 → 需 oldPassword + newPassword + code。
   * @param {{oldPassword?: string, newPassword: string, code: string}} data
   */
  changePassword(data) {
    return request('/user/password', {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  },

  loginByCode(phone, code) {
    return request('/user/login', {
      method: 'POST',
      body: JSON.stringify({ phone, code })
    })
  },

  loginByPassword(phone, password) {
    return request('/user/login', {
      method: 'POST',
      body: JSON.stringify({ phone, password })
    })
  },

  logout() {
    return request('/user/logout', { method: 'POST' })
  },

  getMe() {
    return request('/user/me')
  },

  getUserById(id) {
    return request(`/user/${id}`)
  },

  getUserInfo(id) {
    return request(`/user/info/${id}`)
  },

  /**
   * 更新用户个人资料 (v4.0 增强版 - 支持审计追踪)
   * @param {Object} data - 用户资料数据
   * @param {string} [data.city] - 城市
   * @param {string} [data.introduce] - 个人简介
   * @param {boolean} [data.gender] - 性别
   * @param {string} [data.birthday] - 生日
   * @param {string} [data.icon] - 头像路径
   * @param {string} [data.remark] - 操作备注（可选）
   */
  updateUserInfo(data) {
    const enhancedData = this._addAuditFieldsForUser(data)

    console.log('[API] 👤 updateUserInfo (v4.0) - 审计字段已添加:', {
      sourceChannel: enhancedData.sourceChannel,
      hasRemark: !!enhancedData.remark
    })

    return request('/user/info', {
      method: 'PUT',
      body: JSON.stringify(enhancedData)
    })
  },

  updatePassword(data) {
    return request('/user/password', {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  },

  sign() {
    return request('/user/sign', { method: 'POST' })
  },

  getSignCount() {
    return request('/user/sign/count')
  },

  // ===== 演出类型 =====
  getShowTypes() {
    return request('/show-type/list')
  },

  // ===== 演出 =====
  getShowDetail(id) {
    return request(`/show/${id}`)
  },

  getShowsByType(params = {}) {
    const query = new URLSearchParams(params).toString()
    return request(`/show/of/type?${query}`)
  },

  searchShows(params = {}) {
    const query = new URLSearchParams(params).toString()
    return request(`/show/of/name?${query}`)
  },

  favoriteShow(id) {
    return request(`/show/${id}/favorite`, { method: 'POST' })
  },

  getMyFavorites(current = 1) {
    return request(`/show/favorite/my?current=${current}`)
  },

  followShow(id) {
    return request(`/show/${id}/follow`, { method: 'POST' })
  },

  unfollowShow(id) {
    if (!id || id === 'null' || id === 'undefined' || isNaN(Number(id)) || Number(id) <= 0) {
      return Promise.reject(new Error('演出ID无效'))
    }
    return request(`/show/${id}/follow`, { method: 'DELETE' })
  },

  getMyFollows(current = 1) {
    return request(`/show/follow/my?current=${current}`)
  },

  // Confirmed guide actions stay on Echo's authenticated origin.
  reviewInsights(showId) {return request(`/show-review/guide-insights?showId=${encodeURIComponent(showId)}`)},
  guideOrderFollowUp(orderId) {return request(`/guide/order-follow-up/${encodeURIComponent(orderId)}`)},
  guideCalendar(orderId) {return request(`/guide/orders/${encodeURIComponent(orderId)}/calendar`)},
  guideCheckout(ticketId) { return request(`/guide/checkout/${ticketId}`) },
  guideCreateOrder(ticketId, body) { return request(`/guide/orders/${ticketId}`, {method:'POST',body:JSON.stringify(body)}) },
  guideRequestStatus(action, requestId) { return request(`/guide/requests/${action}/${requestId}`) },
  guideReviewContext(showId) { return request(`/guide/reviews/context/${showId}`) },
  guideReviewDraft(body) { return request('/guide/reviews/draft', {method:'POST',body:JSON.stringify(body),timeout:45000}) },
  guidePublishReview(body) { return request('/guide/reviews', {method:'POST',body:JSON.stringify(body)}) },

  // ===== 票务 =====
  getTicketsByShow(showId) {
    return request(`/ticket/list/${showId}`)
  },

  /**
   * 检查票种状态（轻量级接口，仅返回status字段）
   * @param {number} ticketId - 票种ID
   */
  checkTicketStatus(ticketId) {
    return request(`/ticket/${ticketId}/status`)
  },

  /**
   * 检查用户是否已购买过该秒杀票
   * @param {number} ticketId - 秒杀票ID
   */
  checkSeckillPurchased(ticketId) {
    return request(`/ticket/seckill/check-purchased/${ticketId}`)
  },

  /**
   * 批量检查用户已购买的秒杀票（用于UI禁用已购票种）
   * @param {number[]} ticketIds - 秒杀票ID列表
   * @returns {Promise<{purchasedIds: number[]}>} 已购买的秒杀票ID列表
   */
  checkSeckillPurchasedBatch(ticketIds) {
    return request('/ticket-order/seckill/check-batch', {
      method: 'POST',
      body: JSON.stringify(ticketIds)
    })
  },

  /**
   * 获取票种详情（完整信息，含秒杀信息、库存、是否可售卖等）
   * 用于选座页面初始化加载
   * @param {number} ticketId - 票种ID
   */
  getTicketDetail(ticketId) {
    return request(`/ticket/detail/${ticketId}`)
  },

  getSeatsByTicket(ticketId) {
    return request(`/ticket/${ticketId}/seats`)
  },

  lockSeat(ticketId, seatId) {
    return request(`/ticket/${ticketId}/seat/lock/${seatId}`, { method: 'POST' })
  },

  releaseSeat(ticketId, seatId) {
    return request(`/ticket/${ticketId}/seat/release/${seatId}`, { method: 'POST' })
  },

  /**
   * 秒杀下单（支持选座）
   * @param {number} ticketId - 秒杀票种ID
   * @param {number[]} [seatIds=[]] - 座位ID列表（选座票必填）
   * @param {Object} [options={}] - 额外选项
   * @returns {Promise<any>} 下单结果
   */
  seckillOrder(ticketId, seatIds = [], options = {}) {
    const params = new URLSearchParams()

    if (seatIds && seatIds.length > 0) {
      params.append('seatIds', seatIds.join(','))
    }

    const query = params.toString()
    const url = `/ticket-order/seckill/${ticketId}${query ? '?' + query : ''}`

    console.log('[API] 秒杀下单:', url, '座位数:', seatIds.length)

    return request(url, {
      method: 'POST'
    })
  },

  // 查询秒杀排队状态（排队模式下前端轮询此接口）
  // @param {number|string} ticketId - 秒杀票ID
  // @returns {Promise} 返回 {status: QUEUED|SUCCESS|FAILED|SOLD_OUT|NOT_IN_QUEUE, ahead, tempId, message}
  seckillQueueStatus(ticketId) {
    return request(`/ticket-order/seckill/queue/status/${ticketId}`, {
      method: 'GET'
    })
  },

  /**
   * 秒杀下单并等待排队结果
   * <p>
   * 秒杀采用"先入队、后消费"：POST /ticket-order/seckill/{id} 只表示入队成功
   * （返回 status=QUEUED 与前方人数），真正的预订单号来自排队结果接口。
   * 因此这里把"入队 + 轮询到终态"合成一次调用，调用方直接拿 tempId。
   *
   * @param {number} ticketId - 秒杀票种ID
   * @param {number[]} [seatIds=[]] - 座位ID列表（选座票必填）
   * @param {Object} [options] - 轮询参数
   * @param {number} [options.timeoutMs=20000] - 等待排队结果的最长时间
   * @param {number} [options.intervalMs=400] - 轮询间隔
   * @returns {Promise<{tempId: string, expireTime?: string, message?: string}>}
   */
  async seckillOrderAndWait(ticketId, seatIds = [], options = {}) {
    const { timeoutMs = 20000, intervalMs = 400 } = options

    const orderRes = await api.seckillOrder(ticketId, seatIds)
    const queued = orderRes?.data || {}

    // 同步响应已带预订单号（非排队模式），直接返回
    if (queued.tempId) {
      return queued
    }

    if (queued.status && queued.status !== 'QUEUED') {
      throw new Error(queued.message || '秒杀下单失败，请稍后重试')
    }

    console.log('[API] 秒杀已入队，前方', queued.ahead ?? 0, '人，开始轮询排队结果')
    return waitSeckillResult(ticketId, { timeoutMs, intervalMs })
  },

  createOrder(ticketId, options = {}) {
    // 支持普通订单和选座订单
    if (options.seats && options.seats.length > 0) {
      // 选座订单：使用专用接口 /{ticketId}/seats
      console.log('[API] 创建选座订单, ticketId:', ticketId, ', seats:', options.seats)
      return request(`/ticket-order/${ticketId}/seats`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          count: options.count || options.seats.length,
          seatIds: options.seats
        })
      })
    }

    // 普通订单：使用URL参数
    const params = new URLSearchParams()
    if (options.count) {
      params.append('count', options.count)
    }
    const query = params.toString()
    return request(`/ticket-order/${ticketId}${query ? '?' + query : ''}`, { method: 'POST' })
  },

  getOrderById(orderId) {
    return request(`/ticket-order/${orderId}`)
  },

  /**
   * 支付订单
   * @param {string} tempId - 预订单临时ID（从 createOrder 返回值获取，非真实 orderId）
   * @param {number} [usePoints=0] - 使用积分数（10积分=1元）
   */
  payOrder(tempId, usePoints = 0) {
    if (!tempId || typeof tempId !== 'string') {
      return Promise.reject(new Error('支付订单需要有效的临时订单ID'))
    }
    return request(`/ticket-order/pay/${tempId}?usePoints=${usePoints}`, { method: 'POST' })
  },

  cancelOrder(orderId) {
    const id = String(orderId || '')
    if (!/^(?:[1-9]\d*|TEMP_[A-Za-z0-9_-]+)$/.test(id)) {
      return Promise.reject(new Error('请选择有效订单'))
    }
    return request(`/ticket-order/cancel/${encodeURIComponent(id)}`, { method: 'POST' })
  },

  refundOrder(orderId) {
    return request(`/ticket-order/refund/${orderId}`, { method: 'POST' })
  },

  getMyOrders(params = {}) {
    const query = new URLSearchParams(params).toString()
    return request(`/ticket-order/my?${query}`)
  },

  // ===== 评测 =====
  getHotReviews(current = 1, size = 10) {
    return request(`/show-review/hot?current=${current}&size=${size}`)
  },

  // 注意：request() 返回完整响应信封 {success, data, ...}，后端游标类接口把
  // {records, nextCursor} 放在 data 字段内，这里统一解包，调用方直接取 records
  getLatestReviews(cursor = '') {
    const params = cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''
    return request(`/show-review/latest${params}`)
      .then(res => res.data || { records: [], nextCursor: '' })
  },

  getLatestCommentedReviews(cursor = '') {
    const params = cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''
    return request(`/show-review/latest-commented${params}`)
      .then(res => res.data || { records: [], nextCursor: '' })
  },

  getRecommendedReviews(cursor = '') {
    const params = cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''
    return request(`/show-review/recommended${params}`)
      .then(res => res.data || { records: [], nextCursor: '' })
  },

  getFollowFeed(cursor = '') {
    const params = cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''
    return request(`/show-review/of/follow${params}`)
      .then(res => res.data || { records: [], nextCursor: '' })
  },

  searchReviews(keyword, current = 1) {
    return request(`/show-review/search?keyword=${encodeURIComponent(keyword)}&current=${current}`)
  },

  getReviewDetail(id) {
    return request(`/show-review/${id}`)
  },

  getMyReviews(current = 1) {
    return request(`/show-review/of/me?current=${current}`)
  },

  getMyReview(showId) {
    return request(`/show-review/of/me?showId=${showId}&size=1`)
  },

  getUserReviews(userId, current = 1) {
    return request(`/show-review/of/user?current=${current}&id=${userId}`)
  },

  checkFollow(targetUserId) {
    return request(`/follow/status/${targetUserId}`)
  },

  publishReview(data) {
    return request('/show-review', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },

  likeReview(id) {
    return request(`/show-review/like/${id}`, { method: 'PUT' })
  },

  getReviewsByShow(showId, current = 1, size = 10) {
    return request(`/show-review/of/show?showId=${showId}&current=${current}&size=${size}`)
  },

  deleteReview(id) {
    if (!id && id !== 0) {
      return Promise.reject(new Error('评测ID不能为空'))
    }
    return request(`/show-review/${id}`, { method: 'DELETE' })
  },

  // ===== 评论 =====
  publishComment(data) {
    return request('/show-review-comments/save', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },

  getReviewComments(reviewId, current = 1) {
    return request(`/show-review-comments/of/${reviewId}?current=${current}`)
  },

  // ===== 关注用户 =====
  followUser(targetUserId, isFollow = true) {
    const boolStr = String(isFollow === true)
    return request(`/follow/${targetUserId}/${boolStr}`, { method: 'PUT' })
  },

  // ===== 积分 =====
  getPoints() {
    return request('/points/my')
  },

  getPointsRecords(current = 1) {
    return request(`/points/records?current=${current}`)
  },

  getLotteryRecords(current = 1) {
    return request(`/points/lottery/records?current=${current}`)
  },

  exchangeCoupon() {
    return request('/points/coupon', { method: 'POST' })
  },

  lottery() {
    return request('/points/lottery', { method: 'POST' })
  },

  // ===== 通知 =====
  getNotifications(current = 1, size = 20) {
    return request(`/notification/my?current=${current}&size=${size}`)
  },

  markNotificationRead(id) {
    return request(`/notification/${id}/read`, { method: 'PUT' })
  },

  markAllNotificationsRead() {
    return request('/notification/read-all', { method: 'PUT' })
  },

  // ===== 统计 =====
  getDashboardStats() {
    return request('/statistics/dashboard')
  },

  getHotShows(limit = 10, location = null) {
    let url = `/statistics/hot-shows?limit=${limit}`

    if (location && location.x && location.y) {
      url += `&x=${location.x}&y=${location.y}`
      console.log('[API] getHotShows 带位置参数:', url)
    }

    return request(url)
  },

  getSalesTrend(days = 7) {
    return request(`/statistics/sales-trend?days=${days}`)
  },

  getUserProfile(userId) {
    return request(`/user/profile/${userId}`)
  },

  // ===== 图片上传（统一使用request函数，自动携带Token和CSRF）=====
  uploadImage(file) {
    const formData = new FormData()
    formData.append('file', file)

    // ✅ 关键：不设置Content-Type，让浏览器自动添加 multipart/form-data + boundary
    return request('/upload/blog', {
      method: 'POST',
      body: formData
    })
  },

  uploadAvatar(file) {
    const formData = new FormData()
    formData.append('file', file)

    // ✅ 关键：不设置Content-Type，让浏览器自动添加 multipart/form-data + boundary
    return request('/upload/avatar', {
      method: 'POST',
      body: formData
    })
  },

}

/**
 * 轮询秒杀排队结果直到终态
 * <p>
 * 秒杀请求进入队列后，由后端消费者异步扣减库存并创建预订单，
 * 期间只会读到 QUEUED，预订单号在终态结果里给出。
 *
 * @param {number} ticketId - 秒杀票种ID
 * @param {Object} [options]
 * @param {number} [options.timeoutMs=20000] - 最长等待时间
 * @param {number} [options.intervalMs=400] - 轮询间隔
 * @returns {Promise<{tempId: string, expireTime?: string, message?: string}>}
 */
export async function waitSeckillResult(ticketId, options = {}) {
  const { timeoutMs = 20000, intervalMs = 400 } = options
  const deadline = Date.now() + timeoutMs
  let lastStatus = ''

  while (Date.now() < deadline) {
    const res = await api.seckillQueueStatus(ticketId)
    const data = (res && res.data) || {}
    lastStatus = data.status || ''

    if (lastStatus === 'SUCCESS') {
      if (!data.tempId) {
        throw new Error('秒杀已成功但未取到预订单号，请到「我的订单」查看')
      }
      return data
    }

    if (lastStatus === 'FAILED') {
      throw new Error(data.message || '秒杀失败，请稍后重试')
    }

    if (lastStatus === 'SOLD_OUT') {
      throw new Error(data.message || '该票种已售罄')
    }

    if (lastStatus === 'NOT_IN_QUEUE') {
      throw new Error('排队记录已失效，请返回重新提交')
    }

    await new Promise((resolve) => setTimeout(resolve, intervalMs))
  }

  throw new Error(`秒杀排队超时（最后状态：${lastStatus || '未知'}），请稍后到「我的订单」确认`)
}

/**
 * 格式化价格（分 → 元）
 * @param {number} cents - 价格（单位：分）
 * @returns {string} 格式化后的价格字符串（如 "299.00 元"）
 */
export function formatPrice(cents) {
  if (cents == null) return '-'
  const yuan = Math.round(Number(cents)) / 100
  return yuan.toFixed(2) + ' 元'
}

// ============ 审计追踪工具函数 (v4.0 - 用户端) ============

/**
 * 来源渠道常量定义（用户端）
 */
export const USER_SOURCE_CHANNELS = {
  WEB_USER: 'web-user',
  MOBILE_USER: 'mobile-user',
  MINI_PROGRAM: 'mini-program',
  APP: 'app',
  TEST: 'test'
}

/**
 * 检测用户端来源渠道（基于User-Agent）
 * @returns {string} 渠道标识
 */
function detectUserSourceChannel() {
  if (!window || !window.navigator) {
    return USER_SOURCE_CHANNELS.TEST
  }

  const ua = window.navigator.userAgent.toLowerCase()

  if (ua.includes('miniprogram')) {
    return USER_SOURCE_CHANNELS.MINI_PROGRAM
  }

  if (ua.includes('mobile') || ua.includes('android') || ua.includes('iphone')) {
    // 进一步区分是H5还是App
    if (ua.includes('micromessenger')) {
      return USER_SOURCE_CHANNELS.MOBILE_USER  // 微信内置浏览器
    }
    return USER_SOURCE_CHANNELS.MOBILE_USER
  }

  return USER_SOURCE_CHANNELS.WEB_USER
}

/**
 * 为用户请求数据自动添加审计字段（内部方法）
 * @private
 * @param {Object} data - 原始数据
 * @returns {Object} 增强后的数据（包含 sourceChannel, remark）
 */
api._addAuditFieldsForUser = function(data) {
  return {
    ...data,
    sourceChannel: data.sourceChannel || detectUserSourceChannel(),
    remark: data.remark ? data.remark.substring(0, 500) : undefined
  }
}

export function parseImages(images) {
  if (!images) return []

  try {
    if (Array.isArray(images)) {
      return images
    }

    if (typeof images === 'string') {
      const trimmed = images.trim()

      // 检查是否是JSON数组格式 ["url1","url2"]
      if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
        const parsed = JSON.parse(trimmed)
        if (Array.isArray(parsed)) {
          return parsed.filter(url => typeof url === 'string' && url.length > 0)
        }
      }

      // 检查是否是单个图片路径（以 / 或 http 开头，且包含 .jpg/.png/.jpeg/.webp）
      if (trimmed.startsWith('/') || trimmed.startsWith('http')) {
        if (/\.(jpg|jpeg|png|webp|gif)$/i.test(trimmed)) {
          return [trimmed]
        }
      }

      // 兼容旧格式：纯文件名如 "show1_1.jpg"
      if (/^[a-zA-Z0-9_-]+\.(jpg|jpeg|png|webp|gif)$/i.test(trimmed)) {
        return [trimmed]
      }
    }
  } catch (e) {
    console.warn('[parseImages] 解析失败:', e, '原始数据:', images)
  }

  return []
}

export function getImageUrl(filename) {
  if (!filename) return ''
  if (filename.startsWith('http')) return filename

  let cleanFilename = filename.trim()

  // 标准化路径格式
  if (!cleanFilename.startsWith('/')) {
    cleanFilename = '/' + cleanFilename
  }

  // 如果已经是完整路径，直接返回（通过Vite代理）
  if (cleanFilename.startsWith('/ssn/imgs/')) {
    return cleanFilename  // 开发环境通过Vite代理转发到8081
  }

  // 其他情况，添加 /ssn/imgs 前缀
  return `/ssn/imgs/${cleanFilename.replace(/^\//, '')}`
}

export const ORDER_STATUS = {
  0: { label: '待支付', color: 'var(--color-warning)' },
  1: { label: '秒杀预占', color: 'var(--color-info)' },
  2: { label: '已支付', color: 'var(--color-success)' },
  3: { label: '已核销', color: 'var(--color-primary)' },
  4: { label: '已取消', color: 'var(--color-text-light)' },
  5: { label: '已退款', color: 'var(--color-danger)' },
  6: { label: '申请退款中', color: 'var(--color-warning)' },
  7: { label: '已拒绝', color: 'var(--color-warning)' }
}

export function isLoggedIn() {
  return !!sessionStorage.getItem('token')
}

export function getUser() {
  const raw = sessionStorage.getItem('user')
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

/**
 * 获取当前用户ID（async）。
 * 优先读 sessionStorage 缓存；缺失（旧版本登录数据/getMe 写入失败/结构变更）时
 * 回源 GET /user/me（Token 识别身份）并回写缓存。
 * 解决"前端拿本地缓存的 user.id 做前置校验，字段缺失导致静默不发请求"的隐患。
 * @returns {Promise<string|number|null>} 用户ID；未登录或查询失败返回 null
 */
export async function getUserId() {
  const cached = getUser()
  if (cached && cached.id) return cached.id
  try {
    const res = await request('/user/me')
    if (res && res.data && res.data.id) {
      sessionStorage.setItem('user', JSON.stringify(res.data))
      return res.data.id
    }
  } catch {
    // 未登录或接口异常：返回 null，由调用方决定降级行为
  }
  return null
}

// ==================== 统一分页工具 ====================

/**
 * 分页参数默认配置
 */
const PAGINATION_DEFAULTS = {
  current: 1,
  size: 10,
  maxSize: 100
}

/**
 * 构建分页查询URL（自动添加current和size参数）
 *
 * @param {string} baseUrl - 基础URL（如 /show/list）
 * @param {Object} options - 分页选项
 * @param {number} [options.current=1] - 页码
 * @param {number} [options.size=10] - 每页大小
 * @param {Object} [options.params={}] - 额外查询参数
 * @returns {string} 完整的带分页参数的URL
 *
 * @example
 * buildPaginatedUrl('/show/reviews', { current: 2, size: 20, params: { showId: 123 } })
 * // => '/show/reviews?current=2&size=20&showId=123'
 */
function buildPaginatedUrl(baseUrl, options = {}) {
  const {
    current = PAGINATION_DEFAULTS.current,
    size = PAGINATION_DEFAULTS.size,
    params = {}
  } = options

  const queryParams = new URLSearchParams()

  queryParams.append('current', Math.max(1, current))
  queryParams.append('size', Math.min(Math.max(1, size), PAGINATION_DEFAULTS.maxSize))

  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      queryParams.append(key, value)
    }
  })

  const queryString = queryParams.toString()
  return queryString ? `${baseUrl}?${queryString}` : baseUrl
}

/**
 * 执行分页请求（统一处理分页逻辑）
 *
 * @param {string} url - API端点
 * @param {Object} options - 分页选项
 * @param {number} [options.current=1] - 页码
 * @param {number} [options.size=10] - 每页大小
 * @param {Object} [options.params={}] - 额外查询参数
 * @param {string} [options.method='GET'] - HTTP方法
 * @returns {Promise<{data: Array, total: number, current: number, size: number}>}
 *
 * @example
 * const result = await paginatedRequest('/show/reviews', {
 *   current: 1,
 *   size: 10,
 *   params: { showId: 123 }
 * })
 * console.log(result.data)     // 当前页数据
 * console.log(result.total)    // 总记录数
 * console.log(result.hasNext)  // 是否有下一页
 */
async function paginatedRequest(url, options = {}) {
  const {
    method = 'GET',
    ...paginationOptions
  } = options

  const fullUrl = buildPaginatedUrl(url, paginationOptions)

  const response = await request(fullUrl, { method })

  if (!response || !response.data) {
    return {
      data: [],
      total: 0,
      current: paginationOptions.current || 1,
      size: paginationOptions.size || 10,
      hasNext: false,
      hasPrev: false
    }
  }

  const {
    data = [],
    total = 0,
    current = paginationOptions.current || 1,
    size = paginationOptions.size || 10
  } = response

  const totalPages = Math.ceil(total / size)

  return {
    data,
    total,
    current,
    size,
    totalPages,
    hasNext: current < totalPages,
    hasPrev: current > 1
  }
}

/**
 * 分页响应数据标准化处理
 *
 * 将后端返回的各种格式统一为标准分页结构：
 * - MyBatis-Plus Page格式：{ records, total, size, current }
 * - 自定义格式：{ data, total, pageSize, pageNum }
 *
 * @param {*} response - 后端原始响应
 * @returns {Object} 标准化后的分页数据
 */
function normalizePaginationResponse(response) {
  if (!response) {
    return {
      data: [],
      total: 0,
      current: 1,
      size: 10
    }
  }

  if (Array.isArray(response)) {
    return {
      data: response,
      total: response.length,
      current: 1,
      size: response.length
    }
  }

  const isMybatisPlusPage = 'records' in response && 'total' in response

  if (isMybatisPlusPage) {
    return {
      data: response.records || [],
      total: response.total || 0,
      current: response.current || 1,
      size: response.size || 10
    }
  }

  return {
    data: response.data || [],
    total: response.total || 0,
    current: response.current || response.pageNum || 1,
    size: response.size || response.pageSize || 10
  }
}

export {
  buildPaginatedUrl,
  paginatedRequest,
  normalizePaginationResponse,
  PAGINATION_DEFAULTS
}

export { HttpError, BusinessError }
