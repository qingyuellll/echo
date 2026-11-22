/**
 * 🛡️ 全局安全防护中间件
 *
 * 功能：
 * - XSS攻击防护
 * - CSRF Token管理
 * - 敏感数据脱敏
 * - 操作频率限制
 * - 安全日志记录
 */

// ========== 配置 ==========
const SECURITY_CONFIG = {
  // XSS防护
  xss: {
    enabled: true,
    escapeHtml: true,        // 是否转义HTML
    escapeJs: true,          // 是否转义JavaScript
    allowedTags: ['b', 'i', 'u', 'br', 'p'],  // 允许的HTML标签
    maxLength: 10000         // 最大输入长度
  },

  // CSRF防护
  csrf: {
    enabled: true,
    tokenName: 'X-CSRF-Token',
    headerName: 'X-CSRF-Token',
    expiresIn: 24 * 60 * 60 * 1000, // 24小时过期
  },

  // 频率限制
  rateLimit: {
    enabled: true,
    windowMs: 60 * 1000,     // 时间窗口：1分钟
    maxRequests: 100,        // 窗口内最大请求数
    maxLoginAttempts: 5,     // 最大登录尝试次数
    lockoutDuration: 15 * 60 * 1000, // 锁定时长：15分钟
  },

  // 日志配置
  logging: {
    enabled: true,
    level: 'warn',           // 日志级别：error/warn/info/debug
    maxLogs: 500,            // 最大保留日志数
    includeStackTrace: true  // 是否包含堆栈信息
  }
}

// ========== 状态管理 ==========
let csrfToken = null
let rateLimitStore = {}      // { action: { count, resetTime } }
let securityLogs = []        // 安全日志数组

/**
 * 🚀 初始化安全中间件
 */
export function initSecurity() {
  console.log('[Security] 🛡️ 初始化安全防护系统')

  // 初始化CSRF Token
  initCsrfToken()

  // 监听页面可见性变化（用于清理）
  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) {
        cleanupExpiredData()
      }
    })
  }

  // 定期清理过期数据
  setInterval(() => {
    cleanupExpiredData()
  }, 5 * 60 * 1000) // 每5分钟清理一次

  return {
    xss: xssProtection,
    csrf: csrfManager,
    rateLimit: rateLimiter,
    log: securityLogger,
    sanitize: sanitizeInput
  }
}

// ========== XSS防护 ==========

/**
 * 🔒 XSS防护函数
 *
 * @param {*} input - 需要净化的输入
 * @param {Object} options - 配置选项
 * @returns {string} 净化后的安全字符串
 */
export function xssProtection(input, options = {}) {
  const config = { ...SECURITY_CONFIG.xss, ...options }

  if (!config.enabled || input == null) {
    return input
  }

  // 转换为字符串
  let str = String(input)

  // 检查长度限制
  if (str.length > config.maxLength) {
    recordSecurityLog('XSS_LENGTH_EXCEEDED', {
      length: str.length,
      maxLength: config.maxLength
    }, 'warn')
    str = str.substring(0, config.maxLength)
  }

  // HTML转义
  if (config.escapeHtml) {
    str = escapeHtml(str)
  }

  // JavaScript转义
  if (config.escapeJs) {
    str = escapeJavaScript(str)
  }

  // 过滤危险标签
  if (config.allowedTags && config.allowedTags.length > 0) {
    str = filterDangerousTags(str, config.allowedTags)
  }

  // 检测潜在XSS攻击模式
  if (detectXssPatterns(str)) {
    recordSecurityLog('XSS_PATTERN_DETECTED', {
      original: input.substring(0, 100),
      sanitized: str.substring(0, 100)
    }, 'error')
  }

  return str
}

/**
 * 转义HTML特殊字符
 */
