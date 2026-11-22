<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, isLoggedIn, formatPrice } from '../utils/api.js'
import { Lock, CheckCircle } from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()
const showId = ref(route.query.showId)
const show = ref(null)
const tickets = ref([])
const selectedTicket = ref(null)
const buyCount = ref(1)
const loading = ref(false)
const submitting = ref(false)
const success = ref(false)
const orderId = ref(null)
const orderStatus = ref(null)
const purchasedTicketIds = ref(new Set(JSON.parse(localStorage.getItem('purchasedTicketIds') || '[]'))) // 已购买的票种ID集合（持久化）
const verifyCode = ref('')
const verifyCodeHidden = ref(false)
const verifyCodeReason = ref('')
const countdown = ref(0)
let countdownTimer = null
const errorMsg = ref('')
// 秒杀排队状态（排队模式下展示"排队中，你前面还有N人"）
const queueWaiting = ref(false)
const queueAhead = ref(0)
let queuePollTimer = null

function goBack() {
  router.back()
}

/**
 * 轮询秒杀排队状态
 * 每2秒查询一次，最长等待6分钟（兜底：队列满载时按100单/秒的消耗速率上限）。
 * @param {number|string} ticketId - 秒杀票ID
 * @returns {Promise<Object|null>} 终态结果对象（SUCCESS时含tempId），失败返回null
 */
function pollSeckillQueue(ticketId) {
  return new Promise((resolve) => {
    queueWaiting.value = true
    queueAhead.value = 0
    let elapsed = 0
    const intervalMs = 2000
    const maxWaitMs = 6 * 60 * 1000

    const stop = (result) => {
      clearInterval(queuePollTimer)
      queuePollTimer = null
      queueWaiting.value = false
      resolve(result)
    }

    queuePollTimer = setInterval(async () => {
      elapsed += intervalMs
      if (elapsed > maxWaitMs) {
        errorMsg.value = '排队超时，请稍后在订单列表查看结果'
        stop(null)
        return
      }
      try {
        const statusRes = await api.seckillQueueStatus(ticketId)
        const data = statusRes.data || {}
        if (data.status === 'QUEUED') {
          queueAhead.value = data.ahead ?? 0
          return // 继续等待
        }
        if (data.status === 'SUCCESS') {
          stop(data)
          return
        }
        if (data.status === 'SOLD_OUT') {
          errorMsg.value = data.message || '很遗憾，票已售罄'
          stop(null)
          return
        }
        if (data.status === 'FAILED') {
          errorMsg.value = data.message || '下单失败，请重试'
          stop(null)
          return
        }
        // NOT_IN_QUEUE：结果已过期或异常
        errorMsg.value = '排队结果已过期，请重试'
        stop(null)
      } catch (e) {
        // 网络抖动不中断轮询，累计超时后自然退出
        console.error('查询排队状态失败:', e)
      }
    }, intervalMs)
  })
}

onUnmounted(() => {
  if (queuePollTimer) {
    clearInterval(queuePollTimer)
    queuePollTimer = null
  }
})

// 判断秒杀票是否已过期
function isSeckillExpired(ticket) {
  if (!ticket || ticket.type !== 1) return false
  if (!ticket.endTime) return false

  const now = new Date()
  const endTime = new Date(ticket.endTime)
  return now >= endTime
}

async function loadShow() {
  if (!showId.value) {
    await router.push('/shows')
    return
  }
  loading.value = true
  try {
    const res = await api.getShowDetail(showId.value)
    show.value = res.data || res

    console.log('[Order] 演出数据:', JSON.stringify(show.value, null, 2))
    console.log('[Order] show.name:', show.value?.name)
    console.log('[Order] show.title:', show.value?.title)
  } catch (e) {
    console.error('[Order] 加载演出信息失败:', e)
    errorMsg.value = '加载演出信息失败'
  } finally {
    loading.value = false
  }
}

async function loadPurchasedTickets() {
  try {
    const res = await api.getMyOrders({ size: 100 }) // 获取所有订单（不分状态，size=100覆盖绝大多数用户的购买量）
    if (res && res.data) {
      const orders = Array.isArray(res.data) ? res.data : (res.data.records || [])

      const newPurchasedIds = new Set(
        orders
          .filter(order => order.ticketId && [2, 3, 6].includes(order.status)) // 已支付/已核销/申请退款中（已完成交易的订单才限购）
          .map(order => order.ticketId)
      )

      purchasedTicketIds.value = newPurchasedIds

      localStorage.setItem('purchasedTicketIds', JSON.stringify([...newPurchasedIds]))

      console.log('[Order] 已购买的票种ID（用于秒杀票限购）:', [...purchasedTicketIds.value])
    }
  } catch (e) {
    console.warn('[Order] 获取已购票种失败（非关键）:', e.message)
  }
}

