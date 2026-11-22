<script setup>
/**
 * Design System Card Component
 * 日系轻亚风 × 有机自然感
 * - 仅使用阴影（不使用边框）
 * - 圆角严格 8px
 * - hover 仅 translateY(-2px)，禁止 scale
 */

defineProps({
  hoverable: {
    type: Boolean,
    default: false
  },
  padding: {
    type: String,
    default: 'md',
    validator: (value) => ['sm', 'md', 'lg'].includes(value)
  }
})

defineEmits(['click'])
</script>

<template>
  <div
    class="ds-card"
    :class="[
      `ds-card--padding-${padding}`,
      { 'ds-card--hoverable': hoverable }
    ]"
    @click="$emit('click')"
  >
    <slot />
  </div>
</template>

<style scoped>
/* 基础样式 */
.ds-card {
  background-color: var(--color-white);
  border-radius: var(--radius-md); /* 严格 8px */
  box-shadow: var(--shadow-sm); /* 仅使用轻阴影，不使用边框 */
  transition: transform var(--transition-base), box-shadow var(--transition-base); /* 0.3s ease-out */
}

/* 内边距变体（基于间距系统） */
.ds-card--padding-sm {
  padding: var(--space-3); /* 12px */
}

.ds-card--padding-md {
  padding: var(--space-6); /* 24px */
}

.ds-card--padding-lg {
  padding: var(--space-8); /* 32px */
}

/* 悬停效果（可选） */
.ds-card--hoverable {
  cursor: pointer;
}

.ds-card--hoverable:hover {
  box-shadow: var(--shadow-md); /* 升级为中级阴影 */
  transform: translateY(-2px); /* 上移 2px，禁止 scale */
}
</style>
