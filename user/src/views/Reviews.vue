<script setup>
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { api, parseImages, getImageUrl, isLoggedIn, getUserId } from '../utils/api.js'
import { ChevronLeft } from 'lucide-vue-next'

const router = useRouter()

const activeTab = ref('recommended')
const reviews = ref([])
const loading = ref(false)
const loadingMore = ref(false)
const hasMore = ref(true)

const current = ref(1)
const total = ref(0)
const nextCursor = ref('')

const searchKeyword = ref('')
const searchMode = ref(false)

let scrollObserver = null

function goBack() {
  router.back()
}

function goToUser(userId, event) {
  if (event) event.stopPropagation()
  if (userId) router.push(`/user/${userId}`)
}

function handleAvatarError(event, review) {
  console.error('[Reviews] 头像加载失败:', review.userIcon)
  event.target.style.display = 'none'
  if (review) {
    review.userIcon = null
  }
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function getSeenReviewIds() {
  try {
    const seen = localStorage.getItem('seen_review_ids')
    const ids = seen ? JSON.parse(seen) : []

    // 仅保留最近500条记录（防止localStorage无限增长）
    if (ids.length > 500) {
      const trimmed = ids.slice(-500)
      localStorage.setItem('seen_review_ids', JSON.stringify(trimmed))
      return trimmed
    }

    return ids
  } catch {
    return []
  }
}

function markReviewAsSeen(reviewId) {
  if (!reviewId) return
  try {
    const seen = getSeenReviewIds()
    if (!seen.includes(reviewId)) {
      seen.push(reviewId)
      // 自动清理超过7天的旧记录（基于数组位置简化处理）
      if (seen.length > 500) {
        seen.splice(0, seen.length - 500)
      }
      localStorage.setItem('seen_review_ids', JSON.stringify(seen))
    }
  } catch (e) {
    console.error('Failed to save seen review ID:', e)
  }
}

/**
 * 重置已读记录（用户主动触发或检测到异常时使用）
 */
function clearSeenReviewIds() {
  try {
    localStorage.removeItem('seen_review_ids')
    console.log('[Reviews] 已清空已读评测记录')
  } catch (e) {
    console.error('Failed to clear seen review IDs:', e)
  }
}

async function loadReviews(reset = true, isPagination = false) {
  if (loading.value || loadingMore.value) return

  if (isPagination) {
    loading.value = true
    try {
      const res = await api.getHotReviews(current.value, 10)
      reviews.value = res.data || []
      total.value = res.total || 0
      hasMore.value = current.value * 10 < total.value
    } catch (error) {
      console.error('翻页加载失败:', error)
    } finally {
      loading.value = false
    }
  } else if (reset) {
    const res = await fetchInitialData()
    if (res && res.data) {
      reviews.value = res.data
      total.value = res.total || 0
      nextCursor.value = res.nextCursor || ''
      hasMore.value = activeTab.value === 'hot'
        ? current.value * 10 < (res.total || 0)
        : !!res.nextCursor
    }
  }
}

async function loadRecommendedReviews(reset = true) {
  try {
    let followData = []
    let randomData = []
    let allReviews = []

    // 旧代码从 localStorage 读 user.id——但登录信息存在 sessionStorage，
    // 读不到 → 关注流永远不加载。改用 getUserId()（sessionStorage 优先，缺失回源 /user/me）
    const userId = await getUserId()

    if (userId && reset) {
      try {
        const followRes = await api.getFollowFeed('')
        followData = followRes.records || []
      } catch (e) {
        console.error('Failed to load follow feed:', e)
        followData = []
      }
    }

    const seenIds = getSeenReviewIds()

    const randomRes = await api.getRecommendedReviews(nextCursor.value)
    randomData = randomRes.records || []
    nextCursor.value = randomRes.nextCursor || ''

    const followFiltered = followData.filter(r => !seenIds.includes(r.id))
    const randomFiltered = randomData.filter(r => !seenIds.includes(r.id))

    if (reset) {
      allReviews = [...followFiltered, ...randomFiltered]
    } else {
      allReviews = [...randomFiltered]
    }

    const uniqueReviews = []
    const seenSet = new Set(reviews.value.map(r => r.id))
    for (const review of allReviews) {
      if (!seenSet.has(review.id)) {
        uniqueReviews.push(review)
        seenSet.add(review.id)
      }
    }

    if (reset) {
      reviews.value = uniqueReviews.slice(0, 15)
    } else {
      reviews.value.push(...uniqueReviews.slice(0, 10))
    }

    hasMore.value = !!nextCursor.value || uniqueReviews.length > (reset ? 15 : 10)

    return { data: reviews.value, nextCursor: nextCursor.value }
  } catch (error) {
    console.error('Failed to load recommended reviews:', error)
    return { data: [], nextCursor: '' }
  }
}

async function loadMore() {
  if (loadingMore.value || !hasMore.value || loading.value) return

  console.log('开始加载更多 - 当前tab:', activeTab.value)

  try {
    const res = await fetchMoreData()
    console.log('获取到新数据:', res?.records?.length || 0, '条')

    if (res && res.records && res.records.length > 0) {
      reviews.value.push(...res.records)
      nextCursor.value = res.nextCursor || ''
      hasMore.value = !!res.nextCursor
      console.log('加载完成，总数:', reviews.value.length, 'hasMore:', hasMore.value)
    } else {
      hasMore.value = false
      console.log('没有更多数据了')
    }
  } catch (error) {
    console.error('加载更多失败:', error)
  } finally {
    loadingMore.value = false
  }
}

async function fetchMoreData() {
  switch (activeTab.value) {
    case 'latest':
      return await api.getLatestReviews(nextCursor.value)

    case 'latestComment':
      return await api.getLatestCommentedReviews(nextCursor.value)

    case 'recommended':
      return await api.getRecommendedReviews(nextCursor.value)

    default:
      return { records: [], nextCursor: '' }
  }
}

async function fetchInitialData() {
  switch (activeTab.value) {
    case 'hot':
      const hotRes = await api.getHotReviews(current.value, 10)
      return { data: hotRes.data || [], total: hotRes.total || 0 }

    case 'latest':
      const latestRes = await api.getLatestReviews('')
      return { data: latestRes.records || [], nextCursor: latestRes.nextCursor }

    case 'latestComment':
      const commentRes = await api.getLatestCommentedReviews('')
      return { data: commentRes.records || [], nextCursor: commentRes.nextCursor }

    case 'recommended':
      return await loadRecommendedReviews(true)

    default:
      return { data: [], total: 0 }
  }
}

async function switchTab(tab) {
  if (tab === activeTab.value) return

  console.log('切换标签:', activeTab.value, '->', tab)

  activeTab.value = tab
  searchMode.value = false
  searchKeyword.value = ''

  reviews.value = []
  current.value = 1
  nextCursor.value = ''
  hasMore.value = true
  loading.value = false
  loadingMore.value = false

  await nextTick()
  setupScrollObserver()

  window.scrollTo({ top: 0, behavior: 'smooth' })

  loading.value = true
  try {
    const res = await fetchInitialData()
    if (res && res.data) {
      reviews.value = res.data
      total.value = res.total || 0
      nextCursor.value = res.nextCursor || ''
      hasMore.value = tab === 'hot'
        ? current.value * 10 < (res.total || 0)
        : !!res.nextCursor
    }
  } catch (error) {
    console.error('加载失败:', error)
    reviews.value = []
    hasMore.value = false
  } finally {
    loading.value = false
  }
}

async function search() {
  searchMode.value = true
  await loadSearchResults()
}

async function clearSearch() {
  searchKeyword.value = ''
  searchMode.value = false
  await loadReviews(true, false)
}

async function loadSearchResults() {
  loading.value = true
  try {
    const res = await api.searchReviews(searchKeyword.value.trim(), current.value)
    reviews.value = res.data || []
    total.value = res.total || 0
    hasMore.value = current.value * 10 < total.value
  } catch {
    reviews.value = []
    hasMore.value = false
  } finally {
    loading.value = false
  }
}

/**
 * 搜索模式下的翻页功能（解决"仅首屏无翻页"问题）
 */
async function searchNextPage() {
  if (loadingMore.value || !hasMore.value || !searchMode.value) return

  loadingMore.value = true
  try {
    current.value++
    const res = await api.searchReviews(searchKeyword.value.trim(), current.value)

    if (res && res.data && res.data.length > 0) {
      reviews.value.push(...res.data)
      hasMore.value = current.value * 10 < total.value
    } else {
      hasMore.value = false
      current.value--
    }
  } catch (error) {
    console.error('搜索翻页失败:', error)
    current.value--
  } finally {
    loadingMore.value = false
  }
}

async function prevPage() {
  if (current.value > 1) {
    current.value--
    await loadReviews(false, true)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
}

async function nextPage() {
  if (activeTab.value === 'hot' && current.value * 10 < total.value) {
    current.value++
    await loadReviews(false, true)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
}

function setupScrollObserver() {
  if (scrollObserver) {
    scrollObserver.disconnect()
    scrollObserver = null
  }

  // 热门标签使用传统分页，不需要滚动加载；其他模式（含搜索）使用无限滚动
  if (activeTab.value === 'hot' && !searchMode.value) {
    return
  }

  setTimeout(() => {
    const sentinel = document.querySelector('.scroll-sentinel')
    if (!sentinel) {
      setTimeout(setupScrollObserver, 500)
      return
    }

    scrollObserver = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && hasMore.value && !loadingMore.value && !loading.value) {
          loadMore()
        }
      },
      {
        rootMargin: '400px',
        threshold: 0
      }
    )

    scrollObserver.observe(sentinel)
  }, 100)
}

