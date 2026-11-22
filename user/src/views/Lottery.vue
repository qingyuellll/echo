<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api, isLoggedIn } from '../utils/api.js'
import { ChevronLeft, Gift, Star, RotateCcw, Trophy, History, X } from 'lucide-vue-next'

const router = useRouter()

const LOTTERY_COST = 20

const pointsInfo = ref(null)
const lotteryResult = ref(null)
const isSpinning = ref(false)
const showResultModal = ref(false)
const lotteryHistory = ref([])
const showHistory = ref(false)
const todayLotteryCount = ref(0)
const dailyLimit = ref(3)

const balance = computed(() => pointsInfo.value?.points ?? pointsInfo.value?.balance ?? 0)

const remainingLotteries = computed(() => Math.max(0, dailyLimit.value - todayLotteryCount.value))

const canLottery = computed(() =>
  !isSpinning.value &&
  pointsInfo.value &&
  remainingLotteries.value > 0 &&
  balance.value >= LOTTERY_COST
)

const maxPossibleLotteries = computed(() => Math.floor(balance.value / LOTTERY_COST))

function goBack() {
  router.back()
}

async function loadPoints() {
  try {
    const res = await api.getPoints()
    if (res?.data) {
      console.log('[Lottery] 积分数据:', {
        points: res.data.points,
        todayCount: res.data.todayCount,
        dailyLimit: res.data.dailyLimit
      })

      pointsInfo.value = res.data
      todayLotteryCount.value = res.data.todayCount ?? 0
      dailyLimit.value = res.data.dailyLimit ?? 3

      console.log('[Lottery] 更新后的状态:', {
        balance: balance.value,
        remainingLotteries: remainingLotteries.value,
        canLottery: canLottery.value
      })
    }
  } catch (e) {
    console.error('[Lottery] 获取积分失败:', e)
  }
}

async function loadLotteryHistory() {
  try {
    const res = await api.getLotteryRecords()
    if (res?.data?.records) {
      lotteryHistory.value = res.data.records
    }
  } catch (e) {
    console.error('[Lottery] 获取抽奖记录失败:', e)
  }
}

function getBlockReason() {
  if (remainingLotteries.value <= 0) {
    return `今日抽奖次数已用完！\n\n每日可抽奖 ${dailyLimit.value} 次\n明天 00:00 后重置`
  }
  if (balance.value < LOTTERY_COST) {
    return `积分不足！需要${LOTTERY_COST}积分才能参与抽奖`
  }
  return null
}

async function startLottery() {
  if (!canLottery.value) {
    const reason = getBlockReason()
    if (reason) alert(reason)
    return
  }

  isSpinning.value = true
  lotteryResult.value = null

  try {
    const res = await api.lottery()

    if (res?.data) {
      lotteryResult.value = res.data

      console.log('[Lottery] 抽奖结果:', {
        todayCount: res.data.todayCount,
        dailyLimit: res.data.dailyLimit,
        rewardPoints: res.data.rewardPoints,
        prize: res.data.prize
      })

      todayLotteryCount.value = res.data.todayCount ?? todayLotteryCount.value + 1
      dailyLimit.value = res.data.dailyLimit ?? dailyLimit.value

      console.log('[Lottery] 更新后的抽奖次数:', {
        todayLotteryCount: todayLotteryCount.value,
        remainingLotteries: remainingLotteries.value
      })

      setTimeout(() => {
        showResultModal.value = true
        loadLotteryHistory()
      }, 800)

      await loadPoints()
    }
  } catch (e) {
    console.error('[Lottery] 抽奖失败:', e)
    alert('抽奖失败：' + (e.message || '请稍后重试'))
  } finally {
    setTimeout(() => {
      isSpinning.value = false
    }, 1000)
  }
}

function closeModal() {
  showResultModal.value = false
}

