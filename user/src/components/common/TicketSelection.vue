<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { X, Clock, AlertCircle } from 'lucide-vue-next'
import { api, formatPrice } from '@/utils/api'
import { logger } from 'shared/utils/logger.js'
import type { Ticket, TicketSelectionResult } from '@/types'

interface Props {
  showId: number
  showName?: string
  showEndTime?: string
  isShowEnded?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  showName: '',
  showEndTime: '',
  isShowEnded: false
})

const isShowEnded = computed(() => {
  const now = new Date()

  console.log('[TicketSelection] 📅 演出结束状态检查:', {
    showEndTime: props.showEndTime,
    parentIsShowEnded: props.isShowEnded,
    currentTime: now.toISOString(),
    parsedEndTime: props.showEndTime ? new Date(props.showEndTime).toISOString() : null
  })

  if (props.showEndTime) {
    const endTime = new Date(props.showEndTime)
    const ended = endTime < now

    console.log('[TicketSelection] ✅ 最终判断结果:', {
      endTime: endTime.toISOString(),
      currentTime: now.toISOString(),
      isEnded: ended,
      timeDiff: Math.floor((now.getTime() - endTime.getTime()) / (1000 * 60 * 60 * 24)) + '天'
    })

    return ended
  }

  return props.isShowEnded || false
})

const emit = defineEmits<{
  close: []
  'select-ticket': [result: TicketSelectionResult]
}>()

// 状态管理（带类型注解）
const tickets = ref<Ticket[]>([])
const loading = ref<boolean>(true)
const error = ref<string>('')
const selectedTicketId = ref<number | null>(null)
const quantity = ref<number>(1)
const submitting = ref<boolean>(false)

// 已购买的秒杀票ID集合（用于UI禁用）
const purchasedSeckillTicketIds = ref<Set<number>>(new Set())

// ✅ 动态倒计时：每秒更新
let countdownInterval: ReturnType<typeof setInterval> | null = null
const currentTime = ref<number>(Date.now())

// ✅ 启动倒计时定时器
function startCountdown() {
  if (countdownInterval) return  // 避免重复启动

  countdownInterval = setInterval(() => {
    currentTime.value = Date.now()  // 更新当前时间，触发响应式更新
  }, 1000)  // 每秒更新一次
}

// ✅ 停止倒计时定时器
function stopCountdown() {
  if (countdownInterval) {
    clearInterval(countdownInterval)
    countdownInterval = null
  }
}

// 计算属性（带返回类型）
const selectedTicket = computed<Ticket | null>(() => {
  return tickets.value.find((t: Ticket) => t.id === selectedTicketId.value) || null
})

const maxQuantity = computed<number>(() => {
  const ticket = selectedTicket.value
  if (!ticket) return 1

  const availableStock: number = ticket.isSeckill
    ? (ticket as any).seckillStock ?? 0
    : (ticket as any).remainingStock ?? ticket.stock ?? 10

  const limit: number = 10
  return Math.min(availableStock, limit)
})

const totalPrice = computed<number>(() => {
  const ticket = selectedTicket.value
  if (!ticket) return 0
  return (ticket.payValue || 0) * quantity.value
})

const canSubmit = computed<boolean>(() => {
  return !!selectedTicket.value &&
         quantity.value >= 1 &&
         quantity.value <= maxQuantity.value &&
         !submitting.value
})

