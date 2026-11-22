<script setup>
import GuideReviewInsights from '../components/common/GuideReviewInsights.vue'
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, parseImages, getImageUrl, isLoggedIn } from '../utils/api.js'
import { ChevronLeft } from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()
const reviewId = ref(route.params.id)
const review = ref(null)
const comments = ref([])
const loading = ref(true)
const commentText = ref('')
const submitting = ref(false)
const replyingTo = ref(null)  // 当前回复的目标评论ID（null表示一级评论）

function goBack() {
  router.back()
}

async function loadReview() {
  loading.value = true
  try {
    const res = await api.getReviewDetail(reviewId.value)
    review.value = res.data || res
  } catch {
    review.value = null
  } finally {
    loading.value = false
  }
}

async function loadComments() {
  try {
    const res = await api.getReviewComments(reviewId.value, 1)
    comments.value = (res && res.data) || []
  } catch {
    comments.value = []
  }
}

async function handleLike() {
  if (!isLoggedIn()) {
    await router.push(`/login?redirect=/review/${reviewId.value}`)
    return
  }
  try {
    await api.likeReview(reviewId.value)
    if (review.value.isLike) {
      review.value.liked = Math.max(0, (review.value.liked || 1) - 1)
      review.value.isLike = false
    } else {
      review.value.liked = (review.value.liked || 0) + 1
      review.value.isLike = true
    }
  } catch {
    // ignore
  }
}

function goToUser(userId) {
  if (userId) {
    router.push(`/user/${userId}`)
  }
}

function handleAvatarError(event) {
  console.error('[ReviewDetail] 头像加载失败:', review.value?.userIcon)
  event.target.style.display = 'none'
  if (review.value) {
    review.value.userIcon = null
  }
}

function handleCommentAvatarError(event, comment) {
  console.error('[ReviewDetail] 评论头像加载失败:', comment.icon)
  event.target.style.display = 'none'
  if (comment) {
    comment.icon = '/default-avatar.png'
  }
}

function getAnswerUserName(comment) {
  if (!comment || !comment.answerId) return '用户'

  const allComments = [...comments.value]
  comments.value.forEach(c => {
    if (c.replies) {
      allComments.push(...c.replies)
    }
  })

  const answeredComment = allComments.find(c => c.userId === comment.answerId)
  return answeredComment?.userName || ('用户' + comment.answerId)
}

async function submitComment() {
  if (!commentText.value.trim()) {
    alert('请输入评论内容')
    return
  }

  if (!isLoggedIn()) {
    await router.push(`/login?redirect=/review/${reviewId.value}`)
    return
  }

  submitting.value = true

  try {
    const commentData = {
      reviewId: reviewId.value,
      parentId: replyingTo.value || 0,  // 支持一级评论和子评论（回复）
      answerId: replyingTo.value ? (comments.value.find(c => c.id === replyingTo.value)?.userId || null) : null,  // 被回复的用户ID
      content: commentText.value.trim()
    }

    console.log('发布评论数据:', commentData)

    const res = await api.publishComment(commentData)

    console.log('评论发布成功:', res)

    const wasReplying = replyingTo.value !== null  // 保存回复状态
    commentText.value = ''
    replyingTo.value = null  // 清除回复状态
    await loadComments()

    alert(wasReplying ? '回复成功！' : '评论发布成功！')
  } catch (e) {
    console.error('评论发布失败:', e)
    alert(e.message || '发布失败，请重试')
  } finally {
    submitting.value = false
  }
}