async function loadTickets() {
  try {
    const res = await api.getTicketsByShow(showId.value)
    tickets.value = res.data || []
    console.log('[Order] 票种数据:', JSON.stringify(tickets.value, null, 2))
    tickets.value.forEach((ticket, index) => {
      console.log(`[Order] 票种${index + 1}:`, {
        id: ticket.id,
        title: ticket.title,
        type: ticket.type,
        status: ticket.status,
        stock: ticket.stock,
        seckillStock: ticket.seckillStock
      })
    })
  } catch (error) {
    console.error('[Order] 加载票种失败:', error)
    tickets.value = []
  }
}

function selectTicket(ticket) {
  if (ticket.status !== 1) {
    errorMsg.value = '该票种暂未上架，请等待开售'
    return
  }

  // ========== 秒杀票专属逻辑（type === 1）==========
  if (ticket.type === 1) {

    // 秒杀票限购：每个用户只能买1张
    if (purchasedTicketIds.value.has(ticket.id)) {
      errorMsg.value = '您已购买过该秒杀票，每张秒杀票限购1张'
      return
    }

    // 检查秒杀库存
    const seckillStock = ticket.seckillStock || 0
    if (seckillStock <= 0) {
      errorMsg.value = '秒杀票已售罄，下次再来吧~'
      return
    }

    // 检查秒杀时间是否有效
    const now = new Date()

    if (ticket.beginTime) {
      const beginTime = new Date(ticket.beginTime)
      if (now < beginTime) {
        errorMsg.value = '秒杀活动尚未开始，请耐心等待'
        return
      }
    }

    if (ticket.endTime) {
      const endTime = new Date(ticket.endTime)
      if (now >= endTime) {
        errorMsg.value = '秒杀活动已结束，下次再来吧~'
        return
      }
    }

    selectedTicket.value = ticket
    buyCount.value = 1 // 秒杀票固定1张
    errorMsg.value = ''
    return
  }

  // ========== 普通票逻辑（type === 0）==========

  // 普通票库存检查
  const normalStock = ticket.stock || 0
  if (normalStock <= 0) {
    errorMsg.value = '该票种已售罄，请选择其他票种'
    return
  }

  selectedTicket.value = ticket
  buyCount.value = 1 // 默认1张，用户可调整
  errorMsg.value = ''
}

function decrement() {

  if (buyCount.value > 1) buyCount.value--
}

function increment() {
  if (!selectedTicket.value) return

  // 秒杀票：固定1张，不允许增加
  if (selectedTicket.value.type === 1) {
    return
  }

  // 普通票：可根据库存增加数量
  const maxStock = selectedTicket.value.stock || 1
  if (buyCount.value < maxStock) {
    buyCount.value++
  }
}

const totalPrice = () => {
  if (!selectedTicket.value) return 0
  return (selectedTicket.value.payValue || 0) * buyCount.value
}

// 防重复提交锁
let isSubmitting = false

