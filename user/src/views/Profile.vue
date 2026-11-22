<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { api, getUser, getUserId, isLoggedIn, formatPrice, getImageUrl } from '../utils/api.js'
import { Star, Gift, Camera, AlertCircle, Loader2, CheckCircle } from 'lucide-vue-next'

const router = useRouter()
const user = ref(getUser())

function goToLottery() {
  router.push('/lottery')
}

// ===== 账号安全：设置/修改密码 =====
// 注册采用"手机号+验证码免注册登录"，首次登录的账号没有密码；
// 此处提供设置密码入口（后端规则：未设过密码只需新密码+验证码，已设过需旧密码）
const showPasswordForm = ref(false)
const pwForm = reactive({ oldPassword: '', newPassword: '', code: '' })
const pwCodeCountdown = ref(0)
const pwSubmitting = ref(false)
const pwMessage = ref('')
const pwIsError = ref(false)
let pwTimer = null

function setPwMessage(msg, isError = false) {
  pwMessage.value = msg
  pwIsError.value = isError
}

async function handleSendPwCode() {
  try {
    await api.ensureCsrfToken()
    await api.sendPasswordCode()
    pwCodeCountdown.value = 60
    pwTimer = setInterval(() => {
      pwCodeCountdown.value--
      if (pwCodeCountdown.value <= 0 && pwTimer) clearInterval(pwTimer)
    }, 1000)
    setPwMessage('验证码已发送，请查收短信')
  } catch (e) {
    setPwMessage(e.message || '验证码发送失败，请稍后重试', true)
  }
}

async function handleChangePassword() {
  if (!pwForm.newPassword || pwForm.newPassword.length < 6 || pwForm.newPassword.length > 20) {
    setPwMessage('新密码长度需为 6-20 位', true)
    return
  }
  if (!pwForm.code) {
    setPwMessage('请输入短信验证码', true)
    return
  }
  pwSubmitting.value = true
  try {
    await api.changePassword({
      oldPassword: pwForm.oldPassword || undefined,
      newPassword: pwForm.newPassword,
      code: pwForm.code
    })
    setPwMessage('密码设置成功！之后可在登录页用手机号+密码登录')
    pwForm.oldPassword = ''
    pwForm.newPassword = ''
    pwForm.code = ''
  } catch (e) {
    setPwMessage(e.message || '设置失败，请稍后重试', true)
  } finally {
    pwSubmitting.value = false
  }
}
const orders = ref([])
const reviews = ref([])
const pointsInfo = ref(null)
const pointsRecords = ref([])
const tab = ref('orders')
const loading = ref(false)
const avatarInput = ref(null)
const avatarUploading = ref(false)
const avatarError = ref('')
const avatarSuccess = ref(false)

const pointsBalance = computed(() => {
  return pointsInfo.value?.points || pointsInfo.value?.balance || 0
})

function triggerAvatarUpload() {
  if (avatarUploading.value) return
  avatarInput.value?.click()
}

function handleAvatarError(event) {
  console.warn('[Profile] 头像加载失败:', event.target.src)
  if (user.value) {
    user.value._avatarLoadFailed = true
  }
}

