<script setup>
import GuideReviewInsights from '../components/common/GuideReviewInsights.vue'
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, parseImages, getImageUrl, formatPrice, isLoggedIn, getUserId } from '../utils/api.js'
import { Star, Heart, MapPin, Calendar, Tag, Ticket, ChevronLeft, AlertCircle, RefreshCw, MessageSquare, Users, TrendingUp } from 'lucide-vue-next'
import StarRating from '../components/common/StarRating.vue'
import TicketSelection from '../components/common/TicketSelection.vue'

const route = useRoute()
const router = useRouter()
const showId = ref(route.params.id)
const show = ref(null)
const showTypes = ref([])
const comments = ref([])
const loading = ref(true)
const refreshing = ref(false) // 下拉刷新状态
const errorMsg = ref('') // 错误信息
const errorType = ref('') // 错误类型: network | server | notfound | unknown
const commentPage = ref(1)
const commentTotal = ref(0)
const posterUrl = ref('')
const posterError = ref(false)

// 票种选择弹窗控制
const showTicketSelection = ref(false)

// 评测相关状态
const reviews = ref([])
const reviewPage = ref(1)
const reviewTotal = ref(0)
const reviewLoading = ref(false)
const userReview = ref(null) // 当前用户的评测
const showRating = ref(0) // 演出平均评分
const submittingReview = ref(false)
const reviewFormVisible = ref(false)
const reviewTitle = ref('')  // 评测标题（可选）
const reviewScore = ref(0)
const reviewContent = ref('')
const reviewImages = ref([])
const imagePreviews = ref([])

// 计算属性：是否显示错误状态
const hasError = computed(() => !!errorMsg.value)

// 计算属性：统计数据
const statistics = computed(() => {
  const computedScore = showRating.value || show.value?.score || 0

  // ✅ 调试日志：显示评分数据来源
  if (showRating.value > 0) {
    console.log(`[ShowDetail] 📊 统计数据 - 平均评分:${computedScore} (来源:前端计算, showRating=${showRating.value}, 后端score=${show.value?.score})`)
  }

  return {
    sold: show.value?.sold || 0,
    avgPrice: show.value?.avgPrice || 0,
    comments: reviewTotal.value || show.value?.comments || 0,
    score: computedScore
  }
})

// 计算属性：是否可以提交评测
const canSubmitReview = computed(() => {
  return isLoggedIn() && !userReview.value
})

// 计算属性：错误提示信息
const errorMessage = computed(() => {
  const messages = {
    network: '网络连接失败，请检查网络后重试',
    server: '服务器繁忙，请稍后重试',
    notfound: '该演出不存在或已下架',
    unknown: '加载失败，请重试',
    default: errorMsg.value || '加载失败，请重试'
  }

  return messages[errorType.value] || messages.default
})

// 计算属性：错误图标
const errorIcon = computed(() => {
  return AlertCircle
})

/**
 * 🔄 加载演出详情（带重试和错误分类）
 */
async function loadShow(retryCount = 0) {
  loading.value = true
  errorMsg.value = ''
  errorType.value = ''
  posterError.value = false

  try {
    const res = await api.getShowDetail(showId.value)

    if (!res || (!res.data && !res.id)) {
      throw new Error('DATA_INVALID')
    }

    show.value = res.data || res

    console.log('====================================')
    console.log('[ShowDetail] 演出数据加载成功')
    console.log('[ShowDetail] 完整的show对象:', JSON.stringify(show.value, null, 2))
    console.log('[ShowDetail] show.sold:', show.value?.sold, '类型:', typeof show.value?.sold)
    console.log('[ShowDetail] show.avgPrice:', show.value?.avgPrice, '类型:', typeof show.value?.avgPrice)
    console.log('[ShowDetail] show.id:', show.value?.id)

    // ✅ 新增：演出时间状态检查
    if (show.value?.endTime) {
      const endTime = new Date(show.value.endTime)
      const now = new Date()
      const isEnded = endTime < now
      const daysDiff = Math.floor((now.getTime() - endTime.getTime()) / (1000 * 60 * 60 * 24))

      console.log('[ShowDetail] 📅 演出时间状态:', {
        endTime: endTime.toISOString(),
        currentTime: now.toISOString(),
        isEnded: isEnded,
        status: show.value.status,
        daysDiff: isEnded ? `${daysDiff}天前结束` : `${-daysDiff}天后结束`
      })
    }

    console.log('====================================')

    if (show.value && show.value.images) {
      console.log('[ShowDetail] 原始images字段:', show.value.images)
      const parsedImages = parseImages(show.value.images)
      console.log('[ShowDetail] 解析后的图片列表:', parsedImages)

      if (parsedImages.length > 0) {
        const firstImage = parsedImages[0]
        const fullUrl = getImageUrl(firstImage)
        console.log('[ShowDetail] 第一张图片原始路径:', firstImage)
        console.log('[ShowDetail] 第一张图片完整URL:', fullUrl)

        if (firstImage.startsWith('/ssn/imgs/') || firstImage.startsWith('http')) {
          posterUrl.value = fullUrl
          console.log('[ShowDetail] 使用新格式URL')
        } else if (firstImage.endsWith('.jpg') || firstImage.endsWith('.png') || firstImage.endsWith('.jpeg')) {
          posterUrl.value = fullUrl
          console.warn('[ShowDetail] 检测到旧格式图片路径，可能无法显示:', firstImage)
        } else {
          console.warn('[ShowDetail] 无效的图片格式:', firstImage)
          posterUrl.value = ''
        }
      } else {
        console.log('[ShowDetail] 图片列表为空')
        posterUrl.value = ''
      }
    } else {
      console.log('[ShowDetail] 无图片数据')
      posterUrl.value = ''
    }

  } catch (error) {
    console.error('[ShowDetail] 加载失败:', error)

    // 🔍 分类错误类型
    classifyError(error)

    // 🔄 自动重试机制（最多2次）
    if (retryCount < 2 && isRetryable(error)) {
      console.warn(`[ShowDetail] 第${retryCount + 1}次自动重试...`)

      await new Promise(resolve => setTimeout(resolve, 1000 * (retryCount + 1)))

      return loadShow(retryCount + 1)
    }

    // 重试耗尽或不可重试，显示错误
    showErrorState(error)

  } finally {
    loading.value = false
    refreshing.value = false
  }
}

/**
 * 打开票种选择弹窗
 */
function openTicketSelection() {
  if (!isLoggedIn()) {
    router.push(`/login?redirect=/show/${showId.value}`)
    return
  }
  showTicketSelection.value = true
}

/**
 * 关闭票种选择弹窗
 */