async function submitOrder() {
  if (!selectedTicket.value) {
    errorMsg.value = '请选择票型'
    return
  }

  // 🔒 防止重复提交（客户端层面）
  if (isSubmitting || submitting.value) {
    errorMsg.value = '正在处理中，请勿重复点击'
    return
  }

  // 前端最终安全验证（防止绕过UI直接调用）
  if (selectedTicket.value.type === 1) {
    // 秒杀票二次验证
    const now = new Date()

    // 再次检查是否已购买
    if (purchasedTicketIds.value.has(selectedTicket.value.id)) {
      errorMsg.value = '您已购买过该秒杀票，每张秒杀票限购1张'
      return
    }

    // 再次检查库存
    const seckillStock = selectedTicket.value.seckillStock || 0
    if (seckillStock <= 0) {
      errorMsg.value = '秒杀票已售罄，下次再来吧~'
      return
    }

    // 再次检查时间
    if (selectedTicket.value.beginTime) {
      const beginTime = new Date(selectedTicket.value.beginTime)
      if (now < beginTime) {
        errorMsg.value = '秒杀活动尚未开始，请耐心等待'
        return
      }
    }

    if (selectedTicket.value.endTime) {
      const endTime = new Date(selectedTicket.value.endTime)
      if (now >= endTime) {
        errorMsg.value = '秒杀活动已结束，下次再来吧~'
        return
      }
    }
  } else {
    // 普通票库存二次验证
    const normalStock = selectedTicket.value.stock || 0
    if (normalStock <= 0) {
      errorMsg.value = '该票种已售罄，请选择其他票种'
      return
    }

    // 购买数量验证
    if (buyCount.value < 1 || buyCount.value > normalStock) {
      errorMsg.value = '购买数量无效'
      return
    }
  }

  // 🚨 设置提交锁
  isSubmitting = true
  submitting.value = true
  errorMsg.value = ''

  try {
    let res

    // 根据票种类型调用不同的接口
    // 🔒 注意：不传价格参数！后端必须从数据库读取原价，防止篡改
    if (selectedTicket.value.type === 1) {
      // 秒杀票：调用秒杀下单接口（只传ticketId）
      console.log('创建秒杀订单, ticketId:', selectedTicket.value.id)

      // 添加请求时间戳用于后端防重放
      const timestamp = Date.now()
      res = await api.seckillOrder(selectedTicket.value.id, { timestamp })

      // 排队模式：QUEUED 时轮询排队状态，等待后台按队列顺序处理
      if (res.data?.status === 'QUEUED') {
        const queueResult = await pollSeckillQueue(selectedTicket.value.id)
        if (!queueResult) {
          return // 排队失败/售罄/超时：pollSeckillQueue 内已设置 errorMsg
        }
        // 与原同步响应结构对齐，复用下方 tempId 提取与跳转逻辑
        res = { data: queueResult }
      }
    } else {
      // 普通票：调用普通下单接口（只传ticketId和数量）
      console.log('创建普通订单, ticketId:', selectedTicket.value.id, '数量:', buyCount.value)

      // 不传payValue/actualValue，由后端从数据库获取原价
      res = await api.createOrder(selectedTicket.value.id, {
        count: buyCount.value,
        timestamp: Date.now()
      })
    }

    console.log('订单创建响应:', res)
    console.log('响应完整对象:', JSON.stringify(res, null, 2))

    let newOrderId = ''

    if (res.data?.orderIds && Array.isArray(res.data.orderIds) && res.data.orderIds.length > 0) {
      newOrderId = String(res.data.orderIds[0])
      console.log('[Order] ✅ 使用真正orderId（来自orderIds数组）:', newOrderId)
    } else if (res.data?.tempId) {
      newOrderId = String(res.data.tempId)
      console.log('[Order] ⚠️ 使用tempId（预订单模式）:', newOrderId)
    } else if (res.data?.orderId) {
      newOrderId = String(res.data.orderId)
      console.log('[Order] 使用orderId字段:', newOrderId)
    } else if (res.orderId) {
      newOrderId = String(res.orderId)
      console.log('[Order] 使用顶层orderId:', newOrderId)
    }

    if (!newOrderId) {
      throw new Error('订单创建成功但未返回有效的订单ID')
    }

    console.log('提取的orderId:', newOrderId)
    console.log('orderId类型:', typeof newOrderId)

    // 跳转到支付页面
    const payOrderQuery = {
      orderId: String(newOrderId),
      showId: showId.value,
      ticketId: String(selectedTicket.value.id),
      orderType: selectedTicket.value.type === 1 ? 'seckill' : 'normal'
    }

    console.log('跳转到支付页面，参数:', JSON.stringify(payOrderQuery, null, 2))

    // 标记该票种为已购买（防止重复购买）
    purchasedTicketIds.value.add(selectedTicket.value.id)
    localStorage.setItem('purchasedTicketIds', JSON.stringify([...purchasedTicketIds.value]))
    console.log('[Order] 已标记票种为已购买:', selectedTicket.value.id)

    await router.push({
      path: '/pay-order',
      query: payOrderQuery
    })
  } catch (e) {
    console.error('订单创建失败:', e)

    // 解析后端返回的详细错误信息
    const errorMessage = e.message || '创建订单失败'
    errorMsg.value = errorMessage

    // 针对秒杀票的特殊错误提示（后端应返回明确的错误码）
    if (selectedTicket.value.type === 1) {
      if (errorMessage.includes('已购买') || errorMessage.includes('重复') || errorMessage.includes('限购')) {
        errorMsg.value = '您已购买过该秒杀票，每张秒杀票限购1张'
      } else if (errorMessage.includes('库存') || errorMessage.includes('售罄') || errorMessage.includes('stock')) {
        errorMsg.value = '秒杀票已售罄，下次再来吧~'
      } else if (errorMessage.includes('时间') || errorMessage.includes('未开始') || errorMessage.includes('beginTime')) {
        errorMsg.value = '秒杀活动尚未开始，请耐心等待'
      } else if (errorMessage.includes('结束') || errorMessage.includes('endTime') || errorMessage.includes('expired')) {
        errorMsg.value = '秒杀活动已结束，下次再来吧~'
      } else if (errorMessage.includes('并发') || errorMessage.includes('冲突') || errorMessage.includes('conflict')) {
        errorMsg.value = '抢购人数过多，请稍后重试'
      } else if (errorMessage.includes('频率') || errorMessage.includes('频繁') || errorMessage.includes('rate')) {
        errorMsg.value = '操作过于频繁，请稍后再试'
      }
    } else {
      // 普通票错误处理
      if (errorMessage.includes('库存') || errorMessage.includes('售罄')) {
        errorMsg.value = '该票种库存不足，请减少购买数量或选择其他票种'
      } else if (errorMessage.includes('下架') || errorMessage.includes('未上架')) {
        errorMsg.value = '该票种已下架，无法购买'
      }
    }

    // 🔒 记录错误日志（生产环境应上报到监控系统）
    console.error('[Order] 订单创建失败详情:', {
      ticketId: selectedTicket.value.id,
      ticketType: selectedTicket.value.type,
      buyCount: buyCount.value,
      error: errorMessage,
      timestamp: new Date().toISOString()
    })
  } finally {
    // 🔓 释放提交锁（无论成功失败都必须释放）
    isSubmitting = false
    submitting.value = false
  }
}

