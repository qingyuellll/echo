<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { echoTimeMillis } from '../utils/guide.js'
import { useRoute, useRouter } from 'vue-router'
import { api, formatPrice, isLoggedIn, BusinessError, HttpError } from '../utils/api.js'
import { logger } from 'shared/utils/logger.js'

const AUTO_REDIRECT_DELAY = 3  // 支付成功后自动跳转延迟（秒）

const route = useRoute()
const router = useRouter()

const orderId = ref(String(route.query.orderId || ''))
const showId = ref(String(route.query.showId || ''))
const orderType = ref(route.query.orderType || 'normal') // 'seckill' 或 'normal'（用于UI显示秒杀标识）
const isShowEnded = ref(route.query.isShowEnded === 'true') // 演出是否已结束

function goBack() {
  if (confirm('确定要离开吗？未支付的订单可能会被取消')) {
    router.back()
  }
}
const orderInfo = ref(null)
const selectedPayMethod = ref('')
const paying = ref(false)
const userPoints = ref(0)
const usePoints = ref(0)
const pointsLoading = ref(false)
const paySuccess = ref(false)
const countdown = ref(0)
let countdownTimer = null
const orderError = ref('')  // 新增：订单加载错误信息
const orderLoading = ref(true)  // 新增：是否正在加载
const retryCount = ref(0)
const maxRetries = 8
const MAX_RETRY_TOTAL_TIME = 30000  // 总体超时30秒
let retryStartTime = null  // 记录首次重试时间

const parsedVerificationCodes = computed(() => {
  if (!orderInfo.value?.verificationCode) return []

  const rawCode = orderInfo.value.verificationCode

  try {
    const parsed = JSON.parse(rawCode)
    return Array.isArray(parsed) ? parsed : [rawCode]
  } catch (e) {
    return [rawCode]
  }
})

const payMethods = [{id:'simulation',name:'模拟支付',description:'本地演示支付，不会真实扣款',color:'#52784a'}]

async function loadOrderInfo() {
  if (!orderId.value) return

  orderLoading.value = true
  orderError.value = ''
  orderInfo.value = null

  try {
    logger.info('PayOrder', '加载订单', { orderId: orderId.value, retryCount: retryCount.value })

    const res = await api.getOrderById(orderId.value)

    if (res && res.data) {
      orderInfo.value = res.data

      const orderStatus = res.data.status

      if (res.data.pending) {
        logger.debug('PayOrder', '检测到秒杀订单异步处理中')
        orderError.value = ''
      }

      if (orderStatus === 0 || orderStatus === 1) {
        const createdTime = echoTimeMillis(res.data.createTime)
        const serverExpiry = echoTimeMillis(res.data.expireTime)
        const expireTime = Number.isFinite(serverExpiry) ? serverExpiry : createdTime + 5 * 60 * 1000
        const remaining = Math.floor((expireTime - Date.now()) / 1000)

        if (!Number.isFinite(expireTime)) {
          countdown.value=0;orderError.value='暂时无法核对付款截止时间，请刷新订单后再付款'
        } else if (remaining > 0) {
          startCountdown(remaining)
        } else {
          countdown.value = -1
          logger.warn('PayOrder', '订单已超时')
        }
      } else if (orderStatus === 2) {
        paySuccess.value = true
        countdown.value = -1
        logger.info('PayOrder', '订单已支付', { orderId: orderId.value })
      } else if (orderStatus >= 3) {
        countdown.value = -1
        logger.info('PayOrder', '订单状态', { status: orderStatus, statusText: ['已核销', '已取消', '退款中', '已退款'][orderStatus - 3] })
      }

      retryCount.value = 0
      retryStartTime = null
    } else {
      logger.error('PayOrder', 'API返回空数据', { res })
      orderError.value = '服务器返回数据异常'
    }
  } catch (e) {
    logger.error('PayOrder', '加载订单失败', { error: e.message, retryCount: retryCount.value })

    if ((e.message?.includes('不存在') || e.message?.includes('无权') || e.message?.includes('404')) && retryCount.value < maxRetries) {
      if (retryStartTime === null) {
        retryStartTime = Date.now()
      }

      const elapsed = Date.now() - retryStartTime
      if (elapsed > MAX_RETRY_TOTAL_TIME) {
        logger.warn('PayOrder', '重试超时', { elapsedMs: elapsed, maxMs: MAX_RETRY_TOTAL_TIME })
        orderError.value = '订单创建超时，请返回演出列表重新下单'
        orderLoading.value = false
        return
      }

      retryCount.value++
      const delay = Math.min(1000 * Math.pow(1.5, retryCount.value - 1), 4000)

      logger.warn('PayOrder', '自动重试', { nextRetry: retryCount.value, delayMs: delay, elapsedSec: Math.round(elapsed / 1000) })

      orderLoading.value = false
      orderError.value = `订单创建中（${Math.round(delay / 1000)}秒后重试 ${retryCount.value}/${maxRetries}）...`

      setTimeout(() => loadOrderInfo(), delay)
      return
    }

    orderError.value = handleLoadError(e)
    orderInfo.value = null
  } finally {
    if (!orderError.value?.includes('创建中')) {
      orderLoading.value = false
    }
  }
}

