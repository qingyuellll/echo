<template>
  <div class="empty-state" :class="[size, theme]">
    <!-- 图标/插图 -->
    <div class="empty-state-icon">
      <slot name="icon">
        <!-- 默认图标：根据类型显示不同图标 -->
        <svg v-if="type === 'data'" class="icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />
        </svg>

        <svg v-else-if="type === 'search'" class="icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
        </svg>

        <svg v-else-if="type === 'error'" class="icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
        </svg>

        <svg v-else-if="type === 'success'" class="icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path stroke-linecap="round" stroke-linejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>

        <svg v-else class="icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path stroke-linecap="round" stroke-linejoin="round" d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />
        </svg>
      </slot>
    </div>

    <!-- 标题 -->
    <h3 v-if="title || $slots.title" class="empty-state-title">
      <slot name="title">{{ title || getDefaultTitle() }}</slot>
    </h3>

    <!-- 描述文字 -->
    <p v-if="description || $slots.description" class="empty-state-description">
      <slot name="description">{{ description || getDefaultDescription() }}</slot>
    </p>

    <!-- 操作按钮 -->
    <div v-if="showAction || $slots.action" class="empty-state-action">
      <slot name="action">
        <button
          v-if="actionText"
          class="empty-state-button"
          :class="[actionType]"
          @click="$emit('action')"
        >
          {{ actionText }}
        </button>
      </slot>
    </div>
  </div>
</template>

<script setup>

const props = defineProps({
  // 类型: data | search | error | success | custom
  type: {
    type: String,
    default: 'data'
  },

  // 尺寸: small | medium | large
  size: {
    type: String,
    default: 'medium',
    validator: (value) => ['small', 'medium', 'large'].includes(value)
  },

  // 主题: light | dark
  theme: {
    type: String,
    default: 'light',
    validator: (value) => ['light', 'dark'].includes(value)
  },

  // 自定义标题
  title: {
    type: String,
    default: ''
  },

  // 自定义描述
  description: {
    type: String,
    default: ''
  },

  // 是否显示操作按钮
  showAction: {
    type: Boolean,
    default: false
  },

  // 按钮文字
  actionText: {
    type: String,
    default: ''
  },

  // 按钮类型: primary | secondary | text
  actionType: {
    type: String,
    default: 'primary',
    validator: (value) => ['primary', 'secondary', 'text'].includes(value)
  }
})

const emit = defineEmits(['action'])

/**
 * 获取默认标题（根据类型）
 */
function getDefaultTitle() {
  const titles = {
    data: '暂无数据',
    search: '未找到相关内容',
    error: '加载失败',
    success: '操作成功',
    custom: ''
  }

  return titles[props.type] || '暂无数据'
}

/**
 * 获取默认描述（根据类型）
 */
function getDefaultDescription() {
  const descriptions = {
    data: '这里还没有任何数据',
    search: '换个关键词试试吧',
    error: '请检查网络连接后重试',
    success: '您的操作已成功完成',
    custom: ''
  }

  return descriptions[props.type] || ''
}
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-12) var(--space-6);
  text-align: center;
  min-height: 300px;
  transition: all var(--transition-normal);
}

/* 尺寸变体 */
.empty-state.small {
  padding: var(--space-8) var(--space-4);
  min-height: 200px;
}

.empty-state.small .empty-state-icon {
  width: 48px;
  height: 48px;
  margin-bottom: var(--space-3);
}

.empty-state.small .empty-state-title {
  font-size: var(--font-base);
  margin-bottom: var(--space-1);
}

.empty-state.small .empty-state-description {
  font-size: var(--font-xs);
  max-width: 280px;
}

.empty-state.medium {
  padding: var(--space-12) var(--space-6);
  min-height: 350px;
}

.empty-state.medium .empty-state-icon {
  width: 64px;
  height: 64px;
  margin-bottom: var(--space-4);
}

.empty-state.medium .empty-state-title {
  font-size: var(--font-lg);
  margin-bottom: var(--space-2);
}

.empty-state.medium .empty-state-description {
  font-size: var(--font-sm);
  max-width: 360px;
}