function closeTicketSelection() {
  showTicketSelection.value = false
}

/**
 * 处理票种选择完成（从弹窗回调）
 */
async function handleTicketSelected(orderData) {
  showTicketSelection.value = false

  console.log('[ShowDetail] 收到票种选择结果:', orderData)

  // 添加小延迟，确保弹窗完全关闭后再跳转
  await new Promise(resolve => setTimeout(resolve, 100))

  // 如果需要选座，跳转到座位选择页面
  if (orderData.needSeatSelection) {
    console.log('[ShowDetail] 跳转到选座页面:', orderData)
    await router.push({
      path: '/seat-selection',
      query: {
        ticketId: orderData.ticketId,
        quantity: orderData.quantity,
        isShowEnded: orderData.isShowEnded || false
      }
    })
  } else {
    // 直接跳转到支付页面
    const orderId = orderData.orderId
    console.log('[ShowDetail] 准备跳转到支付页面, orderId:', orderId)

    if (orderId) {
      try {
        await router.push(`/pay-order?orderId=${orderId}&isShowEnded=${orderData.isShowEnded || false}`)
        console.log('[ShowDetail] 成功跳转到支付页面')
      } catch (e) {
        console.error('[ShowDetail] 跳转失败:', e)
        alert('正在跳转到支付页面...')
        window.location.href = `/pay-order?orderId=${orderId}`
      }
    } else {
      console.warn('[ShowDetail] orderId为空，无法跳转到支付页')
      alert('订单创建成功！请前往"我的订单"完成支付')
      await router.push('/order')
    }
  }
}

/**
 * 分类错误类型
 */
function classifyError(error) {
  const message = (error.message || '').toLowerCase()

  if (message.includes('network') || message.includes('fetch') || message.includes('failed to fetch')) {
    errorType.value = 'network'
  } else if (message.includes('404') || message.includes('not found')) {
    errorType.value = 'notfound'
  } else if (message.includes('500') || message.includes('502') || message.includes('503')) {
    errorType.value = 'server'
  } else if (message.includes('data_invalid') || message.includes('无效')) {
    errorType.value = 'notfound'
  } else {
    errorType.value = 'unknown'
  }

  errorMsg.value = error.message || '加载失败'
}

/**
 * 🔄 判断是否可重试
 */
function isRetryable(error) {
  const retryableErrors = [
    'network',
    'server',
    'timeout',
    'fetch'
  ]

  const message = (error.message || '').toLowerCase()
  return retryableErrors.some(keyword => message.includes(keyword))
}

/**
 * 显示错误状态
 */
function showErrorState(error) {
  console.error('[ShowDetail] 最终错误:', errorType.value, error.message)

  // 可以在这里上报错误到监控系统
  // reportErrorToMonitoring({
  //   page: 'ShowDetail',
  //   showId: showId.value,
  //   error: error,
  //   type: errorType.value
  // })
}

/**
 * 🔁 手动重试加载
 */
function handleRetry() {
  console.log('[ShowDetail] 用户手动重试...')
  loadShow(0)
}

/**
 * 🔄 下拉刷新
 */
async function handleRefresh() {
  if (refreshing.value || loading.value) return

  refreshing.value = true
  await loadShow(0)
}

/**
 * 🏠 返回首页
 */
function goHome() {
  router.push('/')
}

function handlePosterError(e) {
  console.error('[ShowDetail] 海报加载失败:', e)
  console.error('[ShowDetail] 失败的URL:', posterUrl.value)
  posterError.value = true
  posterUrl.value = ''
}

function handlePosterLoad() {
  console.log('[ShowDetail] 海报加载成功:', posterUrl.value)
}

function retryLoadPoster() {
  console.log('[ShowDetail] 用户点击重新加载海报')
  posterError.value = false
  loadShow()
}

function isDevMode() {
  return import.meta.env?.DEV || import.meta.env?.MODE === 'development'
}

/**
 * 评测点赞/取消点赞（toggle）：后端 PUT /show-review/like/{id} 幂等切换
 */
async function handleLike(review) {
  if (!isLoggedIn()) {
    await router.push(`/login?redirect=/show/${showId.value}`)
    return
  }
  try {
    await api.likeReview(review.id)
    if (review.isLike) {
      review.liked = Math.max(0, (review.liked || 1) - 1)
      review.isLike = false
    } else {
      review.liked = (review.liked || 0) + 1
      review.isLike = true
    }
  } catch (e) {
    console.warn('[ShowDetail] 点赞失败:', e.message)
  }
}

function handleReviewAvatarError(review) {
  if (review) {
    review.userIcon = null
  }
}

async function loadComments() {
  try {
    comments.value = []
    commentTotal.value = 0
  } catch {
    comments.value = []
  }
}

/**
 * 加载评测列表
 */
async function loadReviews(reset = true) {
  if (reviewLoading.value) return

  reviewLoading.value = true
  try {
    const res = await api.getReviewsByShow(showId.value, reviewPage.value, 10)

    console.log('[ShowDetail] 原始返回数据:', res)

    // 兼容多种后端返回格式：
    // 1. MyBatis-Plus 分页：{ records: [], total: N, ... }
    // 2. 普通数组：[]
    // 3. 包装结构：{ data: [] } 或 { list: [] }
    const pageData = res.data || res || {}
    let rawData = []

    if (Array.isArray(pageData)) {
      rawData = pageData
    } else if (Array.isArray(pageData.records)) {
      rawData = pageData.records
    } else if (Array.isArray(pageData.data)) {
      rawData = pageData.data
    } else if (Array.isArray(pageData.list)) {
      rawData = pageData.list
    }

    console.log('[ShowDetail] 提取的原始记录:', rawData)

    // 数据格式转换：保持与Reviews.vue一致，使用后端原始字段名
    // 注意：后端 ShowReview 实体的作者字段是 userName / userIcon（项目规范），不存在 name / icon
    const mappedData = rawData.map(review => ({
      ...review,
      imageList: parseImages(review.images),  // images字符串 → imageList数组
      isAnonymous: !review.userName,     // 没有名字则显示匿名
      createTime: review.createTime
    }))

    if (reset) {
      reviews.value = mappedData
      reviewTotal.value = pageData.total || 0
    } else {
      reviews.value.push(...mappedData)
    }

    if (reset && mappedData.length > 0) {
      // ✅ 翻页时累加所有已加载的评测计算平均分（而非仅当前页）
      const allReviews = reset ? mappedData : [...reviews.value, ...mappedData]
      const totalScore = allReviews.reduce((sum, review) => sum + (review.score || 0), 0)
      const avgScore = Math.round((totalScore / allReviews.length) * 10) / 10
      showRating.value = avgScore

      console.log(`[ShowDetail] 实时计算平均评分: ${avgScore} (基于${allReviews.length}条评测, 总分:${totalScore})`)
    } else if (reset && mappedData.length === 0) {
      showRating.value = 0
    }

    console.log(`[ShowDetail] 加载评测成功 - showId:${showId.value}, 获取${mappedData.length}条, 总计${pageData.total}条`)
  } catch (error) {
    console.error('加载评测失败:', error)
    if (reset) {
      reviews.value = []
      reviewTotal.value = 0
    }
  } finally {
    reviewLoading.value = false
  }
}

