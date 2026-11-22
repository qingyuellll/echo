<script setup>
const linkGroups = [
  {
    title: '平台',
    links: [
      { label: '首页', path: '/' },
      { label: '演出', path: '/shows' },
      { label: '评测', path: '/reviews' }
    ]
  },
  {
    title: '内容',
    links: [
      { label: '热门评测', path: '/reviews' },
      { label: '发布评测', path: '/reviews/publish' },
      { label: '演出详情', path: '/shows' }
    ]
  },
  {
    title: '支持',
    links: [
      { label: '关于 Echo', path: '/about' },
      { label: '我的', path: '/profile' },
      { label: '通知', path: '/notifications' }
    ]
  }
]
</script>

<template>
  <footer class="footer">
    <!-- 顶部波浪：从页面暖白过渡到品牌灰绿 -->
    <svg class="footer-wave-top" viewBox="0 0 1440 60" preserveAspectRatio="none" aria-hidden="true">
      <path
        class="footer-wave-top-path"
        d="M0 40 C 240 80 480 0 720 30 C 960 60 1200 10 1440 36 L1440 60 L0 60 Z"
      />
    </svg>

    <div class="footer-inner">
      <!-- 品牌 slogan 区域 -->
      <div class="footer-brand">
        <span class="footer-logo">Echo</span>
        <p class="footer-slogan">
          听见每一次现场的心跳<br />
          <span class="footer-slogan-en">Listen to the resonance of every live moment.</span>
        </p>
      </div>

      <!-- 链接分组：平台 / 内容 / 支持 三列 -->
      <div class="footer-cols">
        <div v-for="group in linkGroups" :key="group.title" class="footer-col">
          <h4 class="footer-col-title">{{ group.title }}</h4>
          <ul class="footer-col-links">
            <li v-for="link in group.links" :key="link.path">
              <router-link :to="link.path" class="footer-link">
                {{ link.label }}
              </router-link>
            </li>
          </ul>
        </div>
      </div>

      <!-- 底部虚线分隔 -->
      <div class="footer-copy">
        &copy; {{ new Date().getFullYear() }} Echo. 保留所有权利。
      </div>
    </div>
  </footer>
</template>

<style scoped>
.footer {
  position: relative;
  background-color: var(--color-primary);
  color: var(--color-white);
  padding: var(--space-12) var(--space-6) var(--space-6);
  margin-top: var(--space-16);
}

/* 顶部波浪（暖白 → 灰绿） */
.footer-wave-top {
  position: absolute;
  top: -1px;
  left: 0;
  width: 100%;
  height: 60px;
  display: block;
}

.footer-wave-top-path {
  fill: var(--color-bg);
}

.footer-inner {
  position: relative;
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-8);
}

/* 品牌 slogan */
.footer-brand {
  text-align: center;
}

.footer-logo {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-white);
  letter-spacing: 1px;
}

.footer-slogan {
  font-size: var(--font-base);
  color: rgba(255, 255, 255, 0.85);
  margin-top: var(--space-3);
  line-height: 1.6;
}

.footer-slogan-en {
  font-size: var(--font-sm);
  color: rgba(255, 255, 255, 0.6);
  font-style: italic;
}

/* 链接分组 */
.footer-cols {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-12);
}

.footer-col {
  min-width: 120px;
  text-align: center;
}

.footer-col-title {
  font-family: var(--font-heading);
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-white);
  margin-bottom: var(--space-4);
  position: relative;
  display: inline-block;
}

.footer-col-title::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: -6px;
  width: 24px;
  height: 2px;
  background-color: rgba(255, 255, 255, 0.5);
  transform: translateX(-50%);
  border-radius: var(--radius-full);
}

.footer-col-links {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.footer-link {
  font-size: var(--font-sm);
  color: rgba(255, 255, 255, 0.8);
  text-decoration: none;
  transition: color var(--transition-base);
}

.footer-link:hover {
  color: var(--color-white);
}

/* 底部 copyright：细细的虚线分隔 */
.footer-copy {
  font-size: var(--font-xs);
  color: rgba(255, 255, 255, 0.6);
  padding-top: var(--space-4);
  border-top: 1px dashed rgba(255, 255, 255, 0.3);
  text-align: center;
  width: 100%;
}

@media (max-width: 768px) {
  .footer-cols {
    gap: var(--space-8);
  }

  .footer-col {
    min-width: 100px;
  }
}
</style>
