<template>
  <div class="page-container">
    <!-- 页面标题 -->
    <div class="page-header">
      <h1>票务核销</h1>
      <p class="subtitle">输入核销码进行现场验票，支持逐张核销</p>
    </div>

    <!-- 核销操作区（独立卡片布局） -->

    <!-- 卡片1：输入验证区 -->
    <Card class="verification-input-card">
      <div class="card-header-main">
        <div class="header-icon-large">
          <CheckCircle :size="32" strokeWidth="2" />
        </div>
        <div class="header-text">
          <h2>核销码验证</h2>
          <p>请扫描或手动输入8位核销码</p>
        </div>
      </div>

      <div class="input-body">
        <div class="code-input-wrapper">
          <input
            ref="codeInput"
            v-model="verificationCode"
            type="text"
            maxlength="8"
            placeholder="请输入8位核销码"
            class="code-input"
            :class="{ 'success': verifySuccess }"
            @keyup.enter="handleVerify"
            @input="handleInputChange"
            :disabled="verifying"
          />
          <div class="input-actions">
            <Button
              variant="primary"
              :disabled="!canVerify || verifying"
              @click="handleVerify"
              :loading="verifying"
              size="lg"
            >
              {{ verifying ? '验证中...' : '确认核销' }}
            </Button>
            <Button
              variant="secondary"
              @click="clearInput"
              :disabled="verifying"
              size="lg"
            >
              清空
            </Button>
          </div>
        </div>
      </div>
    </Card>

    <!-- 结果展示区（条件渲染） -->
    <Card v-if="verifySuccess && orderInfo" class="result-display-card success-border">
      <div class="result-header-success">
        <div class="result-icon-circle success">
          <CheckCircle :size="28" strokeWidth="3" />
        </div>
        <div class="result-title-group">
          <h3>核销成功</h3>
          <p>第 {{ currentTicketIndex + 1 }} 张票已验票通过</p>
        </div>
      </div>

      <div class="order-details-grid">
        <div class="detail-item">
          <span class="detail-label">订单号</span>
          <span class="detail-value">#{{ orderInfo.id }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">票种名称</span>
          <span class="detail-value highlight">{{ orderInfo.ticketTitle || '-' }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">演出名称</span>
          <span class="detail-value">{{ orderInfo.showName || '-' }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">购票数量</span>
          <span class="detail-value bold">{{ totalTickets }} 张</span>
        </div>
        <div class="detail-item full-width">
          <span class="detail-label">核销进度</span>
          <div class="progress-wrapper">
            <span class="detail-value success-text verified-count">{{ verifiedTickets }}</span>
            <span class="progress-separator">/</span>
            <span class="detail-value total-count">{{ totalTickets }}</span>
            <div class="mini-progress-bar">
              <div
                class="mini-progress-fill"
                :class="'progress-' + Math.round((verifiedTickets / totalTickets) * 100)"
              ></div>
            </div>
          </div>
        </div>
      </div>

      <div class="result-footer-actions">
        <Button variant="primary" @click="continueVerify" v-if="verifiedTickets < totalTickets" size="md">
          继续核销下一张
        </Button>
        <Button variant="secondary" @click="clearAll" size="md">
          核销其他订单
        </Button>
      </div>
    </Card>

    <!-- 卡片4：错误提示（条件渲染） -->
    <Card v-else-if="inputError" class="result-display-card warning-border">
      <div class="result-header-warning">
        <div class="result-icon-circle warning">
          <AlertCircle :size="28" strokeWidth="3" />
        </div>
        <div class="result-title-group">
          <h3>核销未通过</h3>
          <p>{{ errorMessage }}</p>
        </div>
      </div>
    </Card>

    <!-- 统计卡片 -->
    <div v-if="statisticsData" class="statistics-grid">
      <Card class="stat-card">
        <div class="stat-value">{{ statisticsData.todayCount || 0 }}</div>
        <div class="stat-label">今日核销</div>
      </Card>
      <Card class="stat-card">
        <div class="stat-value">{{ statisticsData.totalCount || 0 }}</div>
        <div class="stat-label">总核销数</div>
      </Card>
      <Card class="stat-card">
        <div class="stat-value">{{ statisticsData.pendingCount || 0 }}</div>
        <div class="stat-label">待处理订单</div>
      </Card>
    </div>

    <!-- 最近核销记录 -->
    <Card class="history-card">
      <div class="card-title-row">
        <h2>最近核销记录</h2>
        <Button variant="ghost" size="sm" @click="loadHistory">
          刷新
        </Button>
      </div>

      <Table
        v-if="recentVerifications.length > 0"
        :columns="historyColumns"
        :data="recentVerifications"
        :loading="loadingHistory"
        :pagination="{ pageSize: 10, current: 1, total: recentVerifications.length }"
      >
        <template #status="{ row }">
          <span :class="['verify-status', row.isVerified ? 'completed' : 'partial']">
            {{ row.verificationStatus }}
          </span>
        </template>
        <template #time="{ row }">
          {{ formatTime(row.useTime) }}
        </template>
      </Table>

      <!-- 空状态提示 -->
      <div v-else-if="!loadingHistory" class="empty-history">
        <div class="empty-icon">
          <svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" opacity="0.3">
            <path d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2"/>
            <rect x="9" y="3" width="6" height="4" rx="1"/>
            <path d="M9 12l2 2 4-4"/>
          </svg>
        </div>
        <h3>暂无核销记录</h3>
        <p>输入核销码进行验票后，记录将在此处显示</p>
      </div>
    </Card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import Card from '@/components/common/Card.vue'
import Button from '@/components/common/Button.vue'
import Table from '@/components/common/Table.vue'
import {
  CheckCircle,
  AlertCircle,
  Info,
  XCircle
} from 'lucide-vue-next'
import { api } from '@/utils/api'

const verificationCode = ref('')
const verifying = ref(false)
const inputError = ref(false)
const errorMessage = ref('')
const verifySuccess = ref(false)
const orderInfo = ref(null)
const currentTicketIndex = ref(0)
const totalTickets = ref(0)
const verifiedTickets = ref(0)
const statisticsLoading = ref(false)

const codeInput = ref(null)

const loadingHistory = ref(false)
const recentVerifications = ref([])
const statisticsData = ref(null)

const canVerify = computed(() => {
  return verificationCode.value.length === 8 &&
         /^[A-Z0-9]{8}$/.test(verificationCode.value)
})

const historyColumns = [
  { key: 'id', title: '订单号', width: '120px' },
  { key: 'ticketTitle', title: '票种名称', width: '150px' },
  { key: 'showName', title: '演出名称', width: '180px' },
  { key: 'quantity', title: '购票数', width: '80px' },
  { key: 'status', title: '核销状态', width: '140px' },
  { key: 'time', title: '核销时间', width: '160px' }
]

function handleInputChange() {
  if (inputError.value) {
    inputError.value = false
    errorMessage.value = ''
  }
}

async function handleVerify() {
  if (!canVerify.value) return

  verifying.value = true
  inputError.value = false
  verifySuccess.value = false

  try {
    const res = await api.verifyOrder(verificationCode.value.toUpperCase())
    console.log('[核销] 后端响应:', JSON.stringify(res, null, 2))

    if (res.success || res.code === 200) {
      verifySuccess.value = true

      const orderId = res.data?.orderId || res.orderId || extractOrderId(res)
      console.log('[核销] 提取的orderId:', orderId)

      if (!orderId) {
        throw new Error('无法获取订单ID，请联系管理员')
      }

      await loadOrderDetail(orderId)
      await loadHistory()

      await nextTick(() => {
        codeInput.value?.focus()
      })
    }
  } catch (error) {
    console.error('[核销] 错误详情:', error)
    inputError.value = true
    errorMessage.value = error.message || '核销失败，请检查核销码是否正确'
  } finally {
    verifying.value = false
  }
}

async function loadOrderDetail(orderId) {
  try {
    const res = await api.getOrderById(orderId)
    if (res.data) {
      orderInfo.value = res.data

      const codes = parseVerificationCodes(res.data.verificationCode)
      totalTickets.value = codes.length

      verifiedTickets.value = await countVerifiedTickets(orderId, codes.length)
      currentTicketIndex.value = findCurrentTicketIndex(verificationCode.value, codes)
    }
  } catch (e) {
    console.error('加载订单详情失败:', e)
  }
}

function parseVerificationCodes(codeString) {
  if (!codeString) return []

  try {
    const parsed = JSON.parse(codeString)
    return Array.isArray(parsed) ? parsed : [codeString]
  } catch (e) {
    return [codeString]
  }
}

async function countVerifiedTickets(orderId, totalCount) {
  let count = 0

  for (let i = 0; i < totalCount; i++) {
    try {
      const res = await api.checkVerificationStatus(orderId, i)
      if (res.data?.used) count++
    } catch (e) {

    }
  }

  return count
}

function findCurrentTicketIndex(currentCode, allCodes) {
  return allCodes.findIndex(code => code === currentCode.toUpperCase())
}

function extractOrderId(response) {
  return response.data?.orderId || response.orderId
}

async function loadHistory() {
  loadingHistory.value = true
  try {
    const res = await api.getRecentVerifications()
    recentVerifications.value = res.data || []
  } catch (e) {
    console.error('加载历史记录失败:', e)
  } finally {
    loadingHistory.value = false
  }
}

async function loadStatistics() {
  if (statisticsLoading.value) return
  statisticsLoading.value = true
  try {
    const res = await api.getVerificationStatistics()
    statisticsData.value = res.data || null
  } catch (e) {
    console.error('加载统计数据失败:', e)
  } finally {
    statisticsLoading.value = false
  }
}

function clearInput() {
  verificationCode.value = ''
  inputError.value = false
  errorMessage.value = ''
  verifySuccess.value = false
  orderInfo.value = null
  codeInput.value?.focus()
}

function clearAll() {
  clearInput()
  currentTicketIndex.value = 0
  totalTickets.value = 0
  verifiedTickets.value = 0
}

function continueVerify() {
  clearInput()
  codeInput.value?.focus()
}

function formatTime(timeStr) {
  if (!timeStr) return '-'
  const date = new Date(timeStr)
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

onMounted(async () => {
  await Promise.all([loadHistory(), loadStatistics()])

  await nextTick(() => {
    codeInput.value?.focus()
  })
})
</script>

<style scoped>
.page-container {
  width: 100%;
  min-height: 100vh;
  padding: var(--space-6);
  box-sizing: border-box;
}

.page-header {
  margin-bottom: var(--space-6);
  width: 100%;
}

.page-header h1 {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  color: var(--color-text);
  margin-bottom: var(--space-2);
}

.subtitle {
  color: var(--color-text-light);
  font-size: var(--font-base);
}

/* ===== 卡片间距系统 ===== */
.verification-input-card,
.result-display-card {
  margin-bottom: var(--space-6);
  width: 100%;
}

/* ===== 卡片1：输入验证区 ===== */
.verification-input-card {
  max-width: 700px;
  margin-left: auto;
  margin-right: auto;
}

.card-header-main {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-5);
  margin-bottom: var(--space-8);
  padding-bottom: var(--space-6);
  border-bottom: 2px solid var(--color-border);
}

.header-icon-large {
  width: 72px;
  height: 72px;
  background: linear-gradient(135deg, var(--color-bg), var(--color-accent));
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-primary);
  box-shadow: var(--shadow-md);
}

.header-text {
  text-align: left;
}

.header-text h2 {
  font-size: var(--font-xl);
  color: var(--color-text);
  margin-bottom: var(--space-1);
  font-weight: 700;
}

.header-text p {
  font-size: var(--font-base);
  color: var(--color-text-light);
}

.input-body {
  padding: var(--space-4) 0;
}

.code-input-wrapper {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  width: 100%;
}

.code-input {
  width: 100%;
  padding: var(--space-6) var(--space-5);
  font-size: 32px;
  font-weight: 700;
  letter-spacing: 14px;
  text-align: center;
  text-transform: uppercase;
  border: 3px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-white);
  transition: all var(--transition-base);
  font-family: 'Courier New', monospace;
  height: 80px;
  box-shadow: var(--shadow-inset);
}

