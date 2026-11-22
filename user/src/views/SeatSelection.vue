<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, CheckCircle, AlertCircle, Clock } from 'lucide-vue-next'
import SeatPicker from '../components/common/SeatPicker.vue'
import { api, formatPrice } from '@/utils/api'
import type { Ticket } from '@/types'

interface SeatInfo {
  id: string
  row: number
  col: number
  rowNum?: number
  colNum?: number
  area?: string
  status: 'available' | 'selected' | 'sold' | 'locked'
}

const route = useRoute()
const router = useRouter()

// 关键标记：订单是否已创建成功（防止onUnmounted误释放座位）
let orderCreatedSuccessfully = false

// 从query参数获取数据（带类型转换）
const ticketId = ref<number>(Number(route.query.ticketId) || 0)
const quantity = ref<number>(Number(route.query.quantity) || 10)
const isShowEnded = ref(route.query.isShowEnded === 'true')

console.log('[SeatSelection] 初始化参数 - ticketId:', ticketId.value, 'quantity:', quantity.value, 'isShowEnded:', isShowEnded.value)

// 状态管理（带完整类型注解）
const ticket = ref<Ticket | null>(null)
const loading = ref<boolean>(true)
const error = ref<string>('')
const selectedSeats = ref<SeatInfo[]>([])
const submitting = ref<boolean>(false)
let timer: ReturnType<typeof setInterval> | null = null
const showCountdownHint = ref<boolean>(true) // 显示选座提示信息

// 计算属性（带返回类型注解）
const totalPrice = computed<number>(() => {
  if (!ticket.value || selectedSeats.value.length === 0) return 0
  return (ticket.value.payValue || 0) * selectedSeats.value.length
})

const canConfirm = computed<boolean>(() => {
  const requiredQuantity = quantity.value || 1
  const selectedCount = selectedSeats.value.length

  console.log('[SeatSelection] canConfirm计算 - selectedSeats:', selectedCount, 'requiredQuantity:', requiredQuantity)

  return selectedCount === requiredQuantity &&
         !submitting.value
})

/**
 * 加载票种详细信息
 */
async function loadTicket(): Promise<void> {
  try {
    loading.value = true
    error.value = ''

    // 调用API获取完整的票种详情
    // @ts-ignore - getTicketDetail方法已在api.js中定义，类型声明待完善
    const res = await api.getTicketDetail(ticketId.value)

    if (!res || !res.data) {
      throw new Error('票种数据异常')
    }

    const ticketData = res.data as Ticket

    // 并发安全检查1: 验证票种状态（是否仍在上架）
    if (ticketData.status !== 1) {
      console.warn('[SeatSelection] 票种已下架或不可用, status:', ticketData.status)
      error.value = '该票种已下架或暂不可购买'
      setTimeout(() => {
        const showId = route.query.showId as string
        if (showId) {
          router.push(`/show/${showId}`)
        } else {
          router.back()
        }
      }, 2500)
      return
    }

    // 检查是否需要选座
    if (!ticketData.hasSeats) {
      console.warn('[SeatSelection] 该票种无需选座')
      error.value = '该票种无需选座，请直接购买'
      setTimeout(() => {
        router.push(`/ticket/buy/${ticketId.value}`)
      }, 2000)
      return
    }

    ticket.value = ticketData
    console.log('[SeatSelection] 票种信息加载成功:', ticket.value)

  } catch (e: any) {
    console.error('[SeatSelection] 加载票种失败:', e)

    // 特殊错误码处理
    if (e.code === 'TICKET_OFFLINE' || e.message?.includes('已下架')) {
      error.value = '该票种已下架，无法购买'
    } else if (e.code === 'TICKET_NOT_FOUND' || e.message?.includes('不存在')) {
      error.value = '票种不存在或已被删除'
    } else if (e.message?.includes('网络') || e.message?.includes('timeout')) {
      error.value = '网络连接失败，请检查网络后重试'
    } else {
      error.value = e.message || '加载票种信息失败，请刷新重试'
    }
  } finally {
    loading.value = false
  }
}

/**
 * 座位选择回调处理
 * @param seats - 已选座位数组
 */
function handleSeatSelect(seats: SeatInfo[]): void {
  selectedSeats.value = seats
  console.log('[SeatSelection] 已选座位更新:', seats.length, '个')
}

