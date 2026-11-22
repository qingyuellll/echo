/**
 * 📝 表单草稿自动保存工具
 *
 * 功能：
 * - 自动定时保存表单数据到localStorage
 * - 页面刷新/关闭时恢复草稿
 * - 支持多表单独立存储
 * - 草稿过期机制（默认7天）
 * - 提供恢复/清除/导出功能
 */

// ========== 配置 ==========
const DRAFT_CONFIG = {
  saveInterval: 30000,        // 自动保存间隔：30秒
  maxAge: 7 * 24 * 60 * 60 * 1000, // 草稿有效期：7天
  maxDrafts: 20,              // 每个表单最多保留的草稿版本数
  storagePrefix: 'form_draft_', // localStorage前缀
  autoSaveEnabled: true,      // 是否启用自动保存
}

// ========== 状态管理 ==========
let saveTimers = {} // 定时器ID映射 { formId: timerId }
let draftVersions = {} // 版本计数器 { formId: count }

/**
 * 🔄 初始化表单草稿功能
 *
 * @param {string} formId - 表单唯一标识（如 'show_create', 'ticket_edit'）
 * @param {Object} options - 配置选项
 * @returns {Object} 草稿管理对象
 */
export function initFormDraft(formId, options = {}) {
  const config = {
    ...DRAFT_CONFIG,
    ...options,
    formId,
    storageKey: `${DRAFT_CONFIG.storagePrefix}${formId}`
  }

  console.log(`[FormDraft] 🚀 初始化表单草稿: ${formId}`)

  return {
    /**
     * 💾 手动保存草稿
     */
    async save(formData) {
      return saveDraft(config, formData)
    },

    /**
     * 📥 加载最新草稿
     */
    load() {
      return loadLatestDraft(config)
    },

    /**
     * 🗑️ 清除所有草稿
     */
    clearAll() {
      clearAllDrafts(config)
    },

    /**
     * 📋 获取草稿列表（所有版本）
     */
    list() {
      listDrafts(config)
    },

    /**
     * ⏸️ 开始自动保存
     */
    startAutoSave(getFormDataCallback) {
      startAutoSaveTimer(config, getFormDataCallback)
    },

    /**
     * ⏹️ 停止自动保存
     */
    stopAutoSave() {
      stopAutoSaveTimer(config)
    },

    /**
     * ✅ 检查是否有可恢复的草稿
     */
    hasDraft() {
      return hasValidDraft(config)
    },

    /**
     * 🕐 获取最后保存时间
     */
    getLastSaveTime() {
      getLastSaveTime(config)
    }
  }
}

/**
 * 💾 保存草稿到localStorage
 */
async function saveDraft(config, formData) {
  try {
    const timestamp = Date.now()
    const version = (draftVersions[config.formId] || 0) + 1

    const draftData = {
      id: generateDraftId(),
      version,
      formData,
      timestamp,
      createdAt: new Date(timestamp).toISOString(),
      url: window.location.href,
      userAgent: navigator.userAgent?.substring(0, 50),
      size: JSON.stringify(formData).length
    }

    // 读取现有草稿列表
    let drafts = []
    try {
      drafts = JSON.parse(localStorage.getItem(config.storageKey) || '[]')
    } catch (e) {
      console.warn('[FormDraft] 解析现有草稿失败:', e)
      drafts = []
    }

    // 添加新草稿
    drafts.push(draftData)

    // 限制最大数量（只保留最新的N个版本）
    if (drafts.length > config.maxDrafts) {
      drafts = drafts.slice(-config.maxDrafts)
    }

    // 过滤过期草稿
    drafts = filterExpiredDrafts(drafts, config.maxAge)

    // 保存到localStorage
    localStorage.setItem(config.storageKey, JSON.stringify(drafts))

    // 更新版本计数
    draftVersions[config.formId] = version

    console.log(`[FormDraft] ✅ 草稿已保存`, {
      formId: config.formId,
      version,
      size: draftData.size,
      totalDrafts: drafts.length
    })

    return {
      success: true,
      version,
      timestamp
    }

  } catch (error) {
    console.error('[FormDraft] ❌ 保存失败:', error)

    // 如果是配额超限错误，尝试清理旧草稿后重试
    if (isQuotaExceeded(error)) {
      console.warn('[FormDraft] ⚠️ 存储空间不足，尝试清理旧草稿...')
      cleanupOldDrafts()

      // 重试一次
      return saveDraft(config, formData)
    }

    throw error
  }
}

/**
 * 📥 加载最新草稿
 */
function loadLatestDraft(config) {
  try {
    const drafts = JSON.parse(localStorage.getItem(config.storageKey) || '[]')

    if (drafts.length === 0) {
      return null
    }

    // 过滤过期草稿
    const validDrafts = filterExpiredDrafts(drafts, config.maxAge)

    if (validDrafts.length === 0) {
      return null
    }

    // 返回最新的草稿
    const latestDraft = validDrafts[validDrafts.length - 1]

    console.log(`[FormDraft] 📥 加载最新草稿`, {
      formId: config.formId,
      version: latestDraft.version,
      savedAt: latestDraft.createdAt
    })

    return latestDraft.formData

  } catch (error) {
    console.error('[FormDraft] ❌ 加载失败:', error)
    return null
  }
}

/**
 * 🗑️ 清除所有草稿
 */
function clearAllDrafts(config) {
  try {
    localStorage.removeItem(config.storageKey)
    delete draftVersions[config.formId]

    console.log(`[FormDraft] 🗑️ 已清除所有草稿: ${config.formId}`)

    return true

  } catch (error) {
    console.error('[FormDraft] ❌ 清除失败:', error)
    return false
  }
}

