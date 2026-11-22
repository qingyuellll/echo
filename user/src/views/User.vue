<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, parseImages, getImageUrl, isLoggedIn } from '../utils/api.js'

const route = useRoute()
const router = useRouter()
const userId = ref(route.params.id)
const user = ref(null)
const reviews = ref([])
const loading = ref(true)
const isFollowing = ref(false)
const followLoading = ref(false)
const current = ref(1)

async function loadUser() {
  loading.value = true
  try {
    const res = await api.getUserProfile(userId.value)

    console.log('[User] API响应:', { res, userId: userId.value })

    // 检查后端返回的错误信息
    if (res && res.errorCode === 1 && res.msg) {
      console.warn('[User] 后端返回错误:', res.msg)
      user.value = null
      return
    }

    if (res && res.data != null) {
      const profileData = res.data
      const userData = profileData.user

      if (!userData) {
        console.warn('[User] 后端返回数据缺少user字段，原始数据:', profileData)
        user.value = null
        loading.value = false
        return
      }

      console.log('[User] 用户原始数据:', {
        id: userData.id,
        nickName: userData.nickName,
        icon: userData.icon,
        allKeys: Object.keys(userData)
      })

      user.value = {
        id: userData.id,
        nickName: userData.nickName || ('用户' + userId.value),
        icon: userData.icon || '/default-avatar.png',
        intro: userData.intro || ''
      }

      if (userData.followInfo) {
        user.value.followerCount = userData.followInfo.fans || 0
        user.value.followingCount = userData.followInfo.followee || 0
      }

      console.log('用户信息加载成功:', user.value)
    } else {
      console.warn('[User] 用户数据为空或格式异常')
      user.value = null
    }
  } catch (e) {
    console.error('加载用户信息失败:', e)
    user.value = null
  } finally {
    loading.value = false
  }
}

async function loadReviews() {
  try {
    const res = await api.getUserReviews(userId.value, current.value)
    reviews.value = (res && res.data) || []
  } catch {
    reviews.value = []
  }
}

async function checkFollow() {
  if (!isLoggedIn()) return
  try {
    const res = await api.checkFollow(userId.value)
    console.log('关注状态:', res)
    isFollowing.value = !!(res && (res.data === true || res.data?.isFollow || res.data?.isFollowed))
  } catch (e) {
    console.error('获取关注状态失败:', e)
    isFollowing.value = false
  }
}

async function toggleFollow() {
  if (!isLoggedIn()) {
    await router.push(`/login?redirect=/user/${userId.value}`)
    return
  }

  followLoading.value = true

  try {
    const newFollowStatus = !isFollowing.value

    // 调用统一的关注接口，传递布尔值参数
    const res = await api.followUser(userId.value, newFollowStatus)

    console.log('关注操作响应:', res)

    // 更新本地状态
    isFollowing.value = newFollowStatus

    // 更新粉丝数
    if (user.value && user.value.followerCount !== undefined) {
      user.value.followerCount += (newFollowStatus ? 1 : -1)
    }
  } catch (e) {
    console.error('关注操作失败:', e)
    alert(e.message || '操作失败，请重试')
  } finally {
    followLoading.value = false
  }
}

function goBack() {
  router.back()
}