// 方法
async function loadTickets() {
  try {
    loading.value = true
    error.value = ''
    const res = await api.getTicketsByShow(props.showId)
    const allTickets = res.data || []

    // ✅ 过滤掉已下架的票种（status !== 1 表示不可用）
    tickets.value = allTickets.filter((ticket: Ticket) => {
      if (ticket.status === 0 || ticket.status === 2) {
        console.log('[TicketSelection] 过滤已下架票种:', ticket.title, 'status:', ticket.status)
        return false
      }
      return true
    })

    console.log('[TicketSelection] 票种过滤结果: 原始', allTickets.length, '个 → 可用', tickets.value.length, '个')

    // ✅ 调试：打印每个秒杀票的关键信息（包含所有可能的字段名）
    tickets.value.forEach((ticket) => {
      if (ticket.isSeckill) {
        console.log(`[TicketSelection] 🔍 秒杀票详情 - ID:${ticket.id}, 标题:${ticket.title}`, {
          // 库存信息
          seckillStock: ticket.seckillStock,
          remainingStock: ticket.remainingStock,
          stock: ticket.stock,

          // 时间信息（多种可能字段名）
          'seckillBeginTime (前端期望)': ticket.seckillBeginTime,
          'beginTime (后端实际返回)': ticket.beginTime,
          'extInfo.seckillBeginTime': ticket.extInfo?.seckillBeginTime,

          'seckillEndTime (前端期望)': ticket.seckillEndTime,
          'endTime (后端实际返回)': ticket.endTime,
          'extInfo.seckillEndTime': ticket.extInfo?.seckillEndTime,

          // 状态信息
          currentTime: new Date(currentTime.value).toLocaleString(),
          status: ticket.status,
          isSeckill: ticket.isSeckill,
          isActive: ticket.isActive
        })
      }
    })

    // 检查已购买的秒杀票（用于UI禁用）
    const seckillTickets = tickets.value.filter(t => t.isSeckill)
    if (seckillTickets.length > 0) {
      try {
        // @ts-ignore - 调用后端接口批量检查秒杀票购买状态
        const checkRes = await api.checkSeckillPurchasedBatch(
          seckillTickets.map(t => t.id)
        )
        if (checkRes?.data?.purchasedIds) {
          purchasedSeckillTicketIds.value = new Set(checkRes.data.purchasedIds)
          console.log('[TicketSelection] 已购买的秒杀票ID:', Array.from(purchasedSeckillTicketIds.value))
        }
      } catch (e) {
        console.warn('[TicketSelection] 检查秒杀票购买状态失败，允许继续购买（后端会再次校验）:', e)
      }
    }

    // 默认选择第一个可用且未购买的票种
    const firstAvailable = tickets.value.find(t => {
      if (t.isSeckill) {
        // 秒杀票：有库存 且 未被当前用户购买
        return (t.seckillStock ?? 0) > 0 && !purchasedSeckillTicketIds.value.has(t.id)
      }
      // 普通票：有库存即可
      return (t.remainingStock ?? t.stock ?? 0) > 0
    })

    if (firstAvailable) {
      selectedTicketId.value = firstAvailable.id
      quantity.value = 1
    } else if (!selectedTicketId.value && tickets.value.length > 0) {
      // 如果没有可用的，但存在票种，默认选中第一个（显示为不可选状态）
      selectedTicketId.value = tickets.value[0].id
    }
  } catch (e) {
    console.error('[TicketSelection] 加载票种失败:', e)
    error.value = '加载票种信息失败，请重试'
  } finally {
    loading.value = false
  }
}

/**
 * 选择票种（拦截已购买和未开始的秒杀票）
 * @param ticketId - 票种ID
 */
function selectTicket(ticketId: number): void {
  const ticket = tickets.value.find(t => t.id === ticketId)
  if (!ticket) return

  // 拦截1：秒杀票已被当前用户购买
  if (ticket.isSeckill && purchasedSeckillTicketIds.value.has(ticketId)) {
    alert('您已购买过该秒杀票，每人限购1张')
    return
  }

  // 拦截2：如果票种不可购买（包括未开始的秒杀票），阻止选择
  // 注意：演出结束时不阻止选择，允许进入下一步在支付页面提示
  if (!canPurchaseSeckill(ticket) && !isShowEnded.value) {
    console.log('[TicketSelection] ⏰ 该票种暂不可选择:', ticket.title, {
      reason: ticket.isSeckill ? '秒杀未开始/已结束' : '其他原因'
    })
    return
  }

  selectedTicketId.value = ticketId
  quantity.value = 1 // 重置数量

  console.log('[TicketSelection] 选择票种:', ticket.title, {
    isShowEnded: isShowEnded.value,
    willShowPaymentError: isShowEnded.value
  })
}

/**
 * 更新购买数量
 * @param delta - 增量（+1 或 -1）
 */
function updateQuantity(delta: number): void {
  const newQty: number = quantity.value + delta
  if (newQty >= 1 && newQty <= maxQuantity.value) {
    quantity.value = newQty
  }
}

/**
 * 提交票种选择（普通票/秒杀票分离处理）
 */
