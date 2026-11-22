<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import SeatPicker from '../components/common/SeatPicker.vue'
import { api, getUser } from '../utils/api.js'
import { guideHome, newGuideRequestId, isSeckill, ticketStock, priceYuan } from '../utils/guide.js'

const route = useRoute(), router = useRouter()
const ticketId = String(route.params.ticketId || '')
const count = ref(Math.min(10, Math.max(1, Number(route.query.count) || 1)))
const preview = ref(null), loading = ref(true), error = ref(''), busy = ref(false)
const acknowledged = ref(false), selected = ref([]), seatVersion = ref(0)
const requestId = ref(newGuideRequestId()), submitted = ref(false), uncertain = ref(false)
const queue = ref(false), queueMessage = ref(''), resultId = ref('')
let disposed = false
const ticket = computed(() => preview.value?.ticket)
const seckill = computed(() => isSeckill(ticket.value))
const maximum = computed(() => Math.min(seckill.value ? 1 : 10, ticket.value ? ticketStock(ticket.value) : 0))
const canSubmit = computed(() => preview.value?.purchasable && maximum.value >= count.value && count.value >= 1 && Number.isInteger(count.value) && acknowledged.value && !busy.value && !submitted.value && !uncertain.value && (!ticket.value?.hasSeats || selected.value.length === count.value))
const storageKey = `echo-guide-order:${getUser()?.id || 'current'}:${ticketId}`
const carriedBudget=Number(route.query.budget)||0
const seatPreference=String(route.query.seatPreference||'')
const previousSaved=ref(null)

function remember() {
  try { sessionStorage.setItem(storageKey, JSON.stringify({ requestId: requestId.value, resultId: resultId.value, count: count.value })) } catch { /* The server still deduplicates this request. */ }
}
function safeDate(value) {
  if(!value)return '时间待公布'
  const source=String(value).replace(' ','T')
  const date=new Date(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?$/.test(source)?source+'+08:00':source)
  return Number.isFinite(date.getTime())?new Intl.DateTimeFormat('zh-CN',{year:'numeric',month:'short',day:'numeric',hour:'2-digit',minute:'2-digit',timeZone:'Asia/Shanghai'}).format(date):value
}
async function releaseSeats() {
  if (submitted.value || uncertain.value) return
  const seats = selected.value; selected.value = []
  await Promise.allSettled(seats.map(seat => api.releaseSeat(ticketId, seat.id)))
}
async function load() {
  loading.value = true; error.value = ''
  try {
    const response = await api.guideCheckout(ticketId)
    preview.value = response.data
    if (seckill.value) count.value = 1
  } catch (failure) { error.value = failure.message || '无法读取订单确认信息' }
  finally { loading.value = false }
}
async function refresh() {
  if (submitted.value || uncertain.value) return
  await releaseSeats(); seatVersion.value++; acknowledged.value = false
  requestId.value = newGuideRequestId()
  try { sessionStorage.removeItem(storageKey) } catch { /* Continue without saved state. */ }
  await load()
}
async function changeCount(event) {
  await releaseSeats(); count.value = Number(event.target.value); seatVersion.value++
  acknowledged.value = false; requestId.value = newGuideRequestId()
}
function selectSeats(seats) { selected.value = seats; requestId.value = newGuideRequestId(); acknowledged.value = false }
async function newPurchase() {
  if(busy.value||uncertain.value)return
  submitted.value=false;queue.value=false;resultId.value='';previousSaved.value=null
  try{sessionStorage.removeItem(storageKey)}catch{}
  await refresh()
}
function payment() { if (resultId.value) router.push({ path:'/pay-order',query:{orderId:resultId.value,showId:String(preview.value.show.id),ticketId,orderType:seckill.value?'seckill':'normal'} }) }