/**
 * 处理票种下线事件（由SeatPicker发出）
 * @param message - 错误信息
 */
function handleTicketOffline(message?: string) {
  const msg = message || '该票种已被管理员下架，无法继续购买'

  console.error('[SeatSelection] 收到票种下线通知:', message)

  alert(`${msg}\n\n是否返回演出详情页？`)

  // 清理倒计时
  if (timer) {
    clearInterval(timer)
    timer = null
  }

  // 返回演出详情页
  const showId = route.query.showId as string
  if (showId) {
    router.push(`/show/${showId}`)
  } else {
    router.back()
  }
}

// 确认选座并创建订单
async function confirmSeats() {
  if (!canConfirm.value) return

  // 并发安全检查3: 创建订单前最终验证票种状态（最重要！）
  try {
    console.log('[SeatSelection] 开始最终票种状态验证...')

    // @ts-ignore - checkTicketStatus方法已在api.js中定义，类型声明待完善
    const statusCheck = await api.checkTicketStatus(ticketId.value)

    if (!statusCheck || !statusCheck.data) {
      throw new Error('票种状态查询失败')
    }

    if (statusCheck.data.status !== 1) {
      let errorMsg = '该票种已下架或暂停销售'
      if (statusCheck.data.status === 0) {
        errorMsg = '该票种已被管理员下架'
      } else if (statusCheck.data.status === 2) {
        errorMsg = '该票种已停用，无法购买'
      }

      console.error('[SeatSelection] 最终验证失败, 票种不可用:', statusCheck.data.status)
      alert(`${errorMsg}\n\n您的选座将被释放，请选择其他票种`)

      // 延迟返回，让用户看到错误信息
      setTimeout(() => {
        router.push(`/show/${route.query.showId || ''}`)
      }, 2000)

      return
    }

    console.log('[SeatSelection] 最终验证通过, 票种状态正常')

  } catch (checkError) {
    console.error('[SeatSelection] 票种状态最终验证异常:', checkError)

    const proceed = confirm(
      '无法验证票种状态（可能是网络问题）\n\n是否继续提交订单？'
    )

    if (!proceed) {
      return
    }
  }

  try {
    submitting.value = true

    // 🔥 秒杀票重复购买检查（在购票前提示）
    if (ticket.value?.type === 1) {
      try {
        console.log('[SeatSelection] 检查秒杀票重复购买...')

        // @ts-ignore - checkSeckillPurchased方法已在api.js中定义
        const purchasedCheck = await api.checkSeckillPurchased(ticketId.value)

        if (purchasedCheck?.data?.purchased) {
          alert('您已购买过该秒杀票，每人限购1张')

          setTimeout(() => {
            router.push(`/show/${route.query.showId || ''}`)
          }, 1500)

          return
        }

        console.log('[SeatSelection] 秒杀票重复购买检查通过')
      } catch (seckillCheckError) {
        console.warn('[SeatSelection] 秒杀票购买检查异常:', seckillCheckError)
        // 检查失败不阻止继续，让后端再次校验
      }
    }

    // 创建订单（带座位ID）
    const seatIdList: number[] = selectedSeats.value.map(s => Number(s.id))
    const orderRes = await api.createOrder(ticketId.value, {
      count: quantity.value,  // 使用原始购买数量
      seats: seatIdList
    })

    console.log('[SeatSelection] 订单创建成功:', orderRes)
    console.log('[SeatSelection] 关键验证点1: 即将设置 orderCreatedSuccessfully = true')

    // 关键：设置订单创建成功标记，防止onUnmounted误释放座位
    orderCreatedSuccessfully = true

    console.log('[SeatSelection] 关键验证点2: orderCreatedSuccessfully 已设置为 TRUE ')
    console.log('[SeatSelection] 当前标记值:', orderCreatedSuccessfully, '')

    const tempId = orderRes.data?.tempId || orderRes.tempId
    console.log('[SeatSelection] 预订单临时ID:', tempId, ', 完整响应:', JSON.stringify(orderRes.data))

    if (tempId) {
      await router.push({
        path: '/pay-order',
        query: {
          orderId: String(tempId),  // 使用预订单临时ID（后端支付接口期望的参数）
          showId: route.query.showId || '',
          ticketId: String(ticketId.value),
          orderType: 'seat',
          isShowEnded: String(isShowEnded.value)  // 转换为字符串以符合router.query类型要求
        }
      })
    } else {
      alert('订单创建成功，请查看我的订单')
      await router.push('/order')
    }

  } catch (e: any) {
    console.error('[SeatSelection] 创建订单失败:', e)
    alert(e.message || '选座确认失败，请重试')
  } finally {
    submitting.value = false
  }
}