async function payOrder() {
  if (!orderId.value) return
  submitting.value = true
  errorMsg.value = ''
  try {
    const res = await api.payOrder(orderId.value)
    orderStatus.value = 2 // 已支付

    const rawCode = res.verificationCode || res.data?.verificationCode || generateVerifyCode()

    if (res.verificationCodeHidden || res.data?.verificationCodeHidden) {
      verifyCodeHidden.value = true
      verifyCodeReason.value = res.verificationCodeReason || res.data?.verificationCodeReason || '核销码暂时不可见'
      verifyCode.value = []
      console.log('[Order] 核销码已隐藏，原因:', verifyCodeReason.value)
    } else {
      try {
        const parsed = JSON.parse(rawCode)
        verifyCode.value = Array.isArray(parsed) ? parsed : [rawCode]
      } catch (parseError) {
        verifyCode.value = [rawCode]
      }
    }

    stopCountdown()
  } catch (e) {
    errorMsg.value = e.message || '支付失败'
  } finally {
    submitting.value = false
  }
}

async function refundOrder() {
  if (!orderId.value || !confirm('确定要申请退款吗？')) return
  submitting.value = true
  errorMsg.value = ''
  try {
    await api.refundOrder(orderId.value)
    orderStatus.value = 6 // 申请退款中
  } catch (e) {
    errorMsg.value = e.message || '退款失败'
  } finally {
    submitting.value = false
  }
}

function startCountdown(seconds) {
  countdown.value = seconds
  if (countdownTimer) clearInterval(countdownTimer)
  countdownTimer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) {
      stopCountdown()
      if (orderStatus.value === 0) {
        orderStatus.value = 4 // 超时取消
        errorMsg.value = '订单已超时自动取消'
      }
    }
  }, 1000)
}

function stopCountdown() {
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
}

function formatCountdown(seconds) {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
}

function generateVerifyCode() {
  return 'VC' + Date.now().toString().slice(-8).toUpperCase()
}

onUnmounted(() => {
  stopCountdown()
})

onMounted(() => {
  if (!isLoggedIn()) {
    router.push(`/login?redirect=/order?showId=${showId.value}`)
    return
  }
  loadShow()
  loadTickets()
  loadPurchasedTickets() // 加载已购买的票种，防止重复购买
})
</script>