watch(activeTab, async () => {
  await nextTick()
  setupScrollObserver()
})

onMounted(async () => {
  console.log('页面初始化 - 当前tab:', activeTab.value)

  loading.value = true
  try {
    const res = await fetchInitialData()
    if (res && res.data) {
      reviews.value = res.data
      total.value = res.total || 0
      nextCursor.value = res.nextCursor || ''
      hasMore.value = activeTab.value === 'hot'
        ? current.value * 10 < (res.total || 0)
        : !!res.nextCursor
    }
  } catch (error) {
    console.error('初始化加载失败:', error)
    reviews.value = []
    hasMore.value = false
  } finally {
    loading.value = false
  }

  setTimeout(() => {
    setupScrollObserver()
  }, 500)
})

onUnmounted(() => {
  if (scrollObserver) {
    scrollObserver.disconnect()
  }
})
</script>

<template>
  <div class="reviews-page">
    <div class="reviews-inner">
      <!-- 返回按钮 -->
      <button class="reviews-back-btn" @click="goBack">
        <ChevronLeft :size="20" />
        <span>返回</span>
      </button>

      <!-- 标题和发布按钮 -->
      <div class="reviews-header">
        <h1 class="reviews-title">评测广场</h1>
        <router-link v-if="isLoggedIn()" to="/reviews/publish" class="reviews-publish-btn">
          发布评测
        </router-link>
      </div>

      <!-- 分类标签 -->
      <div v-if="!searchMode" class="reviews-tabs">
        <button
          :class="['tab-btn', { active: activeTab === 'recommended' }]"
          @click="switchTab('recommended')"
        >
          推荐
        </button>
        <button
          :class="['tab-btn', { active: activeTab === 'latest' }]"
          @click="switchTab('latest')"
        >
          最新发帖
        </button>
        <button
          :class="['tab-btn', { active: activeTab === 'latestComment' }]"
          @click="switchTab('latestComment')"
        >
          最新评论
        </button>
        <button
          :class="['tab-btn', { active: activeTab === 'hot' }]"
          @click="switchTab('hot')"
        >
          热门
        </button>
      </div>

      <!-- 搜索栏 -->
      <div class="reviews-search">
        <input
          v-model="searchKeyword"
          type="text"
          placeholder="搜索评测标题或内容..."
          @keyup.enter="search"
        />
        <button class="reviews-search-btn" @click="search">搜索</button>
        <button v-if="searchMode" class="reviews-clear-btn" @click="clearSearch">清除</button>
      </div>

      <!-- 加载状态 -->
      <div v-if="loading" class="reviews-grid">
        <div v-for="i in 6" :key="i" class="review-card-placeholder" />
      </div>

      <!-- 空状态 -->
      <div v-else-if="!reviews.length" class="reviews-empty">
        {{ searchMode ? '未找到相关评测' : '暂无评测内容' }}
      </div>

      <!-- 评测列表 -->
      <div v-else class="reviews-grid">
        <router-link
          v-for="review in reviews"
          :key="review.id"
          :to="`/review/${review.id}`"
          class="review-card"
          @click="markReviewAsSeen(review.id)"
        >
          <div v-if="review.images && parseImages(review.images).length" class="review-card-img">
            <img
              :src="getImageUrl(parseImages(review.images)[0])"
              :alt="review.title"
              loading="lazy"
            />
          </div>
          <div class="review-card-body">
            <div class="review-card-author" @click="goToUser(review.userId, $event)">
              <img
                v-if="review.userIcon && review.userIcon !== '/default-avatar.png'"
                :src="getImageUrl(review.userIcon)"
                class="review-card-avatar"
                alt=""
                @error="handleAvatarError($event, review)"
              />
              <div v-else class="review-card-avatar-placeholder">
                {{ (review.userName || '匿')?.charAt(0) }}
              </div>
              <span class="review-card-name">{{ review.userName || '匿名用户' }}</span>
            </div>
            <h3 class="review-card-title">{{ review.title }}</h3>
            <p class="review-card-content">{{ review.content }}</p>
            <div class="review-card-meta">
              <span class="review-card-stats">
                <span class="review-card-likes">★ {{ review.liked || 0 }} 点亮</span>
                <span class="review-card-comments">{{ review.comments || 0 }} 评论</span>
              </span>
              <time class="review-card-time">{{ formatTime(review.createTime) }}</time>
            </div>
          </div>
        </router-link>

        <!-- 滚动加载触发器（用于无限滚动，搜索模式也支持） -->
        <div v-if="hasMore && (activeTab !== 'hot' || searchMode)" class="scroll-sentinel" />

        <!-- 加载更多指示器 -->
        <div v-if="loadingMore" class="loading-more">
          <span>加载更多...</span>
        </div>
      </div>

      <!-- 分页控件（仅热门模式） -->
      <div v-if="activeTab === 'hot' && total > 10" class="reviews-pagination">
        <button :disabled="current <= 1" @click="prevPage">上一页</button>
        <span>{{ current }} / {{ Math.ceil(total / 10) || 1 }}</span>
        <button :disabled="current * 10 >= total" @click="nextPage">下一页</button>
      </div>

      <!-- 没有更多数据提示 -->
      <div v-if="!hasMore && reviews.length > 0 && activeTab !== 'hot'" class="no-more">
        — 已经到底了 —
      </div>
    </div>
  </div>
