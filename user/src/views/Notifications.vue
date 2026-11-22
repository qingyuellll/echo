<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../utils/api.js'
import {
  Bell,
  BellOff,
  Check,
  CheckCheck,
  Clock,
  ShoppingCart,
  CreditCard,
  RotateCcw,
  UserCheck,
  Star,
  AlertCircle,
  ChevronLeft,
  RefreshCw,
  Trash2
} from 'lucide-vue-next'

const router = useRouter()

// 状态管理
const notifications = ref([])
const loading = ref(true)
const refreshing = ref(false)
const currentPage = ref(1)
const pageSize = 20
const total = ref(0)
const unreadCount = ref(0)

// 计算属性
const hasNotifications = computed(() => notifications.value.length > 0)
const hasUnread = computed(() => unreadCount.value > 0)

// 图标映射
const iconMap = {
  1: CreditCard,      // 支付成功
  2: Star,           // 关注动态
  3: RotateCcw,      // 退款相关
  4: UserCheck,       // 核销成功
  5: Star,            // 关注评测
  6: Bell             // 开售提醒
}

// 类型名称映射
const typeNames = {
  1: '支付成功',
  2: '关注动态',
  3: '退款通知',
  4: '核销成功',
  5: '新评测',
  6: '开售提醒'
}

/**
 * 加载通知列表
 */
