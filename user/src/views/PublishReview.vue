<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { api, isLoggedIn } from '../utils/api.js'
import StarRating from '../components/common/StarRating.vue'
import ReviewDraftAssistant from '../components/common/ReviewDraftAssistant.vue'
import { getUser } from '../utils/api.js'
import { newGuideRequestId } from '../utils/guide.js'

const router = useRouter()
const route = useRoute()
const title = ref('')
const content = ref('')
const dimensions=ref({}),orderId=ref('')
const dimensionNames={story:'剧情',performance:'表演',sound:'音效',view:'视野',value:'性价比'}
const score = ref(0)  // 评分（必填）
const showId = ref(null)
const shows = ref([])
const images = ref([])
const imagePreviews = ref([])
const submitting = ref(false)
const errorMsg = ref('')
const success = ref(false)

const reviewContext = ref(null)
const contextLoading = ref(false)
const contextError = ref('')
const confirmation = ref(null)
const uncertain = ref(false)
const publishedId = ref('')
const onlyScore = ref(false)
const selectedShow = computed(() => shows.value.find(show => String(show.id) === String(showId.value)))
const storageKey = `echo-guide-review:${getUser()?.id || 'current'}`
let contextVersion = 0
async function loadContext() {
  const version=++contextVersion
  reviewContext.value=null;contextError.value=''
  if(!showId.value)return
  contextLoading.value=true
  try { const response=await api.guideReviewContext(String(showId.value));if(version===contextVersion)reviewContext.value=response.data }
  catch(failure) {if(version===contextVersion)contextError.value=failure.message || '暂时无法核对已有评测'}
  finally {if(version===contextVersion)contextLoading.value=false}
}
watch(showId,()=>{confirmation.value=null;orderId.value='';dimensions.value={};void loadContext()})
async function loadShows() {
  const items=new Map()
  try {
    for(let page=1;page<=1000;page++) {
      const response=await api.searchShows({current:page,size:50})
      const data=response.data || {};const rows=Array.isArray(data)?data:data.records || []
      const before=items.size
      rows.forEach(show=>items.set(String(show.id),show))
      const total=Number(data.total ?? response.total ?? 0)
      if(!rows.length || items.size===before || total>0 && items.size>=total || !total && rows.length<50)break
    }
    // Orders can refer to a show no longer present in public search.
    if(showId.value && !items.has(String(showId.value))) {
      try {const response=await api.getShowDetail(String(showId.value));if(response.data)items.set(String(response.data.id),response.data)} catch { /* Context reports whether the show can still be reviewed. */ }
    }
    shows.value=[...items.values()]
  } catch(failure) {shows.value=[...items.values()];errorMsg.value=failure.message || '演出列表未读完整，可稍后重试'}
}
function applyDraft(draft) { title.value=draft.title;content.value=draft.content;onlyScore.value=false;confirmation.value=null }
function remember(body) {try {sessionStorage.setItem(storageKey,JSON.stringify(body))} catch { /* Request ID still protects the active submission. */ }}
function completed(response) {
  if(response.data?.status==='PENDING') {uncertain.value=true;errorMsg.value='正在确认发布结果，请查看我的评测，勿重复发布。';return}
  if(response.data?.status==='NEW') {uncertain.value=false;return}
  if(response.data==null) {uncertain.value=true;errorMsg.value='请到我的评测核对发布结果。';return}
  publishedId.value=String(response.data);success.value=true;uncertain.value=false
  try {sessionStorage.removeItem(storageKey)}catch { /* Published status is already shown. */ }
}
async function recover() {
  if(!confirmation.value)return
  submitting.value=true;errorMsg.value=''
  try {completed(await api.guideRequestStatus('reviews',confirmation.value.requestId))}
  catch(failure) {
    errorMsg.value=failure.message || '请查看我的评测核对结果'
    if(failure.isBusinessError && failure.code!=='GUIDE_RESULT_UNCERTAIN') {uncertain.value=false;confirmation.value=null;try{sessionStorage.removeItem(storageKey)}catch{}}
  }
  finally{submitting.value=false}
}