<template>
  <div class="order-page">
    <div class="order-inner">
      <!-- 返回按钮 -->
      <button class="order-back-btn" @click="goBack">
        返回
      </button>

      <div v-if="loading" class="order-loading">加载中...</div>

      <template v-else-if="success">
        <div class="order-success">
          <h2>订单提交成功</h2>
          <p>感谢您的购买，请前往个人中心查看订单。</p>
          <router-link to="/profile" class="order-success-btn">查看订单</router-link>
        </div>
      </template>

      <template v-else-if="show">
        <!-- 订单已创建，显示支付/状态 -->
        <template v-if="orderId && orderStatus !== null">
          <h1 class="order-title">订单详情</h1>
          <p class="order-show-name">{{ show.name }}</p>

          <!-- 状态显示 -->
          <div :class="['order-status-badge', 'status-' + orderStatus]">
            {{ orderStatus === 0 ? '待支付' : orderStatus === 2 ? '已支付' : orderStatus === 3 ? '已核销' : orderStatus === 4 ? '已取消' : orderStatus === 5 ? '已退款' : orderStatus === 6 ? '申请退款中' : orderStatus === 7 ? '已拒绝' : '未知状态' }}
          </div>

          <!-- 倒计时（待支付状态） -->
          <div v-if="orderStatus === 0 && countdown > 0" class="order-countdown">
            剩余时间：{{ formatCountdown(countdown) }}
          </div>

          <!-- 核销码（已支付且未核销状态）- 支持多张票显示多个核销码 -->
          <div v-if="orderStatus === 2 && !verifyCodeHidden && verifyCode?.length > 0" class="order-verify-code">
            <p class="order-verify-label">核销码（共{{ verifyCode.length }}张票）</p>

            <!-- 单张票：直接显示 -->
            <div v-if="verifyCode.length === 1" class="order-verify-value">
              {{ verifyCode[0] }}
            </div>

            <!-- 多张票：列表展示每个核销码 -->
            <div v-else class="order-verify-list">
              <div
                v-for="(code, index) in verifyCode"
                :key="index"
                class="order-verify-item"
              >
                <span class="order-verify-index">第{{ index + 1 }}张</span>
                <span class="order-verify-value">{{ code }}</span>
              </div>
            </div>

            <p class="order-verify-tip">请出示对应票数的核销码给工作人员核验</p>
          </div>

          <!-- 核销码已使用（订单已核销） -->
          <div v-else-if="orderStatus === 3 && !verifyCodeHidden && verifyCode?.length > 0" class="order-verify-code-used">
            <div class="used-icon-wrapper">
              <CheckCircle :size="48" :strokeWidth="1.5" />
            </div>
            <p class="used-title">核销码已使用</p>
            <p class="used-reason">该订单已完成核销，共{{ verifyCode.length }}张票已全部验票</p>
            <p class="used-tip">如需查看历史记录，请联系客服</p>
          </div>

          <!-- 核销码已隐藏（退款申请中或已退款） -->
          <div v-else-if="orderStatus >= 2 && verifyCodeHidden" class="order-verify-code-hidden">
            <div class="hidden-icon-wrapper">
              <Lock :size="48" :strokeWidth="1.5" />
            </div>
            <p class="hidden-title">核销码暂时不可见</p>
            <p class="hidden-reason">{{ verifyCodeReason }}</p>
            <p class="hidden-tip" v-if="orderStatus === 6">退款审核期间，核销码已被自动隐藏。审核结果将通知您。</p>
            <p class="hidden-tip" v-else-if="orderStatus === 5">订单已退款，核销码已失效。</p>
          </div>

          <!-- 操作按钮 -->
          <div class="order-actions">
            <!-- 待支付：支付按钮 -->
            <button
              v-if="orderStatus === 0 && countdown > 0"
              class="order-submit order-pay-btn"
              :disabled="submitting"
              @click="payOrder"
            >
              {{ submitting ? '处理中...' : `立即支付 ${formatPrice(totalPrice())}` }}
            </button>

            <!-- 已支付且未核销：退款按钮 -->
            <button
              v-if="orderStatus === 2"
              class="order-refund-btn"
              :disabled="submitting"
              @click="refundOrder"
            >
              申请退款
            </button>

            <!-- 已核销提示 -->
            <div v-if="orderStatus === 3" class="order-status-msg status-3-msg">
              订单已完成核销，无法申请退款
            </div>

            <!-- 申请退款中/已退款/已取消提示 -->
            <div v-if="orderStatus === 6" class="order-status-msg status-6-msg">
              退款申请已提交，等待管理员审批（预计1-2个工作日）
            </div>
            <div v-if="orderStatus === 5" class="order-status-msg">
              退款成功，款项将在3-5个工作日内原路退回
            </div>
            <div v-if="orderStatus === 4" class="order-status-msg">订单已取消</div>
          </div>

          <router-link to="/profile" class="order-back-link">返回我的订单</router-link>
        </template>

        <!-- 选票界面 -->
        <template v-else>
          <h1 class="order-title">选购门票</h1>
          <p class="order-show-name">{{ show.name }}</p>

          <div v-if="!tickets.length" class="order-empty">暂无可用门票</div>

          <div v-else class="order-tickets">
            <div
              v-for="ticket in tickets"
              :key="ticket.id"
              :class="['order-ticket',
                { selected: selectedTicket && selectedTicket.id === ticket.id },
                { 'seckill-ticket': ticket.type === 1 },
                { 'unavailable-ticket': ticket.status !== 1 },
                { 'soldout-ticket': ticket.status === 1 && (ticket.type === 1 ? (ticket.seckillStock || 0) : (ticket.stock || 0)) <= 0 },
                { 'expired-seckill': ticket.type === 1 && isSeckillExpired(ticket) },

                { 'purchased-seckill': ticket.type === 1 && purchasedTicketIds.has(ticket.id) }
              ]"
              @click="selectTicket(ticket)"
            >
              <div class="order-ticket-header">
                <div class="order-ticket-title-row">
                  <span class="order-ticket-type">{{ ticket.title || '普通票' }}</span>
                  <span v-if="ticket.type === 1" class="seckill-tag">秒杀</span>

                  <span v-if="ticket.status !== 1" class="status-tag-unavailable">未上架</span>
                  <span v-if="ticket.status === 1 && (ticket.type === 1 ? (ticket.seckillStock || 0) : (ticket.stock || 0)) <= 0" class="status-tag-soldout">已售罄</span>
                  <span v-if="ticket.type === 1 && isSeckillExpired(ticket)" class="status-tag-expired">已结束</span>

                  <span v-if="ticket.type === 1 && purchasedTicketIds.has(ticket.id)" class="status-tag-purchased-seckill">已购</span>
                </div>
                <span class="order-ticket-price">{{ formatPrice(ticket.payValue) }}</span>
              </div>
              <p class="order-ticket-stock">

                <template v-if="ticket.type === 1 && purchasedTicketIds.has(ticket.id)">
                  您已购买该秒杀票，每张限购1张
                </template>
                <template v-else-if="ticket.status !== 1">
                  暂不可购买
                </template>
                <template v-else-if="(ticket.type === 1 ? (ticket.seckillStock || 0) : (ticket.stock || 0)) <= 0">
                  已售罄
                </template>
                <template v-else-if="ticket.type === 1 && isSeckillExpired(ticket)">
                  秒杀活动已结束
                </template>
                <template v-else>
                  剩余 {{ ticket.type === 1 ? (ticket.seckillStock || 0) : (ticket.stock || 0) }} 张

                  <span v-if="ticket.type === 1" class="seckill-hint">· 限购1张</span>
                </template>
              </p>
            </div>
          </div>

          <div v-if="selectedTicket" class="order-quantity">
            <span>数量</span>
            <!-- 秒杀票：固定1张，不可修改 -->
            <div v-if="selectedTicket.type === 1" class="order-qty-fixed">
              <span class="order-qty-value">1</span>
              <span class="seckill-limit-hint">秒杀票限购1张</span>
            </div>
            <!-- 普通票：可选择数量 -->
            <div v-else class="order-qty-controls">
              <button @click="decrement" :disabled="buyCount <= 1">−</button>
              <span class="order-qty-value">{{ buyCount }}</span>
              <button @click="increment" :disabled="buyCount >= (selectedTicket.stock || 1)">+</button>
            </div>
          </div>

          <div v-if="selectedTicket" class="order-summary">
            <span class="order-summary-label">合计</span>
            <span class="order-summary-price">{{ formatPrice(totalPrice()) }}</span>
          </div>

          <p v-if="errorMsg" class="order-error">{{ errorMsg }}</p>

          <div v-if="queueWaiting" class="queue-banner">
            <span class="queue-dot"></span>
            <span>排队中，你前面还有 <b>{{ queueAhead }}</b> 人，请稍候...</span>
          </div>

          <button
            v-if="selectedTicket"
            class="order-submit"
            :disabled="submitting"
            @click="submitOrder"
          >
            {{ submitting ? '提交中...' : '提交订单' }}
          </button>
        </template>
      </template>

      <div v-else class="order-error">加载演出信息失败</div>
    </div>
  </div>
