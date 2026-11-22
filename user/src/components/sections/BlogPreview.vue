<script setup>
import { ref, onMounted } from 'vue'
import { api, parseImages, getImageUrl } from '@/utils/api.js'

const hotReviews = ref([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    const res = await api.getHotReviews(1)
    hotReviews.value = (res && res.data) || []
  } catch {
    hotReviews.value = []
  } finally {
    loading.value = false
  }
})

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
</script>

<template>
  <section class="blog-section">
    <div class="blog-inner">
      <div class="blog-header">
        <h2 class="blog-title">热门评测</h2>
        <router-link to="/reviews" class="blog-more">查看全部 →</router-link>
      </div>

      <div v-if="loading" class="blog-grid">
        <div v-for="i in 3" :key="i" class="blog-card-placeholder" />
      </div>

      <div v-else-if="!hotReviews.length" class="blog-empty">
        暂无评测内容
      </div>

      <div v-else class="blog-grid">
        <router-link
          v-for="review in hotReviews"
          :key="review.id"
          :to="`/review/${review.id}`"
          class="blog-card"
        >
          <div v-if="review.images && parseImages(review.images).length" class="blog-card-img">
            <img
              :src="getImageUrl(parseImages(review.images)[0])"
              :alt="review.title"
              loading="lazy"
            />
            <!-- 图片区淡入遮罩：hover 时显示半透明深色遮罩 + 标题浮现 -->
            <div class="blog-card-mask">
              <span class="blog-card-mask-title">{{ review.title }}</span>
            </div>
          </div>
          <div class="blog-card-body">
            <div class="blog-card-author">
              <img
                v-if="review.userIcon"
                :src="getImageUrl(review.userIcon)"
                class="blog-card-avatar"
                alt=""
              />
              <div v-else class="blog-card-avatar-placeholder" />
              <span class="blog-card-name">{{ review.userName || '匿名用户' }}</span>
            </div>
            <h3 class="blog-card-title">{{ review.title }}</h3>
            <p class="blog-card-content">{{ review.content }}</p>
            <div class="blog-card-meta">
              <span>{{ review.liked || 0 }} 赞</span>
              <time>{{ formatTime(review.createTime) }}</time>
            </div>
          </div>
        </router-link>
      </div>
    </div>
  </section>
</template>

<style scoped>
.blog-section {
  padding: var(--space-12) var(--space-6);
  background-color: var(--color-bg);
}

.blog-inner {
  max-width: 1200px;
  margin: 0 auto;
}

.blog-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: var(--space-8);
}

.blog-title {
  font-family: var(--font-heading);
  font-size: var(--font-xl);
  font-weight: 700;
  color: var(--color-text);
}

.blog-more {
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-primary);
  text-decoration: none;
}

.blog-more:hover {
  color: var(--color-secondary);
}

.blog-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-6);
}

.blog-empty {
  text-align: center;
  padding: var(--space-8) 0;
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

/* 评测卡片：1px 边框风格（与 FeatureCards 阴影区形成对比） */
.blog-card {
  display: block;
  position: relative;
  background-color: var(--color-white);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  transition: var(--transition-base);
  text-decoration: none;
}

/* hover：仅 translateY(-2px)，禁止 scale */
.blog-card:hover {
  transform: translateY(-2px);
  border-color: var(--color-primary);
}

.blog-card-img {
  position: relative;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  background-color: var(--color-bg-alt);
}

.blog-card-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: var(--transition-base);
}

/* 图片区淡入遮罩（hover 显示） */
.blog-card-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: flex-end;
  padding: var(--space-4);
  background: linear-gradient(
    180deg,
    rgba(93, 62, 63, 0) 0%,
    rgba(93, 62, 63, 0.55) 100%
  );
  opacity: 0;
  transition: opacity var(--transition-base);
}

.blog-card:hover .blog-card-mask {
  opacity: 1;
}

.blog-card-mask-title {
  font-family: var(--font-heading);
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-white);
  line-height: 1.4;
  transform: translateY(8px);
  transition: transform var(--transition-base);
}

.blog-card:hover .blog-card-mask-title {
  transform: translateY(0);
}

.blog-card-body {
  padding: var(--space-5);
}

.blog-card-author {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.blog-card-avatar {
  width: 24px;
  height: 24px;
  border-radius: var(--radius-full);
  object-fit: cover;
}

.blog-card-avatar-placeholder {
  width: 24px;
  height: 24px;
  border-radius: var(--radius-full);
  background-color: var(--color-bg-alt);
}

.blog-card-name {
  font-size: var(--font-xs);
  font-weight: 500;
  color: var(--color-text);
}

.blog-card-title {
  font-family: var(--font-heading);
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.blog-card-content {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  line-height: 1.6;
  margin-bottom: var(--space-3);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.blog-card-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

.blog-card-placeholder {
  background-color: var(--color-white);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  height: 280px;
  animation: shimmer 1.5s infinite;
}

@keyframes shimmer {
  0% { opacity: 0.6; }
  50% { opacity: 0.9; }
  100% { opacity: 0.6; }
}

@media (max-width: 992px) {
  .blog-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 576px) {
  .blog-grid {
    grid-template-columns: 1fr;
  }
}
</style>