/**
 * ✅ 检查当前用户是否已评测（调用独立接口，不依赖已加载的评测列表）
 * 解决问题：评测在后续页时，reviews数组中不存在导致按钮仍显示"写评测"
 */
async function checkUserReview() {
  if (!isLoggedIn()) return

  try {
    // /show-review/of/me 由后端从 Token 识别当前用户。
    // 旧代码先从 localStorage 读 user.id 做前置校验——但登录信息存在 sessionStorage，
    // localStorage 里永远没有 → userId 恒为 undefined → 提前 return，
    // "我已评测"检测从不执行（发布评测后按钮状态不更新）。
    if (!showId.value) return

    const res = await api.getMyReview(showId.value)
    const myReview = res.data || res

    if (myReview && myReview.id) {
      userReview.value = myReview
      console.log('[ShowDetail] 用户已评测', myReview.id, myReview.title || '(纯评分)')
    } else {
      userReview.value = null
    }
  } catch (error) {
    console.warn('[ShowDetail] 检查用户评测状态失败（不影响主流程）:', error)
    // 兜底：如果接口失败，回退到扫描已加载列表
    const userId = await getUserId()
    if (userId) {
      const existingReview = reviews.value.find(r => String(r.userId) === String(userId))
      if (existingReview) {
        userReview.value = existingReview
      }
    }
  }
}

/**
 * 提交评测（统一使用 /show-review/save 接口）
 */
async function submitReview() {
  if (!isLoggedIn()) {
    await router.push(`/login?redirect=/show/${showId.value}`)
    return
  }

  // ✅ 关键修复：提交前确保CSRF Token有效
  console.log('[ShowDetail] 提交评测前检查CSRF Token...')
  try {
    await api.ensureCsrfToken()
    console.log('[ShowDetail] ✅ CSRF Token已就绪')
  } catch (csrfError) {
    console.error('[ShowDetail] ❌ CSRF Token准备失败:', csrfError)
    alert('安全验证失败，请刷新页面重试')
    return
  }

  // 验证评分（必填）
  if (reviewScore.value < 1 || reviewScore.value > 5) {
    alert('请选择评分')
    return
  }

  // 允许纯评分：如果标题和内容都为空，只提交评分
  const hasTitle = reviewTitle.value.trim().length > 0
  const hasContent = reviewContent.value.trim().length > 0

  if (!hasTitle && !hasContent) {
    const confirmOnlyScore = confirm('您没有填写标题和内容，确定只提交评分吗？\n（仅统计评分，不会出现在评测列表中）')
    if (!confirmOnlyScore) return
  }

  submittingReview.value = true
  try {
    let uploadedUrls = []

    // 上传图片
    for (const file of reviewImages.value) {
      const res = await api.uploadImage(file)
      if (res && (res.url || res.data)) {
        uploadedUrls.push(res.url || res.data)
      }
    }

    // 构建评测数据（统一格式）
    const reviewData = {
      showId: showId.value,
      title: hasTitle ? reviewTitle.value.trim() : undefined,
      content: hasContent ? reviewContent.value.trim() : undefined,
      score: reviewScore.value,  // 始终包含评分
      images: uploadedUrls.length > 0 ? JSON.stringify(uploadedUrls) : undefined
    }

    // 调用统一的评测接口（/show-review/save）
    await api.publishReview(reviewData)

    // 重置表单
    resetReviewForm()

    // 重新加载演出详情（包含统计数据：评分、销量等）
    await loadShow()
    // 重新加载评测列表
    await loadReviews()
    // 检查用户是否已评测（用于控制按钮显示）
    await checkUserReview()

    alert(hasTitle || hasContent ? '评测发布成功！' : '评分成功！')
  } catch (error) {
    console.error('提交评测失败:', error)
    alert(error.message || '提交失败，请稍后再试')
  } finally {
    submittingReview.value = false
  }
}

/**
 * 重置评测表单
 */
function resetReviewForm() {
  reviewFormVisible.value = false
  reviewTitle.value = ''
  reviewScore.value = 5
  reviewContent.value = ''
  reviewImages.value = []
  imagePreviews.value = []
}

/**
 * 处理图片选择
 */
function handleImageSelect(e) {
  const files = Array.from(e.target.files)
  files.forEach(file => {
    const reader = new FileReader()
    reader.onload = (ev) => {
      imagePreviews.value.push(ev.target.result)
    }
    reader.readAsDataURL(file)
    reviewImages.value.push(file)
  })
}

/**
 * 移除图片
 */
function removeReviewImage(idx) {
  imagePreviews.value.splice(idx, 1)
  reviewImages.value.splice(idx, 1)
}

/**
 * 删除评测
 */
async function handleDeleteReview(reviewId) {
  if (!reviewId || isNaN(Number(reviewId))) {
    console.error('[删除评测] 无效的评测ID:', reviewId)
    alert('操作失败：评测数据异常，请刷新页面后重试')
    return
  }

  if (!confirm('确定要删除这条评测吗？')) return

  try {
    await api.deleteReview(reviewId)

    // 从列表中移除
    const index = reviews.value.findIndex(r => r.id === reviewId)
    if (index > -1) {
      reviews.value.splice(index, 1)
      reviewTotal.value--
    }

    // 如果删除的是当前用户的评测，重置状态
    if (userReview.value?.id === reviewId) {
      userReview.value = null
    }

    // 重新加载以更新统计数据（评测列表 + 演出详情评分）
    await Promise.all([loadReviews(), loadShowDetail()])
  } catch (error) {
    console.error('删除评测失败:', error)
    alert('删除失败，请稍后再试')
  }
}

function goBack() {
  router.back()
}

function goOrder() {
  openTicketSelection()
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return d.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' })
}

onMounted(() => {
  loadShow()
  loadComments()
  loadReviews()
})
</script>