function handleFileChange(e) {
  const files = Array.from(e.target.files)
  files.forEach(file => {
    const reader = new FileReader()
    reader.onload = (ev) => {
      imagePreviews.value.push(ev.target.result)
    }
    reader.readAsDataURL(file)
  })
  images.value = files
}

function removeImage(idx) {
  imagePreviews.value.splice(idx, 1)
  images.value.splice(idx, 1)
}

async function submit() {
  if(submitting.value || uncertain.value)return
  errorMsg.value=''
  if(!showId.value) {errorMsg.value='请选择关联的演出';return}
  if(!Number.isInteger(score.value) || score.value<1 || score.value>5){errorMsg.value='请由你自己选择 1–5 星评分';return}
  if(!onlyScore.value && !title.value.trim()){errorMsg.value='请填写标题，或切换为仅评分';return}
  if(title.value.trim().length>100 || content.value.length>10000){errorMsg.value='标题最多 100 字，正文最多 10000 字';return}
  await loadContext()
  if(!reviewContext.value) {errorMsg.value=contextError.value || '请先核对演出和已有评测';return}
  if(reviewContext.value.existingReviewId){errorMsg.value='你已评测过这场演出，可查看已有评测。';return}
  confirmation.value={requestId:newGuideRequestId(),confirmed:true,showId:String(showId.value),score:score.value,title:onlyScore.value?'':title.value.trim(),content:onlyScore.value?'':content.value.trim(),images:undefined,dimensions:Object.fromEntries(Object.entries(dimensions.value).filter(([,value])=>Number(value)>=1).map(([key,value])=>[key,Number(value)])),orderId:orderId.value||undefined}
}
async function publishConfirmed() {
  if(!confirmation.value || submitting.value || uncertain.value)return
  submitting.value=true;errorMsg.value=''
  try {
    const body=confirmation.value
    if(!onlyScore.value && images.value.length && !body.images) {
      const uploaded=[]
      for(const file of images.value) {const response=await api.uploadImage(file);if(response.url || response.data)uploaded.push(response.url || response.data)}
      body.images=uploaded.length?JSON.stringify(uploaded):undefined
    }
    remember(body)
    completed(await api.guidePublishReview(body))
  } catch(failure) {
    if(failure.isBusinessError && !['GUIDE_RESULT_UNCERTAIN','GUIDE_RETRY_STATUS'].includes(failure.code)) {
      errorMsg.value=failure.message;confirmation.value=null
      try{sessionStorage.removeItem(storageKey)}catch{}
      void loadContext()
    } else {uncertain.value=true;errorMsg.value='发布结果暂未确认，请查询结果或查看我的评测，勿重复提交。'}
  } finally{submitting.value=false}
}

function goBack() {
  router.back()
}

onMounted(async () => {
  if (!isLoggedIn()) {router.push({path:'/login',query:{redirect:route.fullPath}});return}
  const queryShowId=route.query.showId
  if(queryShowId && /^\d+$/.test(String(queryShowId)))showId.value=String(queryShowId)
  await loadShows()
  try {
    const saved=JSON.parse(sessionStorage.getItem(storageKey) || 'null')
    if(saved?.requestId){confirmation.value=saved;uncertain.value=true;await recover()}
  }catch { /* No previous publication to recover. */ }
})
</script>