async function pollQueue() {
  busy.value = true
  try {
    const deadline = Date.now() + 25000
    while (!disposed && Date.now() < deadline) {
      const response = await api.seckillQueueStatus(ticketId)
      const data = response.data || {}
      if (disposed) return
      if (data.tempId) { resultId.value = String(data.tempId); queue.value = false; remember(); payment(); return }
      if (['FAILED','SOLD_OUT','NOT_IN_QUEUE'].includes(data.status)) { queueMessage.value = data.message || '排队已结束，请到我的订单核对结果'; return }
      queueMessage.value = `正在排队${data.ahead != null ? `，前方 ${data.ahead} 人` : ''}。离开页面不会重复下单。`
      await new Promise(resolve => setTimeout(resolve, 1000))
    }
    queueMessage.value = '排队仍在进行，可以继续查询或到我的订单查看。'
  } catch (failure) { error.value = failure.message || '暂时无法查询排队结果' }
  finally { busy.value = false }
}
async function resolve(response, navigate = false) {
  const data = response.data || {}
  if (data.tempId) {
    resultId.value = String(data.tempId); submitted.value = true; uncertain.value = false; remember()
    if (navigate) payment()
  } else if (data.status === 'QUEUED') {
    submitted.value = true; uncertain.value = false; queue.value = true; remember()
    if (navigate) await pollQueue()
  } else if (data.status === 'PENDING') {
    uncertain.value = true; error.value = data.message || '正在确认结果，请查看我的订单'
  } else if (data.status === 'NEW') { uncertain.value = false }
  else { uncertain.value = true; error.value = '请到我的订单确认本次提交结果，勿重复下单。' }
}
async function recover() {
  busy.value = true; error.value = ''
  try { await resolve(await api.guideRequestStatus('orders', requestId.value)) }
  catch (failure) { error.value = failure.message || '暂时无法查询，请到我的订单确认'; uncertain.value = true }
  finally { busy.value = false }
}
async function confirmOrder() {
  if (!canSubmit.value) return
  busy.value = true; error.value = ''; remember()
  try {
    const response = await api.guideCreateOrder(ticketId, { requestId:requestId.value,confirmed:true,count:count.value,expectedUnitPrice:preview.value.unitPriceCents,seatIds:selected.value.map(seat => String(seat.id)) })
    await resolve(response, true)
  } catch (failure) {
    // A timeout can occur after the server accepted the order. Recover by request ID.
    if (failure.isBusinessError && !['GUIDE_RESULT_UNCERTAIN','GUIDE_RETRY_STATUS'].includes(failure.code)) {
      const message=failure.message;await refresh();error.value=message+'。已重新读取票价和库存，请再次选择座位并确认。';requestId.value = newGuideRequestId(); acknowledged.value = false
      try { sessionStorage.removeItem(storageKey) } catch { /* Continue. */ }
    } else { uncertain.value = true; error.value = '提交结果暂未确认，请查询结果或查看我的订单，勿重复下单。' }
  } finally { busy.value = false }
}
onBeforeRouteLeave(async () => { await releaseSeats() })
onBeforeUnmount(() => { disposed = true; void releaseSeats() })
onMounted(async () => {
  await load()
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey) || 'null')
    if (saved?.requestId) {
      requestId.value=saved.requestId;uncertain.value=true;previousSaved.value=saved;await recover()
      if(route.query.purchase==='new'&&submitted.value&&!uncertain.value&&!queue.value)await newPurchase()
    }
  } catch { /* A new confirmation remains possible when no previous request was stored. */ }
})
</script>