async function handleAvatarChange(event) {
  const file = event.target.files?.[0]
  if (!file) return

  avatarError.value = ''
  avatarSuccess.value = false

  const allowedTypes = ['image/jpeg', 'image/jpg', 'image/png', 'image/gif', 'image/webp']
  if (!allowedTypes.includes(file.type)) {
    avatarError.value = `不支持的格式：${file.type}，请使用JPG/PNG/GIF/WebP`
    return
  }

  const maxSize = 5 * 1024 * 1024
  if (file.size > maxSize) {
    const sizeMB = (file.size / (1024 * 1024)).toFixed(2)
    avatarError.value = `文件过大：${sizeMB}MB，最大允许5MB`
    return
  }

  avatarUploading.value = true

  try {
    const res = await api.uploadAvatar(file)
    if (res && res.data) {
      const avatarUrlNew = res.data
      const updatedUser = await api.updateUserInfo({ icon: avatarUrlNew })

      if (updatedUser && updatedUser.success !== false) {
        user.value.icon = avatarUrlNew
        user.value._avatarLoadFailed = false

        // ✅ 修复：统一使用 sessionStorage（与 getUser() 保持一致）
        const userData = JSON.parse(sessionStorage.getItem('user') || '{}')
        userData.icon = avatarUrlNew
        sessionStorage.setItem('user', JSON.stringify(userData))

        avatarSuccess.value = true
        setTimeout(() => { avatarSuccess.value = false }, 3000)

        try {
          const freshUser = await api.getMe()
          if (freshUser && freshUser.data) {
            user.value = freshUser.data
            // ✅ 修复：统一使用 sessionStorage
            sessionStorage.setItem('user', JSON.stringify(freshUser.data))
          }
        } catch (refreshError) {
          console.warn('[Profile] 刷新用户信息失败:', refreshError)
        }
      } else {
        throw new Error(updatedUser?.errorMsg || '更新用户信息失败')
      }
    } else {
      throw new Error(res?.errorMsg || '头像上传失败：未返回文件路径')
    }
  } catch (e) {
    console.error('[Profile] 头像上传失败:', e)
    let errorMsg = '上传失败，请重试'
    if (e.message.includes('网络')) errorMsg = '网络连接失败，请检查网络后重试'
    else if (e.message.includes('413')) errorMsg = '图片过大，请压缩后重试'
    else if (e.message) errorMsg = e.message

    avatarError.value = errorMsg
    setTimeout(() => { if (avatarError.value === errorMsg) avatarError.value = '' }, 5000)
  } finally {
    avatarUploading.value = false
    event.target.value = ''
  }
}

const orderPage = ref(1)
const orderPageSize = ref(10)
const orderTotal = ref(0)
const hasMoreOrders = ref(true)

async function loadOrders(resetPage = false) {
  if (loading.value) return

  if (resetPage) {
    orderPage.value = 1
    orders.value = []
    hasMoreOrders.value = true
  }

  loading.value = true
  try {
    // /ticket-order/my 由后端从 Token 识别当前用户，无需本地 user.id 前置校验
    // （旧代码在此检查 sessionStorage user.id，字段缺失时会静默不发请求导致"订单列表空"）
    const res = await api.getMyOrders({
      current: orderPage.value,
      size: orderPageSize.value
    })

    if (res && res.data) {
      let rawOrders = Array.isArray(res.data) ? res.data : (res.data.records || [])
      const paginationData = res.data

      if (resetPage) {
        orders.value = sortOrdersWithSubIndex(rawOrders)
      } else {
        orders.value = [...orders.value, ...sortOrdersWithSubIndex(rawOrders)]
      }

      orderTotal.value = paginationData.total || rawOrders.length
      hasMoreOrders.value = orders.value.length < orderTotal.value

      console.log(`[Profile] 订单加载成功 - 页码:${orderPage.value}, 本页:${rawOrders.length}, 总计:${orderTotal.value}, 是否有更多:${hasMoreOrders.value}`)
    } else {
      hasMoreOrders.value = false
    }
  } catch (e) {
    console.error('[Profile] 加载订单失败:', e)
    hasMoreOrders.value = false
  } finally {
    loading.value = false
  }
}

async function loadMoreOrders() {
  if (!hasMoreOrders.value || loading.value) return
  orderPage.value++
  await loadOrders(false)
}

function sortOrdersWithSubIndex(orderList) {
  if (!Array.isArray(orderList) || orderList.length === 0) return []

  console.log('[订单排序] 开始处理订单列表，总数:', orderList.length)

  const batchGroups = new Map()

  orderList.forEach((order, index) => {
    if (order.subOrderIndex && order.subOrderIndex.includes('/')) {
      const batchKey = `${order.ticketId}_${order.showId}_${order.createTime ? new Date(order.createTime).toISOString().slice(0, 16) : 'unknown'}`

      if (!batchGroups.has(batchKey)) {
        batchGroups.set(batchKey, [])
      }
      batchGroups.get(batchKey).push({ ...order, _originalIndex: index })
    }
  })

  console.log('[订单排序] 识别到批次组数量:', batchGroups.size)
  batchGroups.forEach((orders, key) => {
    console.log(`[订单排序] 批次 ${key}: ${orders.length} 个订单`, orders.map(o => o.subOrderIndex))
  })

  const sortedOrders = [...orderList]

  batchGroups.forEach((batchOrders, batchKey) => {
    if (batchOrders.length > 1) {
      const originalOrder = batchOrders.map(o => o.subOrderIndex)

      batchOrders.sort((a, b) => {
        const indexA = parseInt(a.subOrderIndex.split('/')[0]) || 0
        const indexB = parseInt(b.subOrderIndex.split('/')[0]) || 0
        return indexA - indexB
      })

      let minOriginalIndex = Math.min(...batchOrders.map(o => o._originalIndex))

      batchOrders.forEach((sortedOrder, i) => {
        sortedOrders[minOriginalIndex + i] = { ...sortedOrder }
        delete sortedOrders[minOriginalIndex + i]._originalIndex
      })

      const sortedOrder = batchOrders.map(o => o.subOrderIndex)
      console.log(`[订单排序] ✅ 批次已重新排序: ${originalOrder.join(',')} → ${sortedOrder.join(',')}`)
    }
  })

  return sortedOrders
}