async function submitSelection(): Promise<void> {
  const ticket = selectedTicket.value
  if (!canSubmit.value || !ticket) return

  try {
    submitting.value = true

    // ========== 分支1：秒杀票处理 ==========
    if (ticket.isSeckill) {
      console.log('[TicketSelection] [秒杀票] 开始处理秒杀票购买 - ticketId:', ticket.id)

      // 前端预检：调用后端接口检查是否已购买
      try {
        // @ts-ignore
        const checkRes = await api.checkSeckillPurchased(ticket.id)
        if (checkRes?.data?.purchased) {
          alert('您已购买过该秒杀票，每人限购1张')
          return
        }
        console.log('[TicketSelection] [秒杀票] 前端预检通过')
      } catch (checkError) {
        console.warn('[TicketSelection] [秒杀票] 预检异常，继续提交让后端再次校验:', checkError)
      }

      let orderRes: any

      if (ticket.hasSeats) {
        // 秒杀票+有座：跳转到选座页面（座位数量固定为1）
        console.log('[TicketSelection] [秒杀票+有座] 跳转选座页面')
        emit('select-ticket', {
          ticketId: ticket.id,
          ticketTitle: ticket.title,
          quantity: 1,  // 秒杀票固定1张
          unitPrice: ticket.payValue,
          totalPrice: ticket.payValue,
          needSeatSelection: true,
          isSeckill: true,
          isShowEnded: isShowEnded.value
        } as TicketSelectionResult)
        return  // 提前返回，不执行后续代码
      } else {
        // 秒杀票+无座：调用秒杀下单接口
        // 注意：后端秒杀已改为"先入队、后消费"的排队模式，POST 只返回 {status:'QUEUED', ahead:n}，
        // 真正的预订单号(tempId)由后台队列消费后写入排队结果，需轮询 /seckill/queue/status 直到终态。
        // seckillOrderAndWait = 入队 + 轮询到 SUCCESS/FAILED/SOLD_OUT，直接返回 {tempId, ...}
        console.log('[TicketSelection] [秒杀票+无座] 调用秒杀下单接口（排队模式，等待预订单号）')
        orderRes = await api.seckillOrderAndWait(ticket.id)
      }

      logger.debug('TicketSelection', '[秒杀票] 下单成功:', orderRes)
      // seckillOrderAndWait 返回 {tempId, expireTime?, message?}；兼容旧的同步响应结构
      const seckillOrderId = orderRes?.tempId || orderRes?.data?.tempId || orderRes?.data?.id
      logger.debug('TicketSelection', '[秒杀票] 提取的orderId:', seckillOrderId, '类型:', typeof seckillOrderId)

      if (!seckillOrderId) {
        throw new Error('秒杀订单创建成功但未返回订单ID')
      }

      emit('select-ticket', {
        orderId: seckillOrderId,
        ticketId: ticket.id,
        ticketTitle: ticket.title,
        quantity: 1,
        unitPrice: ticket.payValue,
        totalPrice: ticket.payValue,
        needSeatSelection: false,
        isSeckill: true,
        isShowEnded: isShowEnded.value
      })
      return  // 秒杀票处理完成
    }

    // ========== 分支2：普通票处理 ==========
    console.log('[TicketSelection] [普通票] 开始处理普通票购买 - ticketId:', ticket.id, '数量:', quantity.value)

    if (ticket.hasSeats) {
      // 普通票+有座：跳转到选座页面（可选择多个座位）
      console.log('[TicketSelection] [普通票+有座] 跳转选座页面，数量:', quantity.value)
      emit('select-ticket', {
        ticketId: ticket.id,
        ticketTitle: ticket.title,
        quantity: quantity.value,
        unitPrice: ticket.payValue,
        totalPrice: totalPrice.value,
        needSeatSelection: true,
        isSeckill: false,
        isShowEnded: isShowEnded.value
      } as TicketSelectionResult)
    } else {
      // 普通票+无座：直接创建订单
      console.log('[TicketSelection] [普通票+无座] 创建订单，数量:', quantity.value)
      const orderRes = await api.createOrder(ticket.id, { count: quantity.value })

      logger.debug('TicketSelection', '[普通票] 订单创建成功:', orderRes)
      const orderId = orderRes.data?.tempId || orderRes.data?.id
      logger.debug('TicketSelection', '[普通票] 提取的orderId:', orderId, '类型:', typeof orderId)

      if (!orderId) {
        throw new Error('订单创建成功但未返回订单ID')
      }

      emit('select-ticket', {
        orderId: orderId,
        ticketId: ticket.id,
        ticketTitle: ticket.title,
        quantity: quantity.value,
        unitPrice: ticket.payValue,
        totalPrice: totalPrice.value,
        needSeatSelection: false,
        isSeckill: false,
        isShowEnded: isShowEnded.value
      })
    }

  } catch (e: any) {
    console.error('[TicketSelection] 提交失败:', e)
    alert(e.message || '操作失败，请重试')
  } finally {
    submitting.value = false
  }
}

function handleClose(): void {
  emit('close')
}

/**
 * 键盘事件处理（ESC关闭弹窗）
 * @param e - 键盘事件对象
 */
function handleKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') {
    handleClose()
  }
}

// 生命周期
onMounted(() => {
  loadTickets()
  startCountdown()  // ✅ 启动动态倒计时
  document.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  stopCountdown()   // ✅ 停止倒计时定时器（防止内存泄漏）
  document.removeEventListener('keydown', handleKeydown)
})

/**
 * 获取票种的可用库存数量（已购买的秒杀票返回0）
 * ✅ 增强版：详细日志记录库存计算过程，便于排查Redis问题
 * @param ticket - 票种对象
 * @returns 可用库存数量
 */
function getAvailableStock(ticket: import('../../types').Ticket): number {
  // 情况1：已购买的秒杀票
  if (ticket.isSeckill && purchasedSeckillTicketIds.value.has(ticket.id)) {
    console.log(`[TicketSelection] 库存计算 - ID:${ticket.id} 结果:0 (原因:用户已购买)`)
    return 0
  }

  // 情况2：秒杀票
  if (ticket.isSeckill) {
    const seckillStock = ticket.seckillStock ?? 0

    console.log(`[TicketSelection] 库存计算 - ID:${ticket.id} (秒杀票)`, {
      'seckillStock (来自Redis)': seckillStock,
      'remainingStock (备用)': ticket.remainingStock,
      'stock (DB原始)': ticket.stock,
      '最终结果': seckillStock,
      '是否为0': seckillStock === 0,
      '是否为null/undefined': ticket.seckillStock == null
    })

    return seckillStock
  }

  // 情况3：普通票
  const normalStock = ticket.remainingStock ?? ticket.stock ?? 0
  return normalStock
}

