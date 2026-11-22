// 支持环境变量配置（用于CI/CD和测试环境）
// 浏览器环境：使用相对路径（由Vite代理转发）
// Node.js环境（测试）：直接连接后端
const BASE_URL = (typeof window !== 'undefined')
  ? '/api'  // 浏览器环境
  : (process.env.API_BASE_URL || 'http://localhost:8081/api')  // Node.js/测试环境

// 🔒 API安全配置
const API_CONFIG = {
  timeout: 30000,              // 30秒超时
  maxRetries: 2,               // 最大重试次数
  retryDelay: 1000,            // 重试间隔(ms)
  rateLimitWindow: 60000,      // 限流窗口(60s)
  rateLimitMaxRequests: 50,    // 窗口内最大请求数（管理端适度限制，防止误操作）
  sensitiveFields: ['password', 'oldPassword', 'newPassword', 'code', 'token']  // 敏感字段（日志脱敏）
}

// 📊 请求统计（用于限流）
let requestCount = 0
let rateLimitResetTime = Date.now() + API_CONFIG.rateLimitWindow

/**
 * 🎯 自定义错误类型 - 增强错误分类
 */

class NetworkError extends Error {
  constructor(message, { url, method, statusCode, cause } = {}) {
    super(message)
    this.name = 'NetworkError'
    this.url = url
    this.method = method
    this.statusCode = statusCode
    this.cause = cause
    this.isNetworkError = true
  }
}