.code-input:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: var(--shadow-focus-primary), var(--shadow-inset);
}

.code-input.success {
  border-color: var(--color-success-dark);
  background-color: var(--color-bg-success);
  box-shadow: var(--shadow-glow-success);
}

.code-input:disabled {
  background-color: var(--color-white-off);
  cursor: not-allowed;
  opacity: 0.7;
}

.input-actions {
  display: flex;
  gap: var(--space-4);
  justify-content: center;
}

.input-actions :deep(button) {
  padding: var(--space-4) var(--space-10);
  font-size: var(--font-lg);
  font-weight: 600;
  min-width: 160px;
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}

.input-actions :deep(button:hover:not(:disabled)) {
  transform: translateY(-1px);
  box-shadow: var(--shadow-hover);
}

.input-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  border-radius: var(--radius-md);
  font-size: var(--font-base);
  font-weight: 500;
  margin-top: var(--space-2);
}

.error-hint {
  background: var(--gradient-hint-error);
  color: var(--color-danger-dark);
  border: 1px solid var(--color-border-danger);
}

.info-hint {
  background: var(--gradient-hint-info);
  color: var(--color-info-dark);
  border: 1px solid var(--color-border-info);
}

.hint-icon {
  font-weight: bold;
  font-size: var(--font-xl);
}

