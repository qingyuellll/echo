<script setup>
import { ref } from 'vue'
import { api } from '../../utils/api.js'
const props=defineProps({showId:{type:[String,Number],default:''},score:{type:Number,default:0},disabled:Boolean})
const emit=defineEmits(['apply'])
const notes=ref(''),draft=ref(null),loading=ref(false),error=ref('')
async function generate() {
  if(!props.showId || props.score<1 || props.score>5 || !notes.value.trim() || props.disabled)return
  loading.value=true;error.value='';draft.value=null
  const showId=String(props.showId),score=props.score
  try { const response=await api.guideReviewDraft({showId,score,notes:notes.value.trim()}); if(String(props.showId)===showId && props.score===score)draft.value={...response.data,showId,score} }
  catch(failure){error.value=failure.message || '草稿暂时不可用，请直接填写你的感受'}
  finally {loading.value=false}
}
function apply(){if(draft.value && String(props.showId)===draft.value.showId && props.score===draft.value.score)emit('apply',{title:draft.value.title,content:draft.value.content})}
</script>
<template>
  <section class="guide-draft"><h3>把自己的感受整理成评测</h3><p>先选演出和评分，再写下你实际感受到的亮点或不足。助手只整理这些内容，评分由你决定。</p><textarea v-model="notes" :disabled="disabled || loading" maxlength="4000" placeholder="例如：演员表现自然，后排有些台词听不清。只填写你自己的真实感受。" aria-label="供助手整理的观演感受" /><div class="guide-links"><button class="guide-primary" :disabled="disabled || loading || !showId || score<1 || !notes.trim()" @click="generate">{{loading?'正在整理…':'生成可编辑草稿'}}</button></div><p v-if="error" role="alert">{{error}}</p><div v-if="draft && String(showId)===draft.showId && score===draft.score"><h3>{{draft.title}}</h3><div class="guide-draft-preview">{{draft.content}}</div><p>草稿尚未发布，请检查内容是否符合你的体验。</p><button class="guide-secondary" :disabled="disabled" @click="apply">填入编辑区，继续修改</button></div></section>
</template>