/**
 * 获取库存显示文本（根据不同原因显示不同状态，避免误导用户）
 * @param ticket - 票种对象
 * @returns 库存状态文本
 */
function getStockText(ticket: import('../../types').Ticket): string {
  let result = ''

  // 情况1：已购买的秒杀票
  if (ticket.isSeckill && purchasedSeckillTicketIds.value.has(ticket.id)) {
    result = '[已购买]'
    console.log(`[TicketSelection] getStockText - ID:${ticket.id} → "${result}" (原因:已购买)`)
    return result
  }

  // 情况2：秒杀票时间检查（使用响应式currentTime）
  const ticketBeginTime = (ticket as any).seckillBeginTime ||
                          (ticket as any).beginTime ||
                          (ticket as any).extInfo?.seckillBeginTime

  const ticketEndTime = (ticket as any).seckillEndTime ||
                        (ticket as any).endTime ||
                        (ticket as any).extInfo?.seckillEndTime

  if (ticket.isSeckill && ticketBeginTime) {
    try {
      const beginTime = new Date(ticketBeginTime).getTime()
      const now = currentTime.value

      if (!isNaN(beginTime) && now < beginTime) {
        result = `[${formatCountdown(beginTime - now)}后开始]`
        console.log(`[TicketSelection] getStockText - ID:${ticket.id} → "${result}" (原因:未到开始时间)`)
        return result
      }

      if (ticketEndTime) {
        const endTime = new Date(ticketEndTime).getTime()
        if (!isNaN(endTime) && now > endTime) {
          result = '[已结束]'
          console.log(`[TicketSelection] getStockText - ID:${ticket.id} → "${result}" (原因:已过结束时间)`)
          return result
        }
      }
    } catch (e) {
      console.warn('[TicketSelection] 解析时间失败:', e)
    }
  }

  // 情况3：正常库存显示
  const stock: number = getAvailableStock(ticket)

  if (stock <= 0) {
    result = ticket.isSeckill ? '[暂不可购]' : '[已售罄]'
    console.log(`[TicketSelection] getStockText - ID:${ticket.id} → "${result}" (原因:库存为0, stock=${stock})`)
    return result
  }

  if (stock <= 10) {
    result = `仅剩 ${stock} 张`
  } else {
    result = `${stock} 张可购`
  }

  console.log(`[TicketSelection] getStockText - ID:${ticket.id} → "${result}" (正常, stock=${stock})`)
  return result
}

/**
 * ✅ 格式化倒计时（毫秒 → "HH:MM:SS" 或 "X分Y秒后开始"）
 * @param remainingMs - 剩余毫秒数
 * @returns 格式化的倒计时字符串
 */
function formatCountdown(remainingMs: number): string {
  if (remainingMs <= 0) return '即将开始'

  const totalSeconds = Math.ceil(remainingMs / 1000)
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60

  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
  } else if (minutes > 0) {
    return `${minutes}分${seconds}秒后开始`
  } else {
    return `${seconds}秒后开始`
  }
}

/**
 * 检查秒杀票是否可以购买（时间+库存+购买记录）
 * @param ticket - 票种对象
 * @returns 是否可购买
 */