function formatTime(timeStr) {
  if (!timeStr) return ''
  const date = new Date(timeStr)
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function getPrizeClass(rewardPoints) {
  if (rewardPoints >= 200) return 'prize-first'
  if (rewardPoints >= 100) return 'prize-second'
  if (rewardPoints >= 50) return 'prize-third'
  return 'prize-consolation'
}

function getPrizeTitle(rewardPoints) {
  if (rewardPoints >= 200) return '恭喜抽中一等奖！'
  if (rewardPoints >= 100) return '恭喜抽中二等奖！'
  if (rewardPoints >= 50) return '恭喜抽中三等奖！'
  return '感谢参与！'
}

onMounted(async () => {
  if (!isLoggedIn()) {
    await router.push('/login?redirect=/lottery')
    return
  }

  await Promise.all([loadPoints(), loadLotteryHistory()])
})
</script>

<template>
  <div class="lottery-page">
    <div class="lottery-inner">
      <button class="lottery-back-btn" @click="goBack">
        <ChevronLeft :size="20" />
        <span>返回</span>
      </button>

      <div class="lottery-header">
        <Gift :size="48" class="lottery-icon" />
        <h1 class="lottery-title">幸运抽奖</h1>
        <p class="lottery-subtitle">每次消耗{{ LOTTERY_COST }}积分，赢取丰厚奖励</p>
      </div>

      <div class="balance-card" v-if="pointsInfo">
        <div class="balance-label">当前积分</div>
        <div class="balance-amount">
          <Star :size="24" class="balance-icon" />
          <span class="balance-number">{{ balance.toLocaleString() }}</span>
          <span class="balance-unit">分</span>
        </div>
        <div class="balance-hint">可抽奖 {{ maxPossibleLotteries }} 次</div>
      </div>

      <div class="lottery-wheel-container">
        <div :class="['lottery-wheel', { spinning: isSpinning }]">
          <div class="wheel-center">
            <Trophy :size="32" :class="{ active: isSpinning }" />
          </div>
        </div>
      </div>

      <div class="lottery-count-hint" v-if="pointsInfo">
        <span class="count-text">当前积分</span>
        <span class="count-number">{{ balance.toLocaleString() }}</span>
        <span class="count-unit">分</span>
        <span class="count-separator">|</span>
        <Gift :size="14" />
        <span>今日剩余 {{ remainingLotteries }}/{{ dailyLimit }} 次</span>
      </div>

      <button
        :class="['lottery-btn', { disabled: !canLottery, exhausted: remainingLotteries <= 0 }]"
        :disabled="!canLottery"
        @click="startLottery"
      >
        <template v-if="isSpinning">
          <RotateCcw :size="20" class="spin-icon" />
          <span>抽奖中...</span>
        </template>
        <template v-else-if="remainingLotteries <= 0">
          <X :size="20" />
          <span>今日次数已用完</span>
        </template>
        <template v-else-if="balance < LOTTERY_COST">
          <span>积分不足（需要{{ LOTTERY_COST }}分）</span>
        </template>
        <template v-else>
          <Gift :size="20" />
          <span>立即抽奖</span>
        </template>
      </button>

      <button class="history-btn" @click="showHistory = !showHistory">
        <History :size="18" />
        <span>{{ showHistory ? '隐藏记录' : '查看抽奖记录' }}</span>
        <span class="history-count" v-if="lotteryHistory.length">{{ lotteryHistory.length }}条</span>
      </button>

      <div v-if="showHistory" class="history-panel">
        <h3 class="history-title">抽奖记录</h3>
        <div v-if="!lotteryHistory.length" class="history-empty">暂无抽奖记录</div>
        <div v-else class="history-list">
          <div
            v-for="(record, index) in lotteryHistory"
            :key="index"
            :class="['history-item', record.points > 0 ? 'win' : 'cost']"
          >
            <div class="history-info">
              <span class="history-type">{{ record.description || '抽奖' }}</span>
              <span class="history-time">{{ formatTime(record.createTime) }}</span>
            </div>
            <span :class="['history-points', record.points > 0 ? 'positive' : 'negative']">
              {{ record.points > 0 ? '+' : '' }}{{ record.points }}
            </span>
          </div>
        </div>
      </div>

      <div class="lottery-rules">
        <h3 class="rules-title">抽奖规则</h3>
        <ul class="rules-list">
          <li>每次抽奖消耗{{ LOTTERY_COST }}积分</li>
          <li><strong>每日限抽 {{ dailyLimit }} 次</strong>，次日 00:00 自动重置</li>
          <li>奖品设置（100%中奖）：</li>
          <li style="margin-left: 20px; list-style: disc;">一等奖：200积分（概率5%）</li>
          <li style="margin-left: 20px; list-style: disc;">二等奖：100积分（概率15%）</li>
          <li style="margin-left: 20px; list-style: disc;">三等奖：50积分（概率30%）</li>
          <li style="margin-left: 20px; list-style: disc;">参与奖：10积分（概率50%）</li>
          <li>积分可通过购票、评价、签到等方式获得</li>
        </ul>
      </div>
    </div>

    <Transition name="modal">
      <div v-if="showResultModal && lotteryResult" class="modal-overlay" @click="closeModal">
        <div class="modal-content" @click.stop>
          <button class="modal-close" @click="closeModal">
            <X :size="24" />
          </button>

          <div class="modal-icon">
            <Trophy :size="64" :class="getPrizeClass(lotteryResult.rewardPoints)" />
          </div>

          <h2 class="modal-title">
            {{ getPrizeTitle(lotteryResult.rewardPoints) }}
          </h2>

          <div class="modal-prize">
            {{ lotteryResult.prize || '神秘奖品' }}
          </div>

          <div class="modal-points" v-if="lotteryResult.rewardPoints !== undefined">
            <Star :size="20" />
            <span>获得 {{ lotteryResult.rewardPoints }} 积分</span>
          </div>

          <button class="modal-btn" @click="closeModal">
            太棒了
          </button>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.lottery-page {
  padding: var(--space-12) var(--space-6);
  background-color: var(--color-bg);
  min-height: calc(100vh - 60px);
}

.lottery-inner {
  max-width: 500px;
  margin: 0 auto;
}

.lottery-back-btn {
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
  margin-bottom: var(--space-8);
  width: fit-content;
}

.lottery-back-btn:hover {
  color: var(--color-text);
  background-color: var(--color-bg-alt);
  border-color: var(--color-primary);
}

.lottery-header {
  text-align: center;
  margin-bottom: var(--space-8);
}

.lottery-icon {
  color: var(--color-secondary);
  margin-bottom: var(--space-4);
  animation: float 3s ease-in-out infinite;
}

@keyframes float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-10px); }
}