</template>

<style scoped>
.order-page {
  padding: var(--space-12) var(--space-6);
  background-color: var(--color-bg);
  min-height: calc(100vh - 60px);
}

.order-inner {
  max-width: 600px;
  margin: 0 auto;
}

/* 返回按钮样式 */
.order-back-btn {
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
  margin-bottom: var(--space-6);
  width: fit-content;
}

.order-back-btn:hover {
  color: var(--color-text);
  background-color: var(--color-bg-alt);
  border-color: var(--color-primary);
}

.order-loading {
  text-align: center;
  padding: var(--space-12) 0;
  font-size: var(--font-base);
  color: var(--color-text-light);
}

.order-title {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-text);
  margin-bottom: var(--space-2);
  text-align: center;
}

.order-show-name {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  text-align: center;
  margin-bottom: var(--space-8);
}

.order-empty {
  text-align: center;
  padding: var(--space-8) 0;
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.order-tickets {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin-bottom: var(--space-6);
}

.order-ticket {
  background-color: var(--color-white);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.order-ticket:hover,
.order-ticket.selected {
  border-color: var(--color-primary);
  box-shadow: var(--shadow-sm);
}

.order-ticket-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-2);
}

.order-ticket-type {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
}

.order-ticket-price {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-primary);
}

.order-ticket-stock {
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

.order-quantity {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--font-sm);
  color: var(--color-text);
  margin-bottom: var(--space-4);
  padding: var(--space-4);
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
}

.order-qty-controls {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.order-qty-controls button {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-sm);
  font-size: var(--font-lg);
  font-weight: 500;
  color: var(--color-text);
}

.order-qty-controls button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.order-qty-value {
  min-width: 24px;
  text-align: center;
  font-weight: 600;
}

.order-summary {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-4) 0;
  margin-bottom: var(--space-6);
  border-top: 1px solid var(--color-border);
}

.order-summary-label {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
}

.order-summary-price {
  font-size: var(--font-xl);
  font-weight: 700;
  color: var(--color-primary);
}

.order-error {
  font-size: var(--font-sm);
  color: var(--color-danger);
  text-align: center;
  margin-bottom: var(--space-4);
}

.order-submit {
  width: 100%;
  padding: var(--space-3);
  font-size: var(--font-base);
  font-weight: 600;
  border-radius: var(--radius-sm);
  background-color: var(--color-primary);
  color: var(--color-white);
}

.order-submit:hover:not(:disabled) {
  background-color: var(--color-secondary);
}

.order-submit:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.order-success {
  text-align: center;
  padding: var(--space-16) 0;
}

.order-success h2 {
  font-family: var(--font-heading);
  font-size: var(--font-xl);
  font-weight: 700;
  color: var(--color-primary);
  margin-bottom: var(--space-4);
}

.order-success p {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  margin-bottom: var(--space-6);
}

.order-success-btn {
  display: inline-block;
  padding: var(--space-2) var(--space-6);
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: var(--radius-sm);
  background-color: var(--color-primary);
  color: var(--color-white);
  text-decoration: none;
}