const reviewPage = ref(1)
const reviewPageSize = ref(10)
const reviewTotal = ref(0)
const hasMoreReviews = ref(true)

async function loadMyReviews(resetPage = false) {
  if (loading.value) return

  if (resetPage) {
    reviewPage.value = 1
    reviews.value = []
    hasMoreReviews.value = true
  }

  loading.value = true
  try {
    // /show-review/of/user 需要 userId；sessionStorage 缺 id 时自动回源 /user/me 补全
    const currentUserId = await getUserId()
    if (!currentUserId) {
      reviews.value = []
      hasMoreReviews.value = false
      return
    }

    const res = await api.getUserReviews(currentUserId, reviewPage.value)

    if (res && res.data) {
      let rawReviews = Array.isArray(res.data) ? res.data : (res.data.records || [])
      const paginationData = res.data

      if (resetPage) {
        reviews.value = rawReviews
      } else {
        reviews.value = [...reviews.value, ...rawReviews]
      }

      reviewTotal.value = paginationData.total || rawReviews.length
      hasMoreReviews.value = reviews.value.length < reviewTotal.value

      console.log(`[Profile] 评测加载成功 - 页码:${reviewPage.value}, 本页:${rawReviews.length}, 总计:${reviewTotal.value}, 是否有更多:${hasMoreReviews.value}`)
    } else {
      hasMoreReviews.value = false
    }
  } catch (e) {
    console.error('[Profile] 加载评测失败:', e)
    hasMoreReviews.value = false
  } finally {
    loading.value = false
  }
}

async function loadMoreReviews() {
  if (!hasMoreReviews.value || loading.value) return
  reviewPage.value++
  await loadMyReviews(false)
}

async function loadPointsInfo() {
  try {
    const res = await api.getPoints()
    if (res && res.data) {
      pointsInfo.value = res.data
    } else {
      pointsInfo.value = { balance: 0 }
    }
  } catch (e) {
    console.error('[Profile] 获取积分信息失败:', e)
    pointsInfo.value = { balance: 0 }
  }
}

async function loadPointsRecords(page = 1) {
  try {
    // 后端 /points/records 从 Token 识别当前用户，无需传 userId；
    // 参数是分页页码（此前误把 userId 当页码传入，导致请求第N页返回空列表）
    const res = await api.getPointsRecords(page)
    if (res && res.data) {
      pointsRecords.value = Array.isArray(res.data) ? res.data : (res.data.records || [])
    }
  } catch (e) {
    console.warn('[Profile] 获取积分记录失败:', e.message)
    pointsRecords.value = []
  }
}

function switchTab(t) {
  tab.value = t
  if (t === 'orders') loadOrders()
  else if (t === 'reviews') loadMyReviews()
  else if (t === 'points') {
    loadPointsInfo()
    loadPointsRecords()
  }
}

// 积分记录类型文案（与后端 PointsRecord.TYPE_* 对应：1收入 2支出 3退款返还 4抽奖）
const POINTS_TYPE_LABELS = { 1: '积分获取', 2: '积分消耗', 3: '退款返还', 4: '积分抽奖' }
function pointsTypeLabel(type) {
  return POINTS_TYPE_LABELS[type] || '积分变动'
}

async function handleLogout() {
  try {
    await api.logout()
  } catch (e) {
    console.warn('[Profile] 后端登出请求失败:', e.message)
  }

  // 清除所有可能的登录状态存储位置
  sessionStorage.removeItem('token')
  sessionStorage.removeItem('user')
  localStorage.removeItem('token')
  localStorage.removeItem('user')

  // 通知全局状态变更
  window.dispatchEvent(new Event('auth-changed'))

  await router.push('/')
}