.lottery-title {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-text);
  margin-bottom: var(--space-2);
}

.lottery-subtitle {
  font-size: var(--font-base);
  color: var(--color-text-light);
}

.balance-card {
  background: linear-gradient(135deg, #a6baae15 0%, #ece0ca30 100%);
  border: 1px solid rgba(166, 186, 174, 0.3);
  border-radius: var(--radius-md);
  padding: var(--space-6);
  text-align: center;
  margin-bottom: var(--space-8);
  box-shadow: var(--shadow-sm);
}

.balance-label {
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-text-light);
  margin-bottom: var(--space-3);
}

.balance-amount {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}

.balance-icon {
  color: var(--color-secondary);
}

.balance-number {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-accent);
  line-height: 1;
}

.balance-unit {
  font-size: var(--font-lg);
  font-weight: 500;
  color: var(--color-text-light);
}

.balance-hint {
  font-size: var(--font-xs);
  color: var(--color-primary);
  font-weight: 600;
}

.daily-lottery-info {
  margin-top: var(--space-4);
  padding: var(--space-3) var(--space-4);
  background: linear-gradient(135deg, rgba(166, 186, 174, 0.1) 0%, rgba(236, 224, 202, 0.1) 100%);
  border-radius: var(--radius-md);
  border: 2px solid rgba(166, 186, 174, 0.15);
}

.daily-lottery-info.exhausted {
  background: linear-gradient(135deg, rgba(255, 77, 79, 0.08) 0%, rgba(250, 173, 20, 0.08) 100%);
  border-color: rgba(255, 77, 79, 0.2);
}

.daily-label {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-sm);
  color: var(--color-text-light);
  font-weight: 500;
  margin-bottom: var(--space-2);
}

.daily-count {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: var(--space-1);
}

.count-num {
  font-family: var(--font-heading);
  font-size: var(--font-xl);
  font-weight: 800;
  color: var(--color-primary);
  line-height: 1;
  transition: all var(--transition-base);
}

.count-num.warning {
  color: var(--color-warning);
  animation: pulse 1s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(1.05); opacity: 0.8; }
}

.count-total {
  font-size: var(--font-lg);
  color: var(--color-text-light);
  font-weight: 500;
}

.lottery-wheel-container {
  display: flex;
  justify-content: center;
  margin-bottom: var(--space-8);
}