function handleLoadError(e) {
  if (e.message?.includes('不存在') || e.message?.includes('无权')) {
    return e.message + '，请返回演出列表重新下单'
  }
  if (e.message?.includes('未登录') || e.message?.includes('401')) {
    setTimeout(() => router.push(`/login?redirect=/pay-order?orderId=${orderId.value}&showId=${showId.value}`), 1500)
    return '请先登录'
  }
  return '加载订单失败：' + (e.message || '网络异常')
}

function selectPayMethod(methodId) {
  selectedPayMethod.value = methodId
}

async function loadUserPoints() {
  pointsLoading.value = true
  try {
    const res = await api.getPoints()
    userPoints.value = res.data?.points || res.data || 0
  } catch (e) {
    logger.warn('PayOrder', '获取积分失败', { error: e.message })
    userPoints.value = 0
  } finally {
    pointsLoading.value = false
  }
}

function getMaxPointsCanUse() {
  if (!orderInfo.value?.payValue) return 0
  const maxByOrder = Math.floor(orderInfo.value.payValue * 10)
  return Math.min(userPoints.value, maxByOrder)
}

function updateUsePoints(value) {
  const maxPoints = getMaxPointsCanUse()
  usePoints.value = Math.max(0, Math.min(value, maxPoints))
}

async function confirmPay() {
  if(paying.value||paySuccess.value)return
  if(!orderInfo.value||![0,1].includes(Number(orderInfo.value.status))){await loadOrderInfo();return}
  if (!selectedPayMethod.value) {
    alert('请选择支付方式')
    return
  }

  if (!orderId.value) {
    alert('订单信息不存在，请重新下单')
    await router.push('/shows')
    return
  }

  paying.value = true

  try {
    logger.info('PayOrder', '开始支付', { orderId: orderId.value, usePoints: usePoints.value })

    let res
    try {
      res = await api.payOrder(orderId.value, usePoints.value)
    } catch (payError) {
      const handled = await handlePaymentError(payError)
      if (handled) return
      throw payError
    }

    const isSuccessful = res && (
      res.success === true ||
      res.code === 200 ||
      (res.data && res.data.status === 2) ||
      (res.data && res.data.verificationCode) ||
      res.data?.id
    )

    if (isSuccessful) {
      logger.info('PayOrder', '支付成功', { orderId: orderId.value })
      stopCountdown()
      paySuccess.value = true

      // 支付成功后预订单已被删除（tempId 不复存在），TEMP_ 开头的订单号无法也无法需要回查；
      // 页面直接展示成功态，倒计时结束跳转"我的订单"查看正式子订单
      if (!String(orderId.value).startsWith('TEMP_')) {
        try {
          await loadOrderInfo()
        } catch (reloadError) {
          logger.warn('PayOrder', '刷新订单失败', { error: reloadError.message })
        }
      }

      countdown.value = 3
      countdownTimer = setInterval(() => {
        if (countdown.value > 0) {
          countdown.value--
          if (countdown.value === 0) {
            clearInterval(countdownTimer)
            router.push('/profile')
          }
        }
      }, 1000)
    } else {
      const failMsg = res?.errorMsg || res?.message || '支付失败，请检查网络后重试'
      throw new Error(failMsg)
    }
  } catch (e) {
    logger.error('PayOrder', '支付异常', { error: e.message, type: e.constructor.name })

    let userErrorMsg = getFriendlyPaymentErrorMessage(e.message)

    alert(`${userErrorMsg}\n\n请先到购票工作台核对这笔订单的最新状态。`)

    showPaymentRecoveryOptions()
  } finally {
    paying.value = false
  }
}