function showVerifyCode(order) {
  if (order.verificationCode) {
    alert(`核销码：${order.verificationCode}\n\n请出示此核销码给工作人员`)
  } else {
    alert('暂无核销码，请稍后重试或联系客服')
  }
}

function getStatusClass(order) {
  const status = order.status
  if (status === 0) return checkOrderExpired(order) ? 'status-cancelled' : 'status-pending'
  if (status === 1) return 'status-info'
  if (status === 2) return 'status-paid'
  if (status === 3) return 'status-used'
  if (status === 4 || status === 5) return 'status-cancelled'
  if (status === 6 || status === 7) return 'status-warning'
  return 'status-info'
}

function getStatusText(order) {
  const status = order.status
  if (status === 0) return checkOrderExpired(order) ? '已取消' : '预占'
  if (status === 1) return '秒杀预占'
  if (status === 2) return '已支付'
  if (status === 3) return '已核销'
  if (status === 4) return '已取消'
  if (status === 5) return '已退款'
  if (status === 6) return '申请退款中'
  if (status === 7) return '已拒绝'
  return '未知'
}

function formatTime(time) {
  if (!time) return ''
  const date = new Date(time)
  const now = new Date()
  const diff = now - date

  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return `${Math.floor(diff / 60000)}分钟前`
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}小时前`
  if (diff < 604800000) return `${Math.floor(diff / 86400000)}天前`

  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function checkOrderExpired(order) {
  if (!order.createTime || order.status !== 0) return false

  let createdTime
  const createTimeStr = String(order.createTime)

  if (createTimeStr.includes('T') || createTimeStr.includes('Z')) {
    createdTime = new Date(createTimeStr).getTime()
  } else {
    createdTime = new Date(createTimeStr.replace(/-/g, '/')).getTime()
  }

  if (isNaN(createdTime)) return false

  const ORDER_TIMEOUT_MS = 5 * 60 * 1000
  const expireTime = createdTime + ORDER_TIMEOUT_MS

  return Date.now() > expireTime
}

async function handleRefund(order) {
  if (!confirm('确定要申请退款吗？')) {
    return
  }

  try {
    const res = await api.refundOrder(order.id)
    alert('退款申请已提交！请等待管理员审批（预计1-2个工作日）')
    await loadOrders()
  } catch (e) {
    console.error('[退款失败]', e)

    let errorMsg = '未知错误'

    if (e instanceof Error) {
      if (e.message.includes('timeout') || e.message.includes('超时')) {
        errorMsg = '请求超时，请稍后重试'
      } else if (e.message.includes('network') || e.message.includes('网络')) {
        errorMsg = '网络连接不稳定，请检查网络后重试'
      } else if (e.constructor?.name === 'HttpError' && e.statusCode === 403) {
        errorMsg = '权限不足，无法执行此操作'
      } else if (e.constructor?.name === 'BusinessError') {
        errorMsg = e.message || '业务逻辑错误'
      } else {
        errorMsg = e.message || '未知错误'
      }
    }

    alert('退款申请失败：' + errorMsg)
  }
}

onMounted(async () => {
  // 未登录（无 Token）才跳登录页；有 Token 但 sessionStorage.user 缺失时先回源补全
  if (!isLoggedIn()) {
    router.push('/login?redirect=/profile')
    return
  }
  if (!user.value) {
    const uid = await getUserId()
    if (uid) {
      user.value = getUser()
    }
  }
  if (!user.value) {
    router.push('/login?redirect=/profile')
    return
  }
  loadOrders()
  loadPointsInfo()
})
</script>

<template>
  <div class="profile-container">
    <!-- 个人信息头部卡片 -->
    <section class="profile-header">
      <div class="profile-avatar-section" @click="triggerAvatarUpload">
        <img
          v-if="user?.icon && user.icon !== '/default-avatar.png'"
          :src="getImageUrl(user.icon)"
          class="profile-avatar"
          alt="头像"
          @error="handleAvatarError"
        />
        <div v-else class="profile-avatar-placeholder">
          {{ (user?.nickName || 'U')?.charAt(0) }}
        </div>
        <div class="profile-avatar-overlay">
          <Camera :size="16" />
          <span>更换</span>
        </div>
        <input
          ref="avatarInput"
          type="file"
          accept="image/*"
          style="display: none"
          @change="handleAvatarChange"
        />
      </div>

      <h2 class="profile-name">{{ user?.nickName || '用户' }}</h2>

      <p v-if="user?.email || user?.phone" class="profile-email">
        {{ user?.email || user?.phone }}
      </p>

      <div class="profile-points clickable" @click="goToLottery" title="点击进入抽奖">
        <Star :size="18" />
        <span>我的积分</span>
        <strong>{{ pointsBalance.toLocaleString() }}</strong>
        <span class="points-hint">点击抽奖 →</span>
      </div>

      <button class="btn btn-secondary btn-sm profile-logout-btn" @click="handleLogout">
        退出登录
      </button>

      <!-- 错误/成功提示 -->
      <div v-if="avatarError" class="avatar-error">{{ avatarError }}</div>
      <div v-if="avatarSuccess" class="avatar-success">头像更新成功！</div>
    </section>

    <!-- 账号安全：设置/修改密码 -->
    <section class="security-card">
      <div class="security-header">
        <span class="security-title">账号安全</span>
        <button class="btn btn-secondary btn-sm" @click="showPasswordForm = !showPasswordForm">
          {{ showPasswordForm ? '收起' : '设置登录密码' }}
        </button>
      </div>
      <div v-if="showPasswordForm" class="password-form">
        <input
          v-model="pwForm.oldPassword"
          type="password"
          class="form-input"
          placeholder="旧密码（从未设置过密码可留空）"
          autocomplete="current-password"
        />
        <input
          v-model="pwForm.newPassword"
          type="password"
          class="form-input"
          placeholder="新密码（6-20位）"
          autocomplete="new-password"
        />
        <div class="pw-code-row">
          <input
            v-model="pwForm.code"
            type="text"
            class="form-input"
            maxlength="6"
            placeholder="短信验证码"
          />
          <button
            class="btn btn-secondary btn-sm pw-code-btn"
            :disabled="pwCodeCountdown > 0"
            @click="handleSendPwCode"
          >
            {{ pwCodeCountdown > 0 ? pwCodeCountdown + 's后重发' : '发送验证码' }}
          </button>
        </div>
        <button class="btn btn-primary btn-sm" :disabled="pwSubmitting" @click="handleChangePassword">
          {{ pwSubmitting ? '提交中...' : '确认设置' }}
        </button>
        <div v-if="pwMessage" class="security-hint" :class="{ 'security-hint-error': pwIsError }">
          {{ pwMessage }}
        </div>
      </div>
    </section>

    <!-- 标签页导航 -->
    <nav class="tabs">
      <button
        :class="['tab-button', { active: tab === 'orders' }]"
        @click="switchTab('orders')"
      >
        我的订单
      </button>
      <button
        :class="['tab-button', { active: tab === 'reviews' }]"
        @click="switchTab('reviews')"
      >
        我的评测
      </button>
      <button
        :class="['tab-button', { active: tab === 'points' }]"
        @click="switchTab('points')"
      >
        积分记录
      </button>
    </nav>

    <!-- 加载状态 -->
    <div v-if="loading" class="empty-state">
      <Loader2 class="loading-spinner" :size="40" />
      <p>加载中...</p>
    </div>

    <!-- 内容区域 -->
    <template v-else>
      <!-- 订单列表 -->
      <main v-if="tab === 'orders'" class="order-list">
        <div v-if="!orders.length" class="empty-state">
          <AlertCircle :size="64" class="empty-state-icon" />
          <h3 class="empty-state-title">暂无订单</h3>
          <p class="empty-state-description">您还没有任何订单记录，快去发现精彩演出吧！</p>
          <router-link to="/shows" class="btn btn-primary">浏览演出</router-link>
        </div>

        <article v-for="order in orders" :key="order.id" class="order-card">
          <!-- 订单头部 -->
          <header class="order-header">
            <div>
              <p class="order-id">订单 #{{ order.id }}</p>
              <h4 class="order-title">{{ order.showName || '未知演出' }}</h4>
            </div>
            <span :class="['order-status', getStatusClass(order)]">
              {{ getStatusText(order) }}
            </span>
          </header>

          <!-- 订单详情 -->
          <div class="order-details">
            <div class="order-detail-item">
              <p class="order-detail-label">票种</p>
              <p class="order-detail-value">
                {{ order.ticketTitle || '未知票型' }}<span v-if="order.subOrderIndex" class="sub-order-index">{{ order.subOrderIndex }}</span>
                × {{ order.quantity || 1 }} 张
              </p>
            </div>
            <div class="order-detail-item">
              <p class="order-detail-label">下单时间</p>
              <p class="order-detail-value">{{ formatTime(order.createTime) }}</p>
            </div>
            <div class="order-detail-item">
              <p class="order-detail-label">订单金额</p>
              <p class="order-price">{{ formatPrice(order.payValue) }}</p>
            </div>
          </div>

          <!-- 座位信息 -->
          <div v-if="order.seatInfoList && order.seatInfoList.length" class="seat-info">
            <span class="seat-icon-text">座位:</span>
            <span v-for="(seat, seatIndex) in order.seatInfoList" :key="seat.id">
              {{ seat.seatLabel || `${seat.area || ''}-${seat.rowNum || '?'}排${seat.colNum || '?'}座` }}
              <template v-if="seatIndex < order.seatInfoList.length - 1">, </template>
            </span>
          </div>

          <!-- 核销码显示区域（仅已支付未核销状态显示） -->
          <div v-if="order.verificationCode && order.status === 2 && !order.isVerified" class="verify-code-display">
            <p class="verify-code-label">核销码 Verification Code</p>
            <p class="verify-code-value">{{ order.verificationCode }}</p>
          </div>

          <!-- 核销码已使用提示（已核销状态） -->
          <div v-else-if="order.verificationCode && order.status === 3" class="verify-code-used-display">
            <CheckCircle :size="32" :strokeWidth="1.5" class="used-icon" />
            <p class="used-title">核销码已使用</p>
            <p class="used-desc">该订单已完成核销</p>
          </div>

          <!-- 操作按钮组 -->
          <footer class="order-actions">
            <!-- 待支付且未超时：去支付 -->
            <router-link
              v-if="order.status === 0 && !checkOrderExpired(order)"
              :to="`/pay-order?orderId=${order.id}&showId=${order.showId}`"
              class="btn btn-primary btn-sm"
            >
              去支付
            </router-link>

            <!-- 已超时未支付 -->
            <span v-if="order.status === 0 && checkOrderExpired(order)" class="status-badge status-danger">
              已超时取消
            </span>

            <!-- 已支付：查看核销码 -->
            <button
              v-if="order.status === 2"
              class="btn btn-outline btn-sm"
              @click="showVerifyCode(order)"
            >
              查看核销码
            </button>

            <!-- 已支付未核销：申请退款 -->
            <button
              v-if="order.status === 2 && !order.isVerified"
              class="btn btn-secondary btn-sm"
              @click="handleRefund(order)"
            >
              申请退款
            </button>

            <router-link v-if="[2,3].includes(order.status) && order.showId" :to="{path:'/reviews/publish',query:{showId:String(order.showId),from:'order'}}" class="btn btn-outline btn-sm">写评分与观演评测</router-link>
            <!-- 已核销：不可退款提示 -->
            <span v-if="order.status === 3" class="status-badge status-muted">已核销，不可退款</span>

            <!-- 状态提示标签 -->
            <span v-if="order.status === 6" class="status-badge status-warning">申请退款中</span>
            <span v-if="order.status === 5" class="status-badge status-info">已退款</span>
          </footer>
        </article>

        <!-- 加载更多按钮（订单） -->
        <div v-if="tab === 'orders' && hasMoreOrders" class="load-more-container">
          <button
            class="btn btn-outline"
            :disabled="loading"
            @click="loadMoreOrders"
          >
            <Loader2 v-if="loading" :size="16" class="spin-icon" />
            {{ loading ? '加载中...' : '加载更多订单' }}
          </button>
          <p class="load-more-info">已显示 {{ orders.length }} / {{ orderTotal }} 条</p>
        </div>
      </main>

      <!-- 评测列表 -->
      <main v-if="tab === 'reviews'" class="order-list">
        <div v-if="!reviews.length" class="empty-state">
          <AlertCircle :size="64" class="empty-state-icon" />
          <h3 class="empty-state-title">暂无评测</h3>
          <p class="empty-state-description">您还没有发布过评测，快去分享您的观演体验吧！</p>
          <router-link to="/reviews/publish" class="btn btn-primary">写评测</router-link>
        </div>

        <article
          v-for="review in reviews"
          :key="review.id"
          class="order-card"
          style="cursor: pointer;"
        >
          <router-link :to="`/review/${review.id}`" style="text-decoration: none; color: inherit;">
            <header class="order-header">
              <h4 class="order-title">{{ review.title }}</h4>
              <span class="status-badge status-info">
                {{ review.liked || 0 }} 赞 · {{ review.comments || 0 }} 评论
              </span>
            </header>
          </router-link>
        </article>

        <!-- 加载更多按钮（评测） -->
        <div v-if="tab === 'reviews' && hasMoreReviews" class="load-more-container">
          <button
            class="btn btn-outline"
            :disabled="loading"
            @click="loadMoreReviews"
          >
            <Loader2 v-if="loading" :size="16" class="spin-icon" />
            {{ loading ? '加载中...' : '加载更多评测' }}
          </button>
          <p class="load-more-info">已显示 {{ reviews.length }} / {{ reviewTotal }} 条</p>
        </div>
      </main>

      <!-- 积分记录列表 -->
      <main v-if="tab === 'points'" class="order-list">
        <div v-if="!pointsRecords.length" class="empty-state">
          <Gift :size="64" class="empty-state-icon" />
          <h3 class="empty-state-title">暂无积分记录</h3>
          <p class="empty-state-description">参与活动、购买门票即可获得积分哦！</p>
          <router-link to="/lottery" class="btn btn-primary">去抽奖</router-link>
        </div>

        <article
          v-for="(record, index) in pointsRecords"
          :key="index"
          class="order-card"
        >
          <header class="order-header">
            <div>
              <h4 class="order-title">{{ pointsTypeLabel(record.type) }}</h4>
              <p style="font-size: var(--font-sm); color: var(--color-text-light); margin-top: var(--space-1);">
                {{ record.description || '无描述' }}
              </p>
            </div>
            <strong :style="{ color: record.points > 0 ? 'var(--color-success-dark)' : 'var(--color-danger-dark)', fontSize: 'var(--font-lg)' }">
              {{ record.points > 0 ? '+' : '' }}{{ record.points }}
            </strong>
          </header>
          <footer class="order-actions">
            <span style="font-size: var(--font-xs); color: var(--color-text-light);">
              {{ formatTime(record.createTime) }}
            </span>
          </footer>
        </article>
      </main>
    </template>
  </div>
</template>

<style scoped>
@import '../styles/client-design-system.css';

/* 页面特定样式 */
.profile-container {
  min-height: calc(100vh - 60px);
}

/* 个人信息头部样式增强 */
.profile-header {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-4);
}

.profile-points {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-6);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  color: var(--color-secondary);
  font-weight: 600;
  width: 100%;
  max-width: 300px;
  margin-top: var(--space-2);
  cursor: default; /* 默认状态 */
  transition: all 0.3s ease;
}

.profile-points.clickable {
  cursor: pointer;
  background-color: var(--color-primary-alpha);
  border: 1px solid var(--color-primary);
}

.profile-points.clickable:hover {
  background-color: var(--color-primary);
  color: white;
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}

.points-hint {
  margin-left: auto;
  font-size: var(--font-xs);
  color: var(--color-secondary);
  opacity: 0.8;
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 0.8; }
  50% { opacity: 0.4; }
}

.profile-logout-btn {
  margin-top: var(--space-2);
  width: 100%;
  max-width: 200px;
}

/* ===== 账号安全（设置/修改密码） ===== */
/* 与 profile-header / order-card 同款卡片基调：bg-card 背景 + radius-md + shadow-sm */
.security-card {
  padding: var(--space-6);
  background-color: var(--color-bg-card);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  margin-bottom: var(--space-6);
  transition: var(--transition-base);
}

.security-card:hover {
  box-shadow: var(--shadow-md);
}

.security-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-border);
}

.security-title {
  font-weight: 600;
  font-size: var(--font-sm);
  color: var(--color-text);
}

.password-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin-top: var(--space-4);
}

.password-form .form-input {
  max-width: 420px;
}

.pw-code-row {
  display: flex;
  gap: var(--space-2);
}

.pw-code-row .form-input {
  flex: 1;
}

.pw-code-btn {
  white-space: nowrap;
}

.security-hint {
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

.security-hint-error {
  color: var(--color-danger);
}

/* 头像区域 */
.profile-avatar-section {
  position: relative;
  width: 80px;
  height: 80px;
  cursor: pointer;
  border-radius: 50%;
  overflow: hidden;
}

.profile-avatar {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 50%;
}

.profile-avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-xl);
  font-weight: 700;
  color: white;
  background-color: var(--color-primary);
  border-radius: 50%;
}

.profile-avatar-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-1);
  background-color: rgba(0, 0, 0, 0.5);
  color: white;
  font-size: var(--font-xs);
  opacity: 0;
  transition: var(--transition-base);
}

.profile-avatar-section:hover .profile-avatar-overlay {
  opacity: 1;
}

/* 用户名和邮箱 */
.profile-name {
  font-size: var(--font-xl);
  font-weight: 600;
  color: var(--color-text);
  text-align: center;
  margin: 0;
}

.profile-email {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  text-align: center;
  margin: 0;
}

/* 错误/成功提示 */
.avatar-error,
.avatar-success {
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-sm);
  font-size: var(--font-xs);
  text-align: center;
  width: 100%;
  max-width: 300px;
  margin-top: var(--space-2);
}

.avatar-error {
  background-color: var(--color-bg-danger);
  color: var(--color-danger-dark);
  border: 1px solid var(--color-border-danger);
}

.avatar-success {
  background-color: var(--color-bg-success);
  color: var(--color-success-dark);
  border: 1px solid var(--color-border-success);
}

/* 核销码已使用提示 - 柔和的完成状态 */
.verify-code-used-display {
  margin-top: var(--space-4);
  padding: var(--space-4);
  background-color: #f8f9f7;
  border: 1px solid #d5e0d8;
  border-radius: var(--radius-md);
  text-align: center;
}

.used-icon {
  color: #7a9a84;
  margin-bottom: var(--space-2);
}

.used-title {
  font-size: var(--font-base);
  font-weight: 600;
  color: #5d7866;
  margin-bottom: var(--space-1);
}

.used-desc {
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

/* 状态标签 - 已核销（不可退款） */
.status-muted {
  display: inline-block;
  padding: var(--space-1) var(--space-3);
  background-color: var(--color-bg-alt);
  color: var(--color-text-light);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-xs);
  font-weight: 500;
}

/* 序列号样式（如 1/3, 2/3） - 紧贴票名 */
.sub-order-index {
  color: var(--color-primary);
  font-weight: 700;
  font-size: 0.85em;
  margin-left: var(--space-1);
  padding: var(--space-1) var(--space-2);
  background-color: var(--color-primary-alpha);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-primary);
  vertical-align: middle;
  display: inline-block;
  line-height: 1.2;
}

/* 加载更多按钮样式 */
.load-more-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-6);
  padding: var(--space-4) 0;
}

.load-more-container .btn {
  min-width: 200px;
  padding: var(--space-3) var(--space-6);
  font-size: var(--font-sm);
  font-weight: 500;
  transition: all var(--transition-base);
}

.load-more-container .btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}

.load-more-container .btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.spin-icon {
  animation: spin 1s linear infinite;
  margin-right: var(--space-2);
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.load-more-info {
  font-size: var(--font-xs);
  color: var(--color-text-light);
  margin: 0;
}

/* ===== 订单状态样式（符合日系轻亚风设计系统）===== */
.order-status {
  display: inline-block;
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--font-xs);
  font-weight: 600;
  white-space: nowrap;
}

/* 待支付/预占 - 温暖的米色 */
.status-pending {
  background-color: #f7f4ed;
  color: #8b7355;
  border: 1px solid #e8dcc8;
}

/* 已支付 - 柔和的灰绿色 */
.status-paid {
  background-color: #eef5f1;
  color: #5d8a6b;
  border: 1px solid #c5d9cc;
}

/* 已核销 - 柔和的成功色 */
.status-used {
  background-color: #f0f7f2;
  color: #6a9a78;
  border: 1px solid #a5c9aa;
}

/* 已取消/已退款 - 中性灰色调 */
.status-cancelled {
  background-color: #f5f5f5;
  color: #999;
  border: 1px solid #e0e0e0;
}

/* 秒杀预占 - 信息提示色 */
.status-info {
  background-color: #f0f4f8;
  color: #6b7c85;
  border: 1px solid #d0dce6;
}

/* 申请退款中/已拒绝 - 警告色调 */
.status-warning {
  background-color: #faf6f0;
  color: #a67c52;
  border: 1px solid #e8d5be;
}

/* 状态标签（操作区） */
.status-badge {
  display: inline-block;
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--font-xs);
  font-weight: 500;
}

/* 危险状态（超时取消） */
.status-danger {
  background-color: #fef2f2;
  color: #c97a7a;
  border: 1px solid #f5d0d0;
}
</style>
