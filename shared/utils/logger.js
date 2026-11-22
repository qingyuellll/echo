/**
 * Unified Logger Utility
 * Provides environment-aware logging with levels control
 */

const LOG_LEVELS = {
  ERROR: 0,
  WARN: 1,
  INFO: 2,
  DEBUG: 3
}

const isDevelopment = import.meta.env?.MODE === 'development' || window.location.hostname === 'localhost'
const currentLevel = isDevelopment ? LOG_LEVELS.DEBUG : LOG_LEVELS.WARN

function log(level, tag, ...args) {
  if (level > currentLevel) return

  const timestamp = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  const prefix = `[${timestamp}] [${tag}]`

  switch (level) {
    case LOG_LEVELS.ERROR:
      console.error(prefix, ...args)
      break
    case LOG_LEVELS.WARN:
      console.warn(prefix, ...args)
      break
    case LOG_LEVELS.INFO:
      console.log(prefix, ...args)
      break
    case LOG_LEVELS.DEBUG:
      console.debug(prefix, ...args)
      break
  }
}

export const logger = {
  error(tag, ...args) { log(LOG_LEVELS.ERROR, tag, ...args) },
  warn(tag, ...args) { log(LOG_LEVELS.WARN, tag, ...args) },
  info(tag, ...args) { log(LOG_LEVELS.INFO, tag, ...args) },
  debug(tag, ...args) { log(LOG_LEVELS.DEBUG, tag, ...args) }
}

export default logger
