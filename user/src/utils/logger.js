/**
 * Echo前端日志管理器
 * 
 * 功能：
 * 1. 统一管理所有console输出
 * 2. 区分开发/生产环境日志级别
 * 3. 自动过滤敏感信息
 * 4. 支持结构化日志（JSON格式）
 * 5. 性能监控（记录耗时）
 * 
 * 使用示例：
 * import logger from '@/utils/logger'
 * 
 * logger.debug('[Component] 加载数据', { id: 123 })
 * logger.info('[API] 请求成功', response)
 * logger.warn('[Auth] Token即将过期', { expiresIn: 300 })
 * logger.error('[Order] 创建订单失败', error, { orderId: 456 })
 */

const LOG_LEVELS = {
  DEBUG: 'debug',
  INFO: 'info',
  WARN: 'warn',
  ERROR: 'error'
}

class Logger {
  constructor() {
    this.isDev = import.meta.env?.DEV || import.meta.env?.MODE === 'development'
    this.isEnabled = this.isDev || localStorage.getItem('echo_debug') === 'true'
    this.sensitiveKeys = ['token', 'password', 'secret', 'key', 'authorization']
    this.prefix = '[Echo]'
  }

  /**
   * 过滤敏感信息
   */
  filterSensitiveData(data) {
    if (!data || typeof data !== 'object') return data

    const filtered = {}
    Object.keys(data).forEach(key => {
      if (this.sensitiveKeys.some(sensitiveKey => 
        key.toLowerCase().includes(sensitiveKey.toLowerCase())
      )) {
        filtered[key] = '***FILTERED***'
      } else {
        filtered[key] = data[key]
      }
    })

    return filtered
  }

  /**
   * 格式化日志消息
   */
  formatMessage(level, tag, ...args) {
    const timestamp = new Date().toISOString()
    const message = args[0]
    const data = args.slice(1)

    const logEntry = {
      timestamp,
      level,
      tag,
      message,
      data: data.length > 0 ? (data.length === 1 ? data[0] : data) : undefined
    }

    return {
      formatted: `${this.prefix} [${level.toUpperCase()}] ${timestamp} ${tag} ${message}`,
      entry: logEntry
    }
  }

  /**
   * 输出日志到控制台
   */
  output(level, tag, ...args) {
    if (!this.isEnabled && level !== LOG_LEVELS.ERROR) return

    const { formatted, entry } = this.formatMessage(level, tag, ...args)
    
    // 生产环境只显示ERROR和WARN
    if (!this.isDev && ![LOG_LEVELS.ERROR, LOG_LEVELS.WARN].includes(level)) {
      return
    }

    // 过滤敏感信息
    if (entry.data) {
      entry.data = this.filterSensitiveData(entry.data)
    }

    // 根据级别选择输出方法
    switch (level) {
      case LOG_LEVELS.DEBUG:
        console.debug(formatted, entry.data)
        break
      case LOG_LEVELS.INFO:
        console.info(formatted, entry.data)
        break
      case LOG_LEVELS.WARN:
        console.warn(formatted, entry.data)
        break
      case LOG_LEVELS.ERROR:
        console.error(formatted, entry.data)
        // 生产环境的错误可以上报到监控系统
        if (!this.isDev && window.errorReporter) {
          window.errorReporter.report(entry)
        }
        break
      default:
        console.log(formatted, entry.data)
    }
  }

  /**
   * 调试信息（开发环境详细，生产环境不显示）
   */
  debug(tag, message, ...data) {
    this.output(LOG_LEVELS.DEBUG, tag, message, ...data)
  }

  /**
   * 一般信息（开发环境显示，生产环境可选）
   */
  info(tag, message, ...data) {
    this.output(LOG_LEVELS.INFO, tag, message, ...data)
  }

  /**
   * 警告信息（所有环境都显示）
   */
  warn(tag, message, ...data) {
    this.output(LOG_LEVELS.WARN, tag, message, ...data)
  }

  /**
   * 错误信息（所有环境都显示，生产环境可上报）
   */
  error(tag, message, ...data) {
    this.output(LOG_LEVELS.ERROR, tag, message, ...data)
  }

  /**
   * 性能计时开始
   */
  timeStart(tag, label = 'default') {
    if (!this.isEnabled) return
    
    const timeLabel = `${tag}:${label}`
    console.time(timeLabel)
    
    return {
      label: timeLabel,
      end: (message) => this.timeEnd(tag, label, message)
    }
  }

  /**
   * 性能计时结束
   */
  timeEnd(tag, label = 'default', message = '') {
    if (!this.isEnabled) return

    const timeLabel = `${tag}:${label}`
    console.timeEnd(timeLabel)
    
    if (message) {
      this.info(tag, `⏱️ ${message}`, { label })
    }
  }

  /**
   * 分组日志（用于复杂场景）
   */
  group(tag, label, callback) {
    if (!this.isEnabled) return

    const groupLabel = `${this.prefix} ${tag} ${label}`
    console.group(groupLabel)
    
    try {
      callback(this)
    } finally {
      console.groupEnd()
    }
  }

  /**
   * 表格化输出（适合数组数据）
   */
  table(tag, data, columns) {
    if (!this.isEnabled) return

    this.info(tag, `📊 数据表格 (${Array.isArray(data) ? data.length : 'N/A'} 条)`)
    console.table(data, columns)
  }

  /**
   * 启用调试模式（生产环境手动开启）
   */
  enableDebug() {
    this.isEnabled = true
    localStorage.setItem('echo_debug', 'true')
    this.info('Logger', '✅ 调试模式已启用')
  }

  /**
   * 禁用调试模式
   */
  disableDebug() {
    this.isEnabled = this.isDev
    localStorage.removeItem('echo_debug')
    this.info('Logger', '❌ 调试模式已禁用')
  }
}

const logger = new Logger()

export default logger

// 全局挂载（方便使用）
if (typeof window !== 'undefined') {
  window.echoLogger = logger
  
  // 快捷键：Ctrl+Shift+D 切换调试模式
  document.addEventListener('keydown', (e) => {
    if (e.ctrlKey && e.shiftKey && e.key === 'D') {
      e.preventDefault()
      if (logger.isEnabled) {
        logger.disableDebug()
      } else {
        logger.enableDebug()
      }
    }
  })
}