</template>

<style scoped>
.reviews-page {
  padding: var(--space-12) var(--space-6);
  background-color: var(--color-bg);
  min-height: calc(100vh - 60px);
}

.reviews-inner {
  max-width: 900px;
  margin: 0 auto;
}

/* 返回按钮样式 */
.reviews-back-btn {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  font-size: var(--font-base);
  font-weight: 500;
  color: var(--color-text-light);
  background-color: transparent;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  margin-bottom: var(--space-6);
  width: fit-content;
}

.reviews-back-btn:hover {
  color: var(--color-text);
  background-color: var(--color-bg-alt);
  border-color: var(--color-primary);
}

.reviews-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-8);
}

.reviews-title {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-text);
}

.reviews-publish-btn {
  padding: var(--space-2) var(--space-4);
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: var(--radius-sm);
  background-color: var(--color-primary);
  color: var(--color-white);
  text-decoration: none;
}

.reviews-publish-btn:hover {
  background-color: var(--color-secondary);
}

/* 分类标签样式 */
.reviews-tabs {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-8);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-md);
  padding: var(--space-2);
}

.tab-btn {
  flex: 1;
  padding: var(--space-3) var(--space-4);
  font-size: var(--font-base);
  font-weight: 500;
  color: var(--color-text-light);
  background-color: transparent;
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.tab-btn:hover {
  color: var(--color-text);
  background-color: var(--color-white);
}

.tab-btn.active {
  color: var(--color-primary);
  background-color: var(--color-white);
  box-shadow: var(--shadow-sm);
  font-weight: 600;
}

/* 搜索栏 */
.reviews-search {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-6);
  justify-content: center;
}