function startReply(comment) {
  replyingTo.value = comment.id
  commentText.value = ''  // 清空之前的输入
  // 滚动到评论区底部
  setTimeout(() => {
    const commentInput = document.querySelector('.review-detail-comment-input textarea')
    if (commentInput) {
      commentInput.focus()
      commentInput.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
  }, 100)
}

function cancelReply() {
  replyingTo.value = null
  commentText.value = ''
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return d.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' })
}

const images = () => {
  if (!review.value || !review.value.images) return []
  return parseImages(review.value.images)
}

onMounted(() => {
  loadReview()
  loadComments()
})
</script>

<template>
  <div class="review-detail-page">
    <!-- 返回按钮 -->
    <button class="detail-back-btn" @click="goBack">
      <ChevronLeft :size="20" />
      <span>返回</span>
    </button>

    <div v-if="loading" class="review-detail-loading">加载中...</div>
    <template v-else-if="review">
      <GuideReviewInsights :show-id="review.showId || ''" :review-id="reviewId" />
      <div class="review-detail-inner">
        <div class="review-detail-header">
          <div class="review-detail-user" @click="goToUser(review.userId)" style="cursor: pointer">
            <img
              v-if="review.userIcon && review.userIcon !== '/default-avatar.png'"
              :src="getImageUrl(review.userIcon)"
              class="review-detail-avatar"
              alt=""
              @error="handleAvatarError"
            />
            <div v-else class="review-detail-avatar-placeholder">
              {{ (review.userName || '匿')?.charAt(0) }}
            </div>
            <span class="review-detail-name">{{ review.userName || '匿名用户' }}</span>
            <time class="review-detail-time">{{ formatTime(review.createTime) }}</time>
          </div>
          <h1 class="review-detail-title">{{ review.title }}</h1>
        </div>

        <div v-if="images().length" class="review-detail-images">
          <img
            v-for="(img, idx) in images()"
            :key="idx"
            :src="getImageUrl(img)"
            :alt="review.title"
            loading="lazy"
          />
        </div>

        <div class="review-detail-content">{{ review.content }}</div>

        <div class="review-detail-actions">
          <button
            :class="['review-detail-like-btn', { liked: review.isLike }]"
            @click="handleLike"
          >
            <svg class="star-icon" :class="{ 'star-lit': review.isLike }" viewBox="0 0 24 24" width="20" height="20">
              <path
                d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"
                :fill="review.isLike ? '#ffffff' : '#a6baae'"
                stroke="none"
              />
            </svg>
            {{ review.isLike ? '已点亮' : '点亮' }} {{ review.liked || 0 }}
          </button>
        </div>

        <div class="review-detail-comments">
          <h2 class="review-detail-section-title">评论 ({{ comments.length }})</h2>
          <div v-if="!comments.length" class="review-detail-empty">暂无评论</div>
          <div v-for="c in comments" :key="c.id" class="review-detail-comment">
            <div class="review-detail-comment-header">
              <img
                v-if="c.icon && c.icon !== '/default-avatar.png'"
                :src="getImageUrl(c.icon)"
                class="review-detail-comment-avatar"
                alt=""
                @error="handleCommentAvatarError($event, c)"
              />
              <div v-else class="review-detail-comment-avatar-placeholder">
                {{ (c.userName || '匿')?.charAt(0) }}
              </div>
              <div class="review-detail-comment-user-info">
                <span class="review-detail-comment-user" @click="goToUser(c.userId)" style="cursor: pointer">{{ c.userName || ('用户' + (c.userId || c.id)) }}</span>
                <time class="review-detail-comment-time">{{ formatTime(c.createTime || c.created) }}</time>
              </div>
            </div>

            <!-- 回复关系提示（楼中楼） -->
            <div v-if="c.parentId > 0" class="review-detail-reply-relation">
              回复 <span class="reply-target-name">@{{ getAnswerUserName(c) }}</span>
            </div>

            <p class="review-detail-comment-text">{{ c.content }}</p>

            <!-- 回复按钮 -->
            <button class="review-detail-reply-btn" @click="startReply(c)">
              回复
            </button>

            <!-- 子评论列表（嵌套展示） -->
            <div v-if="c.replies && c.replies.length > 0" class="review-detail-sub-comments">
              <div v-for="reply in c.replies" :key="reply.id" class="review-detail-sub-comment">
                <div class="review-detail-comment-header">
                  <img
                    v-if="reply.icon && reply.icon !== '/default-avatar.png'"
                    :src="getImageUrl(reply.icon)"
                    class="review-detail-comment-avatar"
                    style="width: 28px; height: 28px;"
                    alt=""
                    @error="handleCommentAvatarError($event, reply)"
                  />
                  <div v-else class="review-detail-comment-avatar-placeholder" style="width: 28px; height: 28px;">
                    {{ (reply.userName || '匿')?.charAt(0) }}
                  </div>
                  <div class="review-detail-comment-user-info">
                    <span class="review-detail-comment-user" @click="goToUser(reply.userId)" style="cursor: pointer; font-size: 13px;">
                      {{ reply.userName || ('用户' + (reply.userId || reply.id)) }}
                    </span>
                    <time class="review-detail-comment-time">{{ formatTime(reply.createTime || reply.created) }}</time>
                  </div>
                </div>

                <!-- 子评论回复关系提示 -->
                <div v-if="reply.parentId > 0" class="review-detail-reply-relation" style="margin-left: 38px;">
                  回复 <span class="reply-target-name">@{{ getAnswerUserName(reply) }}</span>
                </div>

                <p class="review-detail-comment-text" style="font-size: 13px;">{{ reply.content }}</p>
                <button class="review-detail-reply-btn review-detail-reply-btn-small" @click="startReply(reply)">
                  回复
                </button>
              </div>
            </div>
          </div>

          <div class="review-detail-comment-form">
            <!-- 回复状态提示 -->
            <div v-if="replyingTo" class="review-detail-replying-hint">
              <span class="replying-text">
                正在回复 {{ comments.find(c => c.id === replyingTo)?.userName || '用户' }}
              </span>
              <button class="cancel-reply-btn" @click="cancelReply">取消</button>
            </div>

            <textarea
              v-model="commentText"
              :placeholder="replyingTo ? `回复 ${comments.find(c => c.id === replyingTo)?.userName || '用户'}...` : '写下你的评论...'"
              rows="3"
              class="review-detail-textarea"
            />
            <button
              class="review-detail-submit"
              :disabled="!commentText.trim() || submitting"
              @click="submitComment"
            >
              {{ submitting ? '发布中...' : (replyingTo ? '发送回复' : '发布评论') }}
            </button>
          </div>
        </div>
      </div>
    </template>
    <div v-else class="review-detail-error">评测加载失败</div>
  </div>
</template>

<style scoped>
.review-detail-page {
  padding: var(--space-12) var(--space-6);
  background-color: var(--color-bg);
  min-height: calc(100vh - 60px);
}

/* 返回按钮样式 */
.detail-back-btn {
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

.detail-back-btn:hover {
  color: var(--color-text);
  background-color: var(--color-bg-alt);
  border-color: var(--color-primary);
}

.review-detail-inner {
  max-width: 800px;
  margin: 0 auto;
}

.review-detail-loading,
.review-detail-error {
  text-align: center;
  padding: var(--space-12) 0;
  font-size: var(--font-base);
  color: var(--color-text-light);
}

.review-detail-header {
  margin-bottom: var(--space-6);
}

.review-detail-user {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.review-detail-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
}

.review-detail-avatar-placeholder {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background-color: var(--color-primary);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 600;
}

.review-detail-name {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
}

.review-detail-time {
  font-size: var(--font-xs);
  color: var(--color-text-light);
  margin-left: auto;
}

.review-detail-title {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-text);
}

.review-detail-images {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-4);
  margin-bottom: var(--space-6);
}