.lottery-wheel {
  width: 280px;
  height: 280px;
  border-radius: 50%;
  background: linear-gradient(135deg, #f9f8f4 0%, #ece0ca 50%, #a6baae20 100%);
  border: 3px solid var(--color-primary);
  box-shadow:
    0 10px 40px rgba(166, 186, 174, 0.25),
    inset 0 0 20px rgba(255, 255, 255, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  transition: transform 0.5s ease;
}

.lottery-wheel.spinning {
  animation: spin 2s ease-in-out;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  25% { transform: rotate(90deg); }
  50% { transform: rotate(180deg); }
  75% { transform: rotate(270deg); }
  100% { transform: rotate(360deg); }
}

.wheel-center {
  width: 120px;
  height: 120px;
  border-radius: 50%;
  background: linear-gradient(135deg, #ffffff 0%, #f9f8f4 100%);
  border: 2px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
}

.wheel-center svg {
  color: var(--color-primary);
  transition: all 0.3s ease;
}

.wheel-center svg.active {
  color: var(--color-secondary);
  transform: scale(1.1);
}

.lottery-count-hint {
  text-align: center;
  margin-bottom: var(--space-4);
  padding: var(--space-3) var(--space-4);
  background-color: rgba(166, 186, 174, 0.08);
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-1);
  flex-wrap: wrap;
}

.count-text {
  font-size: var(--font-base);
  color: var(--color-text-light);
  font-weight: 500;
}

.count-number {
  font-size: var(--font-xl);
  font-weight: 700;
  color: var(--color-primary);
  line-height: 1;
}

.count-unit {
  font-size: var(--font-sm);
  color: var(--color-primary);
  font-weight: 600;
}

.count-separator {
  color: rgba(166, 186, 174, 0.4);
  margin: 0 var(--space-1);
}

.lottery-btn {
  width: 100%;
  padding: var(--space-4);
  font-size: var(--font-lg);
  font-weight: 700;
  border-radius: var(--radius-md);
  background-color: var(--color-primary);
  color: white;
  border: none;
  cursor: pointer;
  transition: all var(--transition-fast);
  box-shadow: var(--shadow-md);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}

.lottery-btn:hover:not(.disabled) {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px var(--color-shadow);
}

.lottery-btn:active:not(.disabled) {
  transform: translateY(0);
}

.lottery-btn.disabled {
  opacity: 0.6;
  cursor: not-allowed;
  background-color: var(--color-text-light);
  box-shadow: none;
}

.lottery-btn.exhausted {
  background-color: var(--color-danger);
  box-shadow: var(--shadow-md);
  animation: shake 2s ease-in-out infinite;
}

@keyframes shake {
  0%, 100% { transform: translateX(0); }
  25% { transform: translateX(-2px); }
  75% { transform: translateX(2px); }
}

.spin-icon {
  animation: spin 1s linear infinite;
}

.history-btn {
  width: 100%;
  padding: var(--space-3) var(--space-4);
  font-size: var(--font-base);
  font-weight: 500;
  color: var(--color-text-light);
  background-color: var(--color-white);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}

.history-btn:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
  background-color: rgba(166, 186, 174, 0.05);
}

.history-count {
  font-size: var(--font-xs);
  background-color: var(--color-primary);
  color: white;
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  font-weight: 600;
}

.history-panel {
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  padding: var(--space-5);
  margin-bottom: var(--space-6);
  box-shadow: var(--shadow-sm);
  max-height: 300px;
  overflow-y: auto;
}

.history-title {
  font-size: var(--font-lg);
  font-weight: 700;
  color: var(--color-text);
  margin-bottom: var(--space-4);
  text-align: center;
}

.history-empty {
  text-align: center;
  color: var(--color-text-light);
  font-size: var(--font-sm);
  padding: var(--space-6) 0;
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.history-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-sm);
  background-color: var(--color-bg);
  transition: all var(--transition-fast);
}

.history-item:hover {
  background-color: var(--color-bg-alt);
}

.history-item.win {
  border-left: 3px solid var(--color-success);
}

.history-item.cost {
  border-left: 3px solid var(--color-danger);
}

.history-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.history-type {
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-text);
}