/**
 * 释放所有已选座位（防止资源泄漏）
 * @returns Promise<void> 异步释放操作
 */
async function releaseAllSelectedSeats(): Promise<void> {
  if (selectedSeats.value.length === 0) return

  console.log(`[SeatSelection] 开始释放${selectedSeats.value.length}个已选座位`)

  const releasePromises = selectedSeats.value.map(seat =>
    api.releaseSeat(ticketId.value, Number(seat.id))
      .then(() => {
        console.log('[SeatSelection] 座位释放成功 - seatId:', seat.id)
      })
      .catch(err => {
        console.error('[SeatSelection] 座位释放失败 - seatId:', seat.id, err)
      })
  )

  await Promise.allSettled(releasePromises)
  selectedSeats.value = []  // 清空本地状态
  console.log('[SeatSelection] 所有已选座位释放完成')
}

/**
 * 返回上一页（带确认提示 + 自动释放座位）
 */
async function goBack(): Promise<void> {
  if (selectedSeats.value.length > 0) {
    const confirmed = confirm('确定要返回吗？已选的座位将被释放')
    if (!confirmed) return
  }

  // 停止倒计时
  if (timer) {
    clearInterval(timer)
    timer = null
  }

  // 关键改进：真正释放已锁定的座位
  await releaseAllSelectedSeats()

  router.back()
}

/**
 * 页面卸载前自动释放座位（防用户直接关闭浏览器/标签页）
 * 注意：此为尽力而为，无法保证100%执行（后端有TTL兜底）
 */
async function handleBeforeUnload(event: BeforeUnloadEvent): Promise<void> {
  if (selectedSeats.value.length === 0) return

  // 使用sendBeacon确保页面关闭时也能发送请求（异步、不可取消）
  const payload = JSON.stringify({
    ticketId: ticketId.value,
    seatIds: selectedSeats.value.map(s => s.id)
  })

  // 关键修复：只有订单未创建成功时才释放座位！
  if (orderCreatedSuccessfully) {
    console.log('[SeatSelection] beforeunload - 订单已创建成功，跳过座位释放')
    console.log('[SeatSelection] 验证点3: beforeunload 检测到订单已创建，不释放座位')
    return
  }

  console.log('[SeatSelection] 验证点3: beforeunload 即将发送 release 请求！orderCreatedSuccessfully =', orderCreatedSuccessfully)
  navigator.sendBeacon('/api/ticket/batch-release', new Blob([payload], { type: 'application/json' }))

  event.preventDefault()
  event.returnValue = ''  // 触发浏览器确认对话框
}

// 生命周期
onMounted(() => {
  loadTicket()
  // 选座阶段不启动倒计时，仅在提交订单成功后由PayOrder.vue接管支付倒计时
  // startCountdown()  // 注释：不再在选座页启动倒计时

  window.addEventListener('popstate', async () => {
    if (timer) {
      clearInterval(timer)
      timer = null
    }

    if (selectedSeats.value.length > 0 && !orderCreatedSuccessfully) {
      console.warn('[SeatSelection] 检测到浏览器返回操作，自动释放已选座位')
      await releaseAllSelectedSeats()
    } else if (orderCreatedSuccessfully) {
      console.log('[SeatSelection] 检测到浏览器返回操作，但订单已创建，跳过座位释放')
    }
  })

  window.addEventListener('beforeunload', handleBeforeUnload)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }

  // 移除beforeunload监听
  window.removeEventListener('beforeunload', handleBeforeUnload)


  if (!orderCreatedSuccessfully) {
    console.log('[SeatSelection] onUnmounted - 订单未创建，释放已选座位')
    console.log('[SeatSelection] 验证点4: onUnmounted 即将释放座位！orderCreatedSuccessfully =', orderCreatedSuccessfully)
    releaseAllSelectedSeats().catch(err => {
      console.error('[SeatSelection] onUnmounted释放座位失败:', err)
    })
  } else {
    console.log('[SeatSelection] onUnmounted - 订单已创建成功，跳过座位释放（由PayOrder页面管理）')
    console.log('[SeatSelection] 验证点4: onUnmounted 检测到订单已创建，不释放座位')
  }
})
</script>