<template>
  <div class="publish-page">
    <div class="publish-inner">
      <template v-if="success">
        <div class="publish-success">
          <h2>发布成功</h2>
          <p>你的评测已发布。</p><router-link v-if="publishedId" :to="`/review/${publishedId}`" class="publish-success-btn">查看刚发布的评测</router-link>
          <router-link to="/reviews" class="publish-success-btn">返回评测列表</router-link>
        </div>
      </template>

      <template v-else>
        <div class="publish-header">
          <button class="publish-back" @click="goBack">← 返回</button>
          <h1 class="publish-title">发布评测</h1>
        </div>

        <p v-if="reviewContext" class="guide-muted">{{ reviewContext.verified ? '已有核销订单，请按实际体验评价。' : reviewContext.purchased ? '已有购票记录，请在实际观演后分享感受。' : '评分与内容请基于你自己的真实体验。' }}</p>
        <p v-if="contextLoading" role="status">正在核对已有评测…</p>
        <p v-if="contextError" class="publish-error">{{ contextError }} <button @click="loadContext">重试</button></p>
        <p v-if="reviewContext?.existingReviewId" class="guide-draft">你已评测过这场演出。<router-link :to="`/review/${reviewContext.existingReviewId}`">查看我的评测 ↗</router-link></p>
        <div class="publish-form" :inert="!!confirmation || submitting || uncertain">
          <div class="publish-field">
            <label class="publish-label">关联演出 *</label>
            <select v-model="showId" class="publish-select">
              <option value="" disabled>请选择演出</option>
              <option v-for="s in shows" :key="s.id" :value="String(s.id)">
                {{ s.name }}
              </option>
            </select>
          </div>

          <label class="guide-consent"><input v-model="onlyScore" type="checkbox" />仅发布评分，不填写文字评测</label>
          <ReviewDraftAssistant v-if="!onlyScore" :show-id="showId || ''" :score="score" :disabled="submitting || !!reviewContext?.existingReviewId" @apply="applyDraft" />
          <div v-if="!onlyScore" class="publish-field">
            <label class="publish-label">标题 *</label>
            <input
              v-model="title"
              type="text"
              placeholder="请输入评测标题"
              maxlength="100"
              class="publish-input"
            />
          </div>

          <div class="publish-field">
            <label class="publish-label">评分 *</label>
            <StarRating
              v-model="score"
              :size="32"
              :interactive="true"
            />
            <span class="score-hint">您的评分：{{ score }} 分</span>
          </div>

          <section class="guide-draft"><h3>分项评价（选填）</h3><p class="guide-muted">只评价自己体验过的项目，不适用的可以留空。</p><div class="guide-dimension-inputs"><label v-for="(label,key) in dimensionNames" :key="key">{{label}}<select v-model="dimensions[key]"><option :value="undefined">不评价</option><option v-for="star in 5" :key="star" :value="star">{{star}} 星</option></select></label></div>
          <label v-if="reviewContext?.orders?.length" class="guide-field">关联自己的购票位置<select v-model="orderId"><option value="">不关联座位区域</option><option v-for="order in reviewContext.orders" :key="order.id" :value="String(order.id)">{{order.title}} · {{order.seatArea||'无座位区域'}} · {{order.status===3?'已核验':'已购票'}}</option></select></label><p class="guide-muted">购票和核验标识由订单记录核对；关联后仅公开座位区域，不公开订单号。</p></section>
          <div v-if="!onlyScore" class="publish-field">
            <label class="publish-label">内容（选填）</label>
            <textarea
              v-model="content"
              placeholder="分享你的观演体验..."
              rows="8"
              maxlength="10000"
              class="publish-textarea"
            />
          </div>

          <div v-if="!onlyScore" class="publish-field">
            <label class="publish-label">图片（可选）</label>
            <div class="publish-images">
              <div v-for="(preview, idx) in imagePreviews" :key="idx" class="publish-image-item">
                <img :src="preview" alt="预览" />
                <button class="publish-image-remove" @click="removeImage(idx)">×</button>
              </div>
              <label class="publish-image-add">
                <input type="file" accept="image/*" multiple @change="handleFileChange" hidden />
                <span>+</span>
              </label>
            </div>
          </div>

          <p v-if="errorMsg" class="publish-error">{{ errorMsg }}</p>

          <button
            class="publish-submit"
            :disabled="submitting || contextLoading || !!reviewContext?.existingReviewId || uncertain"
            @click="submit"
          >
            {{ submitting ? '正在核对…' : '预览评分与评测' }}
          </button>
        </div>
        <section v-if="confirmation && !uncertain" class="guide-draft" role="region" aria-label="发布前确认"><h3>确认发布到 Echo</h3><p>{{selectedShow?.name || reviewContext?.show?.name || '所选演出'}} · {{confirmation.score}} 星</p><p v-for="(value,key) in confirmation.dimensions" :key="key">{{dimensionNames[key]}}：{{value}} 星</p><p v-if="confirmation.orderId">已关联自己的购票订单，座位区域将随评测展示。</p><h3 v-if="confirmation.title">{{confirmation.title}}</h3><div class="guide-draft-preview">{{confirmation.content || '本次仅发布评分。'}}</div><p>发布后其他用户可以看到，请核对是否符合自己的真实体验。</p><div class="guide-links"><button class="guide-primary" :disabled="submitting" @click="publishConfirmed">{{submitting?'正在发布…':'确认发布'}}</button><button :disabled="submitting" @click="confirmation=null">返回修改</button></div><p v-if="errorMsg" class="publish-error" role="alert">{{errorMsg}}</p></section>
        <section v-if="uncertain" class="guide-draft" aria-live="polite"><h3>核对这次发布的结果</h3><p>{{errorMsg}}</p><button class="guide-primary" :disabled="submitting" @click="recover">查询发布结果</button><router-link class="guide-secondary" to="/profile">我的评测</router-link></section>
      </template>
    </div>
  </div>
