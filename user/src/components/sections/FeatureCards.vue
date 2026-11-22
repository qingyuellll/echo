<script setup>
import { Ticket, Star, Zap } from 'lucide-vue-next'

const features = [
  {
    icon: Ticket,
    title: '海量演出',
    desc: '覆盖演唱会、话剧、音乐会等多种演出类型，总有一场适合你。'
  },
  {
    icon: Star,
    title: '社区评测',
    desc: '阅读真实观演评测，与同好交流心得，发现更多好演出。'
  },
  {
    icon: Zap,
    title: '即刻购票',
    desc: '流畅的选座购票体验，积分抵扣、秒杀活动等你参与。'
  }
]
</script>

<template>
  <section class="features">
    <div class="features-inner">
      <div class="features-grid">
        <div v-for="feature in features" :key="feature.title" class="feature-card">
          <div class="feature-icon">
            <component :is="feature.icon" :size="28" />
          </div>
          <h3 class="feature-title">{{ feature.title }}</h3>
          <p class="feature-desc">{{ feature.desc }}</p>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.features {
  padding: var(--space-12) var(--space-6);
  background-color: var(--color-bg);
}

.features-inner {
  max-width: 1200px;
  margin: 0 auto;
}

.features-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-6);
}

.feature-card {
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  padding: var(--space-8) var(--space-6);
  text-align: center;
  transition: all var(--transition-base);
}

.feature-card:hover {
  box-shadow: var(--shadow-md); /* 升级为中级阴影 */
  transform: translateY(-2px) scale(1.02); /* 上移2px + 放大1.02倍 - 符合设计规范！ */
}

.feature-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  background-color: var(--color-bg-alt);
  color: var(--color-primary);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-4);
}

.feature-title {
  font-family: var(--font-heading);
  font-size: var(--font-lg);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-2);
}

.feature-desc {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  line-height: 1.6;
}

@media (max-width: 992px) {
  .features-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 576px) {
  .features-grid {
    grid-template-columns: 1fr;
  }
}
</style>