<template>
  <div class="detail-page">

    <!-- 🔄 加载状态（骨架屏） -->
    <div v-if="loading" class="detail-loading-skeleton">
      <div class="skeleton-header">
        <div class="skeleton-poster skeleton-shimmer"></div>
        <div class="skeleton-info">
          <div class="skeleton-line skeleton-shimmer long"></div>
          <div class="skeleton-line skeleton-shimmer medium"></div>
          <div class="skeleton-line skeleton-shimmer short"></div>
          <div class="skeleton-line skeleton-shimmer medium"></div>
          <div class="skeleton-button skeleton-shimmer"></div>
        </div>
      </div>
      <div class="skeleton-content">
        <div class="skeleton-line skeleton-shimmer full"></div>
        <div class="skeleton-line skeleton-shimmer full"></div>
        <div class="skeleton-line skeleton-shimmer half"></div>
      </div>
    </div>

    <!-- 错误状态 -->
    <div v-else-if="hasError" class="detail-error-state">
      <component :is="errorIcon" :size="64" class="error-icon" />

      <h3 class="error-title">加载失败</h3>
      <p class="error-message">{{ errorMessage }}</p>

      <div class="error-actions">
        <button class="btn-primary" @click="handleRetry">
          <RefreshCw :size="18" />
          <span>重新加载</span>
        </button>
        <button class="btn-secondary" @click="goHome">
          返回首页
        </button>
      </div>

      <!-- 错误详情（开发环境显示） -->
      <details v-if="isDevMode()" class="error-details">
        <summary>技术详情（仅开发环境）</summary>
        <pre class="error-stack">{{ errorMsg }}</pre>
      </details>
    </div>

    <!-- 正常内容 -->
    <template v-else-if="show">
      <!-- 下拉刷新提示 -->
      <div
        v-if="refreshing"
        class="refresh-indicator"
        :class="{ active: refreshing }"
      >
        <RefreshCw :size="20" class="refresh-icon spinning" />
        <span>正在刷新...</span>
      </div>

      <div class="detail-hero">
        <div class="detail-hero-img">
          <!-- 海报图片 -->
          <transition name="fade" mode="out-in">
            <img
              v-if="show.images && parseImages(show.images).length && posterUrl && !posterError"
              key="poster"
              :src="posterUrl"
              :alt="show.name"
              loading="lazy"
              @error="handlePosterError"
              @load="handlePosterLoad"
            />
          </transition>

          <!-- 海报占位符或错误状态 -->
          <div
            v-if="!show.images || !parseImages(show.images).length || !posterUrl || posterError"
            class="detail-hero-img-placeholder"
          >
            <span v-if="posterError">图片加载失败</span>
            <span v-else>暂无海报</span>

            <button
              v-if="posterError"
              class="retry-poster-btn"
              @click="retryLoadPoster"
            >
              重新加载图片
            </button>
          </div>
        </div>

        <div class="detail-hero-body">
          <button class="detail-back" @click="goBack">
            <ChevronLeft :size="20" />
            <span>返回</span>
          </button>

          <h1 class="detail-name">{{ show.name || '未命名演出' }}</h1>

          <div v-if="showTypes.length" class="detail-tags">
            <span v-for="t in showTypes" :key="t" class="detail-tag">{{ t }}</span>
          </div>

          <div class="detail-info-list">
            <div class="detail-info-item">
              <MapPin :size="18" />
              <span>{{ show.area || '未知地区' }} · {{ show.address || '详细地址待定' }}</span>
            </div>

            <div v-if="show.openTime" class="detail-info-item">
              <Calendar :size="18" />
              <span>开演时间：{{ formatTime(show.openTime) }}</span>
            </div>

            <div v-if="show.endTime" class="detail-info-item">
              <Calendar :size="18" />
              <span>结束时间：{{ formatTime(show.endTime) }}</span>
            </div>

            <div class="detail-info-item">
              <Tag :size="18" />
              <span>{{ formatPrice(show.avgPrice) }}起</span>
            </div>

            <div class="detail-info-item">
              <Ticket :size="18" />
              <span>已售 {{ show.sold || 0 }}</span>
            </div>
          </div>

          <!-- 简化的购票按钮（点击弹出票种选择） -->
          <button
            class="detail-order-btn"
            :class="{ disabled: !isLoggedIn() }"
            @click="openTicketSelection"
          >
            {{ isLoggedIn() ? '立即购票' : '登录后购票' }}
          </button>

          <!-- 票种选择弹窗 -->
          <TicketSelection
            v-if="showTicketSelection"
            :show-id="Number(showId)"
            :show-name="show?.name"
            :show-end-time="show?.endTime"
            @close="closeTicketSelection"
            @select-ticket="handleTicketSelected"
          />
        </div>
      </div>

      <!-- 统计数据区域 -->
      <div v-if="show" class="statistics-section">
        <h3 class="statistics-title">演出数据</h3>
        <div class="statistics-grid">
          <div class="stat-card">
            <Ticket :size="24" class="stat-icon" />
            <div class="stat-content">
              <span class="stat-value">{{ statistics.sold }}</span>
              <span class="stat-label">已售数量</span>
            </div>
          </div>

          <div class="stat-card">
            <TrendingUp :size="24" class="stat-icon" />
            <div class="stat-content">
              <span class="stat-value">{{ formatPrice(statistics.avgPrice) }}</span>
              <span class="stat-label">平均票价</span>
            </div>
          </div>

          <div class="stat-card">
            <MessageSquare :size="24" class="stat-icon" />
            <div class="stat-content">
              <span class="stat-value">{{ statistics.comments }}</span>
              <span class="stat-label">评测数量</span>
            </div>
          </div>

          <div class="stat-card">
            <Star :size="24" class="stat-icon stat-star" />
            <div class="stat-content">
              <StarRating
                :model-value="statistics.score"
                :size="18"
                :interactive="false"
                :hide-score-text="true"
              />
              <span class="stat-label">平均评分</span>
              <span class="stat-score-text">{{ statistics.score.toFixed(1) }}分</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 演出详情 -->
      <div class="detail-content">
        <h2 class="detail-section-title">演出详情</h2>
        <p class="detail-desc">
          {{ show.description || '暂无详细描述，敬请期待更多精彩内容~' }}
        </p>
      </div>

      <!-- 评测区域 -->
      <div class="review-section">
        <div class="review-header">
          <h2 class="detail-section-title">观众评测 ({{ reviewTotal }})</h2>
          <button
            v-if="canSubmitReview"
            class="write-review-btn"
            @click="reviewFormVisible = true"
          >
            写评测
          </button>
        </div>

        <!-- 当前用户的评测（如果存在） -->
        <div v-if="userReview" class="user-review-card">
          <div class="review-user-info">
            <img
              v-if="userReview.userAvatar"
              :src="getImageUrl(userReview.userAvatar)"
              class="review-avatar"
              alt=""
            />
            <div v-else class="avatar-placeholder small">
              {{ (userReview.userName || '我')[0].toUpperCase() }}
            </div>
            <span class="review-username">我的评测</span>
            <StarRating
              :model-value="userReview.score"
              :size="16"
              :interactive="false"
            />
          </div>
          <p v-if="userReview.content" class="review-content">{{ userReview.content }}</p>
          <!-- 评测图片 -->
          <div v-if="userReview.imageList && userReview.imageList.length" class="review-images">
            <img
              v-for="(img, idx) in userReview.imageList"
              :key="idx"
              :src="getImageUrl(img)"
              class="review-image"
              alt=""
            />
          </div>
          <div class="review-actions">
            <button class="delete-review-btn" @click="handleDeleteReview(userReview.id)">
              删除评测
            </button>
          </div>
        </div>

        <!-- 评测表单（统一格式） -->
        <div v-if="reviewFormVisible && canSubmitReview" class="review-form-card">
          <h3 class="form-title">撰写评测</h3>

          <!-- 评分（必填） -->
          <div class="form-group">
            <label>评分 <span class="required">*</span></label>
            <StarRating
              v-model="reviewScore"
              :size="28"
              :interactive="true"
            />
            <span class="score-hint">您的评分：{{ reviewScore }} 分</span>
          </div>

          <!-- 标题（可选） -->
          <div class="form-group">
            <label>标题 <span class="optional">（选填）</span></label>
            <input
              v-model="reviewTitle"
              type="text"
              placeholder="给评测起个标题..."
              maxlength="50"
              class="form-input"
            />
            <span class="char-count">{{ reviewTitle.length }}/50</span>
          </div>

          <!-- 内容（可选） -->
          <div class="form-group">
            <label>内容 <span class="optional">（选填）</span></label>
            <textarea
              v-model="reviewContent"
              placeholder="分享您的观演体验...（不填写则仅记录评分）"
              rows="4"
              maxlength="500"
            ></textarea>
            <span class="char-count">{{ reviewContent.length }}/500</span>
          </div>

          <!-- 图片上传（可选） -->
          <div class="form-group">
            <label>添加图片 <span class="optional">（选填）</span></label>
            <div class="image-upload-area">
              <input
                type="file"
                accept="image/*"
                multiple
                @change="handleImageSelect"
                ref="fileInput"
                style="display: none"
              />
              <button class="upload-btn" @click="$refs.fileInput?.click()">
                选择图片
              </button>
              <div v-if="imagePreviews.length" class="image-previews">
                <div v-for="(preview, idx) in imagePreviews" :key="idx" class="preview-item">
                  <img :src="preview" alt="" />
                  <button class="remove-img-btn" @click="removeReviewImage(idx)">×</button>
                </div>
              </div>
            </div>
          </div>

          <!-- 提示信息 -->
          <div class="form-tip">
            <router-link :to="`/reviews/publish?showId=${show.id}`">填写分项评分、关联观演座位并预览发布 ↗</router-link>
            <p>* 评分为必填项，标题和内容为选填</p>
            <p>* 仅填写评分时，将只统计在演出评分中，不会出现在评测列表</p>
          </div>

          <!-- 操作按钮 -->
          <div class="form-actions">
            <button
              class="submit-review-btn"
              :disabled="submittingReview"
              @click="submitReview"
            >
              {{ submittingReview ? '提交中...' : (reviewTitle || reviewContent ? '发布评测' : '提交评分') }}
            </button>
            <button class="cancel-btn" @click="resetReviewForm">取消</button>
          </div>
        </div>

        <!-- 评测列表 -->
        <div v-if="reviews.length > 0" class="review-list">
          <GuideReviewInsights :show-id="show?.id || ''" />
