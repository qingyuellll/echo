<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../utils/api.js'
import { guideHome } from '../utils/guide.js'
const orders=ref([]),loading=ref(true),error=ref(''),page=ref(1),hasMore=ref(false),status=ref('')
const detail=ref(null),detailId=ref(''),detailLoading=ref(false),action=ref(''),busy=ref(false),notice=ref(''),detailError=ref('')
const statusName={0:'待付款',1:'秒杀预占',2:'已付款',3:'已核验',4:'已取消',5:'已退款',6:'退款处理中',7:'退款已拒绝'}
const statusHint={0:'付款前可取消。若付款中断，可重新打开本订单继续付款。',1:'预占有时间限制，请在付款页核对剩余时间。',2:'已付款。核对入场凭证和演出安排后赴约。',3:'已核验入场，可以记录实际观演体验。',4:'订单已取消，需要购票时可重新选票和选座。',5:'退款已完成；当前为模拟支付，不涉及真实资金到账。',6:'退款申请已提交，等待演出方审核，可刷新查看最新状态。',7:'退款申请未获批准，可联系演出方了解原因或重新申请。'}
async function load(){
 loading.value=true;error.value=''
 try{const response=await api.getMyOrders({current:page.value,size:12,...(status.value!==''?{status:status.value}:{})});const data=response.data||{};const rows=Array.isArray(data)?data:data.records||data.list||[];orders.value=rows;hasMore.value=page.value<100&&rows.length===12&&(response.total==null&&data.total==null||page.value*12<Number(response.total??data.total))}
 catch(failure){error.value=failure.message||'订单暂时不可用'}finally{loading.value=false}
}
function changePage(delta){page.value+=delta;void load()}
function idOf(order){return String(order.tempId||order.id||'')}
async function inspect(order){detailId.value=idOf(order);detail.value=null;action.value='';notice.value='';detailError.value='';await refreshDetail()}
let detailVersion=0
async function refreshDetail(){
 if(!detailId.value)return
 const version=++detailVersion,id=detailId.value;detailLoading.value=true
 try{const response=await api.guideOrderFollowUp(id);if(version===detailVersion&&id===detailId.value){detail.value=response.data;action.value=''}}catch(failure){if(version===detailVersion&&id===detailId.value)detailError.value=failure.message||'暂时无法核对订单，请稍后重试'}finally{if(version===detailVersion)detailLoading.value=false}
}
async function confirmedAction(){
 if(busy.value||!action.value)return
 busy.value=true;detailError.value='';const kind=action.value;action.value=''
 try{
  const latest=(await api.guideOrderFollowUp(detailId.value)).data;detail.value=latest
  if(kind==='cancel'&&![0,1].includes(Number(latest.status))||kind==='refund'&&![2,7].includes(Number(latest.status)))throw new Error('订单状态已变化，请核对最新进度')
  if(kind==='cancel')await api.cancelOrder(detailId.value);else await api.refundOrder(String(latest.id||detailId.value))
  notice.value=kind==='cancel'?'取消申请已处理，请核对最新订单状态。':'退款申请已提交，等待审核。'
 }catch(failure){detailError.value=failure.isBusinessError?failure.message:'操作结果暂未确认，请刷新这笔订单核对状态，勿连续提交。'}
 finally{busy.value=false;await Promise.allSettled([refreshDetail(),load()])}
}
async function calendar(){
 busy.value=true;detailError.value=''
 try{const response=await api.guideCalendar(String(detail.value.id||detailId.value));const url=URL.createObjectURL(new Blob([response.data.content],{type:'text/calendar;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download=response.data.filename;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);notice.value='已下载日历提醒，请打开文件添加到自己的日历，提前两小时提醒。演出时间有变时请重新添加。'}catch(failure){detailError.value=failure.message||'暂时无法生成开演提醒'}finally{busy.value=false}
}
function repurchase(order){return {path:`/guide/checkout/${String(order.ticketId)}`,query:{count:String(Math.min(10,Math.max(1,Number(order.quantity||1)))),purchase:'new'}}}
onMounted(load)
</script>
<template>
 <main class="guide-commerce"><p class="guide-eyebrow">ECHO / YOUR NEXT STEP</p><h1>每一场现场，都有后续</h1><p class="guide-intro">继续付款、管理订单、添加开演提醒，再记录自己的观演感受。</p>
  <section class="guide-panel guide-hub-actions"><a :href="guideHome()" class="guide-primary">让助手选演出与票档 ↗</a><router-link class="guide-secondary" to="/reviews/publish?from=guide">写评分与评测</router-link><router-link class="guide-secondary" to="/profile">我的个人中心</router-link></section>
  <div class="guide-links"><h2>我的购票进度</h2><label>订单状态 <select v-model="status" :disabled="loading" @change="page=1;load()"><option value="">全部</option><option v-for="(name,value) in statusName" :key="value" :value="String(value)">{{name}}</option></select></label><button :disabled="loading" @click="load">刷新订单 ↻</button></div>
  <p v-if="loading" role="status">正在读取订单…</p><p v-if="error" class="guide-error" role="alert">{{error}} <button @click="load">重试</button></p><p v-if="!loading&&!error&&!orders.length" class="guide-panel">这个条件下还没有订单，可以调整状态或去导购选演出。</p>
  <article v-for="order in orders" :key="idOf(order)" class="guide-panel guide-order"><div><span class="guide-label">{{statusName[order.status]||'订单处理中'}}</span><h3>{{order.showName||'演出订单'}}</h3><p>{{order.ticketTitle||order.ticketName||'票档详情请查看订单'}} · {{order.quantity||order.count||1}} 张</p></div><div class="guide-links"><router-link v-if="[0,1].includes(Number(order.status))" class="guide-primary" :to="{path:'/pay-order',query:{orderId:idOf(order),showId:String(order.showId||'')}}">继续付款</router-link><router-link v-if="order.ticketId" class="guide-secondary" :to="repurchase(order)">重新购买</router-link><router-link v-if="[2,3].includes(Number(order.status))&&order.showId" class="guide-secondary" :to="{path:'/reviews/publish',query:{showId:String(order.showId),from:'guide'}}">写观演评测</router-link><button class="guide-secondary" @click="inspect(order)">订单详情与售后</button></div></article>
  <div v-if="page>1||hasMore" class="guide-links"><button :disabled="page<=1||loading" @click="changePage(-1)">上一页</button><span>第 {{page}} 页</span><button :disabled="!hasMore||loading" @click="changePage(1)">下一页</button></div>
  <div v-if="detailId" class="guide-modal-backdrop" @click.self="!busy&&(detailId='')"><section class="guide-panel guide-order-dialog" role="dialog" aria-modal="true" aria-labelledby="order-detail-title" @keydown.esc="!busy&&(detailId='')"><div class="guide-links"><h2 id="order-detail-title">订单详情与售后</h2><button :disabled="busy" @click="detailId=''">关闭 ×</button></div><p v-if="detailLoading" role="status">正在核对最新状态…</p>
   <template v-if="detail"><h3>{{detail.showName||'演出订单'}}</h3><p>{{statusName[detail.status]||'订单处理中'}} · {{detail.ticketTitle||'所选票档'}}</p><p>{{statusHint[detail.status]}}</p><small v-if="detail.updatedAt">本次核对：{{detail.updatedAt}}</small><ol class="guide-timeline"><li v-if="detail.createTime">创建订单：{{detail.createTime}}</li><li v-if="detail.payTime">完成付款：{{detail.payTime}}</li><li v-if="detail.useTime">核验入场：{{detail.useTime}}</li><li v-if="detail.refundTime">完成退款：{{detail.refundTime}}</li><li v-if="[4,6,7].includes(Number(detail.status))&&detail.updateTime">最近状态更新：{{detail.updateTime}}</li></ol><p v-if="detail.seatInfoList?.length">座位：{{detail.seatInfoList.map(seat=>seat.seatLabel||`${seat.area} ${seat.rowNum}排${seat.colNum}座`).join('、')}}</p>
    <div v-if="!action" class="guide-links"><button :disabled="busy||detailLoading" @click="detailError='';refreshDetail()">核对最新进度</button><button v-if="[0,1].includes(Number(detail.status))" :disabled="busy||detailLoading" @click="action='cancel'">取消订单</button><button v-if="[2,7].includes(Number(detail.status))" :disabled="busy||detailLoading" @click="action='refund'">{{Number(detail.status)===7?'重新申请退款':'申请退款'}}</button><button v-if="[2,3].includes(Number(detail.status))" :disabled="busy||detailLoading" @click="calendar">添加开演提醒</button></div>
    <section v-if="action" class="guide-draft"><h3>{{action==='cancel'?'确认取消这笔订单？':'确认申请退款？'}}</h3><p>{{action==='cancel'?'取消后将释放本订单占用的库存和座位，再次购买需重新选择。':'退款需要审核，具体条件以票档规则与演出方处理为准；现有规则可能扣除信誉分。'}}</p><button class="guide-primary" :disabled="busy" @click="confirmedAction">{{action==='cancel'?'确认取消订单':'确认申请退款'}}</button><button :disabled="busy" @click="action=''">返回</button></section>
   </template><p v-if="notice" role="status">{{notice}}</p><p v-if="detailError" class="guide-error" role="alert">{{detailError}}</p>
  </section></div>
 </main>
</template>
<style src="../styles/guide-commerce.css"></style>
