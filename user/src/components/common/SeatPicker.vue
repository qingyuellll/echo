<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '@/utils/api.js'

const props = defineProps({
  ticketId: {
    type: [Number, String],
    required: true
  },
  maxSelect: {
    type: Number,
    default: 10  // 普通票默认允许选10个座位
  },
  isSeckill: {
    type: Boolean,
    default: false  // 是否为秒杀票（与普通票完全独立）
  }
})


/**
 * 【普通票逻辑】
 * - maxSelect由父组件传入（基于用户选择的数量）
 * - 默认最多选10个座位
 * - 提示信息："最多只能选择 X 个座位"
 */
const normalTicketConfig = computed(() => ({
  maxSelect: props.maxSelect || 10,
  limitHint: (max) => `最多只能选择 ${max} 个座位`,
  title: '选择座位（普通票）'
}))

/**
 * 【秒杀票逻辑】
 * - 固定限制1个座位（不受maxSelect影响）
 * - 独立的提示信息："秒杀票限购1个座位"
 * - 与普通票完全隔离的业务规则
 */
const seckillTicketConfig = computed(() => ({
  maxSelect: 1,
  limitHint: () => '秒杀票限购1个座位',
  title: '选择座位（秒杀票）'
}))

/**
 * 当前票种的配置（根据isSeckill动态切换）
 * 保证两种票种的逻辑完全独立，互不干扰
 */
const currentConfig = computed(() => {
  return props.isSeckill ? seckillTicketConfig.value : normalTicketConfig.value
})

// 兼容旧代码的别名（内部统一使用currentConfig）
const effectiveMaxSelect = computed(() => currentConfig.value.maxSelect)

const emit = defineEmits(['select', 'load-complete', 'ticket-offline'])

const seats = ref([])
const loading = ref(true)
const selectedSeats = ref([])
const error = ref('')

const seatStatusMap = {
  0: { label: '可选', class: 'seat-available' },
  1: { label: '已锁', class: 'seat-locked' },
  2: { label: '已售', class: 'seat-sold' }
}

const areas = computed(() => {
  const areaMap = new Map()
  seats.value.forEach(seat => {
    if (!areaMap.has(seat.area)) {
      areaMap.set(seat.area, [])
    }
    areaMap.get(seat.area).push(seat)
  })
  return areaMap
})

const selectedCount = computed(() => selectedSeats.value.length)
const canConfirm = computed(() => selectedCount.value > 0 && selectedCount.value <= effectiveMaxSelect.value)

function isSelected(seatId) {
  return selectedSeats.value.some(s => s.id === seatId)
}

function canSelectSeat(seat) {
  return seat.status === 0 && !isSelected(seat.id) && selectedCount.value < effectiveMaxSelect.value
}

/**
 * 将座位按行分组
 * @param {Array} seats - 某个区域的所有座位
 * @returns {Array} 二维数组，每个子数组是一行的座位
 */
function groupByRow(seats) {
  const rowMap = new Map()

  // 先按rowNum排序，确保顺序正确
  const sortedSeats = [...seats].sort((a, b) => (a.rowNum || 0) - (b.rowNum || 0))

  sortedSeats.forEach(seat => {
    const rowNum = seat.rowNum || 1
    if (!rowMap.has(rowNum)) {
      rowMap.set(rowNum, [])
    }
    rowMap.get(rowNum).push(seat)
  })

  // 返回按行号排序的数组
  return Array.from(rowMap.entries())
    .sort((a, b) => a[0] - b[0])
    .map(([rowNum, rowSeats]) => rowSeats.sort((a, b) => (a.colNum || 0) - (b.colNum || 0)))
}

/**
 * 获取行的显示标签（字母或数字）
 * @param {number} rowIndex - 行索引（从1开始）
 * @returns {string} 行标签（如 "A", "B", "C" 或 "1排", "2排"）
 */
function getRowLabel(rowIndex) {
  // 使用字母表示：A, B, C... Z, AA, AB...
  if (rowIndex <= 26) {
    return String.fromCharCode(64 + rowIndex)  // A=65, 所以64+1=65='A'
  }

  // 超过26行时使用数字格式
  return `${rowIndex}排`
}