<div v-for="review in reviews" :key="review.id" class="review-item">
            <div class="review-item-header">
              <div class="reviewer-info">
                <img
                  v-if="review.userIcon"
                  :src="getImageUrl(review.userIcon)"
                  class="review-avatar"
                  alt=""
                  @error="handleReviewAvatarError(review)"
                />
                <div v-else class="avatar-placeholder small">
                  {{ (review.userName || '匿')[0].toUpperCase() }}
                </div>
                <div class="reviewer-meta">
                  <span class="reviewer-name">{{ review.isAnonymous ? '匿名用户' : (review.userName || '匿名用户') }}</span>
                  <span class="review-time">{{ formatTime(review.createTime) }}</span>
                </div>
              </div>
              <StarRating
                :model-value="review.score"
                :size="14"
                :interactive="false"
                :hide-score-text="true"
              />
            </div>

            <p v-if="review.content" class="review-item-content">{{ review.content }}</p>

            <!-- 评测图片 -->
            <div v-if="review.imageList && review.imageList.length" class="review-images">
              <img
                v-for="(img, idx) in review.imageList"
                :key="idx"
                :src="getImageUrl(img)"
                class="review-image"
                alt=""
              />
            </div>

            <div class="review-item-footer">
              <button
                class="like-btn"
                :class="{ liked: review.isLike }"
                @click="handleLike(review)"
                :title="review.isLike ? '取消点赞' : '点赞'"
              >
                <Heart :size="14" :fill="review.isLike ? '#e05a5a' : 'none'" />
                {{ review.liked || 0 }}
              </button>
            </div>
          </div>
        </div>

        <!-- 加载更多按钮 -->
        <div v-if="reviewTotal > reviews.length" class="load-more-wrapper">
          <button
            class="load-more-btn"
            :disabled="reviewLoading"
            @click="reviewPage++; loadReviews(false)"
          >
            {{ reviewLoading ? '加载中...' : '加载更多' }}
          </button>
        </div>

        <!-- 无评测时的空状态 -->
        <div v-if="!reviewLoading && reviews.length === 0" class="empty-reviews">
          <MessageSquare :size="48" class="empty-icon" />
          <p>暂无评测，快来发表第一条吧！</p>
        </div>
      </div>
    </template>

    <!-- 数据为空状态 -->
    <div v-else class="detail-empty-state">
      <AlertCircle :size="64" class="empty-icon" />
      <h3>演出信息不存在</h3>
      <p>该演出可能已下架或链接失效</p>
      <button class="btn-primary" @click="goHome">返回首页</button>
    </div>
  </div>
</template>

<style scoped>
.detail-page {
  background-color: var(--color-bg);
  min-height: calc(100vh - 60px);
}

/* ========== 骨架屏加载状态 ========== */
.detail-loading-skeleton {
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--space-12) var(--space-6);
  animation: fadeIn 0.3s ease-out;
}