.review-detail-images img {
  width: 80%;
  max-width: 600px;
  height: auto;
  border-radius: var(--radius-md);
  object-fit: contain;
}

.review-detail-content {
  font-size: var(--font-lg);
  color: var(--color-text-light);
  line-height: 1.8;
  white-space: pre-wrap;
  margin-bottom: var(--space-6);
}

.review-detail-actions {
  display: flex;
  gap: var(--space-4);
  padding-bottom: var(--space-6);
  border-bottom: 1px solid var(--color-border);
  margin-bottom: var(--space-6);
}

.review-detail-like-btn {
  padding: var(--space-2) var(--space-4);
  font-size: var(--font-sm);
  font-weight: 600;
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-alt);
  color: var(--color-text-light);
  border: 1px solid transparent;
  transition: all var(--transition-fast);
}

.review-detail-like-btn:hover:not(.liked) {
  background-color: var(--color-primary-alpha);
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.review-detail-like-btn.liked {
  background-color: var(--color-primary);
  color: var(--color-white);
  border-color: var(--color-primary);
  font-weight: 700;
  box-shadow: 0 2px 4px rgba(166, 186, 174, 0.25);
}

.star-icon {
  width: 20px;
  height: 20px;
  margin-right: 6px;
  vertical-align: middle;
  transition: all 0.3s ease;
}

.star-icon.star-lit {
  filter: drop-shadow(0 1px 2px rgba(166, 186, 174, 0.3))
         drop-shadow(0 2px 4px rgba(166, 186, 174, 0.15));
}

.review-detail-like-btn:hover .star-icon {
  transform: scale(1.1);
}

.review-detail-like-btn:hover .star-lit {
  transform: scale(1.15);
  filter: drop-shadow(0 1px 3px rgba(166, 186, 174, 0.4))
         drop-shadow(0 2px 6px rgba(166, 186, 174, 0.2));
}

.review-detail-section-title {
  font-family: var(--font-heading);
  font-size: var(--font-lg);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-4);
}

