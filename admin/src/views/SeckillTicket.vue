<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import Card from '@/components/common/Card.vue'
import Button from '@/components/common/Button.vue'
import { ArrowLeft, Save } from 'lucide-vue-next'
import { api } from '@/utils/api.js'

const router = useRouter()
const loading = ref(false)

const form = ref({
  showId: null,
  title: '',
  payValue: null,
  actualValue: null,
  seckillStock: null,
  beginTime: '',
  endTime: '',
  limitPerUser: 1,

  // ===== 座位配置（简化版）=====
  hasSeats: false // 是否需要选座（默认不需要）
  // 注意：秒杀票限购1张，无需额外限制选座数量
})

async function handleSubmit() {
  if (!form.value.title || !form.value.showId) {
    alert('请填写必填项')
    return
  }
  if (form.value.seckillStock === null || form.value.seckillStock === undefined || form.value.seckillStock < 0) {
    alert('秒杀库存不能为空且必须大于等于0')
    return
  }
  if (form.value.payValue !== null && form.value.payValue < 0) {
    alert('支付价格不能为负数')
    return
  }
  if (form.value.actualValue !== null && form.value.actualValue < 0) {
    alert('实际价值不能为负数')
    return
  }
  if (form.value.beginTime && form.value.endTime) {
    const start = new Date(form.value.beginTime)
    const end = new Date(form.value.endTime)
    if (start >= end) {
      alert('秒杀结束时间必须晚于开始时间')
      return
    }
  }
  if (form.value.limitPerUser !== null && form.value.limitPerUser < 1) {
    alert('每人限购数量至少为1')
    return
  }
  loading.value = true
  try {
    await api.createSeckillTicket({
      ...form.value,
      showId: Number(form.value.showId),
      payValue: Number(form.value.payValue || 0),
      actualValue: Number(form.value.actualValue || 0),
      seckillStock: Number(form.value.seckillStock),

      // 座位配置（简化版 - 只保留核心字段）
      hasSeats: form.value.hasSeats === true
    })

    console.log('[创建秒杀票] 座位配置:', {
      hasSeats: form.value.hasSeats
    })
    alert('创建成功')
    await router.push('/tickets')
  } catch (e) {
    const businessErrorMsg = e?.response?.data?.data?.errorMsg
                         || e?.response?.data?.errorMsg
                         || e?.response?.data?.msg
                         || e?.message
                         || '创建失败，请重试'

    console.error('[SeckillTicket] 创建秒杀票失败:', businessErrorMsg)
    alert(businessErrorMsg)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="header-left">
        <Button variant="ghost" @click="$router.push('/tickets')">
          <ArrowLeft :size="16" />
        </Button>
        <h1 class="page-title">添加秒杀票</h1>
      </div>
    </div>

    <Card padding="md">
      <div class="form-grid">
        <div class="form-group">
          <label class="form-label">所属演出ID <span class="required">*</span></label>
          <input v-model.number="form.showId" type="number" class="form-input" placeholder="输入演出ID" />
        </div>
        <div class="form-group">
          <label class="form-label">票种标题 <span class="required">*</span></label>
          <input v-model="form.title" class="form-input" placeholder="如: 早鸟秒杀票" />
        </div>
        <div class="form-group form-group-half">
          <label class="form-label">支付价格（分）</label>
          <input v-model.number="form.payValue" type="number" class="form-input" placeholder="单位：分" />
        </div>
        <div class="form-group form-group-half">
          <label class="form-label">实际价值（分）</label>
          <input v-model.number="form.actualValue" type="number" class="form-input" placeholder="单位：分" />
        </div>
        <div class="form-group form-group-half">
          <label class="form-label">秒杀库存 <span class="required">*</span></label>
          <input v-model.number="form.seckillStock" type="number" min="0" class="form-input" placeholder="请输入库存数量" />
        </div>
        <div class="form-group form-group-half">
          <label class="form-label">每人限购</label>
          <input v-model.number="form.limitPerUser" type="number" class="form-input" placeholder="1" />
        </div>
        <div class="form-group form-group-half">
          <label class="form-label">秒杀开始时间</label>
          <input v-model="form.beginTime" type="datetime-local" class="form-input" />
        </div>
        <div class="form-group form-group-half">
          <label class="form-label">秒杀结束时间</label>
          <input v-model="form.endTime" type="datetime-local" class="form-input" />
        </div>
      </div>

      <!-- ===== 座位配置区域（符合设计规范）===== -->
      <div class="seat-config-section">
        <h3 class="section-title">座位配置</h3>

        <div class="form-group form-group-full">
          <label class="form-label">是否需要选座 <span class="required">*</span></label>
          <div class="seat-toggle-group">
            <button
              type="button"
              :class="['toggle-btn', { active: form.hasSeats === false }]"
              @click="form.hasSeats = false"
            >
              <span class="toggle-icon">🎫</span>
              <span class="toggle-text">无需选座</span>
              <span class="toggle-desc">站票/通票</span>
            </button>
            <button
              type="button"
              :class="['toggle-btn', { active: form.hasSeats === true }]"
              @click="form.hasSeats = true"
            >
              <span class="toggle-icon">💺</span>
              <span class="toggle-text">需要选座</span>
              <span class="toggle-desc">剧院/VIP席</span>
            </button>
          </div>
        </div>

        <!-- 选座提示信息 -->
        <div v-if="form.hasSeats === true" class="info-box">
          <span class="icon">✨</span>
          <div>
            <strong>已开启座位选择模式</strong>
            <p>用户购买此秒杀票时，将进入可视化座位选择页面，需选择1个具体座位。</p>
          </div>
        </div>

        <div v-else-if="form.hasSeats === false" class="hint-box">
          <div>
            <strong>无需选座模式</strong>
            <p>适用于音乐节、展览等无需指定座位的场景。</p>
          </div>
        </div>
      </div>

      <div class="form-actions">
        <Button :loading="loading" @click="handleSubmit">
          <Save :size="16" />
          创建秒杀票
        </Button>
      </div>
    </Card>
  </div>
</template>

<style scoped>
.page { max-width: 800px; }
.page-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: var(--space-6);
}
.header-left {
  display: flex; align-items: center; gap: var(--space-3);
}
.page-title { font-family: var(--font-heading); font-size: var(--font-2xl); color: var(--color-text); }

.form-grid {
  display: grid; grid-template-columns: 1fr 1fr;
  gap: var(--space-4);
}
.form-group { display: flex; flex-direction: column; gap: var(--space-1); }
.form-label { font-size: var(--font-sm); font-weight: 500; color: var(--color-text); }
.form-input { width: 100%; }
.required { color: var(--color-danger); }

.form-actions {
  margin-top: var(--space-8);
  padding-top: var(--space-6);
  border-top: 1px solid var(--color-border);
  display: flex; justify-content: flex-end;
}

@media (max-width: 576px) {
  .form-grid { grid-template-columns: 1fr; }
}

/* ===== 座位配置区域样式（符合设计规范）===== */
.seat-config-section {
  margin-top: var(--space-6);
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-border);
}

