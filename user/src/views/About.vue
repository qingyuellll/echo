<script setup>
import { useRouter } from 'vue-router'
import { ChevronLeft } from 'lucide-vue-next'

const router = useRouter()

function goBack() {
  router.back()
}
</script>

<template>
  <div class="about-page">
    <div class="about-inner">
      <!-- 返回按钮 -->
      <button class="about-back-btn" @click="goBack">
        <ChevronLeft :size="20" />
        <span>返回</span>
      </button>

      <h1 class="about-title">关于 Echo</h1>

      <section class="about-section">
        <h2 class="about-section-title">我们的使命</h2>
        <p class="about-text">
          Echo 是一个连接演出与用户的平台。我们致力于为观众提供便捷的演出信息获取渠道，
          让每一场精彩的演出都能被看见，让每一位热爱演出的用户都能找到属于自己的舞台。
        </p>
      </section>

      <section class="about-section">
        <h2 class="about-section-title">平台特色</h2>
        <div class="about-features">
          <div class="about-feature">
            <div class="about-feature-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76"/></svg>
            </div>
            <h3 class="about-feature-title">海量演出</h3>
            <p class="about-feature-desc">覆盖演唱会、话剧、音乐节、展览等多种类型，按城市筛选，精准找到附近演出。</p>
          </div>
          <div class="about-feature">
            <div class="about-feature-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/></svg>
            </div>
            <h3 class="about-feature-title">真实评测</h3>
            <p class="about-feature-desc">用户可以发布图文评测、点赞评论互动，形成一个高质量的演出社区。</p>
          </div>
          <div class="about-feature">
            <div class="about-feature-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg>
            </div>
            <h3 class="about-feature-title">方便购票</h3>
            <p class="about-feature-desc">支持在线选票、下单购买，秒杀热门演出门票，让购票流程简单高效。</p>
          </div>
        </div>
      </section>

      <section class="about-section">
        <h2 class="about-section-title">联系我们</h2>
        <p class="about-text">
          如果您有任何问题或建议，欢迎通过邮箱 <a href="mailto:contact@echo.show" class="about-link">contact@echo.show</a> 与我们联系。
        </p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.about-page {
  padding: var(--space-12) var(--space-6);
  background-color: var(--color-bg);
  min-height: calc(100vh - 60px);
}

.about-inner {
  max-width: 800px;
  margin: 0 auto;
}

/* 返回按钮样式 */
.about-back-btn {
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

.about-back-btn:hover {
  color: var(--color-text);
  background-color: var(--color-bg-alt);
  border-color: var(--color-primary);
}

.about-title {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-text);
  text-align: center;
  margin-bottom: var(--space-12);
}

.about-section {
  margin-bottom: var(--space-12);
}

.about-section-title {
  font-family: var(--font-heading);
  font-size: var(--font-xl);
  font-weight: 600;
  color: var(--color-primary);
  margin-bottom: var(--space-4);
  padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--color-border);
}

.about-text {
  font-size: var(--font-base);
  color: var(--color-text-light);
  line-height: 1.8;
}

.about-link {
  color: var(--color-primary);
  font-weight: 500;
}

.about-link:hover {
  color: var(--color-secondary);
}

.about-features {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-6);
  margin-top: var(--space-6);
}

.about-feature {
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  padding: var(--space-6);
  text-align: center;
}

.about-feature-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: var(--radius-md);
  background-color: var(--color-bg-alt);
  color: var(--color-primary);
  margin-bottom: var(--space-4);
}

.about-feature-title {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-2);
}

.about-feature-desc {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  line-height: 1.6;
}

@media (max-width: 768px) {
  .about-features {
    grid-template-columns: 1fr;
  }
  .about-title {
    font-size: 24px;
  }
}
</style>