/* ===== 结果展示区通用样式 ===== */
.result-display-card {
  max-width: 900px;
  margin-left: auto;
  margin-right: auto;
}

.success-border {
  border-left: 5px solid var(--color-success-dark);
}

.error-border {
  border-left: 5px solid var(--color-danger-dark);
}

.warning-border {
  border-left: 5px solid var(--color-warning-dark);
}

/* 成功结果头部 */
.result-header-success {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  margin-bottom: var(--space-8);
  padding: var(--space-6);
  background: var(--gradient-success);
  border-radius: var(--radius-md);
}

.result-icon-circle {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.result-icon-circle.success {
  background: var(--gradient-icon-success);
  color: var(--color-white);
  box-shadow: var(--shadow-success);
}

.result-icon-circle.error {
  background: var(--gradient-icon-danger);
  color: var(--color-white);
  box-shadow: var(--shadow-danger);
}

.result-title-group h3 {
  font-size: var(--font-xl);
  color: var(--color-success-dark);
  margin-bottom: var(--space-1);
  font-weight: 700;
}

.result-title-group p {
  font-size: var(--font-base);
  color: var(--color-text-light);
}

/* 错误结果头部 */
.result-header-error {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  margin-bottom: var(--space-8);
  padding: var(--space-6);
  background: var(--gradient-danger);
  border-radius: var(--radius-md);
}

.result-header-error .result-title-group h3 {
  color: var(--color-danger-dark);
}

/* 警告结果头部（柔和陶土棕色调） */
.result-header-warning {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  margin-bottom: var(--space-8);
  padding: var(--space-6);
  background: var(--gradient-warning);
  border-radius: var(--radius-md);
}

.result-header-warning .result-title-group h3 {
  color: var(--color-warning-dark);
}

.result-icon-circle.warning {
  background: var(--gradient-icon-warning);
  color: var(--color-white);
  box-shadow: var(--shadow-warning);
}

/* 订单详情网格 */
.order-details-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-5);
  margin-bottom: var(--space-8);
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-4) var(--space-5);
  background: var(--color-bg-alt);
  border-radius: var(--radius-md);
  border: 1px solid transparent;
  transition: all var(--transition-fast);
}