.reviews-search input {
  width: 320px;
  height: 40px;
  padding: 0 var(--space-3);
  font-size: var(--font-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-white);
  color: var(--color-text);
}

.reviews-search input:focus {
  outline: none;
  border-color: var(--color-primary);
}

.reviews-search-btn {
  height: 40px;
  padding: 0 var(--space-4);
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: var(--radius-sm);
  background-color: var(--color-primary);
  color: var(--color-white);
  cursor: pointer;
}

.reviews-search-btn:hover {
  background-color: var(--color-secondary);
}

.reviews-clear-btn {
  height: 40px;
  padding: 0 var(--space-4);
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-alt);
  color: var(--color-text);
  cursor: pointer;
}

.reviews-clear-btn:hover {
  background-color: var(--color-border);
}

/* 评测卡片网格 */
.reviews-grid {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
  position: relative;
}

.reviews-empty {
  text-align: center;
  padding: var(--space-12) 0;
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.review-card {
  display: block;
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
  transition: all var(--transition-base);
  text-decoration: none;
}

.review-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}

.review-card-img {
  aspect-ratio: 16 / 6;
  overflow: hidden;
  background-color: var(--color-bg-alt);
}

.review-card-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.review-card-body {
  padding: var(--space-6);
}

.review-card-author {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
  cursor: pointer;
  transition: opacity var(--transition-fast);
}