.history-time {
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

.history-points {
  font-size: var(--font-lg);
  font-weight: 700;
}

.history-points.positive {
  color: var(--color-success-dark);
}

.history-points.negative {
  color: var(--color-danger-dark);
}

.lottery-rules {
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  padding: var(--space-6);
  box-shadow: var(--shadow-sm);
}

.rules-title {
  font-size: var(--font-lg);
  font-weight: 700;
  color: var(--color-text);
  margin-bottom: var(--space-4);
  text-align: center;
}

.rules-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.rules-list li {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  padding: var(--space-2) 0;
  line-height: 1.6;
  position: relative;
  padding-left: var(--space-5);
}

.rules-list li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 12px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: var(--color-primary);
}

.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: var(--space-4);
}

.modal-content {
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  padding: var(--space-8) var(--space-6);
  max-width: 420px;
  width: 100%;
  text-align: center;
  position: relative;
  box-shadow: var(--shadow-md);
  animation: modalIn 0.4s cubic-bezier(0.34, 1.56, 0.64, 1);
  border: 2px solid var(--color-border);
}

@keyframes modalIn {
  from {
    opacity: 0;
    transform: scale(0.8) translateY(30px) rotate(-2deg);
  }
  60% {
    opacity: 1;
    transform: scale(1.03) translateY(-5px) rotate(1deg);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0) rotate(0deg);
  }
}

.modal-close {
  position: absolute;
  top: var(--space-4);
  right: var(--space-4);
  background-color: var(--color-white);
  border: 2px solid var(--color-border);
  border-radius: 50%;
  cursor: pointer;
  color: var(--color-text-light);
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all var(--transition-fast);
}

.modal-close:hover {
  color: var(--color-text);
  background-color: white;
  border-color: var(--color-primary);
  transform: rotate(90deg);
}

.modal-icon {
  margin-bottom: var(--space-6);
  margin-top: var(--space-2);
}

.modal-icon svg.prize-first {
  color: var(--color-danger);
  animation: trophyBounce 0.8s cubic-bezier(0.34, 1.56, 0.64, 1);
  filter: drop-shadow(0 4px 16px rgba(196, 135, 123, 0.5));
}

.modal-icon svg.prize-second {
  color: var(--color-secondary);
  animation: trophyBounce 0.8s cubic-bezier(0.34, 1.56, 0.64, 1);
  filter: drop-shadow(0 4px 12px rgba(150, 116, 98, 0.4));
}

.modal-icon svg.prize-third {
  color: var(--color-success);
  animation: trophyBounce 0.8s cubic-bezier(0.34, 1.56, 0.64, 1);
  filter: drop-shadow(0 4px 12px rgba(122, 158, 126, 0.4));
}

.modal-icon svg.prize-consolation {
  color: var(--color-info);
  animation: trophyBounce 0.6s ease-out;
  filter: drop-shadow(0 2px 8px rgba(138, 159, 184, 0.3));
}

@keyframes trophyBounce {
  0% { transform: scale(0) rotate(-180deg); opacity: 0; }
  50% { transform: scale(1.25) rotate(10deg); }
  70% { transform: scale(0.95) rotate(-5deg); }
  100% { transform: scale(1) rotate(0deg); opacity: 1; }
}

.modal-title {
  font-family: var(--font-heading);
  font-size: var(--font-xl);
  font-weight: 800;
  color: var(--color-text);
  margin-bottom: var(--space-6);
  line-height: 1.3;
}

.modal-prize {
  font-size: var(--font-lg);
  font-weight: 700;
  color: var(--color-primary);
  margin-bottom: var(--space-5);
  padding: var(--space-4) var(--space-6);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  line-height: 1.5;
  box-shadow: var(--shadow-sm);
}

.modal-points {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-success);
  margin-bottom: var(--space-6);
  padding: var(--space-3) var(--space-4);
  background-color: rgba(122, 158, 126, 0.08);
  border-radius: var(--radius-md);
  border: 1px solid rgba(122, 158, 126, 0.2);
}

.modal-points svg {
  color: var(--color-secondary);
}

.modal-btn {
  width: 100%;
  padding: var(--space-4) var(--space-8);
  font-size: var(--font-lg);
  font-weight: 700;
  border-radius: var(--radius-md);
  background-color: var(--color-primary);
  color: white;
  border: none;
  cursor: pointer;
  transition: all var(--transition-base);
  box-shadow: var(--shadow-md);
}

.modal-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px var(--color-shadow);
}

.modal-btn:active {
  transform: translateY(-1px) scale(0.98);
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
</style>