.detail-item:hover {
  border-color: var(--color-border);
  transform: translateX(2px);
}

.detail-item.full-width {
  grid-column: 1 / -1;
}

.detail-label {
  font-size: var(--font-xs);
  color: var(--color-text-light);
  text-transform: uppercase;
  letter-spacing: 1px;
  font-weight: 600;
}

.detail-value {
  font-size: var(--font-lg);
  font-weight: 700;
  color: var(--color-text);
}

.detail-value.highlight {
  color: var(--color-primary);
}

.detail-value.bold {
  font-weight: 800;
}

.progress-wrapper {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-2);
}

.verified-count {
  color: var(--color-success-dark) !important;
  font-size: var(--font-2xl) !important;
}

.total-count {
  color: var(--color-text-light);
  font-size: var(--font-lg);
}

.progress-separator {
  color: var(--color-text-light);
  font-weight: 300;
  font-size: var(--font-xl);
}

.mini-progress-bar {
  flex: 1;
  height: 10px;
  background: var(--color-bg-alt);
  border-radius: var(--radius-sm);
  overflow: hidden;
  box-shadow: var(--shadow-inset-subtle);
}

.mini-progress-fill {
  height: 100%;
  background: var(--gradient-progress);
  border-radius: var(--radius-sm);
  transition: width var(--transition-smooth);
  box-shadow: var(--shadow-progress);
}