class HttpError extends Error {
  constructor(message, { url, method, statusCode, responseBody } = {}) {
    super(message)
    this.name = 'HttpError'
    this.url = url
    this.method = method
    this.statusCode = statusCode
    this.responseBody = responseBody
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

class ValidationError extends Error {
  constructor(message, { fields } = {}) {
    super(message)
    this.name = 'ValidationError'
    this.fields = fields
    this.isValidationError = true
  }
}

/**
 * 🔒 检查是否触发限流
 */
function checkRateLimit() {
  const now = Date.now()

  // 重置窗口
  if (now >= rateLimitResetTime) {
    requestCount = 0
    rateLimitResetTime = now + API_CONFIG.rateLimitWindow
  }

  requestCount++

  if (requestCount > API_CONFIG.rateLimitMaxRequests) {
    const waitTime = Math.ceil((rateLimitResetTime - now) / 1000)
    throw new Error(`操作过于频繁，请${waitTime}秒后重试`)
  }
}

/**
 * 敏感数据脱敏（日志用）
 */
function sanitizeForLogging(data) {
  if (!data || typeof data !== 'object') return data

  const sanitized = { ...data }

  for (const field of API_CONFIG.sensitiveFields) {
    if (sanitized[field]) {
      sanitized[field] = field === 'token'
        ? `${String(sanitized[field]).substring(0, 10)}...`
        : '******'
    }
  }

  return sanitized
}

/**
 * 📝 记录API请求日志
 */
function logRequest(method, url, data, duration, success, error) {
  const logData = {
    timestamp: new Date().toISOString(),
    method,
    url: url.substring(0, 50) + (url.length > 50 ? '...' : ''),
    data: data ? sanitizeForLogging(data) : null,
    duration: `${duration}ms`,
    success,
    error: error?.message || null
  }

  if (success) {
    console.log(`[API 成功] ${method} ${logData.url} (${logData.duration})`, logData.data)
  } else {
    console.error(`[API 失败] ${method} ${logData.url} -`, logData.error)
  }

  // 生产环境可上报到监控系统
  // sendToMonitoring(logData)
}

/**
 * 🔄 带重试的请求函数
 */
async function requestWithRetry(url, options = {}, retryCount = 0) {
  const startTime = Date.now()
  const method = (options.method || 'GET').toUpperCase()

  try {
    // 🔒 检查限流
    checkRateLimit()

    // 获取Token（使用sessionStorage实现标签页隔离）
    const token = sessionStorage.getItem('token')

    // 构建请求头
    const defaultHeaders = {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest',  // 标识AJAX请求
      'X-Request-ID': generateRequestId(),   // 请求唯一ID（用于追踪）
    }

    if (token) {
      defaultHeaders['Authorization'] = `Bearer ${token}`
    }

    // CSRF Token（如果有）
    const csrfToken = getCsrfToken()
    if (csrfToken) {
      defaultHeaders['X-CSRF-Token'] = csrfToken
    }

    const config = {
      ...options,
      headers: {
        ...defaultHeaders,
        ...options.headers
      },
      signal: AbortSignal.timeout(API_CONFIG.timeout)  // 超时控制
    }

    console.log(`[API] 发起请求: ${method} ${url}`, sanitizeForLogging(options.body ? JSON.parse(options.body) : null))

    let response
    try {
      response = await fetch(`${BASE_URL}${url}`, config)
    } catch (fetchError) {
      const duration = Date.now() - startTime
      logRequest(method, url, options.body, duration, false, fetchError)
      throw new NetworkError(`网络请求失败: ${fetchError.message}`, { url, method, cause: fetchError })
    }

    if (!response) {
      throw new NetworkError('网络请求未返回响应', { url, method })
    }

    const duration = Date.now() - startTime

    // 处理HTTP错误状态码
    if (!response.ok) {
      await handleHttpError(response, url, method, duration)
      return  // handleHttpError会抛出异常
    }

    // 解析响应
    let data
    try {
      data = await response.json()
    } catch (parseError) {
      logRequest(method, url, null, duration, false, new Error('响应解析失败'))
      throw new Error('服务器返回数据格式异常')
    }

    // 检查业务状态码
    if (data.success === false || data.code && data.code !== 200 && data.code !== 0) {
      const businessErrorMsg = data.errorMsg || data.msg || '业务逻辑错误'
      logRequest(method, url, options.body, duration, false, new Error(businessErrorMsg))
      throw new BusinessError(businessErrorMsg, {
        code: data.code,
        data: data.data
      })
    }

    // 成功日志
    logRequest(method, url, options.body, duration, true)

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
    if (!updatedCsrfToken && data) {
      if (data.csrfToken) {
        updatedCsrfToken = data.csrfToken
      } else if (data.data && data.data.csrfToken) {
        updatedCsrfToken = data.data.csrfToken
      }
    }

    // 更新本地存储的 CSRF Token
    if (updatedCsrfToken) {
      try {
        localStorage.setItem('csrf_token', updatedCsrfToken)
        localStorage.setItem('csrf_token_time', Date.now().toString())
        console.log('[API] CSRF Token已更新:', updatedCsrfToken.substring(0, 8) + '...', '有效期为1小时')
      } catch (e) {
        console.warn('[API] CSRF Token保存失败:', e.message)
      }
    }

    return data

  } catch (error) {
    const duration = Date.now() - startTime

    // 判断是否应该重试
    const shouldRetry =
      retryCount < API_CONFIG.maxRetries &&
      isRetryableError(error) &&
      !options._noRetry  // 可通过选项禁用重试

    if (shouldRetry) {
      console.warn(`[API] 请求失败，准备第${retryCount + 1}次重试...`, error.message)

      // 等待后重试
      await new Promise(resolve => setTimeout(resolve, API_CONFIG.retryDelay * (retryCount + 1)))

      return requestWithRetry(url, options, retryCount + 1)
    }

    // 重试次数耗尽或不可重试，记录失败并抛出异常
    logRequest(method, url, options.body, duration, false, error)
    throw error
  }
}

/**
 * 处理HTTP错误
 */
async function handleHttpError(response, url, method, duration) {
  let errorMsg = `请求失败 (${response.status})`

  try {
    const err = await response.json()
    // 错误字段优先级（与后端Result.errorMsg保持一致）：
    // 1. errorMsg - 后端业务异常标准字段
    // 2. message - Spring框架默认异常字段
    // 3. msg - 兼容其他可能的响应格式
    // 4. 默认值 - HTTP状态码描述
    errorMsg = err.errorMsg || err.message || err.msg || errorMsg

    // 特定状态码处理
    switch (response.status) {
      case 400:
        errorMsg = `参数错误: ${errorMsg}`
        break
      case 401:
        console.warn('[API] 🔒 401 未授权，Token可能已过期')
        sessionStorage.removeItem('token')
        sessionStorage.removeItem('phone')
        sessionStorage.removeItem('user')
        sessionStorage.removeItem('login_time')

        // 延迟跳转，让错误信息显示
        setTimeout(() => {
          if (window.location.hash !== '#/login') {
            window.location.hash = '#/login'
            window.location.reload()
          }
        }, 800)

        errorMsg = '登录已过期，请重新登录'
        break

      case 403:
        errorMsg = '权限不足：您没有执行此操作的权限'
        console.error('[API] 🚫 403 禁止访问:', url)
        break

      case 404:
        errorMsg = '请求的资源不存在'
        break

      case 409:
        errorMsg = '数据冲突：该资源已被其他人修改，请刷新后重试'
        break

      case 413:
        errorMsg = '上传文件过大，请压缩后重试'
        break

      case 429:
        errorMsg = '操作过于频繁，请稍后再试'
        break

      case 500:
      case 502:
      case 503:
      case 504:
        errorMsg = `服务器错误(${response.status})，请稍后重试`
        console.error(`[API] 💥 服务器异常 ${response.status}:`, url)
        break

      default:
        if (response.status >= 500) {
          errorMsg = `服务器内部错误 (${response.status})`
        }
    }
  } catch (_) {
    // 响应体为空或非JSON
    if (response.status === 401) {
      errorMsg = '登录已过期，请重新登录'
    }
  }

  logRequest(method, url, null, duration, false, new Error(errorMsg))

  let errorBody
  try {
    errorBody = await response.clone().json()
  } catch (_) {
    errorBody = null
  }

  throw new HttpError(errorMsg, {
    url,
    method,
    statusCode: response.status,
    responseBody: errorBody
  })
}

/**
 * 🔄 判断错误是否可重试
 */
function isRetryableError(error) {
  // NetworkError 可重试（网络断开、DNS解析失败等）
  if (error instanceof NetworkError || error.isNetworkError) {
    return true
  }

  // 原生网络错误可重试
  if (error.name === 'TypeError' && error.message.includes('fetch')) {
    return true
  }

  // 超时可重试
  if (error.name === 'TimeoutError') {
    return true
  }

  // HttpError中的5xx服务端错误可重试
  if (error instanceof HttpError || error.isHttpError) {
    if (error.statusCode >= 500 && error.statusCode < 600) {
      return true
    }
    // 429 Too Many Requests 也可重试
    if (error.statusCode === 429) {
      return true
    }
  }

  // 兼容旧格式：服务端5xx错误可重试一次
  if (error.message?.includes('服务器')) {
    return true
  }

  // BusinessError 和 ValidationError 不重试
  if (error.isBusinessError || error.isValidationError) {
    return false
  }

  return false
}

/**
 * 🆔 生成请求唯一ID
 */
function generateRequestId() {
  return 'req_' + Date.now().toString(36) + '_' + Math.random().toString(36).substr(2, 9)
}

/**
 * 🎫 获取CSRF Token（带过期检查）
 */
function getCsrfToken() {
  try {
    const CSRF_TOKEN_EXPIRY = 3600000  // CSRF Token有效期为1小时（单位：毫秒）
    const csrfToken = localStorage.getItem('csrf_token') || ''
    const csrfTokenTime = parseInt(localStorage.getItem('csrf_token_time') || '0')
    const csrfTokenValid = csrfToken && (Date.now() - csrfTokenTime < CSRF_TOKEN_EXPIRY)

    if (csrfToken && !csrfTokenValid) {
      console.warn('[API] CSRF Token已过期，清除并等待服务端刷新')
      localStorage.removeItem('csrf_token')
      localStorage.removeItem('csrf_token_time')
      return ''
    }

    return csrfTokenValid ? csrfToken : ''
  } catch {
    return null
  }
}

// 兼容旧接口
const request = (url, options = {}) => requestWithRetry(url, options)

export const api = {
  // ============ 通用请求 ============
  get(url, options = {}) {
    if (options.params) {
      const q = new URLSearchParams()
      for (const [k, v] of Object.entries(options.params)) {
        if (v !== undefined && v !== null && v !== '') q.append(k, v)
      }
      const qs = q.toString()
      url = url + (qs ? '?' + qs : '')
    }
    return request(url)
  },
  post(url, data) {
    return request(url, {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },
  put(url, data) {
    return request(url, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  },
  delete(url) {
    if (!url || url.includes('/null') || url.includes('/undefined')) {
      console.error('[API-安全拦截] 拒绝无效的DELETE请求:', url)
      return Promise.reject(new Error('请求URL无效'))
    }

    // ✅ 删除操作禁止重试（防止重复删除）
    console.log('[API-DELETE] 发起删除请求:', url, new Error().stack?.split('\n')?.[2]?.trim())
    return request(url, { method: 'DELETE', _noRetry: true })
  },

  /**
   * 📤 文件上传（自动携带认证信息）
   * @param {string} url - 上传接口路径
   * @param {FormData} formData - 表单数据（包含文件）
   * @param {Object} options - 可选配置
   * @returns {Promise<Object>} 上传结果
   */
  uploadFile(url, formData, options = {}) {
    const token = sessionStorage.getItem('token')
    const csrfToken = getCsrfToken()

    const headers = {
      'X-Requested-With': 'XMLHttpRequest',
      'X-Request-ID': generateRequestId()
    }

    if (token) {
      headers['Authorization'] = `Bearer ${token}`
    }

    if (csrfToken) {
      headers['X-CSRF-Token'] = csrfToken
    }

    // 注意：不设置 Content-Type，让浏览器自动设置 multipart/form-data boundary
    console.log(`[API] 📤 文件上传: POST ${url}`, {
      fileSize: formData.get('file')?.size,
      fileName: formData.get('file')?.name,
      hasAuth: !!token,
      hasCSRF: !!csrfToken
    })

    return fetch(`${BASE_URL}${url}`, {
      method: 'POST',
      headers,
      body: formData,
      signal: AbortSignal.timeout(API_CONFIG.timeout)
    }).then(async response => {
      const result = await response.json()

      if (!response.ok) {
        throw new Error(`上传失败 (${response.status}: ${response.statusText})`)
      }

      if (result.success && result.data) {
        console.log(`[API] ✅ 文件上传成功:`, result.data)

        // 更新 CSRF Token（如果响应中有新的）
        if (result.csrfToken) {
          localStorage.setItem('csrf_token', result.csrfToken)
          localStorage.setItem('csrf_token_time', Date.now().toString())
          console.log('[API] CSRF Token已更新:', result.csrfToken.substring(0, 8) + '...')
        }

        return result
      } else {
        const errorMsg = result.errorMsg || result.message || '未知错误'
        throw new Error(errorMsg)
      }
    })
  },

  // ============ 认证 ============
  /**
   * 发送管理员登录验证码
   * 后端按 query 参数读取手机号，验证码 120 秒内有效，60 秒内不可重复发送
   * @param {string} phone - 已绑定管理员的手机号
   */
  sendLoginCode(phone) {
    return request(`/admin/code?phone=${encodeURIComponent(phone)}`, {
      method: 'POST'
    })
  },

  /**
   * 密码登录
   * @param {string} phone - 手机号
   * @param {string} password - 密码
   */
  login(phone, password) {
    return request('/admin/login', {
      method: 'POST',
      body: JSON.stringify({ phone, password })
    })
  },

  /**
   * 验证码登录
   * @param {string} phone - 手机号
   * @param {string} code - 短信验证码
   */
  loginByCode(phone, code) {
    return request('/admin/login', {
      method: 'POST',
      body: JSON.stringify({ phone, code })
    })
  },

  logout() {
    return request('/admin/logout', { method: 'POST' })
  },

  sendPasswordCode() {
    return request('/admin/password/code', { method: 'POST' })
  },

  changePassword(oldPassword, newPassword, code) {
    return request('/admin/password', {
      method: 'PUT',
      body: JSON.stringify({ oldPassword, newPassword, code })
    })
  },

  getAdminInfo() {
    return request('/admin/info', { method: 'GET' })
  },

  // ============ 仪表盘 ============
  getDashboard() {
    return request('/admin/dashboard')
  },

  // ============ 演出管理 ============
  // GET /admin/show/list — 管理员专用演出列表（含所有状态，支持多条件筛选）
  getShowList(params = {}) {
    const query = new URLSearchParams()
    if (params.current) query.append('current', params.current)
    if (params.pageSize) query.append('pageSize', params.pageSize)
    if (params.name) query.append('name', params.name)
    if (params.id) query.append('id', params.id)
    if (params.status !== undefined && params.status !== null) query.append('status', params.status)
    if (params.typeId) query.append('typeId', params.typeId)
    if (params.sortBy) query.append('sortBy', params.sortBy)
    if (params.sortOrder) query.append('sortOrder', params.sortOrder)
    return request(`/admin/show/list?${query.toString()}`)
  },

  // POST /admin/show/cache/clear — 清除演出缓存（仅超级管理员）
  clearShowCache() {
    return request('/admin/show/cache/clear', {
      method: 'POST'
    })
  },

  createShow(data) {
    return request('/admin/show', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },

  updateShow(data) {
    if (!data || !data.id) {
      return Promise.reject(new Error('更新演出信息需要提供演出ID'))
    }
    return request(`/admin/show/${data.id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  },

  onlineShow(id) {
    return request(`/admin/show/${id}/online`, { method: 'PUT' })
  },

  offlineShow(id) {
    return request(`/admin/show/${id}/offline`, { method: 'PUT' })
  },

  // 管理端搜索：按关键词搜索演出（使用管理员专用接口）
  searchShows(keyword) {
    const query = new URLSearchParams()
    query.append('name', keyword)
    query.append('current', '1')
    return request(`/admin/show/list?${query.toString()}`)
  },

  // ============ 票种管理 ============
  getTicketList(params = {}) {
    const query = new URLSearchParams()
    if (params.current) query.append('current', params.current)
    if (params.pageSize) query.append('pageSize', params.pageSize)
    if (params.keyword) query.append('keyword', params.keyword)
    if (params.showId !== undefined && params.showId !== null) query.append('showId', params.showId)
    if (params.sortBy) query.append('sortBy', params.sortBy)
    if (params.sortOrder) query.append('sortOrder', params.sortOrder)
    return request(`/admin/ticket/list?${query.toString()}`)
  },

  updateTicket(data) {
    return request('/admin/ticket', {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  },

  /**
   * 上架/下架票种（专用端点，避免误改其他字段）
   * @param {number} id - 票种ID
   */
  toggleTicketStatus(id, status) {
    return request(`/admin/ticket/${id}/status?status=${status}`, {
      method: 'PUT'
    })
  },

  /**
   * 删除票种
   * @param {number} id - 票种ID
   */
  deleteTicket(id) {
    if (!id || id === 'null' || id === 'undefined' || isNaN(Number(id)) || Number(id) <= 0) {
      return Promise.reject(new Error('票种ID无效'))
    }
    return request(`/admin/ticket/${id}`, {
      method: 'DELETE'
    })
  },

  /**
   * 创建票种并生成座位（事务性原子操作）- v4.0 增强版（支持审计追踪）
   * 推荐用于需要选座的票种（hasSeats=true）
   * @param {Object} data - 包含 ticket 和 seatConfig
   * @param {Object} data.ticket - 票种信息（title, payValue, hasSeats, showId 等）
   * @param {Object} [data.seatConfig] - 座位配置（仅 hasSeats=true 时需要）
   * @param {Array} data.seatConfig.areas - 区域配置数组
   * @param {string} [data.remark] - 操作备注（可选，≤500字符）
   * @returns {Promise<Object>} 创建结果（包含 idempotencyKey 用于重试）
   */
  createTicketWithSeats(data) {
    const enhancedData = this._addAuditFields(data)
    console.log('[API] 🎫 createTicketWithSeats (v4.0) - 审计字段已添加:', {
      hasIdempotencyKey: !!enhancedData.idempotencyKey,
      sourceChannel: enhancedData.sourceChannel,
      remark: enhancedData.remark || '(无)'
    })

    return request('/admin/ticket/with-seats', {
      method: 'POST',
      body: JSON.stringify(enhancedData)
    }).then(result => {
      result._idempotencyKey = enhancedData.idempotencyKey
      return result
    })
  },

  /**
   * 安全重试创建票种（使用相同的幂等键）
   * 用于网络超时等异常情况下的重试
   * @param {Object} data - 原始请求数据（同 createTicketWithSeats）
   * @param {string} cachedIdempotencyKey - 首次请求时缓存的幂等键
   * @returns {Promise<Object>} 重试结果
   */
  retryCreateTicketWithSeats(data, cachedIdempotencyKey) {
    if (!cachedIdempotencyKey) {
      throw new Error('retryCreateTicketWithSeats 需要提供缓存的幂等键')
    }

    const enhancedData = {
      ...data,
      idempotencyKey: cachedIdempotencyKey,
      sourceChannel: detectSourceChannel(),
      remark: data.remark || '安全重试'
    }

    console.log('[API] 🔄 retryCreateTicketWithSeats - 使用缓存的幂等键:', {
      idempotencyKey: maskIdempotencyKey(cachedIdempotencyKey),
      sourceChannel: enhancedData.sourceChannel
    })

    return request('/admin/ticket/with-seats', {
      method: 'POST',
      body: JSON.stringify(enhancedData),
      _noRetry: true  // 重试请求本身不再自动重试
    }).then(result => {
      result._idempotencyKey = cachedIdempotencyKey
      result._isRetry = true
      return result
    })
  },

  adjustStock(id, stock) {
    return request(`/admin/ticket/${id}/stock?stock=${encodeURIComponent(stock)}`, { method: 'PUT' })
  },

  /**
   * 批量生成座位
   * @param {number} ticketId - 票种ID
   * @param {Object} data - 座位配置
   * @param {Array} data.areas - 区域配置数组
   * @param {string} data.areas[].name - 区域名称
   * @param {number} data.areas[].rows - 行数
   * @param {number} data.areas[].cols - 每行座位数
   */
  batchCreateSeats(ticketId, data) {
    return request(`/admin/ticket/${ticketId}/seats/batch`, {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },

  /**
   * 获取票种座位列表
   * @param {number} ticketId - 票种ID
   * @param {Object} [params] - 查询参数
   * @param {number} [params.status] - 座位状态筛选（可选）：0=空闲, 1=预占, 2=已售
   * @returns {Promise<Object>} { records: Array, total: number, ticketId: number }
   */
  getSeatList(ticketId, params = {}) {
    const query = new URLSearchParams()
    if (params.status !== undefined && params.status !== null) query.append('status', params.status)
    return request(`/admin/ticket/${ticketId}/seats?${query.toString()}`)
  },

  // 秒杀票 API 路径修正: /admin/ticket/seckill (非 /admin/seckill)
  createSeckillTicket(data) {
    return request('/admin/ticket/seckill', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },

  updateSeckillTicket(data) {
    return request('/admin/ticket/seckill', {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  },

  // ============ 订单管理 ============
  getOrderList(params = {}) {
    const query = new URLSearchParams()
    if (params.current) query.append('current', params.current)
    if (params.pageSize) query.append('pageSize', params.pageSize)
    if (params.status !== undefined && params.status !== null && params.status !== '') query.append('status', params.status)
    if (params.keyword) query.append('keyword', params.keyword)
    if (params.userName) query.append('userName', params.userName)
    if (params.sortBy) query.append('sortBy', params.sortBy)
    if (params.sortOrder) query.append('sortOrder', params.sortOrder)
    return request(`/admin/order/list?${query.toString()}`)
  },

  getOrderById(orderId) {
    return request(`/admin/order/${orderId}`)
  },

  approveRefund(orderId) {
    return request(`/admin/order/${orderId}/approve-refund`, { method: 'POST' })
  },

  exportOrders(params = {}) {
    const query = new URLSearchParams()
    if (params.status !== undefined && params.status !== '') query.append('status', params.status)
    return request(`/admin/order/export?${query.toString()}`)
  },

  // ============ 用户管理 ============
  getUserList(params = {}) {
    const query = new URLSearchParams()
    if (params.current) query.append('current', params.current)
    if (params.pageSize) query.append('pageSize', params.pageSize)
    if (params.keyword) query.append('keyword', params.keyword)
    if (params.phone) query.append('phone', params.phone)
    if (params.id) query.append('id', params.id)
    if (params.sortBy) query.append('sortBy', params.sortBy)
    if (params.sortOrder) query.append('sortOrder', params.sortOrder)
    return request(`/admin/user/list?${query.toString()}`)
  },

  disableUser(userId) {
    return request(`/admin/user/${userId}/disable`, { method: 'PUT' })
  },

  enableUser(userId) {
    return request(`/admin/user/${userId}/enable`, { method: 'PUT' })
  },

  // ============ 员工管理 ============
  getStaffList() {
    return request('/admin/staff/list')
  },

  addStaff(data) {
    return request('/admin/staff/add', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },

  disableStaff(adminId) {
    return request(`/admin/staff/${adminId}/disable`, { method: 'PUT' })
  },

  enableStaff(adminId) {
    return request(`/admin/staff/${adminId}/enable`, { method: 'PUT' })
  },

  removeStaff(userId) {
    return request(`/admin/staff/remove?userId=${encodeURIComponent(userId)}`, { method: 'POST' })
  },

  // ============ 批量操作接口 ============

  /**
   * 批量操作演出
   * @param {Array<number>} ids - 演出ID列表
   * @param {string} operation - 操作类型: online(上架)/offline(下架)/delete(删除)
   * @returns {Promise<Object>} 操作结果 { successCount, failCount, failedIds }
   */
  batchOperationShows(ids, operation) {
    return request('/admin/show/batch', {
      method: 'POST',
      body: JSON.stringify({ ids, operation })
    })
  },

  /**
   * 批量操作票种
   * @param {Array<number>} ids - 票种ID列表
   * @param {string} operation - 操作类型: update(更新)/delete(删除)
   * @param {Object} [extraData] - 更新时的额外数据（仅update时需要）
   * @returns {Promise<Object>} 操作结果
   */
  batchOperationTickets(ids, operation, extraData = null) {
    const data = { ids, operation }
    if (extraData && operation === 'update') {
      data.extraData = extraData
    }
    return request('/admin/ticket/batch', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },

  /**
   * 批量操作订单
   * @param {Array<number>} ids - 订单ID列表
   * @param {string} operation - 操作类型: approve-refund(审批退款)/reject-refund(拒绝退款)
   * @returns {Promise<Object>} 操作结果
   */
  batchOperationOrders(ids, operation) {
    return request('/admin/order/batch', {
      method: 'POST',
      body: JSON.stringify({ ids, operation })
    })
  },

  /**
   * 批量操作用户
   * @param {Array<number>} ids - 用户ID列表
   * @param {string} operation - 操作类型: enable(启用)/disable(禁用)
   * @param {null} [reason] - 操作原因（禁用时必填）
   * @returns {Promise<Object>} 操作结果
   */
  batchOperationUsers(ids, operation, reason = null) {
    const data = { ids, operation }
    if (reason && operation === 'disable') {
      data.reason = reason
    }
    return request('/admin/user/batch', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },

  // ============ 操作日志与监控接口 ============

/**
 * 查询操作日志
 * @param {Object} params - 查询参数
 * @param {number} [params.adminId] - 管理员ID
 * @param {string} [params.operationType] - 操作类型
 * @param {number} [params.resultStatus] - 结果状态 (0=进行中,1=成功,2=失败,3=部分成功)
 * @param {string} [params.startTime] - 开始时间 (ISO格式)
 * @param {string} [params.endTime] - 结束时间 (ISO格式)
 * @param {number} [params.current=1] - 页码
 * @param {number} [params.pageSize=20] - 每页条数
 */
  queryOperationLogs(params = {}) {
    const queryParams = new URLSearchParams()
    if (params.adminId) queryParams.append('adminId', params.adminId)
    if (params.operationType) queryParams.append('operationType', params.operationType)
    if (params.resultStatus !== undefined) queryParams.append('resultStatus', params.resultStatus)
    if (params.startTime) queryParams.append('startTime', params.startTime)
    if (params.endTime) queryParams.append('endTime', params.endTime)
    if (params.current) queryParams.append('current', params.current)
    if (params.pageSize) queryParams.append('pageSize', params.pageSize)

    return request(`/admin/operation-logs?${queryParams.toString()}`)
  },

/**
 * 导出操作日志
 * @param {Object} params - 导出参数（同 queryOperationLogs）
 */
  exportOperationLogs(params = {}) {
    const queryParams = new URLSearchParams()
    if (params.adminId) queryParams.append('adminId', params.adminId)
    if (params.operationType) queryParams.append('operationType', params.operationType)
    if (params.resultStatus !== undefined) queryParams.append('resultStatus', params.resultStatus)
    if (params.startTime) queryParams.append('startTime', params.startTime)
    if (params.endTime) queryParams.append('endTime', params.endTime)

    return request(`/admin/operation-logs/export?${queryParams.toString()}`)
  },

  // ============ 票务核销接口 ============

  /**
   * 核销订单（输入核销码）
   * @param {string} verificationCode - 8位核销码
   * @returns {Promise<Object>} { success, orderId, message }
   */
  verifyOrder(verificationCode) {
    return request(`/ticket-order/verify/${encodeURIComponent(verificationCode)}`, {
      method: 'POST'
    })
  },

  /**
   * 检查某张票的核销状态
   * @param {number} orderId - 订单ID
   * @param {number} ticketIndex - 票索引（从0开始）
   * @returns {Promise<Object>} { used: boolean, useTime?: string }
   */
  checkVerificationStatus(orderId, ticketIndex) {
    return request(`/ticket-order/verify/status/${orderId}/${ticketIndex}`)
  },

  /**
   * 获取核销统计数据
   * 返回: { todayCount, weekCount, pendingCount, totalCount }
   */
  getVerificationStatistics() {
    return request('/admin/verification/statistics')
  },

  /**
   * 获取最近核销记录
   * @param {number} [limit=20] - 数量限制
   * @returns {Promise<Array>} 核销记录列表
   */
  getRecentVerifications(limit = 20) {
    return request(`/admin/verification/recent?limit=${limit}`)
  },

// ============ 操作回滚接口 ============

/**
 * 获取可回滚的操作列表（最近24小时内的成功/部分成功操作）
 * @param {null} [targetType] - 目标实体类型（可选）
 *   - show: 演出
 *   - ticket: 票种
 *   - order: 订单
 *   - user: 用户
 */
  getRollableOperations(targetType = null) {
    let url = '/admin/rollback/rollable-operations'
    if (targetType) {
      url += `?targetType=${targetType}`
    }
    return request(url)
  }
}

// ============ 常量定义 ============

// 订单状态映射（与后端 TicketOrder 实体一致）
// 0=待支付, 1=秒杀预占, 2=已支付, 3=已核销, 4=已取消, 5=已退款
export const ORDER_STATUS = {
  0: { label: '待支付', color: '#f0b57a' },
  1: { label: '秒杀预占', color: '#e8a87c' },
  2: { label: '已支付', color: '#5fa88b' },
  3: { label: '已核销', color: '#6b8fa3' },
  4: { label: '已取消', color: '#ccc' },
  5: { label: '已退款', color: '#999' }
}

export const TICKET_STATUS = {
  0: { label: '下架', color: '#999' },
  1: { label: '上架', color: '#5fa88b' }
}

// ============ 工具函数 ============

/**
 * 格式化价格（分 → 元）
 * @param {number} value - 价格（单位：分）
 * @returns {string} 格式化后的价格字符串（如 "¥299.00"）
 */
export function formatPrice(value) {
  if (value === null || value === undefined) return '-'
  const yuan = Math.round(Number(value)) / 100
  return `¥${yuan.toFixed(2)}`
}

// ============ 审计追踪工具函数 (v4.0) ============

/**
 * 来源渠道常量定义
 */
export const SOURCE_CHANNELS = {
  WEB_ADMIN: 'web-admin',
  MOBILE_ADMIN: 'mobile-admin',
  API_THIRDPARTY: 'api-thirdparty',
  BATCH_JOB: 'batch-job',
  MIGRATION: 'migration',
  TEST: 'test'
}

/**
 * 检测当前来源渠道（基于User-Agent）
 * @returns {string} 渠道标识
 */
function detectSourceChannel() {
  if (!window || !window.navigator) {
    return SOURCE_CHANNELS.TEST
  }

  const ua = window.navigator.userAgent.toLowerCase()

  if (ua.includes('mobile') || ua.includes('android') || ua.includes('iphone')) {
    return SOURCE_CHANNELS.MOBILE_ADMIN
  }

  return SOURCE_CHANNELS.WEB_ADMIN
}

/**
 * 生成UUID v4格式的幂等键
 * @returns {string} UUID字符串（如 "550e8400-e29b-41d4-a716-446655440000"）
 */
export function generateIdempotencyKey() {
  try {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) {
      return crypto.randomUUID()
    }
  } catch (e) {
    console.warn('[审计] crypto.randomUUID 不可用，使用备用方案')
  }

  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    const r = Math.random() * 16 | 0
    const v = c === 'x' ? r : (r & 0x3 | 0x8)
    return v.toString(16)
  })
}

/**
 * 幂等键脱敏显示（用于日志，避免泄露完整UUID）
 * @param {string} key - 完整的幂等键
 * @returns {string} 脱敏后的字符串（如 "550e8400-..."）
 */
export function maskIdempotencyKey(key) {
  if (!key) return '(空)'
  if (key.length <= 8) return `${key}...`
  return `${key.substring(0, 8)}...`
}

/**
 * 为请求数据自动添加审计字段（内部方法）
 * @private
 * @param {Object} data - 原始数据
 * @returns {Object} 增强后的数据（包含 idempotencyKey, sourceChannel, remark）
 */
api._addAuditFields = function(data) {
  return {
    ...data,
    idempotencyKey: data.idempotencyKey || generateIdempotencyKey(),
    sourceChannel: data.sourceChannel || detectSourceChannel(),
    remark: data.remark ? data.remark.substring(0, 500) : undefined
  }
}

export function formatDateTime(value) {
  if (!value) return '-'
  const d = new Date(value)
  if (isNaN(d.getTime())) return value
  const Y = d.getFullYear()
  const M = String(d.getMonth() + 1).padStart(2, '0')
  const D = String(d.getDate()).padStart(2, '0')
  const h = String(d.getHours()).padStart(2, '0')
  const m = String(d.getMinutes()).padStart(2, '0')
  return `${Y}/${M}/${D} ${h}:${m}`
}

// 导出自定义错误类型，供外部使用和测试
export { NetworkError, HttpError, BusinessError, ValidationError, sanitizeForLogging }