.review-card-author:hover {
  opacity: 0.8;
}

.review-card-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  object-fit: cover;
}

.review-card-avatar-placeholder {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background-color: var(--color-primary);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
}

.review-card-name {
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-text);
}

.review-card-title {
  font-family: var(--font-heading);
  font-size: var(--font-lg);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-3);
}

.review-card-content {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  line-height: 1.6;
  margin-bottom: var(--space-4);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.review-card-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.review-card-stats {
  display: flex;
  gap: var(--space-4);
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

.review-card-time {
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

/* 骨架屏占位符 */
.review-card-placeholder {
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  height: 180px;
  animation: shimmer 1.5s infinite;
}

@keyframes shimmer {
  0% { opacity: 0.6; }
  50% { opacity: 0.9; }
  100% { opacity: 0.6; }
}

/* 分页控件 */
.reviews-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-4);
  margin-top: var(--space-8);
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.reviews-pagination button {
  padding: var(--space-2) var(--space-4);
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-alt);
  color: var(--color-text);
}

.reviews-pagination button:hover:not(:disabled) {
  background-color: var(--color-primary);
  color: var(--color-white);
}

.reviews-pagination button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

/* 无限滚动相关 */
.scroll-sentinel {
  height: 50px;
  width: 100%;
  background-color: transparent;
  /* 调试时可取消注释下面这行
  border: 2px dashed red;
  */
}

.loading-more {
  text-align: center;
  padding: var(--space-6) 0;
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.no-more {
  text-align: center;
  padding: var(--space-8) 0;
  font-size: var(--font-xs);
  color: var(--color-text-light);
}
</style>