.mini-progress-fill[class*="progress-"] {
  /* 动态进度条宽度通过class控制 */
}

.success-text {
  color: var(--color-success-dark);
}

/* 结果底部操作区 */
.result-footer-actions {
  display: flex;
  gap: var(--space-4);
  padding-top: var(--space-6);
  border-top: 2px dashed var(--color-border);
}

/* ===== 历史记录卡片 ===== */
.history-card {
  margin-top: var(--space-6);
  width: 100%;
}

.empty-history {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-12) var(--space-6);
  min-height: 300px;
  background: linear-gradient(135deg, var(--color-bg-alt), var(--color-bg-card));
  border-radius: var(--radius-md);
}

.empty-icon {
  color: var(--color-text-light);
  margin-bottom: var(--space-4);
  opacity: 0.4;
}

.empty-history h3 {
  font-size: var(--font-lg);
  color: var(--color-text-light);
  margin-bottom: var(--space-2);
}

.empty-history p {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  opacity: 0.7;
}

.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-5);
  padding-bottom: var(--space-4);
  border-bottom: 2px solid var(--color-border);
}

.card-title-row h2 {
  font-size: var(--font-lg);
  color: var(--color-text);
  font-weight: 700;
}

.verify-status {
  display: inline-block;
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-md);
  font-size: var(--font-sm);
  font-weight: 600;
}

.verify-status.completed {
  background-color: var(--color-bg-success);
  color: var(--color-success-dark);
  border: 1px solid var(--color-border-success);
}

.verify-status.partial {
  background-color: var(--color-bg-warning);
  color: var(--color-warning-dark);
  border: 1px solid var(--color-border-warning);
}

/* ===== 响应式设计 ===== */
@media (max-width: 1200px) {
  .stats-grid {
    grid-template-columns: 1fr;
    gap: var(--space-4);
    padding: var(--space-5);
  }

  .stat-divider-vertical {
    width: 100%;
    height: 2px;
    background: linear-gradient(to right, transparent, var(--color-border), transparent);
  }

}

@media (max-width: 1024px) {
  .page-container {
    padding: var(--space-4);
  }

  .verification-input-card,
  .result-display-card,
  .history-card {
    max-width: 100%;
  }
}

@media (max-width: 768px) {
  .page-header h1 {
    font-size: var(--font-xl);
  }

  .card-header-main {
    flex-direction: column;
    text-align: center;
    gap: var(--space-3);
  }

  .header-text {
    text-align: center;
  }

  .header-icon-large {
    width: 60px;
    height: 60px;
  }

  .code-input {
    font-size: 26px;
    height: 70px;
    letter-spacing: 10px;
    padding: var(--space-5) var(--space-4);
  }

  .input-actions :deep(button) {
    padding: var(--space-3) var(--space-8);
    font-size: var(--font-base);
    min-width: 140px;
  }

  .order-details-grid {
    grid-template-columns: 1fr;
  }

  .stat-box {
    padding: var(--space-3) var(--space-4);
  }

  .stat-value {
    font-size: var(--font-xl);
  }

  .result-header-success,
  .result-header-error,
  .result-header-warning {
    flex-direction: column;
    text-align: center;
    padding: var(--space-5);
  }

  .result-footer-actions {
    flex-direction: column;
  }

  .result-footer-actions :deep(button) {
    width: 100%;
  }
}

/* ===== 统计卡片 ===== */
.statistics-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-4);
  margin-bottom: var(--space-6);
}

.stat-card {
  text-align: center;
  padding: var(--space-6) var(--space-4);
}

.stat-value {
  font-size: var(--font-3xl);
  font-weight: 700;
  color: var(--color-primary);
  margin-bottom: var(--space-2);
}

.stat-label {
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

@media (max-width: 768px) {
  .statistics-grid {
    grid-template-columns: 1fr;
  }
}
</style>