</template>

<style scoped>
.publish-page {
  padding: var(--space-12) var(--space-6);
  background-color: var(--color-bg);
  min-height: calc(100vh - 60px);
}

.publish-inner {
  max-width: 700px;
  margin: 0 auto;
}

.publish-header {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  margin-bottom: var(--space-8);
}

.publish-back {
  background: none;
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-text-light);
}

.publish-back:hover {
  color: var(--color-primary);
}

.publish-title {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-text);
}

.publish-form {
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  padding: var(--space-6);
}

.publish-field {
  margin-bottom: var(--space-5);
}

.publish-label {
  display: block;
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-text);
  margin-bottom: var(--space-2);
}

.score-hint {
  display: block;
  margin-top: var(--space-2);
  font-size: var(--font-sm, 14px);
  color: var(--color-primary);
  font-weight: 500;
}

.publish-input {
  width: 100%;
  padding: var(--space-3);
  font-size: var(--font-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-white);
  color: var(--color-text);
}

.publish-input:focus {
  outline: none;
  border-color: var(--color-primary);
}

.publish-select {
  width: 100%;
  padding: var(--space-3);
  font-size: var(--font-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-white);
  color: var(--color-text);
}

.publish-select:focus {
  outline: none;
  border-color: var(--color-primary);
}

.publish-textarea {
  width: 100%;
  padding: var(--space-3);
  font-size: var(--font-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-white);
  color: var(--color-text);
  resize: vertical;
  font-family: inherit;
}

.publish-textarea:focus {
  outline: none;
  border-color: var(--color-primary);
}

.publish-images {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
}

.publish-image-item {
  position: relative;
  width: 100px;
  height: 100px;
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.publish-image-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.publish-image-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background-color: rgba(0, 0, 0, 0.5);
  color: var(--color-white);
  font-size: var(--font-sm);
  display: flex;
  align-items: center;
  justify-content: center;
}

.publish-image-add {
  width: 100px;
  height: 100px;
  border: 2px dashed var(--color-border);
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: var(--font-xl);
  color: var(--color-text-light);
  transition: border-color var(--transition-fast);
}

.publish-image-add:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.publish-error {
  font-size: var(--font-sm);
  color: var(--color-danger);
  margin-bottom: var(--space-4);
}

.publish-submit {
  width: 100%;
  padding: var(--space-3);
  font-size: var(--font-base);
  font-weight: 600;
  border-radius: var(--radius-sm);
  background-color: var(--color-primary);
  color: var(--color-white);
}

.publish-submit:hover:not(:disabled) {
  background-color: var(--color-secondary);
}

.publish-submit:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.publish-success {
  text-align: center;
  padding: var(--space-16) 0;
}

.publish-success h2 {
  font-family: var(--font-heading);
  font-size: var(--font-xl);
  font-weight: 700;
  color: var(--color-primary);
  margin-bottom: var(--space-4);
}

.publish-success p {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  margin-bottom: var(--space-6);
}

.publish-success-btn {
  display: inline-block;
  padding: var(--space-2) var(--space-6);
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: var(--radius-sm);
  background-color: var(--color-primary);
  color: var(--color-white);
  text-decoration: none;
}

.publish-success-btn:hover {
  background-color: var(--color-secondary);
}

@media (max-width: 768px) {
  .publish-title {
    font-size: 24px;
  }
}
</style>

<style src="../styles/guide-commerce.css"></style>