.review-detail-empty {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  margin-bottom: var(--space-4);
}

.review-detail-comment {
  padding: var(--space-4) 0;
  border-bottom: 1px solid var(--color-border);
}

.review-detail-comment:last-child {
  border-bottom: none;
}

.review-detail-comment-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}

.review-detail-comment-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}

.review-detail-comment-avatar-placeholder {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--color-primary) 0%, var(--color-primary-dark) 100%);
  color: var(--color-white);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
  flex-shrink: 0;
}

.review-detail-comment-user-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.review-detail-comment-user {
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--color-text);
  cursor: pointer;
  transition: color var(--transition-fast);
}

.review-detail-comment-user:hover {
  color: var(--color-primary);
}

.review-detail-comment-time {
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

.review-detail-comment-text {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  margin-top: var(--space-1);
}

.review-detail-comment-form {
  margin-top: var(--space-6);
}

.review-detail-textarea {
  width: 100%;
  padding: var(--space-3);
  font-size: var(--font-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-white);
  color: var(--color-text);
  resize: vertical;
  font-family: inherit;
}

.review-detail-textarea:focus {
  outline: none;
  border-color: var(--color-primary);
}

.review-detail-submit {
  margin-top: var(--space-3);
  padding: var(--space-2) var(--space-4);
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: var(--radius-sm);
  background-color: var(--color-primary);
  color: var(--color-white);
}

.review-detail-submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.review-detail-submit:hover:not(:disabled) {
  background-color: var(--color-secondary);
}

/* 回复按钮样式 */
.review-detail-reply-btn {
  margin-top: var(--space-2);
  padding: var(--space-1) var(--space-3);
  font-size: var(--font-xs);
  color: var(--color-text-light);
  background-color: transparent;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.review-detail-reply-btn:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
  background-color: var(--color-primary-alpha);
}

.review-detail-reply-btn-small {
  font-size: 12px;
  padding: 2px 8px;
}

/* 回复关系提示（楼中楼） */
.review-detail-reply-relation {
  margin-top: var(--space-1);
  margin-bottom: var(--space-1);
  padding: var(--space-1) var(--space-2);
  font-size: var(--font-xs);
  color: var(--color-text-light);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-sm);
  border-left: 3px solid var(--color-primary);
}

.reply-target-name {
  font-weight: 600;
  color: var(--color-primary);
}

/* 子评论列表 */
.review-detail-sub-comments {
  margin-top: var(--space-3);
  margin-left: var(--space-6);
  padding-left: var(--space-4);
  border-left: 2px solid var(--color-border);
}

.review-detail-sub-comment {
  padding: var(--space-3) 0;
  border-bottom: 1px dashed var(--color-border);
}

.review-detail-sub-comment:last-child {
  border-bottom: none;
}

/* 正在回复状态提示 */
.review-detail-replying-hint {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-2) var(--space-3);
  margin-bottom: var(--space-2);
  background-color: var(--color-primary-alpha);
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
}

.replying-text {
  color: var(--color-primary);
  font-weight: 500;
}

.cancel-reply-btn {
  padding: var(--space-1) var(--space-3);
  font-size: var(--font-xs);
  color: var(--color-danger);
  background-color: var(--color-white);
  border: 1px solid var(--color-border-danger);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.cancel-reply-btn:hover {
  background-color: var(--color-bg-danger);
  border-color: var(--color-danger);
}

@media (max-width: 768px) {
  .review-detail-title {
    font-size: 24px;
  }
}
</style>