.skeleton-header {
  display: flex;
  gap: var(--space-8);
  margin-bottom: var(--space-12);
}

.skeleton-poster {
  width: 400px;
  height: 300px;
  border-radius: var(--radius-md);
  flex-shrink: 0;
}

.skeleton-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  justify-content: center;
}

.skeleton-line {
  height: 16px;
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-alt);
}

.skeleton-line.long { width: 80%; }
.skeleton-line.medium { width: 60%; }
.skeleton-line.short { width: 40%; }
.skeleton-line.full { width: 100%; }
.skeleton-line.half { width: 50%; }

.skeleton-button {
  width: 140px;
  height: 44px;
  border-radius: var(--radius-sm);
  margin-top: var(--space-4);
}

.skeleton-content {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

/* 骨架屏闪烁动画 */
.skeleton-shimmer {
  animation: shimmer 1.5s ease-in-out infinite;
}

@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

/* ========== 错误状态 ========== */
.detail-error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: calc(var(--space-16) * 8);
  text-align: center;
  padding: var(--space-16) var(--space-6);
  animation: fadeInUp var(--transition-base);
}

.error-icon {
  color: var(--color-text-light, #999);
  margin-bottom: var(--space-6);
  opacity: 0.7;
}

.error-title {
  font-size: var(--font-xl, 20px);
  font-weight: 600;
  color: var(--color-text, #333);
  margin-bottom: var(--space-3);
}

.error-message {
  font-size: var(--font-base);
  color: var(--color-text-light);
  max-width: calc(var(--space-16) * 6);
  line-height: 1.6;
  margin-bottom: var(--space-8);
}

.error-actions {
  display: flex;
  gap: var(--space-4);
  flex-wrap: wrap;
  justify-content: center;
}

.btn-primary,
.btn-secondary {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-6);
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-base);
  outline: none;
  border: none;
}

.btn-primary {
  background-color: var(--color-primary);
  color: var(--color-white);
}

.btn-primary:hover {
  background-color: var(--color-secondary);
  transform: translateY(-1px);
  box-shadow: var(--shadow-md);
}

.btn-secondary {
  background-color: transparent;
  color: var(--color-text-light);
  border: 1px solid var(--color-border);
}

.btn-secondary:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.error-details {
  margin-top: var(--space-8);
  max-width: calc(var(--space-16) * 10);
  text-align: left;
}

.error-details summary {
  cursor: pointer;
  color: var(--color-text-light);
  font-size: var(--font-xs);
  user-select: none;
}

.error-stack {
  margin-top: var(--space-3);
  padding: var(--space-4);
  background-color: rgba(45, 58, 49, 0.03);
  border-radius: var(--radius-sm);
  font-size: var(--font-xs);
  color: var(--color-text-light);
  word-break: break-all;
  max-height: calc(var(--space-16) * 3);
  overflow-y: auto;
  line-height: 1.5;
}

@keyframes fadeInUp {
  from { opacity: 0; transform: translateY(20px); }
  to { opacity: 1; transform: translateY(0); }
}

/* ========== 下拉刷新指示器 ========== */
.refresh-indicator {
  position: fixed;
  top: 0;
  left: 50%;
  transform: translateX(-50%) translateY(-100%);
  background-color: white;
  box-shadow: var(--shadow-md);
  padding: var(--space-3) var(--space-6);
  border-radius: 0 0 var(--radius-md) var(--radius-md);
  z-index: 1000;
  transition: transform var(--transition-base);
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-sm);
  color: var(--color-primary);
  font-weight: 500;
}

.refresh-indicator.active {
  transform: translateX(-50%) translateY(0);
}

.refresh-icon.spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  100% { transform: rotate(360deg); }
}

/* ========== 图片过渡动画 ========== */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* ========== 海报重试按钮 ========== */
.retry-poster-btn {
  margin-top: var(--space-3);
  padding: var(--space-2) var(--space-4);
  font-size: var(--font-xs);
  color: var(--color-primary);
  background-color: transparent;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-base);
}

.retry-poster-btn:hover {
  background-color: var(--color-primary);
  color: var(--color-white);
}

/* ========== 购票按钮禁用状态 ========== */
.detail-order-btn.disabled {
  opacity: 0.5;
  cursor: not-allowed;
  pointer-events: none;
}

/* ========== 空状态（无评价） ========== */
.detail-comments-empty {
  text-align: center;
  padding: var(--space-16) var(--space-6);
  color: var(--color-text-light, #999);
}

.empty-icon {
  margin-bottom: var(--space-4);
  opacity: 0.4;
}

.detail-comments-empty p {
  font-size: var(--font-base, 16px);
  margin: 0;
}

/* ========== 数据为空状态 ========== */
.detail-empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: calc(var(--space-16) * 8);
  text-align: center;
  padding: var(--space-16) var(--space-6);
  animation: fadeInUp var(--transition-base);
}

.detail-empty-state h3 {
  font-size: var(--font-xl, 20px);
  font-weight: 600;
  color: var(--color-text, #333);
  margin: var(--space-4) 0 var(--space-2);
}

.detail-empty-state p {
  font-size: var(--font-base, 16px);
  color: var(--color-text-light, #666);
  margin-bottom: var(--space-8);
}

/* ========== 头像占位符 ========== */
.avatar-placeholder {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background-color: var(--color-primary);
  color: var(--color-white);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-xs);
  font-weight: 600;
  flex-shrink: 0;
}

/* ========== 原有样式保留 ========== */
.detail-loading,
.detail-error {
  text-align: center;
  padding: var(--space-16) 0;
  font-size: var(--font-base);
  color: var(--color-text-light);
}

.detail-hero {
  display: flex;
  gap: var(--space-8);
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--space-12) var(--space-6);
}

.detail-hero-img {
  flex: 1;
  border-radius: var(--radius-md);
  overflow: hidden;
  max-height: 400px;
}

.detail-hero-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.detail-hero-img-placeholder {
  width: 100%;
  height: 100%;
  min-height: 300px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background-color: var(--color-bg-alt);
  color: var(--color-text-light);
  font-size: var(--font-lg);
  font-weight: 500;
  border-radius: var(--radius-md);
}

.detail-hero-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.detail-back {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  background: none;
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-text-light);
  padding: 0;
  margin-bottom: var(--space-2);
  cursor: pointer;
  transition: color 0.2s ease;
}

.detail-back:hover {
  color: var(--color-primary);
}

.detail-name {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-text);
  line-height: 1.3;
}

.detail-tags {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.detail-tag {
  padding: var(--space-1) var(--space-3);
  font-size: var(--font-xs);
  font-weight: 500;
  background-color: var(--color-bg-alt);
  color: var(--color-primary);
  border-radius: var(--radius-sm);
}

.detail-info-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4) 0;
}