async function loadNotifications(reset = true) {
  if (refreshing.value) return

  if (reset) {
    loading.value = true
    currentPage.value = 1
  } else {
    refreshing.value = true
  }

  try {
    const res = await api.getNotifications(currentPage.value, pageSize)
    const data = res.data || res

    if (Array.isArray(data)) {
      if (reset) {
        notifications.value = data
        total.value = data.length
      } else {
        notifications.value.push(...data)
        total.value = notifications.value.length
      }
    } else if (data.records) {
      // MyBatis-Plus 分页格式
      if (reset) {
        notifications.value = data.records
        total.value = data.total || 0
      } else {
        notifications.value.push(...data.records)
        total.value = data.total || 0
      }
    }

    console.log(`[Notifications] 加载成功 - 获取${Array.isArray(data) ? data.length : (data.records?.length || 0)}条, 总计${total.value}条`)
  } catch (error) {
    console.error('[Notifications] 加载失败:', error)
    if (reset) {
      notifications.value = []
    }
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

/**
 * 标记单条为已读
 */
async function markAsRead(notificationId) {
  try {
    await api.markNotificationRead(notificationId)

    const notification = notifications.value.find(n => n.id === notificationId)
    if (notification && !notification.isRead) {
      notification.isRead = true
      unreadCount.value = Math.max(0, unreadCount.value - 1)
    }

    console.log('[Notifications] 已标记已读', notificationId)
  } catch (error) {
    console.error('[Notifications] 标记已读失败:', error)
  }
}

/**
 * 全部标记已读
 */
async function markAllAsRead() {
  if (!hasUnread.value) return

  try {
    await api.markAllNotificationsRead()

    // 更新本地状态
    notifications.value.forEach(n => {
      n.isRead = true
    })
    unreadCount.value = 0

    console.log('[Notifications] 已全部标记已读')
  } catch (error) {
    console.error('[Notifications] 全部标记已读失败:', error)
  }
}

/**
 * 刷新列表
 */
async function handleRefresh() {
  await loadNotifications(true)
}

/**
 * 格式化时间
 */
function formatTime(timeStr) {
  if (!timeStr) return ''

  const date = new Date(timeStr)
  const now = new Date()
  const diff = now - date

  // 小于1分钟
  if (diff < 60 * 1000) return '刚刚'
  // 小于1小时
  if (diff < 60 * 60 * 1000) return `${Math.floor(diff / (60 * 1000))}分钟前`
  // 小于24小时
  if (diff < 24 * 60 * 60 * 1000) return `${Math.floor(diff / (60 * 60 * 1000))}小时前`
  // 小于7天
  if (diff < 7 * 24 * 60 * 60 * 1000) return `${Math.floor(diff / (24 * 60 * 60 * 1000))}天前`
  // 其他显示完整日期
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/**
 * 点击通知
 */
function handleClick(notification) {
  // 标记已读
  if (!notification.isRead) {
    markAsRead(notification.id)
  }

  // 根据类型跳转
  if (notification.relatedId) {
    switch (notification.type) {
      case 1: // 支付成功 → 订单详情
        router.push(`/order/${notification.relatedId}`)
        break
      case 4: // 核销成功 → 订单详情
        router.push(`/order/${notification.relatedId}`)
        break
      default:
        // 其他类型暂不跳转，可扩展
        break
    }
  }
}

/**
 * 返回上一页
 */
function goBack() {
  router.back()
}

// 生命周期
onMounted(async () => {
  await Promise.all([
    loadNotifications(true),
    // 可选：获取未读计数（如果后端有单独接口）
  ])
})
</script>

<template>
  <div class="notifications-page">
    <!-- 头部 -->
    <header class="header">
      <button class="back-btn" @click="goBack">
        <ChevronLeft :size="24" />
      </button>
      <h1 class="title">消息通知</h1>
      <div class="header-actions">
        <button
          v-if="hasUnread"
          class="action-btn mark-all-btn"
          @click="markAllAsRead"
          title="全部标为已读"
        >
          <CheckCheck :size="20" />
          <span>全部已读</span>
        </button>
        <button
          class="action-btn refresh-btn"
          @click="handleRefresh"
          :disabled="refreshing"
          title="刷新"
        >
          <RefreshCw :size="20" :class="{ spinning: refreshing }" />
        </button>
      </div>
    </header>

    <!-- 未读提示 -->
    <div v-if="hasUnread" class="unread-banner">
      <Bell :size="18" />
      <span>您有 {{ unreadCount }} 条未读消息</span>
      <button class="mark-read-btn" @click="markAllAsRead">全部已读</button>
    </div>

    <!-- 内容区域 -->
    <main class="content">
      <!-- 加载中 -->
      <div v-if="loading" class="loading-state">
        <RefreshCw :size="32" class="spinning" />
        <p>加载中...</p>
      </div>

      <!-- 空状态 -->
      <div v-else-if="!hasNotifications" class="empty-state">
        <BellOff :size="64" />
        <h3>暂无通知</h3>
        <p>您的所有消息都会在这里显示</p>
        <button class="primary-btn" @click="handleRefresh">刷新</button>
      </div>

      <!-- 通知列表 -->
      <div v-else class="notification-list">
        <article
          v-for="notification in notifications"
          :key="notification.id"
          class="notification-item"
          :class="{ unread: !notification.isRead }"
          @click="handleClick(notification)"
        >
          <!-- 图标 -->
          <div class="icon-wrapper" :type="notification.type">
            <component :is="iconMap[notification.type] || Bell" :size="22" />
          </div>

          <!-- 内容 -->
          <div class="content-wrapper">
            <h3 class="notification-title">{{ notification.title }}</h3>
            <p class="notification-content">{{ notification.content }}</p>
            <div class="meta">
              <span class="type-tag">{{ typeNames[notification.type] || '通知' }}</span>
              <time class="time">{{ formatTime(notification.createTime) }}</time>
            </div>
          </div>

          <!-- 未读标识 + 操作 -->
          <div class="actions">
            <span v-if="!notification.isRead" class="unread-dot"></span>
            <button
              v-if="!notification.isRead"
              class="mark-read-btn-small"
              @click.stop="markAsRead(notification.id)"
              title="标为已读"
            >
              <Check :size="16" />
            </button>
          </div>
        </article>

        <!-- 加载更多 -->
        <div v-if="notifications.length < total" class="load-more">
          <button
            class="load-more-btn"
            @click="loadNotifications(false)"
            :disabled="refreshing"
          >
            {{ refreshing ? '加载中...' : '加载更多' }}
          </button>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.notifications-page {
  min-height: 100vh;
  background-color: var(--color-bg);
  display: flex;
  flex-direction: column;
}

/* 头部 */
.header {
  position: sticky;
  top: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background-color: var(--color-white);
  box-shadow: var(--shadow-sm);
}

.back-btn {
  width: 40px;
  height: 40px;
  border: none;
  background: transparent;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background-color 0.2s;
}

.back-btn:hover {
  background-color: var(--color-bg-alt);
}

.title {
  font-size: 18px;
  font-weight: 600;
  color: var(--color-text);
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.action-btn {
  padding: 6px 12px;
  border: none;
  background: transparent;
  border-radius: 6px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  color: var(--color-text-light);
  transition: all 0.2s;
}

.action-btn:hover {
  background-color: var(--color-bg-alt);
  color: var(--color-primary);
}

.mark-all-btn span {
  font-size: 13px;
}

.refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 未读提示横幅 */
.unread-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: linear-gradient(135deg, var(--color-primary) 0%, var(--color-primary-dark) 100%);
  color: var(--color-white);
  font-size: 14px;
  gap: 12px;
}

.unread-banner .mark-read-btn {
  padding: 4px 12px;
  border: 1px solid rgba(255, 255, 255, 0.6);
  background: rgba(255, 255, 255, 0.2);
  color: #fff;
  border-radius: 12px;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s;
}

.unread-banner .mark-read-btn:hover {
  background: rgba(255, 255, 255, 0.3);
  border-color: #fff;
}

/* 内容区域 */
.content {
  flex: 1;
  overflow-y: auto;
}

/* 加载状态 */
.loading-state,
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 20px;
  color: var(--color-text-light);
  gap: 16px;
}

.empty-state h3 {
  font-size: 18px;
  color: var(--color-text-light);
  margin: 0;
}

.empty-state p {
  font-size: 14px;
  margin: 0;
}

.primary-btn {
  padding: 10px 24px;
  background: linear-gradient(135deg, var(--color-primary) 0%, var(--color-primary-dark) 100%);
  color: var(--color-white);
  border: none;
  border-radius: 20px;
  cursor: pointer;
  font-size: 15px;
  transition: transform 0.2s, box-shadow 0.2s;
}

.primary-btn:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}

/* 通知列表 */
.notification-list {
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.notification-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px;
  background: var(--color-white);
  border-radius: 12px;
  box-shadow: var(--shadow-sm);
  cursor: pointer;
  transition: all 0.2s;
  position: relative;
}

.notification-item:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.notification-item.unread {
  background: linear-gradient(to right, var(--color-primary-alpha), var(--color-white));
  border-left: 3px solid var(--color-primary);
}

/* 图标容器 */
.icon-wrapper {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: var(--color-bg-alt);
  color: var(--color-text-light);
}

.icon-wrapper[type="1"] {
  background: var(--color-primary-alpha);
  color: var(--color-primary);
}

.icon-wrapper[type="2"] {
  background: var(--color-bg-warning);
  color: var(--color-warning-dark);
}

.icon-wrapper[type="3"] {
  background: var(--color-bg-danger);
  color: var(--color-danger);
}

.icon-wrapper[type="4"] {
  background: var(--color-bg-success);
  color: var(--color-success);
}

.icon-wrapper[type="5"],
.icon-wrapper[type="6"] {
  background: var(--color-primary-alpha);
  color: var(--color-primary);
}

/* 内容区域 */
.content-wrapper {
  flex: 1;
  min-width: 0;
}

.notification-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text);
  margin: 0 0 6px 0;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.notification-content {
  font-size: 13px;
  color: var(--color-text-light);
  margin: 0 0 8px 0;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.type-tag {
  display: inline-block;
  padding: 2px 8px;
  background: var(--color-bg-alt);
  color: var(--color-text-light);
  font-size: 12px;
  border-radius: 10px;
}

.time {
  font-size: 12px;
  color: var(--color-text-light);
  white-space: nowrap;
}

/* 操作按钮 */
.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.unread-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-danger);
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.mark-read-btn-small {
  width: 28px;
  height: 28px;
  border: none;
  background: var(--color-bg-alt);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--color-text-light);
  transition: all 0.2s;
}

.mark-read-btn-small:hover {
  background: var(--color-primary);
  color: var(--color-white);
}

/* 加载更多 */
.load-more {
  display: flex;
  justify-content: center;
  padding: 20px 0;
}

.load-more-btn {
  padding: 10px 32px;
  border: 1px solid var(--color-border);
  background: var(--color-white);
  border-radius: 20px;
  cursor: pointer;
  font-size: 14px;
  color: var(--color-text-light);
  transition: all 0.2s;
}

.load-more-btn:hover:not(:disabled) {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.load-more-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 动画 */
.spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