.section-title {
  font-family: var(--font-heading);
  font-size: var(--font-lg);
  font-weight: 600;
  color: var(--color-primary);
  margin-bottom: var(--space-4);
}

.form-group-full {
  grid-column: 1 / -1;
}

/* 切换按钮组（符合管理端设计规范）*/
.seat-toggle-group {
  display: flex;
  gap: var(--space-3);
  margin-top: var(--space-2);
}

.toggle-btn {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-4) var(--space-3);
  background-color: var(--color-white);
  border: 2px solid var(--color-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-base);
}

.toggle-btn:hover {
  border-color: var(--color-primary);
  transform: translateY(-2px);
  box-shadow: var(--shadow-sm);
}

.toggle-btn.active {
  border-color: var(--color-primary);
  background-color: rgba(166, 186, 174, 0.08);
  box-shadow: var(--shadow-md);
}

.toggle-icon {
  font-size: var(--font-xl);
}

.toggle-text {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
}

.toggle-desc {
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

/* 信息提示框（简洁风格）*/
.info-box,
.hint-box {
  grid-column: 1 / -1;
  margin-top: var(--space-3);
  padding: var(--space-4);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-md);
  border-left: 3px solid var(--color-primary);
  display: flex;
  gap: var(--space-3);
  align-items: flex-start;
}

.hint-box {
  border-left-color: var(--color-text-light);
  opacity: 0.7;
}

.icon {
  font-size: var(--font-lg);
  flex-shrink: 0;
  line-height: 1.5;
}

.info-box div,
.hint-box div {
  flex: 1;
  font-size: var(--font-sm);
  line-height: 1.6;
}

.info-box strong,
.hint-box strong {
  display: block;
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-1);
}

.info-box p,
.hint-box p {
  margin: var(--space-1) 0;
  color: var(--color-text-light);
}

/* 响应式适配 */
@media (max-width: 768px) {
  .seat-toggle-group {
    flex-direction: column;
    gap: var(--space-2);
  }

  .section-title {
    font-size: var(--font-base);
  }
}
</style>