.detail-info-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.detail-order-btn {
  margin-top: var(--space-4);
  padding: var(--space-3) var(--space-6);
  font-size: var(--font-base);
  font-weight: 600;
  border-radius: var(--radius-sm);
  background-color: var(--color-primary);
  color: var(--color-white);
  align-self: flex-start;
  cursor: pointer;
  transition: all 0.2s ease;
  border: none;
  outline: none;
}

.detail-order-btn:hover:not(.disabled) {
  background-color: var(--color-secondary);
  transform: translateY(-1px);
  box-shadow: var(--shadow-md);
}

.detail-content,
.detail-comments {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 var(--space-6) var(--space-8);
}

.detail-section-title {
  font-family: var(--font-heading);
  font-size: var(--font-xl);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-4);
  padding-bottom: var(--space-2);
  border-bottom: 1px solid var(--color-border);
}

.detail-desc {
  font-size: var(--font-base);
  color: var(--color-text-light);
  line-height: 1.8;
  white-space: pre-wrap;
}

.detail-comment {
  padding: var(--space-4) 0;
  border-bottom: 1px solid var(--color-border);
  transition: background-color 0.2s ease;
}

.detail-comment:hover {
  background-color: rgba(0, 0, 0, 0.01);
}

.detail-comment:last-child {
  border-bottom: none;
}

.detail-comment-user {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-text);
  margin-bottom: var(--space-2);
}

.detail-comment-avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  object-fit: cover;
}

.detail-comment-text {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  margin-bottom: var(--space-1);
  line-height: 1.6;
}

.detail-comment-time {
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

/* ========== 响应式设计增强 ========== */
@media (max-width: 1024px) {
  .detail-hero {
    gap: var(--space-6);
    padding: var(--space-10) var(--space-5);
  }

  .skeleton-header {
    flex-direction: column;
  }

  .skeleton-poster {
    width: 100%;
    max-width: 500px;
    height: 280px;
    margin: 0 auto;
  }
}

@media (max-width: 768px) {
  .detail-hero {
    flex-direction: column;
    padding: var(--space-6);
  }

  .detail-name {
    font-size: 22px;
  }

  .skeleton-poster {
    height: 240px;
  }

  .error-actions {
    flex-direction: column;
    width: 100%;
    max-width: 280px;
  }

  .btn-primary,
  .btn-secondary {
    width: 100%;
    justify-content: center;
  }

  .refresh-indicator {
    left: 0;
    right: 0;
    transform: translateY(-100%);
    border-radius: 0 0 0 0;
  }

  .refresh-indicator.active {
    transform: translateY(0);
  }
}

@media (max-width: 480px) {
  .detail-page {
    min-height: calc(100vh - 50px);
  }

  .detail-hero {
    padding: var(--space-4);
    gap: var(--space-4);
  }

  .detail-hero-img {
    max-height: 250px;
  }

  .detail-hero-img-placeholder {
    min-height: 200px;
    font-size: var(--font-base);
  }

  .detail-name {
    font-size: 18px;
  }

  .detail-info-list {
    gap: var(--space-2);
    padding: var(--space-3) 0;
  }

  .detail-info-item {
    font-size: var(--font-xs);
  }

  .detail-order-btn {
    width: 100%;
    justify-content: center;
    padding: var(--space-3) var(--space-4);
  }

  .error-icon {
    width: 48px !important;
    height: 48px !important;
  }

  .empty-icon {
    width: 36px !important;
    height: 36px !important;
  }
}

/* ========== 减少动画（无障碍） ========== */
@media (prefers-reduced-motion: reduce) {
  .skeleton-shimmer {
    animation-duration: 3s;
  }

  .fade-enter-active,
  .fade-leave-active {
    transition-duration: 0.01ms;
  }

  .refresh-icon.spinning {
    animation-duration: 3s;
  }

  .detail-order-btn:hover:not(.disabled),
  .btn-primary:hover {
    transform: none;
  }
}

/* ========== 统计数据区域 ========== */
.statistics-section {
  max-width: 1200px;
  margin: var(--space-8) auto 0;
  padding: 0 var(--space-6);
}

.statistics-title {
  font-size: var(--font-lg, 18px);
  font-weight: 600;
  color: var(--color-text, #333);
  margin-bottom: var(--space-4);
}

.statistics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--space-4);
}