function canPurchaseSeckill(ticket: import('../../types').Ticket): boolean {
  if (!ticket.isSeckill) return true

  // 检查1：是否已购买
  if (purchasedSeckillTicketIds.value.has(ticket.id)) {
    console.log(`[TicketSelection] ❌ 秒杀票不可购 - ID:${ticket.id} 原因:用户已购买`)
    return false
  }

  // 检查2：秒杀时间（使用响应式currentTime）
  const now = currentTime.value
  let timeStatus = '正常'

  // ✅ 关键修复：兼容后端返回的多种字段名格式
  // 后端可能返回：seckillBeginTime / beginTime / extInfo.seckillBeginTime
  const seckillBeginTime = (ticket as any).seckillBeginTime ||
                           (ticket as any).beginTime ||
                           (ticket as any).extInfo?.seckillBeginTime

  const seckillEndTime = (ticket as any).seckillEndTime ||
                         (ticket as any).endTime ||
                         (ticket as any).extInfo?.seckillEndTime

  if (seckillBeginTime) {
    try {
      const beginTime = new Date(seckillBeginTime).getTime()

      if (isNaN(beginTime)) {
        console.warn(`[TicketSelection] ⚠️ 秒杀开始时间解析失败 - ID:${ticket.id}, 原始值:${seckillBeginTime}`)
      } else if (now < beginTime) {
        timeStatus = '未开始'
        console.log(`[TicketSelection] ❌ 秒杀票不可购 - ID:${ticket.id} 原因:未到开始时间`, {
          now: new Date(now).toLocaleString(),
          beginTime: new Date(beginTime).toLocaleString(),
          remainingMs: beginTime - now,
          usedField: 'seckillBeginTime/beginTime/extInfo.seckillBeginTime'
        })
        return false
      }
    } catch (e) {
      console.warn(`[TicketSelection] ⚠️ 秒杀开始时间解析异常 - ID:${ticket.id}`, e)
    }
  }

  if (seckillEndTime) {
    try {
      const endTime = new Date(seckillEndTime).getTime()

      if (isNaN(endTime)) {
        console.warn(`[TicketSelection] ⚠️ 秒杀结束时间解析失败 - ID:${ticket.id}, 原始值:${seckillEndTime}`)
      } else if (now > endTime) {
        timeStatus = '已结束'
        console.log(`[TicketSelection] ❌ 秒杀票不可购 - ID:${ticket.id} 原因:已过结束时间`, {
          now: new Date(now).toLocaleString(),
          endTime: new Date(endTime).toLocaleString(),
          exceededMs: now - endTime,
          usedField: 'seckillEndTime/endTime/extInfo.seckillEndTime'
        })
        return false
      }
    } catch (e) {
      console.warn(`[TicketSelection] ⚠️ 秒杀结束时间解析异常 - ID:${ticket.id}`, e)
    }
  }

  // 检查3：库存
  const stock = getAvailableStock(ticket)
  if (stock <= 0) {
    console.log(`[TicketSelection] ❌ 秒杀票不可购 - ID:${ticket.id} 原因:库存不足`, {
      seckillStock: (ticket as any).seckillStock,
      remainingStock: (ticket as any).remainingStock,
      stock: (ticket as any).stock,
      calculatedStock: stock
    })
    return false
  }

  console.log(`[TicketSelection] ✅ 秒杀票可购买 - ID:${ticket.id}, 标题:${ticket.title}, 库存:${stock}, 时间状态:${timeStatus}`)
  return true
}

/**
 * 判断秒杀票是否处于等待开始状态（未到开始时间）
 * ✅ 兼容后端返回的多种字段名格式
 * @param ticket - 票种对象
 * @returns 是否等待中
 */
function isSeckillWaiting(ticket: import('../../types').Ticket): boolean {
  if (!ticket.isSeckill) return false

  // 使用与canPurchaseSeckill相同的字段兼容逻辑
  const beginTimeValue = (ticket as any).seckillBeginTime ||
                         (ticket as any).beginTime ||
                         (ticket as any).extInfo?.seckillBeginTime

  if (!beginTimeValue) return false

  try {
    const beginTime = new Date(beginTimeValue).getTime()
    return !isNaN(beginTime) && currentTime.value < beginTime
  } catch (e) {
    console.warn('[TicketSelection] 解析等待状态失败:', e)
    return false
  }
}

/**
 * 判断秒杀票是否有时间信息需要显示
 * ✅ 兼容后端返回的多种字段名格式
 * @param ticket - 票种对象
 * @returns 是否有时间信息
 */
function hasSeckillTimeInfo(ticket: import('../../types').Ticket): boolean {
  if (!ticket.isSeckill) return false

  return !!(
    (ticket as any).seckillBeginTime ||
    (ticket as any).beginTime ||
    (ticket as any).extInfo?.seckillBeginTime ||
    (ticket as any).seckillEndTime ||
    (ticket as any).endTime ||
    (ticket as any).extInfo?.seckillEndTime
  )
}

/**
 * 格式化秒杀时间显示（兼容多种字段名）
 * @param ticket - 票种对象
 * @returns 时间文本
 */
function formatSeckillTime(ticket: import('../../types').Ticket): string {
  // ✅ 关键修复：使用字段兼容逻辑
  const beginTime = (ticket as any).seckillBeginTime ||
                    (ticket as any).beginTime ||
                    (ticket as any).extInfo?.seckillBeginTime

  const endTime = (ticket as any).seckillEndTime ||
                  (ticket as any).endTime ||
                  (ticket as any).extInfo?.seckillEndTime

  if (!beginTime && !endTime) return ''

  const formatDate = (dateStr: string): string => {
    const date = new Date(dateStr)
    const month = date.getMonth() + 1
    const day = date.getDate()
    const hours = String(date.getHours()).padStart(2, '0')
    const minutes = String(date.getMinutes()).padStart(2, '0')
    return `${month}/${day} ${hours}:${minutes}`
  }

  if (beginTime && endTime) {
    return `${formatDate(beginTime)} ~ ${formatDate(endTime)}`
  } else if (beginTime) {
    return `${formatDate(beginTime)} 开始`
  } else {
    return `截止 ${formatDate(endTime!)}`
  }
}
</script>

