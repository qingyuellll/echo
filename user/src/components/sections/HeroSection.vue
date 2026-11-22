<script setup>
import { Music3 } from 'lucide-vue-next'

defineProps({
  headline: {
    type: String,
    default: '发现你热爱的演出'
  },
  subheadline: {
    type: String,
    default: '从独立现场到大型演唱会——购票、分享、连接，一站开启你的现场体验。'
  }
})
</script>

<template>
  <section class="hero">
    <div class="hero-inner">
      <div class="hero-text">
        <h1 class="hero-headline">{{ headline }}</h1>
        <p class="hero-subheadline">{{ subheadline }}</p>
        <div class="hero-actions">
          <router-link to="/shows" class="hero-btn hero-btn-primary">
            浏览演出
          </router-link>
          <router-link to="/reviews" class="hero-btn hero-btn-outline">
            热门评测
          </router-link>
        </div>
      </div>

      <!-- 有机视觉区：多个半透明椭圆 SVG（品牌色）+ 主圆形 -->
      <div class="hero-visual">
        <div class="hero-stage">
          <svg class="hero-organic" viewBox="0 0 400 400" fill="none" aria-hidden="true">
            <ellipse class="hero-ellipse hero-ellipse-1" cx="150" cy="165" rx="130" ry="95" fill="var(--color-bg-alt)" />
            <ellipse class="hero-ellipse hero-ellipse-2" cx="255" cy="245" rx="110" ry="140" fill="var(--color-primary-alpha)" />
            <ellipse class="hero-ellipse hero-ellipse-3" cx="205" cy="120" rx="72" ry="56" fill="var(--color-primary-alpha)" />
          </svg>
          <div class="hero-center">
            <Music3 :size="56" />
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.hero {
  /* Hero 背景：淡淡的垂直暖白 → 米白渐变 */
  background: linear-gradient(180deg, var(--color-white) 0%, var(--color-bg-alt) 100%);
  padding: var(--space-16) var(--space-6);
}

.hero-inner {
  max-width: 1200px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-12);
  align-items: center;
}

.hero-headline {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-text);
  line-height: 1.2;
  margin-bottom: var(--space-4);
}

.hero-subheadline {
  font-size: var(--font-lg);
  color: var(--color-text-light);
  line-height: 1.6;
  margin-bottom: var(--space-8);
  max-width: 480px;
}

.hero-actions {
  display: flex;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.hero-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-body);
  font-size: var(--font-base);
  font-weight: 500;
  padding: var(--space-3) var(--space-8);
  border-radius: var(--radius-sm);
  transition: var(--transition-base);
  text-decoration: none;
}

/* primary：灰绿背景 + 白字 */
.hero-btn-primary {
  background-color: var(--color-primary);
  color: var(--color-white);
}

.hero-btn-primary:hover {
  background-color: var(--color-secondary);
  transform: translateY(-2px);
}

/* outline：透明背景 + 灰绿边框 */
.hero-btn-outline {
  background-color: transparent;
  color: var(--color-primary);
  border: 1px solid var(--color-primary);
}

.hero-btn-outline:hover {
  background-color: var(--color-primary);
  color: var(--color-white);
  transform: translateY(-2px);
}

/* ===== 有机视觉区 ===== */
.hero-visual {
  display: flex;
  align-items: center;
  justify-content: center;
}

.hero-stage {
  position: relative;
  width: 320px;
  height: 320px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 半透明椭圆 SVG 叠加（微妙上下浮动） */
.hero-organic {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  animation: heroFloat 6s ease-in-out infinite;
}

.hero-ellipse {
  transform-origin: center;
}

/* 主圆形（柔和白圆 + 图标），同样上下浮动 */
.hero-center {
  position: relative;
  z-index: 2;
  width: 172px;
  height: 172px;
  border-radius: var(--radius-full);
  background: var(--color-white);
  box-shadow: var(--shadow-md);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-primary);
  animation: heroFloat 6s ease-in-out infinite;
}

/* 上下浮动 ±8px，6s ease-in-out infinite */
@keyframes heroFloat {
  0%, 100% { transform: translateY(-8px); }
  50% { transform: translateY(8px); }
}

@media (max-width: 768px) {
  .hero {
    padding: var(--space-12) var(--space-6);
  }

  .hero-inner {
    grid-template-columns: 1fr;
    gap: var(--space-8);
    text-align: center;
  }

  .hero-subheadline {
    margin-left: auto;
    margin-right: auto;
  }

  .hero-actions {
    justify-content: center;
  }

  .hero-stage {
    width: 260px;
    height: 260px;
  }

  .hero-center {
    width: 144px;
    height: 144px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .hero-organic,
  .hero-center {
    animation: none;
  }
}
</style>