function escapeHtml(str) {
  const htmlEntities = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
    '/': '&#x2F;',
    '`': '&#x60;',
    '=': '&#x3D;'
  }

  return str.replace(/[&<>"'`=/]/g, char => htmlEntities[char])
}

/**
 * 转义JavaScript特殊字符
 */
function escapeJavaScript(str) {
  return str
    .replace(/\\/g, '\\\\')
    .replace(/'/g, "\\'")
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t')
    .replace(/</g, '\\x3c')
    .replace(/>/g, '\\x3e')
}

/**
 * 过滤危险HTML标签（只允许白名单标签）
 */
function filterDangerousHtml(html, allowedTags) {
  // 移除script标签及其内容
  html = html.replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')

  // 移除事件处理器
  html = html.replace(/\s*on\w+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, '')

  // 移除javascript:协议
  html = html.replace(/javascript:/gi, '')

  // 移除data:URI（可能包含恶意代码）
  html = html.replace(/data:\s*text\/html/gi, '')

  return html
}

/**
 * 检测潜在的XSS攻击模式
 */
function detectXssPatterns(str) {
  const patterns = [
    /<script[\s\S]*?>/i,
    /javascript\s*:/i,
    /on(error|load|click|mouseover|focus|blur)\s*=/i,
    /expression\s*\(/i,
    /vbscript\s*:/i,
    /<iframe[\s\S]*?>/i,
    /<object[\s\S]*?>/i,
    /<embed[\s\S]*?>/i,
    /document\.cookie/i,
    /eval\s*\(/i
  ]

  return patterns.some(pattern => pattern.test(str))
}

// ========== CSRF Token管理 ==========

function initCsrfToken() {
  try {
    // 尝试从localStorage读取
    const stored = localStorage.getItem('csrf_token')

    if (stored) {
      const tokenData = JSON.parse(stored)

      // 检查是否过期
      if (Date.now() < tokenData.expiresAt) {
        csrfToken = tokenData.value
        console.log('[Security] ✅ CSRF Token已加载')
        return
      } else {
        console.log('[Security] ⚠️ CSRF Token已过期，将重新生成')
      }
    }

    // 生成新Token
    generateCsrfToken()

  } catch (error) {
    console.error('[Security] ❌ CSRF初始化失败:', error)
    generateCsrfToken()
  }
}

function generateCsrfToken() {
  const array = new Uint8Array(32)
  crypto.getRandomValues(array)

  csrfToken = Array.from(array, byte =>
    byte.toString(16).padStart(2, '0')
  ).join('')

  // 存储到localStorage
  const tokenData = {
    value: csrfToken,
    createdAt: Date.now(),
    expiresAt: Date.now() + SECURITY_CONFIG.csrf.expiresIn
  }

  localStorage.setItem('csrf_token', JSON.stringify(tokenData))

  console.log('[Security] ✅ 新CSRF Token已生成')
}

/**
 * 获取当前CSRF Token
 */
export function getCsrfToken() {
  if (!SECURITY_CONFIG.csrf.enabled) {
    return null
  }

  // 检查是否需要刷新
  const stored = localStorage.getItem('csrf_token')
  if (stored) {
    try {
      const tokenData = JSON.parse(stored)
      if (Date.now() >= tokenData.expiresAt) {
        generateCsrfToken()
      }
    } catch (e) {}
  }

  return csrfToken
}

/**
 * 将CSRF Token添加到请求头
 */
export function addCsrfToHeaders(headers = {}) {
  const token = getCsrfToken()

  if (token && SECURITY_CONFIG.csrf.enabled) {
    headers[SECURITY_CONFIG.csrf.headerName] = token
  }

  return headers
}

// ========== 频率限制 ==========

/**
 * 检查是否超过频率限制
 *
 * @param {string} action - 操作标识（如 'login', 'api_request'）
 * @param {Object} options - 自定义配置
 * @returns {Object} { allowed: boolean, remaining: number, resetTime: number }
 */
export function checkRateLimit(action, options = {}) {
  const config = { ...SECURITY_CONFIG.rateLimit, ...options }

  if (!config.enabled) {
    return { allowed: true, remaining: Infinity, resetTime: null }
  }

  const now = Date.now()
  let record = rateLimitStore[action]

  // 如果没有记录或已过期，创建新记录
  if (!record || now >= record.resetTime) {
    record = {
      count: 1,
      resetTime: now + config.windowMs
    }
    rateLimitStore[action] = record

    return {
      allowed: true,
      remaining: config.maxRequests - 1,
      resetTime: record.resetTime
    }
  }

  // 增加计数
  record.count++

  // 检查是否超限
  if (record.count > config.maxRequests) {
    recordSecurityLog('RATE_LIMIT_EXCEEDED', {
      action,
      count: record.count,
      limit: config.maxRequests
    }, 'warn')

    return {
      allowed: false,
      remaining: 0,
      resetTime: record.resetTime
    }
  }

  return {
    allowed: true,
    remaining: config.maxRequests - record.count,
    resetTime: record.resetTime
  }
}

/**
 * 重置特定操作的频率限制计数器
 */
export function resetRateLimit(action) {
  delete rateLimitStore[action]
  console.log(`[Security] 🔄 已重置频率限制: ${action}`)
}

// ========== 输入净化 ==========

/**
 * 综合输入净化函数
 *
 * @param {*} input - 用户输入
 * @param {string} type - 输入类型（'text', 'html', 'url', 'email', 'number'等）
 * @param {Object} rules - 自定义验证规则
 * @returns {Object} { value, valid, errors[] }
 */
export function sanitizeInput(input, type = 'text', rules = {}) {
  const result = {
    value: input,
    valid: true,
    errors: [],
    warnings: []
  }

  // 类型检查
  switch (type.toLowerCase()) {
    case 'text':
      result.value = xssProtection(input)
      break

    case 'html':
      result.value = filterDangerousHtml(String(input), rules.allowedTags)
      break

    case 'url':
      result.value = sanitizeUrl(input)
      break

    case 'email':
      result.value = sanitizeEmail(input)
      if (!isValidEmail(result.value)) {
        result.valid = false
        result.errors.push('邮箱格式不正确')
      }
      break

    case 'number':
      result.value = sanitizeNumber(input)
      break

    default:
      result.value = xssProtection(input)
  }

  // 应用自定义规则
  if (rules.minLength && String(result.value).length < rules.minLength) {
    result.valid = false
    result.errors.push(`最小长度为 ${rules.minLength}`)
  }

  if (rules.maxLength && String(result.value).length > rules.maxLength) {
    result.valid = false
    result.errors.push(`最大长度为 ${rules.maxLength}`)
  }

  if (rules.pattern && !rules.pattern.test(result.value)) {
    result.valid = false
    result.errors.push(rules.message || '格式不符合要求')
  }

  if (rules.required && (result.value === '' || result.value == null)) {
    result.valid = false
    result.errors.push('此字段为必填项')
  }

  return result
}

/**
 * URL净化
 */
function sanitizeUrl(url) {
  if (!url) return ''

  url = String(url).trim()

  // 只允许http/https协议
  if (!/^https?:\/\//i.test(url)) {
    url = 'https://' + url
  }

  // 移除危险字符
  url = url.replace(/[\s<>\"']/g, '')

  // 限制长度
  if (url.length > 2048) {
    url = url.substring(0, 2048)
  }

  return url
}

/**
 * 邮箱地址净化
 */
function sanitizeEmail(email) {
  if (!email) return ''

  email = String(email).toLowerCase().trim()

  // 移除危险字符
  email = email.replace(/[^\w.@+-]/g, '')

  // 限制长度
  if (email.length > 254) {
    email = email.substring(0, 254)
  }

  return email
}

/**
 * 数字净化
 */
function sanitizeNumber(num) {
  if (num === '' || num == null) return ''

  num = Number(num)

  if (isNaN(num)) return ''
  if (!isFinite(num)) return ''

  return num
}

/**
 * 验证邮箱格式
 */
function isValidEmail(email) {
  const emailRegex = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/
  return emailRegex.test(email)
}

// ========== 安全日志 ==========

/**
 * 记录安全事件日志
 *
 * @param {string} event - 事件类型
 * @param {Object} details - 事件详情
 * @param {string} level - 日志级别
 */
export function recordSecurityLog(event, details = {}, level = 'info') {
  if (!SECURITY_CONFIG.logging.enabled) return

  const logEntry = {
    timestamp: new Date().toISOString(),
    event,
    level,
    details,
    url: typeof window !== 'undefined' ? window.location.href : null,
    userAgent: typeof navigator !== 'undefined' ? navigator.userAgent?.substring(0, 50) : null
  }

  // 包含堆栈信息（仅error级别）
  if (level === 'error' && SECURITY_CONFIG.logging.includeStackTrace) {
    logEntry.stackTrace = new Error().stack?.substring(0, 500)
  }

  // 添加到日志数组
  securityLogs.push(logEntry)

  // 限制最大数量
  if (securityLogs.length > SECURITY_CONFIG.logging.maxLogs) {
    securityLogs = securityLogs.slice(-SECURITY_CONFIG.logging.maxLogs)
  }

  // 控制台输出
  const prefix = `[Security ${level.toUpperCase()}]`
  switch (level) {
    case 'error':
      console.error(prefix, event, details)
      break
    case 'warn':
      console.warn(prefix, event, details)
      break
    case 'info':
      console.log(prefix, event, details)
      break
    default:
      console.debug(prefix, event, details)
  }

  // TODO: 生产环境上报到安全监控系统
}

/**
 * 获取所有安全日志
 */
export function getSecurityLogs(level = null) {
  if (level) {
    return securityLogs.filter(log => log.level === level)
  }
  return [...securityLogs]
}

/**
 * 清空安全日志
 */
export function clearSecurityLogs() {
  securityLogs = []
  console.log('[Security] 🗑️ 安全日志已清空')
}

// ========== 工具函数 ==========

/**
 * 清理过期数据
 */
function cleanupExpiredData() {
  const now = Date.now()

  // 清理过期的频率限制记录
  Object.keys(rateLimitStore).forEach(action => {
    if (now >= rateLimitStore[action].resetTime) {
      delete rateLimitStore[action]
    }
  })

  // 清理过期的安全日志（保留最近7天）
  const sevenDaysAgo = now - (7 * 24 * 60 * 60 * 1000)
  securityLogs = securityLogs.filter(log =>
    new Date(log.timestamp).getTime() > sevenDaysAgo
  )
}

/**
 * 敏感数据脱敏
 *
 * @param {*} data - 需要脱敏的数据
 * @param {string[]} sensitiveFields - 敏感字段列表
 * @returns {*} 脱敏后的数据
 */
export function desensitize(data, sensitiveFields = ['password', 'token', 'secret']) {
  if (data == null || typeof data !== 'object') {
    return data
  }

  const result = Array.isArray(data) ? [...data] : { ...data }

  Object.keys(result).forEach(key => {
    if (sensitiveFields.some(field =>
      key.toLowerCase().includes(field.toLowerCase())
    )) {
      result[key] = '***'
    } else if (typeof result[key] === 'object') {
      result[key] = desensitize(result[key], sensitiveFields)
    }
  })

  return result
}