<template>
  <div class="modal-overlay" @click.self="handleClose">
    <div class="modal-container" role="dialog" aria-modal="true" aria-labelledby="modal-title">

      <!-- 头部 -->
      <div class="modal-header">
        <div class="header-left">
          <h2 id="modal-title" class="modal-title">选择票种</h2>
          <span v-if="showName" class="show-name">{{ showName }}</span>
        </div>
        <button class="close-btn" @click="handleClose" aria-label="关闭">
          <X :size="20" />
        </button>
      </div>

      <!-- 内容区 -->
      <div class="modal-body">

        <!-- 加载状态 -->
        <div v-if="loading" class="loading-state">
          <div class="loading-spinner"></div>
          <p>加载票种信息...</p>
        </div>

        <!-- 错误状态 -->
        <div v-else-if="error" class="error-state">
          <AlertCircle :size="32" />
          <p>{{ error }}</p>
          <button class="retry-btn" @click="loadTickets">重新加载</button>
        </div>

        <!-- 票种列表 -->
        <template v-else>

          <!-- 演出已结束提示 -->
          <div v-if="isShowEnded" class="show-ended-banner">
            <AlertCircle :size="20" />
            <span>该演出已结束，暂不可购买</span>
          </div>

          <!-- 无票种提示 -->
          <div v-if="tickets.length === 0" class="empty-state">
            <AlertCircle :size="48" />
            <p>暂无可购票种</p>
            <p class="hint">该演出可能尚未开售或已售罄</p>
          </div>

          <!-- 票种卡片 -->
          <div v-else class="ticket-list">
            <div
              v-for="ticket in tickets"
              :key="ticket.id"
              :class="['ticket-card', {
                'ticket-selected': selectedTicketId === ticket.id,
                'ticket-disabled': !canPurchaseSeckill(ticket) || isShowEnded,
                'seckill-waiting': isSeckillWaiting(ticket),
                'show-ended': isShowEnded
              }]"
              @click="selectTicket(ticket.id)"
            >

              <!-- 票种信息 -->
              <div class="ticket-main">
                <div class="ticket-info-row">
                  <span class="ticket-name">{{ ticket.title || '未命名票种' }}</span>
                  <span v-if="ticket.isSeckill" class="badge badge-seckill">秒杀</span>
                  <span v-if="ticket.hasSeats" class="badge badge-seat">需选座</span>
                  <!-- 等待开始标签（动态显示）-->
                  <span v-if="isSeckillWaiting(ticket)"
                        class="badge badge-waiting">
                    [即将开始]
                  </span>
                </div>

                <!-- 秒杀时间显示 -->
                <div v-if="ticket.isSeckill && hasSeckillTimeInfo(ticket)"
                     class="seckill-time-info">
                  <Clock :size="12" />
                  <span>{{ formatSeckillTime(ticket) }}</span>
                </div>

                <div class="ticket-meta-row">
                  <span class="ticket-price">{{ formatPrice(ticket.payValue) }}</span>
                  <span v-if="!ticket.isSeckill && ticket.actualValue" class="ticket-original-price">
                    {{ formatPrice(ticket.actualValue) }}
                  </span>
                  <span class="ticket-stock-status" :class="{
                    'stock-low': getAvailableStock(ticket) <= 10 && getAvailableStock(ticket) > 0,
                    'status-waiting': isSeckillWaiting(ticket)
                  }">
                    {{ getStockText(ticket) }}
                  </span>
                </div>
              </div>

              <!-- 选中指示器 -->
              <div v-if="selectedTicketId === ticket.id" class="check-indicator">
                ✓
              </div>
            </div>
          </div>

          <!-- 数量选择器（仅普通票需要，秒杀票固定1张） -->
          <div v-if="selectedTicket && !selectedTicket.isSeckill" class="quantity-section">
            <label class="quantity-label">购买数量{{ selectedTicket.hasSeats ? '（将选择等量座位）' : '' }}</label>
            <div class="quantity-controls">
              <button
                class="qty-btn"
                :disabled="quantity <= 1"
                @click="updateQuantity(-1)"
              >
                −
              </button>
              <input
                type="number"
                :value="quantity"
                min="1"
                :max="maxQuantity"
                readonly
                class="qty-input"
              />
              <button
                class="qty-btn"
                :disabled="quantity >= maxQuantity"
                @click="updateQuantity(1)"
              >
                +
              </button>
            </div>
          </div>

          <!-- 选座提示 -->
          <div v-else-if="selectedTicket?.hasSeats" class="seat-hint">
            <Clock :size="16" />
            <span>此票种需要选择具体座位，点击确认后将进入座位选择页面</span>
          </div>

        </template>
      </div>

      <!-- 底部操作栏 -->
      <div v-if="!loading && !error && tickets.length > 0" class="modal-footer">
        <div class="footer-summary">
          <div class="summary-item" v-if="selectedTicket">
            <span class="summary-label">已选：</span>
            <span class="summary-value">{{ selectedTicket.title }}</span>  <!-- ✅ 修正为 title -->
          </div>
          <div class="summary-item summary-total">
            <span class="summary-label">合计：</span>
            <span class="summary-price">{{ formatPrice(totalPrice) }}</span>
            <span v-if="quantity > 1" class="summary-qty">×{{ quantity }}</span>
          </div>
        </div>

        <button
          class="submit-btn"
          :class="{ disabled: !canSubmit }"
          :disabled="!canSubmit"
          @click="submitSelection"
        >
          <span v-if="submitting">处理中...</span>
          <span v-else-if="selectedTicket?.hasSeats">确认并选座</span>
          <span v-else>立即购买</span>
        </button>
      </div>

    </div>
  </div>
