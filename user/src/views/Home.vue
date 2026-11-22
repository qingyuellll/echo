<script setup>
import HeroSection from '../components/sections/HeroSection.vue'
import FeatureCards from '../components/sections/FeatureCards.vue'
import StatsSection from '../components/sections/StatsSection.vue'
import BlogPreview from '../components/sections/BlogPreview.vue'
</script>

<template>
  <div class="home">
    <!-- 有机背景装饰：垂直暖白渐变 + 径向渐变点阵 + 柔和色团（无星星） -->
    <div class="home-bg" aria-hidden="true">
      <span class="home-bg-tint"></span>
      <span class="home-bg-dots"></span>
      <span class="home-bg-blob home-bg-blob-1"></span>
      <span class="home-bg-blob home-bg-blob-2"></span>
    </div>

    <HeroSection />
    <FeatureCards />
    <StatsSection />
    <BlogPreview />
  </div>
</template>

<style scoped>
.home {
  position: relative;
  overflow-x: hidden;
}

/* 有机背景装饰层（固定，不随内容滚动） */
.home-bg {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background-color: var(--color-bg);
  overflow: hidden;
}

/* 垂直暖白渐变：淡到几乎透明的暖白 → 米白 */
.home-bg-tint {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, var(--color-white) 0%, var(--color-bg-alt) 100%);
  opacity: 0.5;
}

/* 径向渐变点阵纹理（radial-gradient dots） */
.home-bg-dots {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(var(--color-primary) 1px, transparent 1.2px);
  background-size: 28px 28px;
  opacity: 0.06;
}

/* 柔和有机色团（淡淡的色晕，增强自然感） */
.home-bg-blob {
  position: absolute;
  border-radius: var(--radius-full);
  filter: blur(60px);
  opacity: 0.4;
}

.home-bg-blob-1 {
  width: 380px;
  height: 380px;
  top: -100px;
  left: -120px;
  background: radial-gradient(circle at 50% 50%, var(--color-primary-alpha), transparent 70%);
}

.home-bg-blob-2 {
  width: 420px;
  height: 420px;
  right: -140px;
  bottom: -160px;
  background: radial-gradient(circle at 50% 50%, var(--color-bg-alt), transparent 70%);
}

/* 内容层在背景之上 */
.home > :not(.home-bg) {
  position: relative;
  z-index: 1;
  opacity: 0;
  animation: homeFadeInUp 0.8s ease-out forwards;
}

/* 交错入场动画（staggered fade-in-up） */
.home > :not(.home-bg):nth-child(2) { animation-delay: 0.12s; }
.home > :not(.home-bg):nth-child(3) { animation-delay: 0.24s; }
.home > :not(.home-bg):nth-child(4) { animation-delay: 0.36s; }
.home > :not(.home-bg):nth-child(5) { animation-delay: 0.48s; }

@keyframes homeFadeInUp {
  from {
    opacity: 0;
    transform: translateY(28px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .home > :not(.home-bg) {
    opacity: 1;
    animation: none;
  }
}
</style>
