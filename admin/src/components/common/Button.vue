<script setup>
/**
 * Design System Button Component
 * 日系轻亚风 × 有机自然感
 * - 所有样式基于 CSS 变量
 * - 圆角严格 ≤ 8px（--radius-md）
 * - 过渡动画统一 0.15s ease-out（--transition-fast）
 */

defineProps({
  variant: {
    type: String,
    default: 'primary',
    validator: (value) => ['primary', 'secondary', 'danger', 'ghost'].includes(value)
  },
  size: {
    type: String,
    default: 'md',
    validator: (value) => ['sm', 'md', 'lg'].includes(value)
  },
  loading: Boolean,
  disabled: Boolean,
  block: Boolean
})

defineEmits(['click'])
</script>

<template>
  <button
    class="ds-button"
    :class="[
      `ds-button--${variant}`,
      `ds-button--${size}`,
      {
        'ds-button--loading': loading,
        'ds-button--disabled': disabled || loading,
        'ds-button--block': block
      }
    ]"
    :disabled="disabled || loading"
    @click="$emit('click')"
  >
    <!-- Loading Spinner -->
    <span v-if="loading" class="ds-button__spinner" aria-hidden="true"></span>

    <!-- Button Content -->
    <span class="ds-button__content">
      <slot />
    </span>
  </button>
</template>

<style scoped>
/* 基础样式（所有变体共用） */
.ds-button {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  font-family: var(--font-body);
  font-weight: 500;
  line-height: 1.5;
  text-align: center;
  white-space: nowrap;
  cursor: pointer;
  border: none;
  border-radius: var(--radius-md); /* 严格 8px */
  transition: all var(--transition-fast); /* 0.15s ease-out */
  outline: none;

  /* 禁止文本选中 */
  user-select: none;
  -webkit-user-select: none;
}

/* Focus 状态 - 使用陶土棕的描边光晕 */
.ds-button:focus,
.ds-button:focus-visible {
  box-shadow: 0 0 0 2px rgba(150, 116, 98, 0.2);
}

/* Disabled 状态 */
.ds-button--disabled {
  opacity: 0.55;
  cursor: not-allowed;
  pointer-events: none;
}

/* Block 宽度 */
.ds-button--block {
  width: 100%;
}

/* 尺寸变体（严格遵循间距系统） */

/* Small */
.ds-button--sm {
  padding: var(--space-2) var(--space-3); /* 8px 12px */
  font-size: var(--font-sm);               /* 14px */
  min-height: var(--space-6);              /* 24px 最小高度 */
}

/* Medium（默认） */
.ds-button--md {
  padding: var(--space-3) var(--space-4);   /* 12px 16px */
  font-size: var(--font-base);              /* 16px */
  min-height: calc(var(--space-6) + var(--space-2)); /* ~36px */
}

/* Large */
.ds-button--lg {
  padding: var(--space-4) var(--space-6);   /* 16px 24px */
  font-size: var(--font-lg);                /* 20px */
  min-height: calc(var(--space-8) + var(--space-2)); /* ~44px */
}

/* 颜色变体（完全基于 CSS 变量） */

/**
 * Primary - 主按钮
 * 管理端：陶土棕（#967462）
 */
.ds-button--primary {
  background-color: var(--color-primary);
  color: var(--color-white);
}

.ds-button--primary:hover:not(.ds-button--disabled) {
  opacity: 0.85;
  transform: translateY(-1px);
  box-shadow: var(--shadow-sm);
}

.ds-button--primary:active:not(.ds-button--disabled) {
  transform: translateY(0);
  opacity: 0.95;
}

/**
 * Secondary - 次要按钮
 * 透明背景 + 品牌色 1px 边框
 */
.ds-button--secondary {
  background-color: transparent;
  color: var(--color-primary);
  border: 1px solid var(--color-primary);
}

.ds-button--secondary:hover:not(.ds-button--disabled) {
  background-color: var(--color-primary);
  color: var(--color-white);
  transform: translateY(-1px);
}

/**
 * Danger - 危险/删除按钮
 * 陶土粉（#c4877b）- 不刺眼
 */
.ds-button--danger {
  background-color: var(--color-danger);
  color: var(--color-white);
}

.ds-button--danger:hover:not(.ds-button--disabled) {
  opacity: 0.9;
  transform: translateY(-1px);
}

/**
 * Ghost - 幽灵按钮
 * 无边框无背景，仅文字
 */
.ds-button--ghost {
  background-color: transparent;
  color: var(--color-text-light);
}

.ds-button--ghost:hover:not(.ds-button--disabled) {
  background-color: var(--color-bg-alt); /* 米白悬停背景 */
  color: var(--color-text);
}

/* Loading 状态 */

.ds-button__spinner {
  display: inline-block;
  width: 16px;
  height: 16px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Loading 时内容半透明 */
.ds-button--loading .ds-button__content {
  opacity: 0.7;
}
</style>