<template>
  <div class="seat-selection-page">

    <!-- 顶部导航栏 -->
    <header class="page-header">
      <div class="header-left">
        <button class="back-btn" @click="goBack" title="返回">
          <ArrowLeft :size="20" />
        </button>
        <h1 class="page-title">选择座位</h1>
      </div>

      <!-- 选座提示（替代原倒计时：选座阶段不限时，支付阶段由PayOrder.vue控制） -->
      <div v-if="showCountdownHint" class="countdown">
        <Clock :size="16" />
        <span>请从容选座，提交订单后请在5分钟内完成支付</span>
      </div>
    </header>

    <!-- 加载状态 -->
    <div v-if="loading" class="loading-state">
      <div class="loading-spinner"></div>
      <p>加载座位信息...</p>
    </div>

    <!-- 错误状态 -->
    <div v-else-if="error" class="error-state">
      <AlertCircle :size="48" />
      <p>{{ error }}</p>
      <button class="retry-btn" @click="loadTicket">重新加载</button>
    </div>

    <!-- 主内容区 -->
    <template v-else>

      <!-- 座位选择器 -->
      <div class="seat-picker-container">
        <SeatPicker
          :ticket-id="ticketId"
          :max-select="quantity || 10"
          :is-seckill="ticket?.type === 1"
          @select="handleSeatSelect"
          @ticket-offline="handleTicketOffline"
        />
      </div>

      <!-- 底部操作栏 -->
      <footer class="action-bar">

        <!-- 已选座位信息 -->
        <div v-if="selectedSeats.length > 0" class="selected-info">
          <div class="selected-seats">
            <CheckCircle :size="16" class="check-icon" />
            <span>已选 {{ selectedSeats.length }} 个座位</span>
          </div>

          <div class="seats-detail">
            <span
              v-for="(seat, index) in selectedSeats"
              :key="seat.id"
              class="seat-tag"
            >
              {{ seat.area }}{{ seat.rowNum }}排{{ seat.colNum }}座
              <span v-if="index < selectedSeats.length - 1" class="separator">、</span>
            </span>
          </div>
        </div>

        <!-- 价格和按钮 -->
        <div class="price-action">
          <!-- 座位选择提示 -->
          <div v-if="selectedSeats.length !== quantity" class="seat-requirement-hint">
            <span class="hint-icon">!</span>
            <span class="hint-text">
              请选满 {{ quantity }} 个座位（已选 {{ selectedSeats.length }}/{{ quantity }}）
            </span>
          </div>

          <div class="price-info">
            <span class="price-label">合计：</span>
            <span class="price-value">{{ formatPrice(totalPrice) }}</span>
            <span v-if="selectedSeats.length > 1" class="quantity-hint">
              ({{ selectedSeats.length }}张)
            </span>
          </div>

          <button
            class="confirm-btn"
            :class="{ disabled: !canConfirm || submitting }"
            :disabled="!canConfirm || submitting"
            @click="confirmSeats"
          >
            <span v-if="submitting">处理中...</span>
            <span v-else>确认选座</span>
          </button>
        </div>

      </footer>
    </template>

  </div>
</template>

<style scoped>
/* ===== 页面容器 ===== */
.seat-selection-page {
  min-height: 100vh;
  background-color: var(--color-bg);
  display: flex;
  flex-direction: column;
}

/* ===== 顶部导航栏 ===== */
.page-header {
  position: sticky;
  top: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-6);
  background-color: var(--color-white);
  border-bottom: 1px solid var(--color-border);
  box-shadow: var(--shadow-sm);
}

.header-left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.back-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: transparent;
  color: var(--color-text);
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
}

.back-btn:hover {
  background-color: var(--color-bg-alt);
  color: var(--color-primary);
}

.page-title {
  font-family: var(--font-heading); /* Playfair Display */
  font-size: var(--font-lg); /* 使用变量：20px */
  color: var(--color-text);
  font-weight: 600;
}