/**
 * 📋 列出所有草稿
 */
function listDrafts(config) {
  try {
    const drafts = JSON.parse(localStorage.getItem(config.storageKey) || '[]')
    const validDrafts = filterExpiredDrafts(drafts, config.maxAge)

    return validDrafts.map(draft => ({
      version: draft.version,
      savedAt: draft.createdAt,
      timeAgo: formatTimeAgo(draft.timestamp),
      size: formatSize(draft.size),
      url: draft.url
    }))

  } catch (error) {
    console.error('[FormDraft] ❌ 列出失败:', error)
    return []
  }
}

/**
 * ⏸️ 启动自动保存定时器
 */
function startAutoSaveTimer(config, getFormDataCallback) {
  // 先停止已有的定时器
  stopAutoSaveTimer(config)

  if (!config.autoSaveEnabled || typeof getFormDataCallback !== 'function') {
    console.warn('[FormDraft] ⚠️ 自动保存未启用或回调无效')
    return
  }

  console.log(`[FormDraft] ⏸️ 启动自动保存 (${config.saveInterval / 1000}s间隔)`)

  saveTimers[config.formId] = setInterval(async () => {
    try {
      const formData = await getFormDataCallback()

      if (formData && Object.keys(formData).length > 0) {
        await saveDraft(config, formData)
      }

    } catch (error) {
      console.error('[FormDraft] ❌ 自动保存失败:', error)
    }
  }, config.saveInterval)
}

/**
 * ⏹️ 停止自动保存定时器
 */
function stopAutoSaveTimer(config) {
  if (saveTimers[config.formId]) {
    clearInterval(saveTimers[config.formId])
    delete saveTimers[config.formId]

    console.log(`[FormDraft] ⏹️ 已停止自动保存: ${config.formId}`)
  }
}

/**
 * ✅ 检查是否有有效草稿
 */
function hasValidDraft(config) {
  try {
    const drafts = JSON.parse(localStorage.getItem(config.storageKey) || '[]')
    const validDrafts = filterExpiredDrafts(drafts, config.maxAge)

    return validDrafts.length > 0

  } catch (error) {
    return false
  }
}

/**
 * 🕐 获取最后保存时间
 */
function getLastSaveTime(config) {
  try {
    const drafts = JSON.parse(localStorage.getItem(config.storageKey) || '[]')

    if (drafts.length > 0) {
      return drafts[drafts.length - 1].timestamp
    }

    return null

  } catch (error) {
    return null
  }
}

// ========== 工具函数 ==========

/**
 * 生成唯一草稿ID
 */
function generateDraftId() {
  return `draft_${Date.now().toString(36)}_${Math.random().toString(36).substr(2, 9)}`
}

/**
 * 过滤过期草稿
 */
function filterExpiredDrafts(drafts, maxAge) {
  const now = Date.now()

  return drafts.filter(draft => {
    const age = now - draft.timestamp
    return age <= maxAge && age >= 0 // 排除未来时间戳
  })
}

/**
 * 检查是否超出存储配额
 */
function isQuotaExceeded(error) {
  return (
    error.name === 'QuotaExceededError' ||
    error.message?.includes('quota') ||
    error.message?.includes('storage')
  )
}

/**
 * 清理所有旧草稿（全局）
 */
function cleanupOldDrafts() {
  try {
    const keysToRemove = []

    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)

      if (key?.startsWith(DRAFT_CONFIG.storagePrefix)) {
        keysToRemove.push(key)
      }
    }

    // 只删除超过14天的草稿
    keysToRemove.forEach(key => {
      try {
        const drafts = JSON.parse(localStorage.getItem(key) || '[]')
        const validDrafts = filterExpiredDrafts(drafts, 14 * 24 * 60 * 60 * 1000)

        if (validDrafts.length === 0) {
          localStorage.removeItem(key)
          console.log(`[FormDraft] 🧹 已清理过期草稿: ${key}`)
        } else {
          localStorage.setItem(key, JSON.stringify(validDrafts))
        }
      } catch (e) {
        // 无法解析则直接删除
        localStorage.removeItem(key)
      }
    })

    console.log(`[FormDraft] 🧹 清理完成，共检查 ${keysToRemove.length} 个草稿`)

  } catch (error) {
    console.error('[FormDraft] 🧹 清理失败:', error)
  }
}

/**
 * 格式化时间差（如 "5分钟前"）
 */
function formatTimeAgo(timestamp) {
  const now = Date.now()
  const diff = now - timestamp

  const seconds = Math.floor(diff / 1000)
  const minutes = Math.floor(seconds / 60)
  const hours = Math.floor(minutes / 60)
  const days = Math.floor(hours / 24)

  if (seconds < 60) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  if (hours < 24) return `${hours}小时前`
  if (days < 30) return `${days}天前`

  return new Date(timestamp).toLocaleDateString('zh-CN')
}

/**
 * 格式化文件大小
 */
function formatSize(bytes) {
  if (bytes < 1024) return `${bytes}B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`
}

// ========== 全局事件监听 ==========

// 页面关闭前提示未保存的草稿（可选）
if (typeof window !== 'undefined') {
  window.addEventListener('beforeunload', (event) => {
    // 可以在这里添加提示逻辑
    // 注意：现代浏览器会忽略自定义消息，但可以触发提示
  })

  // 定期清理过期草稿（每天一次）
  setInterval(() => {
    cleanupOldDrafts()
  }, 24 * 60 * 60 * 1000)
}