async function toggleSeat(seat) {
  if (seat.status !== 0) return

  if (isSelected(seat.id)) {
    // ⚠️ 并发安全检查：释放座位前再次验证票种状态
    try {
      const statusCheck = await api.checkTicketStatus(props.ticketId)

      if (!statusCheck || !statusCheck.data) {
        throw new Error('票种状态查询失败')
      }

      // 如果票种已下架或不可用
      if (statusCheck.data.status !== 1) {
        console.warn('[SeatPicker] ⚠️ 票种状态异常，无法释放座位, status:', statusCheck.data.status)
        emit('ticket-offline', '该票种已下架或不可用，选座已终止')
        return
      }
    } catch (checkError) {
      console.error('[SeatPicker] 票种状态检查失败:', checkError)
      // 即使检查失败也允许继续（降级处理），避免阻塞用户操作
    }

    try {
      await api.releaseSeat(props.ticketId, seat.id)
      selectedSeats.value = selectedSeats.value.filter(s => s.id !== seat.id)
      emit('select', [...selectedSeats.value])
    } catch (e) {
      console.error('释放座位失败:', e)
      import('../../utils/message').then(({ showMessage }) => {
        showMessage('座位释放失败，请重试', 'error')
      })
    }
  } else {
      if (selectedCount.value >= effectiveMaxSelect.value) {
        // 使用当前票种配置的提示信息（普通票/秒杀票各自独立）
        const hint = currentConfig.value.limitHint(effectiveMaxSelect.value)
        import('../../utils/message').then(({ showMessage }) => {
          showMessage(hint, 'warning')
        })
        return
      }

    // ⚠️ 并发安全检查：锁定座位前验证票种状态（关键！）
    try {
      const statusCheck = await api.checkTicketStatus(props.ticketId)

      if (!statusCheck || !statusCheck.data) {
        throw new Error('票种状态查询失败')
      }

      // 如果票种已下架或不可用 → 阻止锁座
      if (statusCheck.data.status !== 1) {
        console.warn('[SeatPicker] ❌ 票种已下架，禁止锁座, status:', statusCheck.data.status)

        let errorMsg = '该票种已下架或暂停销售'
        if (statusCheck.data.status === 0) {
          errorMsg = '该票种已被管理员下架，无法继续选座'
        } else if (statusCheck.data.status === 2) {
          errorMsg = '该票种已停用，无法购买'
        }

        emit('ticket-offline', errorMsg)
        import('../../utils/message').then(({ showMessage }) => {
          showMessage(errorMsg, 'error', 5000)
        })
        return
      }

      console.log('[SeatPicker] ✅ 票种状态正常, status:', statusCheck.data.status)

    } catch (checkError) {
      console.error('[SeatPicker] 票种状态检查失败:', checkError)

      // 检查失败时的处理策略：
      // 方案A：阻止操作（更安全）
      // alert('无法验证票种状态，为保障您的权益，暂时无法选座。请刷新页面重试。')
      // return

      // 方案B：允许继续但记录警告（用户体验更好）← 当前采用
      console.warn('[SeatPicker] ⚠️ 状态检查失败，允许继续操作（降级模式）')
    }

    try {
      await api.lockSeat(props.ticketId, seat.id)
      selectedSeats.value.push(seat)
      emit('select', [...selectedSeats.value])
    } catch (e) {
      console.error('锁定座位失败:', e)

      // 特殊错误处理
      if (e.code === 'TICKET_OFFLINE' || e.message?.includes('已下架')) {
        emit('ticket-offline', e.message || '票种已下架')
        return
      }

      if (e.code === 'SEAT_LOCKED' || e.message?.includes('被占用') || e.message?.includes('已被锁定')) {
        import('../../utils/message').then(({ showMessage }) => {
          showMessage('该座位刚刚被其他人抢先选择了，请选择其他座位', 'warning')
        })

        // 自动刷新座位列表以获取最新状态
        setTimeout(() => {
          loadSeats()
        }, 500)
        return
      }

      import('../../utils/message').then(({ showMessage }) => {
        showMessage(e.message || '座位锁定失败，请重试', 'error')
      })
    }
  }
}