<template>
  <main class="guide-commerce">
    <p class="guide-eyebrow">ECHO / CONFIRM YOUR CHOICE</p>
    <h1>确认这次现场</h1><p class="guide-intro">助手帮你准备选择，订单信息由你确认。</p>
    <p v-if="loading" role="status">正在核对演出、票价与余票…</p>
    <p v-if="error" class="guide-error" role="alert">{{ error }}</p>
    <template v-if="preview">
      <section class="guide-panel"><span class="guide-label">演出与票档</span><h2>{{ preview.show.name }}</h2><p>{{ preview.show.address }} · {{ safeDate(preview.show.openTime) }}</p><h3>{{ ticket.title }}</h3><p v-if="ticket.subTitle">{{ ticket.subTitle }}</p><strong class="guide-price">¥{{ priceYuan(preview.unitPriceCents) }} <small>/ 张</small></strong><p v-if="preview.updatedAt" class="guide-muted">核对时间：{{safeDate(preview.updatedAt)}}。下单前将再次校验价格和库存。</p><p v-if="seatPreference" class="guide-draft">{{seatPreference==='front'?'偏好前排／更近的位置':'偏好相邻座位'}}，请在座位图中确认；尚未替你锁座。</p><p>参考余票 {{ ticketStock(ticket) }} 张 · {{ ticket.hasSeats ? '需要选座' : '无需选座' }}<span v-if="seckill"> · 秒杀票，每次限购 1 张</span></p>
        <label class="guide-field">购票数量<select :value="count" :disabled="busy || submitted || uncertain" @change="changeCount"><option v-for="number in Math.max(1, maximum)" :key="number" :value="number">{{ number }} 张</option></select></label>
        <p v-if="!preview.purchasable || maximum < count" class="guide-error">当前票档暂不可购或余票不足，请刷新信息或选择其他票档。</p>
        <div v-if="ticket.hasSeats && !submitted && !uncertain && preview.purchasable" :inert="busy"><SeatPicker :key="seatVersion" :ticket-id="ticketId" :max-select="count" :is-seckill="seckill" @select="selectSeats" @ticket-offline="message => { error = message; preview.purchasable = false }" /><p>已选 {{ selected.length }} / {{ count }} 个座位</p></div>
        <details open><summary>购票与退改规则</summary><p class="guide-rules">{{ ticket.rules || '此票档暂未提供具体规则，请联系演出方确认。' }}</p></details>
      </section>
      <section v-if="!submitted && !uncertain" class="guide-panel"><div class="guide-total"><span>订单金额</span><strong>¥{{ priceYuan(Number(preview.unitPriceCents) * count) }}</strong></div><p v-if="carriedBudget>0" :class="Number(preview.unitPriceCents)*count>carriedBudget*100?'guide-error':'guide-muted'">本次总预算 ¥{{carriedBudget}} · {{Number(preview.unitPriceCents)*count>carriedBudget*100?'当前选择超过预算，请核对后决定':'当前选择在预算内'}}</p><p class="guide-muted">Echo 当前使用模拟支付。确认下单后进入独立付款页，最终金额以付款页为准。</p><label class="guide-consent"><input v-model="acknowledged" type="checkbox" :disabled="busy" />我已核对演出、票档、数量、座位和购票规则。</label><button class="guide-primary" :disabled="!canSubmit" @click="confirmOrder">{{ busy ? '正在确认订单…' : '确认下单，前往付款' }}</button></section>
      <section v-if="uncertain || queue || resultId" class="guide-panel" aria-live="polite"><h2>{{ resultId ? '订单已生成' : queue ? '已加入抢票队列' : '正在确认提交结果' }}</h2><p>{{ queueMessage || '可以查询同一次提交的结果，或到我的订单核对。' }}</p><button v-if="resultId" class="guide-primary" @click="payment">前往付款</button><button v-else-if="queue" class="guide-primary" :disabled="busy" @click="pollQueue">{{ busy ? '正在查询…' : '继续查询排队结果' }}</button><button v-else class="guide-primary" :disabled="busy" @click="recover">查询提交结果</button><router-link class="guide-secondary" to="/guide">核对订单与售后</router-link><button v-if="submitted&&!uncertain&&!queue" class="guide-secondary" :disabled="busy" @click="newPurchase">另购一笔，重新确认</button></section>
    </template>
    <div class="guide-links"><button :disabled="busy || submitted || uncertain" @click="refresh">刷新确认信息</button><router-link to="/guide">购票与评测工作台</router-link><a :href="guideHome()">返回导购</a></div>
  </main>
</template>

<style src="../styles/guide-commerce.css"></style>
