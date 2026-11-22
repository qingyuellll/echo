<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { Ticket, Users, TrendingUp, Heart } from 'lucide-vue-next'
import { api } from '@/utils/api.js'

const stats = ref([
  { label: '演出数量', value: 128, icon: Ticket },
  { label: '注册用户', value: 5600, icon: Users },
  { label: '累计订单', value: 8942, icon: TrendingUp },
  { label: '平均评分', value: 4.8, icon: Heart }
])

// 页面加载时从 0 计数到目标值
const display = ref([0, 0, 0, 0])
let rafId = null

async function loadStats() {
  try {
    const res = await api.getDashboardStats()
    if (res && res.data) {
      const map = {
        演出数量: res.data.showCount,
        注册用户: res.data.userCount,
        累计订单: res.data.orderCount,
        平均评分: res.data.avgScore
      }
      stats.value = stats.value.map((s) => ({
        ...s,
        value: map[s.label] != null ? map[s.label] : s.value
      }))
    }
  } catch (e) {
    console.error('[StatsSection] 加载统计数据失败，使用默认数据:', e)
  }
}

// 页面加载时触发 count-up（requestAnimationFrame，1.5s）
function runCountUp() {
  const duration = 1500
  const start = performance.now()
  const from = [0, 0, 0, 0]
  const to = stats.value.map((s) => Number(s.value) || 0)

  function tick(now) {
    const t = Math.min(1, (now - start) / duration)
    const eased = 1 - Math.pow(1 - t, 3) // easeOutCubic
    display.value = to.map((target, i) => from[i] + (target - from[i]) * eased)
    if (t < 1) {
      rafId = requestAnimationFrame(tick)
    } else {
      display.value = to.slice()
    }
  }
  rafId = requestAnimationFrame(tick)
}

function formatValue(stat, index) {
  const v = display.value[index] || 0
  if (stat.label === '平均评分') {
    return v.toFixed(1) + ' 分'
  }
  return Math.round(v).toLocaleString()
}

onMounted(async () => {
  await loadStats()
  runCountUp()
})

onBeforeUnmount(() => {
  if (rafId) cancelAnimationFrame(rafId)
})
</script>

<template>
  <section class="stats-section">
    <div class="stats-inner">
      <!-- 波浪分隔线（替代五角星 waistband） -->
      <div class="stats-divider" aria-hidden="true">
        <span class="stats-divider-line"></span>
        <svg class="stats-divider-wave" viewBox="0 0 140 14" fill="none">
          <path
            class="stats-divider-wave-path"
            d="M0 7 Q 17.5 0 35 7 T 70 7 T 105 7 T 140 7"
            stroke-width="2"
            stroke-linecap="round"
            fill="none"
          />
        </svg>
        <span class="stats-divider-line"></span>
      </div>

      <h2 class="section-title">平台数据</h2>

      <div class="stats-grid">
        <div
          v-for="(stat, index) in stats"
          :key="stat.label"
          class="stat-card"
        >
          <component :is="stat.icon" :size="28" class="stat-icon" />
          <span class="stat-value">{{ formatValue(stat, index) }}</span>
          <span class="stat-label">{{ stat.label }}</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.stats-section {
  background-color: var(--color-bg-alt);
  padding: var(--space-12) var(--space-6);
}

.stats-inner {
  max-width: 1200px;
  margin: 0 auto;
}

.section-title {
  font-family: var(--font-heading);
  font-size: var(--font-xl);
  color: var(--color-text);
  text-align: center;
  margin-bottom: var(--space-8);
  margin-top: var(--space-8);
  font-weight: 600;
}

/* 波浪分隔线（暖白线条 + 灰绿波形） */
.stats-divider {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-4);
  margin: var(--space-4) auto 0;
  max-width: 520px;
}

.stats-divider-line {
  flex: 1;
  height: 1px;
  background: linear-gradient(
    90deg,
    transparent,
    var(--color-primary),
    transparent
  );
}

.stats-divider-wave {
  width: 140px;
  height: 14px;
  flex-shrink: 0;
  opacity: 0.85;
}

.stats-divider-wave-path {
  stroke: var(--color-primary);
}

/* 统计卡片网格 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--space-6);
}

.stat-card {
  text-align: center;
  padding: var(--space-6);
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-3);
  transition: var(--transition-base);
}

.stat-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}

.stat-icon {
  color: var(--color-primary);
  margin-bottom: var(--space-1);
}

/* 数字颜色：灰绿 */
.stat-value {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  color: var(--color-primary);
  font-weight: 700;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}

.stat-label {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  font-weight: 500;
}

@media (max-width: 768px) {
  .stats-section {
    padding: var(--space-8) var(--space-4);
  }

  .section-title {
    font-size: var(--font-lg);
    margin-bottom: var(--space-6);
  }

  .stats-grid {
    grid-template-columns: 1fr;
    gap: var(--space-4);
  }

  .stat-card {
    padding: var(--space-4);
  }

  .stat-value {
    font-size: var(--font-xl);
  }
}
</style>