async function loadSeats() {
  try {
    loading.value = true
    error.value = ''

    console.log('[SeatPicker] 开始加载座位, ticketId:', props.ticketId)

    const res = await api.getSeatsByTicket(props.ticketId)

    console.log('[SeatPicker] 座位数据响应:', res)

    if (!res || !res.data) {
      throw new Error('座位数据异常：响应为空')
    }

    seats.value = res.data || []

    console.log('[SeatPicker] ✅ 座位加载成功, 数量:', seats.value.length)

    if (seats.value.length === 0) {
      console.warn('[SeatPicker] ⚠️ 该票种暂无座位数据！')
      console.warn('[SeatPicker] 可能原因:')
      console.warn('  1. 管理员尚未生成座位（需在管理端点击"生成座位"按钮）')
      console.warn('  2. 票种配置错误（hasSeats字段应为true）')
      console.warn('  3. 数据库连接异常')

      error.value = '该票种暂无座位信息\n\n请确认：\n• 管理员已在管理端生成座位\n• 该票种需要选座购票'
    } else {
      emit('load-complete', seats.value)
    }
  } catch (e) {
    console.error('[SeatPicker] ❌ 加载座位失败:', e)

    let errorMsg = '加载座位信息失败'

    if (e.message?.includes('401') || e.message?.includes('未授权')) {
      errorMsg = '请先登录后再选择座位'
    } else if (e.message?.includes('404') || e.message?.includes('不存在')) {
      errorMsg = '票种不存在或已被删除'
    } else if (e.message?.includes('网络') || e.message?.includes('timeout')) {
      errorMsg = '网络连接失败，请检查网络'
    } else if (e.message) {
      errorMsg += ': ' + e.message
    }

    errorMsg += '\n\n建议操作：\n• 刷新页面重试\n• 返回演出详情页重新选择票种\n• 联系管理员确认座位已生成'

    error.value = errorMsg
  } finally {
    loading.value = false
  }
}

onMounted(loadSeats)

defineExpose({ loadSeats, selectedSeats })
</script>

<template>
  <div class="seat-picker">
    <div class="picker-header">
      <h3 class="picker-title">{{ currentConfig.title }}</h3>
      <div class="picker-info">
        <span class="info-text">已选: <strong>{{ selectedCount }}/{{ effectiveMaxSelect }}</strong></span>
      </div>
    </div>

    <div v-if="loading" class="picker-loading">
      <div class="loading-spinner"></div>
      <span>加载座位图中...</span>
    </div>

    <div v-else-if="error" class="picker-error">
      <p>{{ error }}</p>
      <button class="retry-btn" @click="loadSeats">重新加载</button>
    </div>

    <div v-else class="picker-content">
      <div class="stage-indicator">舞台</div>

      <div v-for="[area, areaSeats] in areas" :key="area" class="seat-area">
        <h4 class="area-name">{{ area }}</h4>

        <!-- 按行分组显示座位 -->
        <div v-for="(rowSeats, rowIndex) in groupByRow(areaSeats)" :key="rowIndex" class="seat-row">
          <div class="row-label">{{ getRowLabel(rowIndex + 1) }}</div>
          <div class="seat-grid-row">
            <div
              v-for="seat in rowSeats"
              :key="seat.id"
              :class="[
                'seat',
                seatStatusMap[seat.status]?.class,
                { 'seat-selected': isSelected(seat.id) }
              ]"
              :title="`${area} 第${getRowLabel(rowIndex + 1)}排 第${seat.colNum}号 - ${seatStatusMap[seat.status]?.label}`"
              @click="toggleSeat(seat)"
            >
              <span class="seat-label">{{ seat.colNum }}</span>
            </div>
          </div>
        </div>
      </div>

      <div v-if="seats.length === 0" class="empty-state">
        <p>暂无座位信息</p>
      </div>
    </div>

    <div class="picker-legend">
      <div class="legend-item">
        <div class="legend-seat seat-available"></div>
        <span>可选</span>
      </div>
      <div class="legend-item">
        <div class="legend-seat seat-selected"></div>
        <span>已选</span>
      </div>
      <div class="legend-item">
        <div class="legend-seat seat-locked"></div>
        <span>已锁</span>
      </div>
      <div class="legend-item">
        <div class="legend-seat seat-sold"></div>
        <span>已售</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.seat-picker {
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  padding: var(--space-6);
  box-shadow: var(--shadow-sm);
}