.stat-card {
  background-color: white;
  border-radius: var(--radius-md);
  padding: var(--space-5);
  display: flex;
  align-items: center;
  gap: var(--space-4);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  transition: all 0.3s ease;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.stat-icon {
  color: var(--color-primary, #1890ff);
  flex-shrink: 0;
}

.stat-icon.stat-star {
  color: var(--color-secondary);
}

.stat-content {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat-value {
  font-size: var(--font-xl, 20px);
  font-weight: 700;
  color: var(--color-text, #333);
}

.stat-label {
  font-size: var(--font-sm, 14px);
  color: var(--color-text-light, #666);
}

.stat-score-text {
  font-size: var(--font-xs, 12px);
  color: var(--color-text-light, #999);
  margin-top: 2px;
}

/* ========== 评测区域 ========== */
.review-section {
  max-width: 1200px;
  margin: var(--space-12) auto 0;
  padding: 0 var(--space-6);
}

.review-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-6);
}

.write-review-btn {
  padding: var(--space-2) var(--space-5);
  background-color: var(--color-primary, #1890ff);
  color: white;
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-sm, 14px);
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.write-review-btn:hover {
  background-color: var(--color-secondary, #40a9ff);
  transform: translateY(-1px);
}

/* 用户评测卡片 */
.user-review-card {
  background-color: var(--color-bg-alt);
  border-left: 4px solid var(--color-primary);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  margin-bottom: var(--space-6);
}

.review-user-info {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.review-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
}

.avatar-placeholder.small {
  width: 36px;
  height: 36px;
  font-size: 14px;
}

.review-username {
  font-weight: 600;
  color: var(--color-text, #333);
  margin-right: auto;
}

.review-content {
  color: var(--color-text, #555);
  line-height: 1.6;
  margin-bottom: var(--space-3);
}

.review-images {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.review-image {
  width: 100%;
  height: 120px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: transform 0.2s ease;
}

.review-image:hover {
  transform: scale(1.05);
}

.review-actions {
  display: flex;
  justify-content: flex-end;
}

.delete-review-btn {
  padding: var(--space-1) var(--space-3);
  background-color: transparent;
  color: var(--color-danger-dark);
  border: 1px solid var(--color-danger-dark);
  border-radius: var(--radius-sm);
  font-size: var(--font-xs, 12px);
  cursor: pointer;
  transition: all 0.2s ease;
}

.delete-review-btn:hover {
  background-color: var(--color-danger-dark);
  color: white;
}

/* 评测表单 */
.review-form-card {
  background-color: white;
  border: 2px dashed var(--color-border, #d9d9d9);
  border-radius: var(--radius-md);
  padding: var(--space-6);
  margin-bottom: var(--space-6);
}

.form-title {
  font-size: var(--font-lg, 18px);
  font-weight: 600;
  color: var(--color-text, #333);
  margin-bottom: var(--space-5);
}

.form-group {
  margin-bottom: var(--space-4);
}

.form-group label {
  display: block;
  font-size: var(--font-sm, 14px);
  font-weight: 500;
  color: var(--color-text, #555);
  margin-bottom: var(--space-2);
}

.form-group .required {
  color: var(--color-danger, #ff4d4f);
  font-weight: normal;
}

.form-group .optional {
  color: var(--color-text-light, #999);
  font-weight: normal;
  font-size: var(--font-xs, 12px);
}

.score-hint {
  display: inline-block;
  margin-left: var(--space-3);
  font-size: var(--font-sm, 14px);
  color: var(--color-primary, #a6baae);
  font-weight: 500;
}

.form-input {
  width: 100%;
  padding: var(--space-3);
  border: 1px solid var(--color-border, #d9d9d9);
  border-radius: var(--radius-md);
  font-size: var(--font-base, 16px);
  transition: border-color 0.2s ease;
}

.form-input:focus {
  outline: none;
  border-color: var(--color-primary, #a6baae);
  box-shadow: 0 0 0 2px rgba(166, 186, 174, 0.1);
}

.form-group textarea {
  width: 100%;
  padding: var(--space-3);
  border: 1px solid var(--color-border, #d9d9d9);
  border-radius: var(--radius-md);
  font-size: var(--font-base, 16px);
  resize: vertical;
  font-family: inherit;
  transition: border-color 0.2s ease;
}

.form-group textarea:focus {
  outline: none;
  border-color: var(--color-primary, #a6baae);
  box-shadow: 0 0 0 2px rgba(166, 186, 174, 0.1);
}

.form-tip {
  background-color: var(--color-bg-alt, #f5f5f5);
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-sm);
  margin-bottom: var(--space-4);
}

.form-tip p {
  font-size: var(--font-xs, 12px);
  color: var(--color-text-light, #666);
  line-height: 1.6;
  margin: 0;
}

.form-tip p + p {
  margin-top: var(--space-1);
}

.char-count {
  display: block;
  text-align: right;
  font-size: var(--font-xs, 12px);
  color: var(--color-text-light, #999);
  margin-top: var(--space-1);
}

.image-upload-area {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
}

.upload-btn {
  padding: var(--space-2) var(--space-4);
  background-color: white;
  color: var(--color-primary, #1890ff);
  border: 1px dashed var(--color-primary, #1890ff);
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: var(--font-sm, 14px);
  transition: all 0.2s ease;
}

.upload-btn:hover {
  background-color: var(--color-bg-alt);
}

.image-previews {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.preview-item {
  position: relative;
  width: 80px;
  height: 80px;
}

.preview-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: var(--radius-sm);
}

.remove-img-btn {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background-color: var(--color-danger);
  color: white;
  border: none;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.2s ease;
}

.remove-img-btn:hover {
  transform: scale(1.1);
}

.form-actions {
  display: flex;
  gap: var(--space-3);
  justify-content: flex-end;
  margin-top: var(--space-5);
}

.submit-review-btn {
  padding: var(--space-3) var(--space-6);
  background-color: var(--color-primary, #1890ff);
  color: white;
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-base, 16px);
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.submit-review-btn:hover:not(:disabled) {
  background-color: var(--color-secondary, #40a9ff);
  transform: translateY(-1px);
}

.submit-review-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.cancel-btn {
  padding: var(--space-3) var(--space-6);
  background-color: transparent;
  color: var(--color-text-light, #666);
  border: 1px solid var(--color-border, #d9d9d9);
  border-radius: var(--radius-md);
  font-size: var(--font-base, 16px);
  cursor: pointer;
  transition: all 0.2s ease;
}

.cancel-btn:hover {
  border-color: var(--color-text-light, #999);
  color: var(--color-text, #333);
}

/* 评测列表 */
.review-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.review-item {
  background-color: white;
  border-radius: var(--radius-md);
  padding: var(--space-5);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  transition: all 0.2s ease;
}

.review-item:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.review-item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-3);
}

.reviewer-info {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.reviewer-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.reviewer-name {
  font-weight: 600;
  color: var(--color-text, #333);
  font-size: var(--font-sm, 14px);
}

.review-time {
  font-size: var(--font-xs, 12px);
  color: var(--color-text-light, #999);
}

.review-item-content {
  color: var(--color-text, #555);
  line-height: 1.6;
  margin-bottom: var(--space-3);
}

.review-item-footer {
  display: flex;
  justify-content: flex-end;
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-bg-alt, #f5f5f5);
}

.like-btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-xs, 12px);
  color: var(--color-text-light, #999);
  cursor: pointer;
  background: none;
  border: none;
  padding: 0;
  transition: color 0.2s ease, transform 0.15s ease;
}

.like-btn:hover {
  color: var(--color-danger, #d9363e);
  transform: scale(1.08);
}

.like-btn.liked {
  color: #e05a5a;
  font-weight: 600;
}

.like-btn:active {
  transform: scale(0.92);
}

/* 加载更多 */
.load-more-wrapper {
  text-align: center;
  margin-top: var(--space-6);
}

.load-more-btn {
  padding: var(--space-3) var(--space-8);
  background-color: white;
  color: var(--color-primary, #1890ff);
  border: 1px solid var(--color-primary, #1890ff);
  border-radius: var(--radius-md);
  font-size: var(--font-sm, 14px);
  cursor: pointer;
  transition: all 0.2s ease;
}

.load-more-btn:hover:not(:disabled) {
  background-color: var(--color-bg-alt);
}

.load-more-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 空状态 */
.empty-reviews {
  text-align: center;
  padding: var(--space-16) var(--space-6);
  color: var(--color-text-light, #999);
}

.empty-reviews p {
  font-size: var(--font-base, 16px);
  margin: var(--space-4) 0 0;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .statistics-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .review-header {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-3);
  }

  .write-review-btn {
    width: 100%;
  }

  .form-actions {
    flex-direction: column;
  }

  .submit-review-btn,
  .cancel-btn {
    width: 100%;
  }
}
</style>