// 调试：强制刷新用户缓存
async function forceRefreshUser() {
  if (!confirm('确定要强制刷新用户信息吗？（将清除本地缓存）')) return

  loading.value = true
  try {
    // 清除可能的浏览器缓存
    const cacheKey = `user_cache_${userId.value}`
    localStorage.removeItem(cacheKey)

    // 重新请求（后端会重新查询数据库）
    await loadUser()

    if (user.value) {
      alert('用户信息已刷新！')
    } else {
      alert('该用户确实不存在')
    }
  } catch (e) {
    console.error('刷新失败:', e)
    alert('刷新失败，请稍后重试')
  } finally {
    loading.value = false
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

onMounted(() => {
  loadUser()
  loadReviews()
  checkFollow()
})
</script>

<template>
  <div class="user-page">
    <div v-if="loading" class="user-loading">加载中...</div>

    <template v-else-if="user">
      <div class="user-inner">
        <!-- 返回按钮 -->
        <button class="user-back-btn" @click="goBack" title="返回上一页">
          ← 返回
        </button>

        <!-- User Info Card -->
        <div class="user-card">
          <div class="user-avatar-section">
            <img
              v-if="user.icon"
              :src="getImageUrl(user.icon)"
              class="user-avatar"
              alt=""
            />
            <div v-else class="user-avatar-placeholder" />
          </div>
          <h1 class="user-name">{{ user.nickName }}</h1>
          <p v-if="user.followerCount !== undefined" class="user-stats">
            <span>{{ user.followerCount }} 粉丝</span>
            <span>·</span>
            <span>{{ user.followingCount }} 关注</span>
          </p>
          <p v-if="user.intro" class="user-intro">{{ user.intro }}</p>
          <button
            v-if="isLoggedIn()"
            :class="['user-follow-btn', { following: isFollowing }]"
            :disabled="followLoading"
            @click="toggleFollow"
          >
            {{ followLoading ? '处理中...' : isFollowing ? '已关注' : '+ 关注' }}
          </button>
        </div>

        <!-- Reviews Section -->
        <div class="user-reviews-section">
          <h2 class="user-section-title">TA的评测</h2>
          <div v-if="!reviews.length" class="user-empty">暂无评测</div>
          <div v-else class="user-reviews-list">
            <router-link
              v-for="review in reviews"
              :key="review.id"
              :to="`/review/${review.id}`"
              class="user-review-item"
            >
              <h3 class="user-review-title">{{ review.title }}</h3>
              <p class="user-review-content">{{ review.content }}</p>
              <div class="user-review-meta">
                <span>{{ review.liked || 0 }} 赞 · {{ review.comments || 0 }} 评论</span>
                <time>{{ formatTime(review.createTime) }}</time>
              </div>
            </router-link>
          </div>
        </div>
      </div>
    </template>

    <div v-else class="user-error">
      <p class="error-text">用户不存在</p>
      <button class="refresh-btn" @click="forceRefreshUser">
        刷新
      </button>
    </div>
  </div>
</template>

<style scoped>
.user-page {
  padding: var(--space-12) var(--space-6);
  background-color: var(--color-bg);
  min-height: calc(100vh - 60px);
}

.user-inner {
  max-width: 700px;
  margin: 0 auto;
}

.user-loading,
.user-error {
  text-align: center;
  padding: var(--space-12) 0;
  font-size: var(--font-base);
  color: var(--color-text-light);
}

.error-text {
  margin-bottom: var(--space-4);
}

.refresh-btn {
  padding: var(--space-2) var(--space-4);
  font-size: var(--font-sm);
  color: var(--color-primary);
  background-color: transparent;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.refresh-btn:hover {
  background-color: var(--color-primary);
  color: var(--color-white);
}

.user-back-btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-4);
  margin-bottom: var(--space-4);
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-primary);
  background-color: transparent;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.user-back-btn:hover {
  background-color: var(--color-primary-alpha);
  color: var(--color-primary-dark);
  border-color: var(--color-primary-dark);
}

.user-card {
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  padding: var(--space-8);
  text-align: center;
  margin-bottom: var(--space-6);
}

.user-avatar-section {
  display: flex;
  justify-content: center;
  margin-bottom: var(--space-4);
}

.user-avatar {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  object-fit: cover;
}

.user-avatar-placeholder {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background-color: var(--color-bg-alt);
}

.user-name {
  font-family: var(--font-heading);
  font-size: var(--font-xl);
  font-weight: 700;
  color: var(--color-text);
  margin-bottom: var(--space-2);
}

.user-stats {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  margin-bottom: var(--space-3);
}

.user-intro {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  margin-bottom: var(--space-4);
}

.user-follow-btn {
  padding: var(--space-2) var(--space-6);
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: var(--radius-full);
  background-color: var(--color-primary);
  color: var(--color-white);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.user-follow-btn:hover:not(:disabled) {
  background-color: var(--color-secondary);
}

.user-follow-btn.following {
  background-color: var(--color-bg-alt);
  color: var(--color-text);
}

.user-follow-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.user-reviews-section {
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  padding: var(--space-6);
}

.user-section-title {
  font-family: var(--font-heading);
  font-size: var(--font-lg);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-4);
}

.user-empty {
  text-align: center;
  padding: var(--space-8) 0;
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.user-reviews-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.user-review-item {
  display: block;
  padding: var(--space-4);
  border-radius: var(--radius-sm);
  background-color: var(--color-bg);
  text-decoration: none;
  transition: all var(--transition-fast);
}

.user-review-item:hover {
  background-color: var(--color-bg-alt);
}

.user-review-title {
  font-family: var(--font-heading);
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-2);
}

.user-review-content {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  line-height: 1.6;
  margin-bottom: var(--space-2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.user-review-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--font-xs);
  color: var(--color-text-light);
}
</style>