.empty-state.large {
  padding: var(--space-16) var(--space-8);
  min-height: 450px;
}

.empty-state.large .empty-state-icon {
  width: 80px;
  height: 80px;
  margin-bottom: var(--space-6);
}

.empty-state.large .empty-state-title {
  font-size: var(--font-xl);
  margin-bottom: var(--space-3);
}

.empty-state.large .empty-state-description {
  font-size: var(--font-base);
  max-width: 480px;
  line-height: 1.6;
}

/* 主题变体 */
.empty-state.light {
  background-color: transparent;
}

.empty-state.light .empty-state-icon {
  color: var(--color-text-light);
  opacity: 0.6;
}

.empty-state.light .empty-state-title {
  color: var(--color-text);
  opacity: 0.8;
}

.empty-state.light .empty-state-description {
  color: var(--color-text-light);
}

.empty-state.dark {
  background-color: rgba(0, 0, 0, 0.02);
  border-radius: var(--radius-lg);
}

.empty-state.dark .empty-state-icon {
  color: var(--color-text-light);
  opacity: 0.5;
}

.empty-state.dark .empty-state-title {
  color: var(--color-text);
  opacity: 0.9;
}

.empty-state.dark .empty-state-description {
  color: var(--color-text-light);
}

/* 图标样式 */
.empty-state-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: var(--space-4);
  transition: transform var(--transition-normal);
}

.empty-state:hover .empty-state-icon {
  transform: scale(1.05);
}

.icon-svg {
  width: 100%;
  height: 100%;
}

/* 标题样式 */
.empty-state-title {
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-2);
  line-height: 1.4;
}

/* 描述样式 */
.empty-state-description {
  color: var(--color-text-light);
  line-height: 1.6;
  margin-bottom: 0;
  max-width: 400px;
  margin-left: auto;
  margin-right: auto;
}

/* 操作按钮区域 */
.empty-state-action {
  margin-top: var(--space-6);
}

/* 按钮样式 */
.empty-state-button {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-6);
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: var(--radius-md);
  border: none;
  cursor: pointer;
  transition: all var(--transition-fast);
  outline: none;
}

.empty-state-button:focus-visible {
  box-shadow: 0 0 0 3px var(--color-primary-alpha);
}

.empty-state-button.primary {
  background-color: var(--color-primary);
  color: var(--color-white);
}

.empty-state-button.primary:hover {
  background-color: var(--color-secondary);
  transform: translateY(-1px);
  box-shadow: var(--shadow-md);
}

.empty-state-button.secondary {
  background-color: var(--color-bg-alt);
  color: var(--color-text);
  border: 1px solid var(--color-border);
}

.empty-state-button.secondary:hover {
  background-color: var(--color-white);
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.empty-state-button.text {
  background-color: transparent;
  color: var(--color-primary);
  padding: var(--space-2) var(--space-4);
}

.empty-state-button.text:hover {
  background-color: var(--color-primary-alpha);
  color: var(--color-primary-dark);
}

/* 动画效果 */
@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(10px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.empty-state {
  animation: fadeInUp 0.3s ease-out;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .empty-state {
    padding: var(--space-10) var(--space-4);
    min-height: 250px;
  }

  .empty-state.medium .empty-state-icon {
    width: 56px;
    height: 56px;
  }

  .empty-state.medium .empty-state-title {
    font-size: var(--font-base);
  }

  .empty-state.medium .empty-state-description {
    font-size: var(--font-xs);
    max-width: 280px;
  }

  .empty-state.large {
    min-height: 350px;
    padding: var(--space-12) var(--space-6);
  }

  .empty-state.large .empty-state-icon {
    width: 64px;
    height: 64px;
  }

  .empty-state.large .empty-state-title {
    font-size: var(--font-lg);
  }

  .empty-state.large .empty-state-description {
    font-size: var(--font-sm);
    max-width: 320px;
  }
}

@media (max-width: 480px) {
  .empty-state {
    padding: var(--space-8) var(--space-3);
    min-height: 200px;
  }

  .empty-state-action {
    width: 100%;
  }

  .empty-state-button {
    width: 100%;
    justify-content: center;
  }
}
</style>