async function handlePaymentError(payError) {
  let errorMsg = extractPaymentErrorMessage(payError)

  if (errorMsg.includes('已支付')) {
    alert('该订单已支付，正在刷新状态...')
    await loadOrderInfo()
    return true
  }

  if (errorMsg.includes('不存在') || errorMsg.includes('404')) {
    alert('付款结果暂未确认，请到购票工作台核对订单，再决定是否重新购买')
    await router.push('/guide')
    return true
  }

  if (errorMsg.includes('已取消')) {
    alert('订单已取消，请重新下单')
    await router.push('/shows')
    return true
  }

  if (errorMsg.includes('已退款')) {
    alert('订单已退款，无法支付')
    await router.push('/profile')
    return true
  }

  return false
}

function extractPaymentErrorMessage(payError) {
  if (payError instanceof BusinessError) {
    return payError.message
  }

  if (payError instanceof HttpError && payError.response?.data) {
    const data = payError.response.data
    if (data.errorMsg) return data.errorMsg
    if (data.message && !data.message.includes('Unexpected')) {
      return data.message.split('\n')[0].split('Caused by:')[0].trim().substring(0, 150)
    }
  }

  if (payError.message && !payError.message.includes('Unexpected')) {
    return payError.message.substring(0, 150)
  }

  return '支付请求失败'
}

function getFriendlyPaymentErrorMessage(errorMsg) {
  if (errorMsg.includes('timeout') || errorMsg.includes('超时')) return '支付超时，请稍后重试'
  if (errorMsg.includes('network') || errorMsg.includes('网络')) return '网络连接不稳定，请检查网络后重试'
  if (errorMsg.includes('余额') || errorMsg.includes('积分')) return errorMsg
  return errorMsg || '支付失败，请检查网络后重试'
}

function showPaymentRecoveryOptions() {
  orderError.value='本次付款结果暂未确认，请先刷新订单或到购票工作台核对，避免重复操作。'
  void loadOrderInfo()
}

async function cancelPay() {
  if (!orderId.value) {
    alert('订单信息不存在')
    return
  }

  if (countdown.value <= 0) {
    alert('订单已超时或已失效，无法取消')
    return
  }

  const confirmed = confirm('确定要取消订单吗？\n\n取消后库存将释放，您需要重新下单购买')
  if (!confirmed) return

  try {
    logger.info('PayOrder', '取消订单', { orderId: orderId.value })

    await api.cancelOrder(orderId.value)
    stopCountdown()

    alert('订单已成功取消\n\n库存已释放，您可以重新选择其他票种')

    setTimeout(() => router.push('/shows'), 1500)
  } catch (e) {
    logger.error('PayOrder', '取消订单失败', { error: e.message })

    let errorMsg = e.message || '取消失败'
    if (errorMsg.includes('已支付')) errorMsg = '该订单已支付，如需取消请申请退款'
    if (errorMsg.includes('已取消')) errorMsg = '订单已被取消，无需重复操作'
    if (errorMsg.includes('不存在') || errorMsg.includes('无权')) errorMsg = '订单不存在或无权操作'

    alert(`${errorMsg}\n\n如果问题持续存在，请联系客服`)
  }
}