/* 倒计时 */
.countdown {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background-color: rgba(166, 186, 174, 0.08); /* 浅灰绿背景 */
  border-radius: var(--radius-sm);
  font-size: var(--font-sm); /* 14px */
  font-weight: 600;
  color: var(--color-primary);
  transition: all var(--transition-base);
}

.countdown-warning {
  background-color: rgba(196, 135, 123, 0.12); /* 浅红背景 */
  color: var(--color-danger);
  animation: pulse 1s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

/* ===== 加载/错误状态 ===== */
.loading-state,
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  padding: var(--space-16) var(--space-6);
  color: var(--color-text-light);
  text-align: center;
  flex: 1;
}

.loading-spinner {
  width: 48px;
  height: 48px;
  border: 3px solid var(--color-border);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.retry-btn {
  margin-top: var(--space-2);
  padding: var(--space-2) var(--space-6);
  background-color: var(--color-primary);
  color: var(--color-white);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  transition: all var(--transition-base);
}

.retry-btn:hover {
  background-color: var(--color-secondary);
}

/* ===== 座位选择器容器 ===== */
.seat-picker-container {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-4);
}

/* ===== 底部操作栏 ===== */
.action-bar {
  position: sticky;
  bottom: 0;
  background-color: var(--color-white);
  border-top: 1px solid var(--color-border);
  box-shadow: 0 -2px 8px var(--color-shadow);
  padding: var(--space-4) var(--space-6);
  padding-bottom: calc(var(--space-4) + env(safe-area-inset-bottom));
}

/* 已选信息 */
.selected-info {
  margin-bottom: var(--space-3);
  padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--color-border);
}

.selected-seats {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--color-primary);
}

.check-icon {
  flex-shrink: 0;
}

.seats-detail {
  font-size: var(--font-xs); /* 12px */
  color: var(--color-text-light);
  line-height: 1.6;
}

.seat-tag {
  font-weight: 500;
  color: var(--color-text);
}

.separator {
  margin: 0 2px;
}

/* 价格和按钮区域 */
.price-action {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
}

.price-info {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
}

.price-label {
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.price-value {
  font-family: var(--font-heading); /* Playfair Display */
  font-size: var(--font-xl); /* 使用变量：24px */
  font-weight: 700;
  color: var(--color-secondary); /* 陶土棕 */
}

.quantity-hint {
  font-size: var(--font-xs); /* 12px */
  color: var(--color-text-light);
}

/* 座位数量要求提示 */
.seat-requirement-hint {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  margin-bottom: var(--space-3);
  background-color: var(--color-bg-warning);
  border: 1px solid var(--color-border-warning);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  color: var(--color-warning-dark);
}

.hint-icon {
  font-size: var(--font-base);
}

.hint-text {
  font-weight: 500;
}

.confirm-btn {
  padding: var(--space-3) var(--space-8); /* 12px 32px */
  background-color: var(--color-primary); /* 灰绿主色 */
  color: var(--color-white);
  font-family: var(--font-body);
  font-size: var(--font-base); /* 16px */
  font-weight: 600;
  border-radius: var(--radius-md); /* 8px */
  white-space: nowrap;
  transition: all var(--transition-base); /* 0.3s ease-out */
}

.confirm-btn:hover:not(.disabled) {
  background-color: var(--color-secondary); /* 陶土棕 */
  transform: translateY(-1px);
  box-shadow: var(--shadow-md);
}

.confirm-btn.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ===== 响应式设计 ===== */
@media (max-width: 768px) {
  .page-header {
    padding: var(--space-3) var(--space-4);
  }

  .page-title {
    font-size: var(--font-base); /* 移动端缩小：16px */
  }

  .countdown {
    font-size: var(--font-xs); /* 移动端：12px */
    padding: var(--space-1) var(--space-2);
  }

  .seat-picker-container {
    padding: var(--space-3);
  }

  .action-bar {
    padding: var(--space-3) var(--space-4);
  }

  .price-action {
    flex-direction: column;
    gap: var(--space-3);
    align-items: stretch;
  }

  .confirm-btn {
    width: 100%;
    padding: var(--space-4);
  }

  .price-value {
    font-size: var(--font-lg); /* 移动端：20px */
  }

  /* 移动端覆盖字号变量 */
  :root {
    --font-xl: 20px; /* 从24px缩小到20px */
  }
}
</style>