.picker-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-6);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-border);
}

.picker-title {
  font-family: var(--font-heading);
  font-size: var(--font-lg);
  color: var(--color-text);
}

.picker-info {
  display: flex;
  align-items: center;
  gap: var(--space-4);
}

.info-text {
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.confirm-btn {
  background-color: var(--color-primary);
  color: var(--color-white);
  font-family: var(--font-body);
  font-size: var(--font-sm);
  font-weight: 500;
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-sm);
  transition: all var(--transition-base);
}

.confirm-btn:hover:not(:disabled) {
  background-color: var(--color-secondary);
}

.picker-loading,
.picker-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  min-height: 200px;
  color: var(--color-text-light);
}

.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--color-border);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.retry-btn {
  background-color: var(--color-primary);
  color: var(--color-white);
  font-size: var(--font-sm);
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-sm);
}

.picker-content {
  margin-bottom: var(--space-6);
}

.stage-indicator {
  text-align: center;
  font-family: var(--font-heading);
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-primary);
  padding: var(--space-3);
  margin-bottom: var(--space-6);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-sm);
}

.seat-area {
  margin-bottom: var(--space-8);
}

.area-name {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-primary);
  text-align: center;
  padding: var(--space-2) var(--space-3);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-sm);
  margin-bottom: var(--space-4);
}

/* 行容器 */
.seat-row {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
  padding: var(--space-1) 0;
}

/* 行标签（A, B, C...） */
.row-label {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-xs);
  font-weight: 700;
  color: var(--color-primary);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-sm);
  flex-shrink: 0;
}

/* 每行的座位网格 */
.seat-grid-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  justify-content: flex-start;
}

.seat {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm) var(--radius-sm) 4px 4px;
  font-size: var(--font-xs);
  font-weight: 500;
  cursor: pointer;
  transition: all var(--transition-base);
  position: relative;
}

.seat::before {
  content: '';
  position: absolute;
  top: 0;
  left: 20%;
  right: 20%;
  height: 4px;
  background-color: inherit;
  border-radius: 2px 2px 0 0;
  opacity: 0.6;
}

.seat-available {
  background-color: var(--color-primary);
  color: var(--color-white);
  border: 1px solid transparent;
}

.seat-available:hover {
  transform: scale(1.1);
  box-shadow: var(--shadow-md);
  z-index: 1;
}

.seat-selected {
  background-color: var(--color-secondary);
  color: var(--color-white);
  transform: scale(1.05);
  box-shadow: var(--shadow-md);
  z-index: 1;
}

.seat-locked {
  background-color: var(--color-warning);
  color: var(--color-white);
  cursor: not-allowed;
  opacity: 0.7;
}

.seat-sold {
  background-color: var(--color-border);
  color: var(--color-text-light);
  cursor: not-allowed;
  opacity: 0.5;
}

.seat-label {
  position: relative;
  z-index: 1;
}

.empty-state {
  text-align: center;
  padding: var(--space-8);
  color: var(--color-text-light);
}

.picker-legend {
  display: flex;
  justify-content: center;
  gap: var(--space-6);
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-border);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

.legend-seat {
  width: 24px;
  height: 24px;
  border-radius: var(--radius-sm) var(--radius-sm) 3px 3px;
}

@media (max-width: 768px) {
  .seat-picker {
    padding: var(--space-4);
  }

  .picker-header {
    flex-direction: column;
    gap: var(--space-3);
    align-items: stretch;
  }

  .seat {
    width: 28px;
    height: 28px;
    font-size: 10px;
  }

  .picker-legend {
    flex-wrap: wrap;
    gap: var(--space-3);
  }
}
</style>