</template>

<style scoped>
/* ===== 遮罩层 ===== */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(45, 58, 49, 0.6); /* 深绿半透明 */
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: var(--space-4);
  animation: fadeIn 0.2s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

/* ===== 弹窗容器 ===== */
.modal-container {
  background-color: var(--color-white);
  border-radius: var(--radius-md); /* 8px */
  box-shadow: var(--shadow-md); /* 中级阴影 */
  width: 100%;
  max-width: 560px; /* 最大宽度 */
  max-height: 90vh; /* 最大高度 */
  display: flex;
  flex-direction: column;
  animation: slideUp 0.3s ease-out;
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* ===== 头部 ===== */
.modal-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: var(--space-6) var(--space-6) var(--space-4);
  border-bottom: 1px solid var(--color-border);
}

.header-left {
  flex: 1;
  margin-right: var(--space-4);
}

.modal-title {
  font-family: var(--font-heading); /* Playfair Display */
  font-size: var(--font-xl); /* 使用变量：24px */
  color: var(--color-text);
  font-weight: 600;
  margin-bottom: var(--space-1);
}

.show-name {
  font-size: var(--font-sm); /* 14px */
  color: var(--color-text-light);
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.close-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: transparent;
  color: var(--color-text-light);
  border-radius: var(--radius-sm); /* 6px */
  transition: all var(--transition-fast);
  flex-shrink: 0;
}

.close-btn:hover {
  background-color: var(--color-bg-alt);
  color: var(--color-text);
}

/* ===== 内容区 ===== */
.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-6);
}

/* 加载状态 */
.loading-state,
.error-state,
/* 演出已结束提示横幅 */
.show-ended-banner {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  margin-bottom: var(--space-4);
  background-color: rgba(196, 135, 123, 0.1);
  border: 1px solid var(--color-danger);
  border-radius: var(--radius-md);
  color: var(--color-danger);
  font-size: var(--font-sm);
  font-weight: 600;
}

.show-ended-banner svg {
  flex-shrink: 0;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  padding: var(--space-12) var(--space-6);
  color: var(--color-text-light);
  text-align: center;
}

.loading-spinner {
  width: 40px;
  height: 40px;
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
  padding: var(--space-2) var(--space-4);
  background-color: var(--color-primary);
  color: var(--color-white);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  transition: all var(--transition-base);
}

.retry-btn:hover {
  background-color: var(--color-secondary);
}

.hint {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  margin-top: var(--space-2);
}

/* ===== 票种列表 ===== */
.ticket-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3); /* 12px间距 */
  margin-bottom: var(--space-4);
}

.ticket-card {
  background-color: var(--color-white);
  border: 2px solid var(--color-border);
  border-radius: var(--radius-md); /* 8px */
  padding: var(--space-4); /* 16px */
  cursor: pointer;
  transition: all var(--transition-base); /* 0.3s ease-out */
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.ticket-card:hover:not(.ticket-disabled) {
  border-color: var(--color-primary);
  box-shadow: var(--shadow-sm);
  transform: translateX(4px); /* 轻微右移效果 */
}

.ticket-selected {
  border-color: var(--color-primary) !important; /* 灰绿色边框 */
  background-color: rgba(166, 186, 174, 0.06); /* 浅灰绿背景 */
}

.ticket-disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 演出结束状态 */
.show-ended {
  opacity: 0.6;
  border-color: var(--color-danger) !important;
  background-color: rgba(196, 135, 123, 0.05);
}

.show-ended:hover {
  cursor: pointer; /* 允许点击，进入支付页面提示 */
  transform: none; /* 不移动 */
}

.ticket-main {
  flex: 1;
}

.ticket-info-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}

.ticket-name {
  font-size: var(--font-base); /* 16px */
  font-weight: 600;
  color: var(--color-text);
}

.badge {
  font-size: var(--font-xs); /* 12px */
  font-weight: 600;
  padding: var(--space-1) var(--space-2); /* 4px 8px */
  border-radius: var(--radius-sm); /* 6px */
  text-transform: uppercase;
}

.badge-seckill {
  background-color: var(--color-danger); /* #c4877b */
  color: var(--color-white);
}

.badge-seat {
  background-color: var(--color-bg-alt);
  color: var(--color-secondary);
}

