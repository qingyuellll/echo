<script setup>
import { computed, ref, watch } from 'vue'
import { api } from '../../utils/api.js'
const props=defineProps({showId:{type:[String,Number],default:''},reviewId:{type:[String,Number],default:''}})
const items=ref([]),area=ref(''),error=ref(''),loading=ref(false)
const dimensions={story:'剧情',performance:'表演',sound:'音效',view:'视野',value:'性价比'}
const areas=computed(()=>[...new Set(items.value.map(item=>item.seatArea).filter(Boolean))])
const filtered=computed(()=>items.value.filter(item=>(!props.reviewId||String(item.reviewId)===String(props.reviewId))&&(!area.value||item.seatArea===area.value)))
const summary=computed(()=>Object.entries(dimensions).map(([key,label])=>{const scores=filtered.value.map(item=>Number(item.dimensions?.[key])).filter(value=>value>=1&&value<=5);return {key,label,count:scores.length,average:scores.length?scores.reduce((a,b)=>a+b,0)/scores.length:0}}))
let version=0
async function load(){const current=++version;items.value=[];error.value='';if(!props.showId)return;loading.value=true;try{const response=await api.reviewInsights(String(props.showId));if(current===version)items.value=response.data.items||[]}catch{if(current===version)error.value='分项评分与购票凭据暂未读取，可稍后重试。'}finally{if(current===version)loading.value=false}}
watch(()=>props.showId,()=>{area.value='';void load()},{immediate:true})
</script>
<template>
 <section class="guide-review-insights" aria-label="分项评分与购票凭据"><p v-if="loading" role="status">正在核对分项评分与购票凭据…</p><p v-if="error">{{error}} <button @click="load">重试</button></p>
 <template v-if="!loading&&!error"><h3 v-if="!reviewId">不同位置，听见不同回响</h3><label v-if="!reviewId">按座位区域查看 <select v-model="area"><option value="">全部区域</option><option v-for="value in areas" :key="value">{{value}}</option></select></label><p v-if="!reviewId&&!areas.length" class="guide-muted">暂无关联座位区域的评测；历史评测可继续正常阅读。</p>
 <div class="guide-dimension-grid"><div v-for="entry in summary" :key="entry.key"><span>{{entry.label}}</span><strong>{{entry.count?entry.average.toFixed(1):'—'}}<small>/5</small></strong><small>{{entry.count?`${entry.count} 人评价`:'尚未填写'}}</small></div></div>
 <p class="guide-muted">分项评分由观众自选填写，分别统计样本；购票记录与入场核验分别核对。</p>
 <article v-for="item in (reviewId?filtered:filtered.slice(0,20))" :key="item.reviewId" class="guide-evidence-row"><span v-if="item.purchased" class="guide-evidence-badge">已购票</span><span v-if="item.verified" class="guide-evidence-badge">已核验</span><span v-if="!item.purchased&&!item.verified" class="guide-muted">未关联有效购票凭据</span><span v-if="item.seatArea">{{item.seatArea}}</span><router-link v-if="!reviewId" :to="`/review/${item.reviewId}`">{{item.title||'观众评分'}} · {{item.score}} 星 ↗</router-link></article>
 <p v-if="!filtered.length" class="guide-muted">这个区域暂没有评测。</p></template>
 </section>
</template>
<style scoped>
.guide-review-insights{margin:24px 0;padding:20px;border:1px solid var(--color-border,#ddd);border-radius:12px}.guide-review-insights h3{margin:0 0 16px}.guide-dimension-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(80px,1fr));gap:12px;margin:16px 0}.guide-dimension-grid>div{display:grid;gap:4px}.guide-dimension-grid strong{font-size:24px}.guide-dimension-grid small{font-size:12px;font-weight:400}.guide-evidence-row{display:flex;flex-wrap:wrap;align-items:center;gap:8px;padding:10px 0;border-top:1px solid #eee}.guide-evidence-badge{background:#eaf0e2;color:#355333;padding:3px 7px;border-radius:5px;font-size:12px}.guide-muted{font-size:13px;color:var(--color-text-light,#666)}
</style>
