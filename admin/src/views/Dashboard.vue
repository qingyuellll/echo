<script setup>
import { ref, onMounted } from 'vue'
import Card from '@/components/common/Card.vue'
import Button from '@/components/common/Button.vue'
import Table from '@/components/common/Table.vue'
import {
  Clapperboard,
  Ticket,
  ShoppingCart,
  Users,
  TrendingUp,
  CalendarDays
} from 'lucide-vue-next'
import { api, formatPrice } from '@/utils/api.js'

const dashboard = ref(null)
const loading = ref(true)
const topShowsColumns = ref([
  { key: 'ticketId', title: '票种ID' },
  { key: 'ticketTitle', title: '票种名称' },
  { key: 'showName', title: '所属演出' },
  { key: 'count', title: '销量' }
])

async function loadDashboard() {
  try {
    loading.value = true
    const res = await api.getDashboard()
    dashboard.value = res.data
  } catch (e) {
    console.error('加载仪表盘失败:', e)
  } finally {
    loading.value = false
  }
}

onMounted(loadDashboard)

const statCards = [
  { key: 'showCount', label: '演出总数', icon: Clapperboard },
  { key: 'ticketCount', label: '票种总数', icon: Ticket },
  { key: 'orderCount', label: '订单总数', icon: ShoppingCart },
  { key: 'userCount', label: '注册用户', icon: Users },
  { key: 'todayOrderCount', label: '今日订单', icon: TrendingUp }
]
</script>

<template>
  <div class="dashboard">
    <div class="page-header">
      <h1 class="page-title">仪表盘</h1>
      <Button @click="loadDashboard" size="sm" variant="ghost">刷新数据</Button>
    </div>

    <div class="stat-grid" v-if="!loading && dashboard">
      <Card
        v-for="card in statCards"
        :key="card.key"
        class="stat-card"
        padding="sm"
      >
        <div class="stat-bg">
          <component :is="card.icon" :size="24" class="stat-icon" />
          <span class="stat-value">{{ dashboard[card.key] ?? '-' }}</span>
          <span class="stat-label">{{ card.label }}</span>
        </div>
      </Card>
    </div>

    <div class="stat-grid-skeleton" v-if="loading">
      <Card v-for="i in 5" :key="i" class="stat-card stat-card-skeleton" padding="sm">
        <div class="skeleton-block"></div>
      </Card>
    </div>

    <div class="section" v-if="!loading && dashboard?.topShows?.length">
      <h2 class="section-title">
        <TrendingUp :size="20" class="section-title-icon" />
        热门票种 TOP10
      </h2>
      <Table
        :columns="topShowsColumns"
        :data="dashboard.topShows"
        :total="dashboard.topShows.length"
      />
    </div>
  </div>
</template>

<style scoped>
.dashboard {
  max-width: 1200px;
  animation: dashboardFadeIn var(--transition-base) both;
}

@keyframes dashboardFadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-6);
}

.page-title {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  color: var(--color-text);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-4);
  margin-bottom: var(--space-8);
}

.stat-grid-skeleton {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-4);
  margin-bottom: var(--space-8);
}

.stat-card {
  background-color: var(--color-bg) !important; /* 灰绿背景 #a6baae - 符合设计规范 */
  border: none; /* 无边框，仅使用阴影 */
  box-shadow: var(--shadow-sm);
  transition: transform var(--transition-base), box-shadow var(--transition-base);
  /* 页面加载淡入（仅 opacity，避免与 hover translateY 冲突） */
  animation: statCardFadeIn var(--transition-base) both;
}

.stat-card:nth-child(1) { animation-delay: 0.05s; }
.stat-card:nth-child(2) { animation-delay: 0.10s; }
.stat-card:nth-child(3) { animation-delay: 0.15s; }
.stat-card:nth-child(4) { animation-delay: 0.20s; }
.stat-card:nth-child(5) { animation-delay: 0.25s; }

@keyframes statCardFadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.stat-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-2px); /* 仅上移 2px - 符合设计规范，禁止 scale */
}

.stat-bg {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-6) var(--space-4);
  color: var(--color-white);
}

.stat-icon {
  color: var(--color-white); /* 白色图标 */
  opacity: 0.92;
  margin-bottom: var(--space-1);
}

.stat-value {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-white); /* 白色数字 - 符合设计规范 */
  line-height: 1.2;
}

.stat-label {
  font-size: var(--font-sm);
  opacity: 0.9;
  font-weight: 500;
}

.stat-card-skeleton {
  background-color: var(--color-bg-card) !important;
}

.skeleton-block {
  height: 120px;
  background-color: var(--color-border);
  border-radius: var(--radius-sm);
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.section {
  margin-bottom: var(--space-8);
  animation: dashboardFadeIn var(--transition-base) both;
}

.section-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-family: var(--font-heading);
  font-size: var(--font-xl);
  color: var(--color-text);
  margin-bottom: var(--space-4);
}

.section-title-icon {
  color: var(--color-primary);
}

@media (max-width: 992px) {
  .stat-grid,
  .stat-grid-skeleton {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 576px) {
  .stat-grid,
  .stat-grid-skeleton {
    grid-template-columns: 1fr;
  }
}
</style>