/* ✅ 新增：等待开始标签 */
.badge-waiting {
  background-color: var(--color-warning);
  color: white;
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

/* ✅ 新增：秒杀等待状态 */
.seckill-waiting {
  border-color: var(--color-warning) !important;
  background-color: var(--color-warning-alpha) !important;
}

.seckill-time-info {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: var(--font-xs);
  color: var(--color-warning);
  margin-top: 4px;
  font-weight: 500;
}

.status-waiting {
  color: var(--color-warning) !important;
  font-weight: 600;
}

.ticket-meta-row {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
}

.ticket-price {
  font-family: var(--font-heading); /* Playfair Display */
  font-size: var(--font-lg); /* 20px */
  font-weight: 700;
  color: var(--color-secondary); /* 陶土棕 #967462 */
}

.ticket-original-price {
  font-size: var(--font-sm); /* 14px */
  color: var(--color-text-light);
  text-decoration: line-through;
}

.ticket-stock-status {
  font-size: var(--font-xs); /* 12px */
  color: var(--color-text-light);
  margin-left: auto;
}

.stock-low {
  color: var(--color-danger);
  font-weight: 600;
}

/* 选中指示器 */
.check-indicator {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--color-primary);
  color: var(--color-white);
  border-radius: 50%;
  font-weight: 700;
  font-size: var(--font-sm);
  flex-shrink: 0;
}

/* ===== 数量选择器 ===== */
.quantity-section {
  margin-top: var(--space-4);
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-border);
}

.quantity-label {
  display: block;
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-text);
  margin-bottom: var(--space-3);
}

.quantity-controls {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.qty-btn {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--color-white);
  color: var(--color-text);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm); /* 6px */
  font-size: var(--font-xl); /* 24px */
  font-weight: 300;
  transition: all var(--transition-fast);
}

.qty-btn:hover:not(:disabled) {
  background-color: var(--color-primary);
  color: var(--color-white);
  border-color: var(--color-primary);
}

.qty-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.qty-input {
  width: 60px;
  height: 40px;
  text-align: center;
  font-size: var(--font-base); /* 16px */
  font-weight: 600;
  color: var(--color-text);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-alt);
}

.stock-warning {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background-color: rgba(196, 135, 123, 0.08); /* 浅红色背景 */
  border-radius: var(--radius-sm);
  font-size: var(--font-xs); /* 12px */
  color: var(--color-danger);
}

/* 选座提示 */
.seat-hint {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-4);
  padding: var(--space-3) var(--space-4);
  background-color: rgba(166, 186, 174, 0.08); /* 浅灰绿背景 */
  border-radius: var(--radius-sm);
  font-size: var(--font-sm); /* 14px */
  color: var(--color-primary);
}

/* ===== 底部操作栏 ===== */
.modal-footer {
  padding: var(--space-4) var(--space-6) var(--space-6);
  border-top: 1px solid var(--color-border);
  background-color: var(--color-bg); /* 米白背景 */
}

.footer-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-4);
  padding: var(--space-3);
  background-color: var(--color-white);
  border-radius: var(--radius-sm);
}

.summary-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.summary-label {
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.summary-value {
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--color-text);
}

.summary-total {
  font-weight: 600;
}

.summary-price {
  font-family: var(--font-heading); /* Playfair Display */
  font-size: var(--font-lg); /* 20px */
  font-weight: 700;
  color: var(--color-secondary); /* 陶土棕 */
}

.summary-qty {
  font-size: var(--font-xs); /* 12px */
  color: var(--color-text-light);
}

.submit-btn {
  width: 100%;
  padding: var(--space-4); /* 16px */
  background-color: var(--color-primary); /* 灰绿主色 */
  color: var(--color-white);
  font-family: var(--font-body);
  font-size: var(--font-base); /* 16px */
  font-weight: 600;
  border-radius: var(--radius-md); /* 8px */
  transition: all var(--transition-base); /* 0.3s ease-out */
}

.submit-btn:hover:not(.disabled) {
  background-color: var(--color-secondary); /* 陶土棕 */
  transform: translateY(-1px);
  box-shadow: var(--shadow-md);
}

.submit-btn.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ===== 响应式设计 ===== */
@media (max-width: 768px) {
  .modal-overlay {
    padding: 0;
    align-items: flex-end; /* 移动端从底部滑出 */
  }

  .modal-container {
    max-width: 100%;
    max-height: 85vh;
    border-radius: var(--radius-md) var(--radius-md) 0 0; /* 仅顶部圆角 */
    animation: slideUpMobile 0.3s ease-out;
  }

  @keyframes slideUpMobile {
    from {
      transform: translateY(100%);
    }
    to {
      transform: translateY(0);
    }
  }

  .modal-header {
    padding: var(--space-4) var(--space-4) var(--space-3);
  }

  .modal-body {
    padding: var(--space-4);
  }

  .modal-footer {
    padding: var(--space-3) var(--space-4) var(--space-4);
  }

  .ticket-meta-row {
    flex-wrap: wrap;
  }
}
</style>