/* 秒杀票样式 - 与普通票统一 */
.seckill-ticket {
  /* 与普通票样式一致，无特殊边框和背景 */
}

/* 未上架票种样式 */
.unavailable-ticket {
  opacity: 0.6;
  cursor: not-allowed;
  background-color: var(--color-bg-alt);
}

.unavailable-ticket:hover {
  border-color: var(--color-border);
  box-shadow: none;
}

.status-tag-unavailable {
  display: inline-block;
  padding: var(--space-1) var(--space-2);
  font-size: var(--font-xs);
  font-weight: 600;
  color: var(--color-text-muted);
  background-color: var(--color-white-gray);
  border-radius: var(--radius-sm);
}

/* 售罄票种样式 */
.soldout-ticket {
  opacity: 0.6;
  cursor: not-allowed;
  background-color: var(--color-bg-danger);
}

.soldout-ticket:hover {
  border-color: var(--color-border);
  box-shadow: none;
}

.status-tag-soldout {
  display: inline-block;
  padding: 2px 8px;
  font-size: var(--font-xs);
  font-weight: 600;
  color: var(--color-danger-dark);
  background-color: var(--color-bg-danger);
  border-radius: var(--radius-sm);
}

/* 秒杀已结束票种样式 */
.expired-seckill {
  opacity: 0.6;
  cursor: not-allowed;
  background-color: var(--color-bg-warning);
}

.expired-seckill:hover {
  border-color: var(--color-border);
  box-shadow: none;
}

.status-tag-expired {
  display: inline-block;
  padding: 2px 8px;
  font-size: var(--font-xs);
  font-weight: 600;
  color: var(--color-warning-dark);
  background-color: var(--color-bg-warning);
  border-radius: var(--radius-sm);
}

.status-tag-purchased-seckill {
  display: inline-block;
  padding: 2px 8px;
  font-size: var(--font-xs);
  font-weight: 600;
  color: var(--color-success-dark);
  background-color: var(--color-bg-success);
  border-radius: var(--radius-sm);
}

.purchased-seckill {
  opacity: 0.7;
  cursor: not-allowed;
}

.purchased-seckill:hover {
  transform: none !important;
  box-shadow: var(--shadow-sm) !important;
}

.order-ticket-title-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.seckill-tag {
  display: inline-block;
  padding: 2px 8px;
  font-size: var(--font-xs);
  font-weight: 600;
  color: var(--color-text-light);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-sm);
}

.seckill-hint {
  color: var(--color-text-light);
  font-weight: 400;
}

.order-qty-fixed {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.seckill-limit-hint {
  font-size: var(--font-xs);
  color: var(--color-text-light);
  font-weight: 400;
}

/* 订单状态样式 */
.order-status-badge {
  text-align: center;
  padding: var(--space-2) var(--space-4);
  font-size: var(--font-base);
  font-weight: 600;
  border-radius: var(--radius-sm);
  margin-bottom: var(--space-4);
}

.status-0 {
  background-color: var(--color-bg-warning);
  color: var(--color-warning-dark);
  border: 1px solid var(--color-border-warning);
}

.status-2 {
  background-color: var(--color-bg-success);
  color: var(--color-success-dark);
  border: 1px solid var(--color-border-success);
}

.status-3 {
  background-color: var(--color-bg-info);
  color: var(--color-info-dark);
  border: 1px solid var(--color-border-info);
}

.status-4 {
  background-color: var(--color-white-gray);
  color: var(--color-text-muted);
  border: 1px solid var(--color-border-neutral);
}

.status-5 {
  background-color: var(--color-bg-danger);
  color: var(--color-danger-dark);
  border: 1px solid var(--color-border-danger);
}

.status-7 {
  background-color: var(--color-white-gray);
  color: var(--color-text-muted);
  border: 1px solid var(--color-border-neutral);
}

/* 倒计时样式 - 统一风格 */
.order-countdown {
  text-align: center;
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
  padding: var(--space-3);
  margin-bottom: var(--space-4);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-sm);
}

/* 核销码样式 */
.order-verify-code {
  text-align: center;
  padding: var(--space-6);
  margin-bottom: var(--space-4);
  background-color: var(--color-bg-info);
  border: 2px dashed var(--color-info-dark);
  border-radius: var(--radius-md);
}

.order-verify-label {
  font-size: var(--font-sm);
  color: var(--color-text-muted);
  margin-bottom: var(--space-2);
}

.order-verify-value {
  font-size: var(--font-3xl);
  font-weight: 700;
  letter-spacing: 4px;
  color: var(--color-info-dark);
  font-family: 'Courier New', monospace;
  margin-bottom: var(--space-2);
}

/* 多核销码列表样式 */
.order-verify-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.order-verify-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-4);
  background-color: var(--color-white);
  border: 1px solid var(--color-border-neutral);
  border-radius: var(--radius-sm);
}