function startCountdown(seconds) {
  countdown.value = seconds
  if (countdownTimer) clearInterval(countdownTimer)

  countdownTimer = setInterval(() => {
    countdown.value--

    if (countdown.value <= 0) {
      stopCountdown()

      const userChoice = confirm(
        '支付超时，订单已自动取消\n\n' +
        '您可以：\n' +
        '• 点击"确定"查看我的订单\n' +
        '• 点击"取消"继续浏览演出'
      )

      if (userChoice) {
        router.push('/profile')
      } else {
        router.push('/shows')
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
  if (seconds <= 0) return '00:00'

  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
}

// 页面卸载前提示（防止误关闭）
function beforeUnloadHandler(e) {
  if (countdown.value > 0 && !paySuccess.value) {
    e.preventDefault()
    e.returnValue = ''
  }
}

onMounted(() => {
  if (!isLoggedIn()) {
    router.push(`/login?redirect=/pay-order?orderId=${orderId.value}&showId=${showId.value}`)
    return
  }

  if (!orderId.value) {
    orderError.value = '订单ID缺失，请从订单列表重新进入'
    orderLoading.value = false
    return
  }

  // 检查演出是否已结束，显示明确提示
  if (isShowEnded.value) {
    console.warn('[PayOrder] ⚠️ 检测到演出已结束')
    orderError.value = '该演出已结束，无法完成支付'
    orderLoading.value = false
    return
  }

  loadOrderInfo()
  loadUserPoints()

  // 添加页面关闭确认
  window.addEventListener('beforeunload', beforeUnloadHandler)
})

onUnmounted(() => {
  stopCountdown()
  paySuccess.value = false
  countdown.value = 0
  window.removeEventListener('beforeunload', beforeUnloadHandler)
})
</script>

<template>
  <div class="pay-page">
    <div class="pay-inner">

      <!-- 支付成功状态 -->
      <template v-if="paySuccess">
        <div class="pay-success">
          <h1 class="pay-success-title">支付成功</h1>
          <p class="pay-success-desc">您的订单已支付完成</p>

          <p v-if="countdown > 0" class="pay-countdown">
            {{ countdown }}秒后自动跳转到个人中心...
          </p>

          <div v-if="orderInfo" class="pay-success-info">
            <p>订单号：#{{ orderId }}</p>
            <p>支付金额：{{ formatPrice(orderInfo.payValue) }}</p>
            <p>支付方式：模拟支付（无真实扣款）</p>

            <!-- 核销码（支持多张票） -->
            <div v-if="parsedVerificationCodes.length > 0" class="pay-verification-codes">
              <p class="pay-verify-title">核销码（共{{ parsedVerificationCodes.length }}张票）</p>

              <!-- 单张票 -->
              <div v-if="parsedVerificationCodes.length === 1" class="pay-verification-code">
                <strong>{{ parsedVerificationCodes[0] }}</strong>
              </div>

              <!-- 多张票列表 -->
              <div v-else class="pay-verify-list">
                <div
                  v-for="(code, index) in parsedVerificationCodes"
                  :key="index"
                  class="pay-verify-item"
                >
                  <span class="pay-verify-label">第{{ index + 1 }}张：</span>
                  <strong>{{ code }}</strong>
                </div>
              </div>
            </div>
          </div>

          <div class="pay-success-actions">
            <router-link to="/profile" class="pay-btn-primary">查看我的订单</router-link>
            <router-link to="/shows" class="pay-btn-secondary">继续浏览演出</router-link>
          </div>

          <p class="pay-countdown-tip">{{ AUTO_REDIRECT_DELAY }}秒后自动跳转到个人中心...</p>
        </div>
      </template>

      <!-- 支付界面 -->
      <template v-else>
        <!-- 返回按钮 -->
        <button class="pay-back-btn" @click="goBack">
          返回
        </button>

        <!-- 订单信息 -->
        <div class="pay-order-info">
          <h1 class="pay-title">确认支付</h1>

          <div v-if="orderInfo" class="pay-detail">
            <div class="pay-detail-row">
              <span class="pay-detail-label">订单号</span>
              <span class="pay-detail-value">#{{ orderId }}</span>
            </div>
            <div class="pay-detail-row">
              <span class="pay-detail-label">演出名称</span>
              <span class="pay-detail-value">{{ orderInfo.showName || '未知演出' }}</span>
            </div>
            <div class="pay-detail-row">
              <span class="pay-detail-label">票型</span>
              <span class="pay-detail-value">
                {{ orderInfo.ticketTitle || '普通票' }}
                <span v-if="orderType === 'seckill'" class="pay-seckill-badge">秒杀</span>
              </span>
            </div>

            <!-- 购买数量（重要：显示实际购买张数） -->
            <div class="pay-detail-row">
              <span class="pay-detail-label">购买数量</span>
              <span class="pay-detail-value">
                <strong>{{ orderInfo.quantity || 1 }} 张</strong>
                <span class="pay-unit-price" v-if="orderInfo.unitPrice">
                  （单价 {{ formatPrice(orderInfo.unitPrice) }}）
                </span>
              </span>
            </div>

            <div class="pay-detail-divider"></div>
            <div class="pay-detail-row pay-total">
              <span class="pay-detail-label">应付金额</span>
              <!-- 显示总价（单价 × 数量） -->
              <span class="pay-detail-price">{{ formatPrice(orderInfo.payValue) }}</span>
            </div>
          </div>

          <!-- 倒计时警告 -->
          <div v-if="countdown > 0" class="pay-countdown-warning">
            <span>请在 <strong>{{ formatCountdown(countdown) }}</strong> 内完成支付，超时订单将自动取消</span>
          </div>

          <div v-else-if="countdown === -1 && !paySuccess && orderInfo && orderInfo.status === 3" class="pay-expired">
            订单已核销，无法重复支付
          </div>
          <div v-else-if="countdown === -1 && !paySuccess && orderInfo && orderInfo.status === 4" class="pay-expired">
            订单已取消，请重新下单
          </div>
          <div v-else-if="countdown === -1 && !paySuccess && orderInfo && orderInfo.status === 5" class="pay-expired">
            订单退款中，请稍后查看
          </div>
          <div v-else-if="countdown === -1 && !paySuccess && orderInfo && orderInfo.status === 6" class="pay-expired">
            订单已退款，无法支付
          </div>

          <div v-else-if="countdown === -1 && !paySuccess && orderInfo && orderInfo.status === 0" class="pay-countdown-warning pay-expired-warning">
            <span>订单可能已超时，但仍可尝试支付</span>
            <button @click="confirmPay" class="retry-pay-btn">尝试支付</button>
          </div>

          <div v-else-if="orderLoading" class="pay-loading">
            <div class="loading-spinner"></div>
            加载订单信息中...
          </div>

          <div v-else-if="orderError?.includes('正在创建中')" class="pay-loading pay-loading-pending">
            <div class="loading-spinner"></div>
            <p>{{ orderError }}</p>
            <button @click="loadOrderInfo" class="retry-pay-btn" style="margin-top: 12px;">
              立即重试
            </button>
          </div>

          <div v-else-if="orderError" :class="['pay-error-container', { 'show-ended-error': isShowEnded }]">
            <div class="pay-error-icon">{{ isShowEnded ? '!' : '!' }}</div>
            <h3 class="pay-error-title">{{ isShowEnded ? '演出已结束' : '加载失败' }}</h3>
            <p class="pay-error-message">{{ orderError }}</p>
            <p v-if="isShowEnded" class="pay-error-hint">该演出已经结束，无法完成购票支付</p>
            <div class="pay-error-actions">
              <button @click="goBack" class="pay-error-btn pay-error-btn-secondary">返回演出列表</button>
              <button v-if="!isShowEnded" @click="loadOrderInfo" class="pay-error-btn pay-error-btn-primary">重新加载</button>
              <button v-else @click="router.push('/')" class="pay-error-btn pay-error-btn-primary">浏览其他演出</button>
            </div>
          </div>
        </div>

        <!-- 积分抵扣 -->
        <div v-if="(countdown > 0 || (orderInfo && orderInfo.status === 0)) && userPoints > 0" class="points-section">
          <h3 class="points-title">积分抵扣</h3>
          <p class="points-info">当前积分：{{ userPoints }}（10积分=1元，最多抵扣 {{ formatPrice(Math.min(orderInfo?.payValue || 0, userPoints / 10)) }}）</p>
          <div class="points-controls">
            <button
              class="points-btn"
              :disabled="usePoints <= 0"
              @click="updateUsePoints(usePoints - 10)"
            >−</button>
            <input
              type="number"
              :value="usePoints"
              min="0"
              :max="getMaxPointsCanUse()"
              step="10"
              readonly
              class="points-input"
            />
            <button
              class="points-btn"
              :disabled="usePoints >= getMaxPointsCanUse()"
              @click="updateUsePoints(usePoints + 10)"
            >+</button>
            <span class="points-deduction">−{{ formatPrice(usePoints / 10) }}</span>
          </div>
        </div>

        <!-- 支付方式选择 -->
        <div v-if="(countdown > 0 || (orderInfo && orderInfo.status === 0))" class="pay-methods">
          <h2 class="pay-methods-title">选择支付方式</h2>

          <div class="pay-method-list">
            <div
              v-for="method in payMethods"
              :key="method.id"
              :class="['pay-method-item', { active: selectedPayMethod === method.id }]"
              @click="selectPayMethod(method.id)"
            >
              <div class="pay-method-radio">
                <div :class="['radio-circle', { checked: selectedPayMethod === method.id }]">
                  <div v-if="selectedPayMethod === method.id" class="radio-dot"></div>
                </div>
              </div>

              <div class="pay-method-info">
                <div class="pay-method-name" :style="{ color: method.color }">{{ method.name }}</div>
                <div class="pay-method-desc">{{ method.description }}</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 操作按钮 -->
        <div v-if="(countdown > 0 || (orderInfo && orderInfo.status === 0))" class="pay-actions">
          <button
            class="pay-confirm-btn"
            :class="{ disabled: !selectedPayMethod || paying }"
            :disabled="!selectedPayMethod || paying"
            @click="confirmPay"
          >
            {{ paying ? '支付处理中...' : `确认支付 ${orderInfo ? formatPrice((orderInfo.payValue || 0) - usePoints / 10) : ''}` }}
          </button>

          <button class="pay-cancel-btn" @click="cancelPay">
            取消支付
          </button>
        </div>


      </template>

    </div>
  </div>
</template>

<style scoped>
.pay-page {
  padding: var(--space-12) var(--space-8);
  background-color: var(--color-bg);
  min-height: calc(100vh - 60px);
}

.pay-inner {
  max-width: 600px;
  margin: 0 auto;
}

/* ===== 返回按钮样式 ===== */
.pay-back-btn {
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

.pay-back-btn:hover {
  color: var(--color-text);
  background-color: var(--color-bg-alt);
  border-color: var(--color-primary);
}

/* ===== 支付成功样式 ===== */
.pay-success {
  text-align: center;
  padding: var(--space-12) 0;
}

.pay-success-title {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-success);
  margin-bottom: var(--space-3);
}

.pay-success-desc {
  font-size: var(--font-base);
  color: var(--color-text-light);
  margin-bottom: var(--space-2);
}

.pay-countdown {
  font-size: var(--font-sm);
  color: var(--color-success-dark);
  margin-bottom: var(--space-6);
  animation: pulse 1s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.pay-success-info {
  background-color: var(--color-bg-success);
  border: 1px solid var(--color-border-success);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  margin-bottom: var(--space-6);
  text-align: left;
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.pay-success-info p {
  margin-bottom: var(--space-2);
}

.pay-success-info p:last-child {
  margin-bottom: 0;
}

.pay-success-actions {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.pay-btn-primary,
.pay-btn-secondary {
  display: block;
  padding: var(--space-3) var(--space-6);
  font-size: var(--font-base);
  font-weight: 600;
  border-radius: var(--radius-sm);
  text-align: center;
  text-decoration: none;
  transition: all var(--transition-fast);
}

.pay-btn-primary {
  background-color: var(--color-primary);
  color: white;
  box-shadow: var(--shadow-md);
}

.pay-btn-primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 20px var(--color-shadow);
}

.pay-btn-secondary {
  background-color: var(--color-white);
  color: var(--color-text);
  border: 1px solid var(--color-border);
}

.pay-btn-secondary:hover {
  background-color: var(--color-bg-alt);
}

.pay-countdown-tip {
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

/* 核销码样式（支持多张票） */
.pay-verification-codes {
  margin-top: var(--space-4);
  padding: var(--space-4);
  background-color: var(--color-bg-success);
  border: 1px solid var(--color-border-success);
  border-radius: var(--radius-md);
}

.pay-verify-title {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  margin-bottom: var(--space-3);
}

.pay-verification-code {
  margin-top: 0;
  padding: 0;
  background-color: transparent;
  border: none;
}

.pay-verification-code strong {
  font-size: var(--font-lg);
  color: var(--color-success-dark);
  letter-spacing: 2px;
  font-family: 'Courier New', monospace;
}

.pay-verify-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-top: var(--space-2);
}

.pay-verify-item {
  display: flex;
  align-items: center;
  padding: var(--space-2) var(--space-3);
  background-color: white;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.pay-verify-label {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  margin-right: var(--space-2);
}

.pay-verify-item strong {
  font-size: var(--font-base);
  color: var(--color-success-dark);
  letter-spacing: 1px;
  font-family: 'Courier New', monospace;
}

/* ===== 订单信息样式 ===== */
.pay-title {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-text);
  text-align: center;
  margin-bottom: var(--space-6);
}

.pay-detail {
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
  padding: var(--space-8) var(--space-6);
  margin-bottom: var(--space-6);
}

.pay-detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-2) 0;
  font-size: var(--font-sm);
}

.pay-detail-label {
  color: var(--color-text-light);
}

.pay-detail-value {
  color: var(--color-text);
  font-weight: 500;
}

.pay-detail-divider {
  height: 1px;
  background-color: var(--color-border);
  margin: var(--space-3) 0;
}

.pay-total {
  padding-top: var(--space-3);
}

.pay-total .pay-detail-label {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
}

.pay-detail-price {
  font-size: var(--font-xl);
  font-weight: 700;
  color: var(--color-primary);
}

/* 购买数量和单价显示 */
.pay-unit-price {
  font-size: var(--font-sm, 14px);
  color: var(--color-text-light, #999);
  font-weight: normal;
  margin-left: var(--space-2);
}

/* ===== 倒计时警告样式 ===== */
.pay-countdown-warning {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  background-color: var(--color-bg-warning);
  border: 1px solid var(--color-border-warning);
  border-radius: var(--radius-sm);
  padding: var(--space-4) var(--space-6);
  margin-bottom: var(--space-6);
  font-size: var(--font-base);
  color: var(--color-warning-dark);
  animation: pulse 2s ease-in-out infinite;
  font-weight: 500;
  letter-spacing: 1px;
}

@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.85; transform: scale(0.99); }
}

.pay-countdown-warning strong {
  font-size: var(--font-lg);
  font-weight: 800;
  color: var(--color-warning-dark);
  letter-spacing: 2px;
  margin: 0 4px;
  text-shadow: 0 1px 2px rgba(166, 186, 174, 0.15);
}

.pay-expired {
  text-align: center;
  padding: var(--space-6);
  background-color: var(--color-bg-warning);
  border: 1px solid var(--color-border-warning);
  border-radius: var(--radius-md);
  color: var(--color-warning-dark);
  font-weight: 600;
  margin-bottom: var(--space-6);
}

.pay-expired-warning {
  background-color: var(--color-bg-warning);
  border-color: var(--color-border-warning);
  color: var(--color-warning-dark);
}

.retry-pay-btn {
  margin-top: var(--space-3);
  padding: var(--space-2) var(--space-6);
  background-color: var(--color-warning);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  cursor: pointer;
  transition: all 0.2s;
}

.retry-pay-btn:hover {
  background-color: var(--color-warning-dark);
}

.pay-loading {
  text-align: center;
  padding: var(--space-6);
  color: var(--color-text-light);
}

.pay-loading-pending {
  background-color: var(--color-bg-alt);
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-md);
  padding: var(--space-8) var(--space-6);
}

.pay-loading-pending p {
  margin-top: var(--space-3);
  color: var(--color-primary);
  font-weight: 500;
}

.loading-spinner {
  display: inline-block;
  width: 32px;
  height: 32px;
  border: 3px solid var(--color-border);
  border-top: 3px solid var(--color-primary);
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin-bottom: var(--space-3);
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

.pay-error-container {
  text-align: center;
  padding: var(--space-8) var(--space-6);
  background-color: var(--color-bg-danger);
  border: 1px solid var(--color-border-danger);
  border-radius: var(--radius-md);
  margin: var(--space-4) 0;
}

/* 演出已结束的特殊样式 */
.show-ended-error {
  background-color: rgba(196, 135, 123, 0.08);
  border-color: var(--color-danger, #c4877b);
}

.show-ended-error .pay-error-icon {
  color: var(--color-danger, #c4877b);
}

.show-ended-error .pay-error-title {
  color: var(--color-danger, #c4877b);
}

.pay-error-hint {
  font-size: var(--font-sm, 14px);
  color: var(--color-text-light);
  margin: var(--space-2) 0 var(--space-4);
  font-style: italic;
}

.pay-error-icon {
  font-size: 48px;
  margin-bottom: var(--space-4);
}

.pay-error-title {
  font-size: var(--font-lg);
  font-weight: 600;
  color: var(--color-danger-dark);
  margin: 0 0 var(--space-3);
}

.pay-error-message {
  font-size: var(--font-base);
  color: var(--color-text-light);
  margin: 0 0 var(--space-6);
  line-height: 1.6;
}

.pay-error-actions {
  display: flex;
  gap: var(--space-4);
  justify-content: center;
}

.pay-error-btn {
  padding: var(--space-3) var(--space-6);
  border-radius: var(--radius-sm);
  font-size: var(--font-base);
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  border: none;
}

.pay-error-btn-secondary {
  background-color: var(--color-white);
  color: var(--color-text-light);
  border: 1px solid var(--color-border);
}

.pay-error-btn-secondary:hover {
  border-color: var(--color-primary);
  color: var(--color-text);
}

.pay-error-btn-primary {
  background-color: var(--color-primary);
  color: var(--color-white);
}

.pay-error-btn-primary:hover {
  background-color: var(--color-primary-dark);
}

/* ===== 支付方式选择样式 ===== */
.pay-methods {
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
  padding: var(--space-8) var(--space-6);
  margin-bottom: var(--space-6);
}

.pay-methods-title {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-4);
}

.pay-method-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.pay-method-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-4);
  border: 2px solid var(--color-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  background-color: var(--color-white);
}

.pay-method-item:hover {
  border-color: var(--color-primary);
  background-color: var(--color-bg-alt);
}

.pay-method-item.active {
  border-color: var(--color-primary);
  background-color: var(--color-primary-alpha);
  box-shadow: 0 2px 8px rgba(166, 186, 174, 0.15);
}

.pay-method-radio {
  flex-shrink: 0;
}

.radio-circle {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 2px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all var(--transition-fast);
}

.radio-circle.checked {
  border-color: var(--color-primary);
  background-color: var(--color-primary);
}

.radio-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: white;
}

.pay-method-info {
  flex: 1;
}

.pay-method-name {
  font-size: var(--font-base);
  font-weight: 700;
  color: var(--color-text);
  margin-bottom: var(--space-1);
  letter-spacing: 1px;
}

.pay-method-desc {
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

/* ===== 操作按钮样式 ===== */
.pay-actions {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.pay-confirm-btn {
  width: 100%;
  padding: var(--space-4);
  font-size: var(--font-lg);
  font-weight: 700;
  border-radius: var(--radius-md);
  background-color: var(--color-primary);
  color: var(--color-white);
  border: none;
  cursor: pointer;
  transition: all var(--transition-fast);
  box-shadow: var(--shadow-md);
}

.pay-confirm-btn:hover:not(.disabled) {
  transform: translateY(-2px);
  background-color: var(--color-primary-dark);
  box-shadow: 0 6px 16px rgba(166, 186, 174, 0.45);
}

.pay-confirm-btn:active:not(.disabled) {
  transform: translateY(0);
  background-color: var(--color-primary-darker);
}

.pay-confirm-btn.disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

.pay-cancel-btn {
  width: 100%;
  padding: var(--space-3);
  font-size: var(--font-base);
  font-weight: 500;
  border-radius: var(--radius-sm);
  background-color: transparent;
  color: var(--color-text-light);
  border: 1px solid var(--color-border);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.pay-cancel-btn:hover {
  background-color: var(--color-bg-alt);
  color: var(--color-text);
}

@media (max-width: 768px) {
  .pay-inner {
    max-width: 100%;
  }

  .pay-confirm-btn {
    font-size: var(--font-base);
  }
}

/* 秒杀票标识 - 统一风格 */
.pay-seckill-badge {
  display: inline-block;
  margin-left: var(--space-2);
  padding: var(--space-1) var(--space-2);
  font-size: var(--font-xs);
  font-weight: 600;
  color: var(--color-text-light);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-sm);
}

/* ===== 积分抵扣样式 ===== */
.points-section {
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
  padding: var(--space-6);
  margin-bottom: var(--space-4);
}

.points-title {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-2);
}

.points-info {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  margin-bottom: var(--space-3);
}

.points-controls {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.points-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--color-white);
  color: var(--color-text);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-lg);
  font-weight: 300;
  cursor: pointer;
  transition: all var(--transition-fast);
}

.points-btn:hover:not(:disabled) {
  background-color: var(--color-primary);
  color: var(--color-white);
  border-color: var(--color-primary);
}

.points-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.points-input {
  width: 80px;
  height: 36px;
  text-align: center;
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-alt);
}

.points-deduction {
  margin-left: auto;
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--color-primary);
}
</style>