.order-verify-index {
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--color-text-light);
  min-width: 60px;
}

.order-verify-item .order-verify-value {
  font-size: var(--font-xl);
  letter-spacing: 1px;
  margin-bottom: 0;
}

.order-verify-tip {
  font-size: var(--font-xs);
  color: var(--color-text-muted);
}

/* 核销码已使用状态样式 */
.order-verify-code-used {
  text-align: center;
  padding: var(--space-6);
  margin-bottom: var(--space-4);
  background: var(--gradient-success-bg);
  border: 2px solid var(--color-border-success);
  border-radius: var(--radius-md);
}

.used-icon-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 80px;
  height: 80px;
  margin: 0 auto var(--space-4);
  background: linear-gradient(135deg, var(--color-bg-success), var(--color-success));
  border-radius: 50%;
  color: var(--color-success-dark);
  box-shadow: var(--shadow-md);
}

.used-icon-wrapper :deep(svg) {
  filter: drop-shadow(0 2px 4px rgba(82, 196, 26, 0.2));
}

.used-title {
  font-size: var(--font-lg);
  font-weight: 700;
  color: var(--color-success-dark);
  margin-bottom: var(--space-2);
}

.used-reason {
  font-size: var(--font-base);
  color: var(--color-success-dark);
  margin-bottom: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background-color: rgba(82, 196, 26, 0.08);
  border-radius: var(--radius-sm);
}

.used-tip {
  font-size: var(--font-xs);
  color: var(--color-text-muted);
  line-height: 1.6;
}

/* 已核销提示样式 */
.status-3-msg {
  color: var(--color-success-dark);
  background-color: var(--color-bg-success);
  border: 1px solid var(--color-border-success);
  font-weight: 500;
}

/* 核销码隐藏状态样式 */
.order-verify-code-hidden {
  text-align: center;
  padding: var(--space-6);
  margin-bottom: var(--space-4);
  background: var(--gradient-warning-bg);
  border: 2px dashed var(--color-warning-dark);
  border-radius: var(--radius-md);
}

.hidden-icon-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 80px;
  height: 80px;
  margin: 0 auto var(--space-4);
  background: var(--gradient-warning-icon);
  border-radius: 50%;
  color: var(--color-warning-darker);
  box-shadow: 0 4px 12px rgba(250, 140, 22, 0.15);
}

.hidden-icon-wrapper :deep(svg) {
  filter: drop-shadow(0 2px 4px rgba(212, 107, 8, 0.2));
}

.hidden-title {
  font-size: var(--font-lg);
  font-weight: 700;
  color: var(--color-warning-darker);
  margin-bottom: var(--space-2);
}

.hidden-reason {
  font-size: var(--font-base);
  color: var(--color-warning-dark);
  margin-bottom: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background-color: rgba(250, 140, 22, 0.1);
  border-radius: var(--radius-sm);
}

.hidden-tip {
  font-size: var(--font-xs);
  color: var(--color-text-muted);
  line-height: 1.6;
}

/* 操作按钮区域 */
.order-actions {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin-bottom: var(--space-6);
}

.order-pay-btn {
  background-color: var(--color-primary);
  font-size: var(--font-lg);
  padding: var(--space-4);
  box-shadow: var(--shadow-md);
}

.order-pay-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 20px var(--color-shadow);
}

.order-refund-btn {
  width: 100%;
  padding: var(--space-3);
  font-size: var(--font-base);
  font-weight: 600;
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-danger);
  color: var(--color-danger-dark);
  border: 1px solid var(--color-border-danger);
}

.order-refund-btn:hover:not(:disabled) {
  background-color: var(--color-danger-light);
}

.order-status-msg {
  text-align: center;
  padding: var(--space-3);
  font-size: var(--font-sm);
  color: var(--color-text-light);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-sm);
}

.status-6-msg {
  color: var(--color-warning-darker);
  background-color: var(--color-bg-warning);
  border: 1px solid var(--color-border-warning-light);
  font-weight: 500;
}

.status-badge.status-6 {
  background-color: var(--color-bg-warning);
  color: var(--color-warning-darker);
  border: 1px solid var(--color-border-warning-light);
}

.order-back-link {
  display: block;
  text-align: center;
  padding: var(--space-3);
  font-size: var(--font-sm);
  color: var(--color-primary);
  text-decoration: none;
  border-top: 1px solid var(--color-border);
  margin-top: var(--space-4);
}

.order-back-link:hover {
  color: var(--color-secondary);
}

/* 秒杀排队提示条 */
.queue-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding: 12px 16px;
  background: #fff7e6;
  border: 1px solid #ffd591;
  border-radius: 10px;
  font-size: 14px;
  color: #ad6800;
}

.queue-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #fa8c16;
  flex-shrink: 0;
  animation: queue-pulse 1s ease-in-out infinite;
}

@keyframes queue-pulse {
  0%, 100% { opacity: 0.3; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1.2); }
}
</style>
