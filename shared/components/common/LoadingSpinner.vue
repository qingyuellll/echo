<template>
  <div class="loading-spinner" :class="[size, variant, { 'full-screen': fullscreen, 'inline': inline }]">
    <!-- 背景遮罩（全屏模式） -->
    <div v-if="fullscreen" class="spinner-overlay" @click="$emit('close')"></div>

    <!-- 加载容器 -->
    <div class="spinner-container">
      <!-- SVG 旋转动画 -->
      <svg
        class="spinner-svg"
        :class="{ 'with-text': showText }"
        viewBox="0 0 50 50"
        :width="svgSize"
        :height="svgSize"
      >
        <circle
          class="spinner-circle"
          cx="25"
          cy="25"
          r="20"
          fill="none"
          :stroke="circleColor"
          stroke-width="4"
          stroke-linecap="round"
          :stroke-dasharray="dashArray"
          :stroke-dashoffset="dashOffset"
        ></circle>
      </svg>

      <!-- 加载文字 -->
      <div v-if="showText && text" class="spinner-text">{{ text }}</div>

      <!-- 自定义内容插槽 -->
      <slot></slot>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  // 尺寸: small | medium | large | xlarge
  size: {
    type: String,
    default: 'medium',
    validator: (value) => ['small', 'medium', 'large', 'xlarge'].includes(value)
  },

  // 变体: default | primary | light | dark
  variant: {
    type: String,
    default: 'default',
    validator: (value) => ['default', 'primary', 'light', 'dark'].includes(value)
  },

  // 是否全屏显示
  fullscreen: {
    type: Boolean,
    default: false
  },

  // 是否内联显示
  inline: {
    type: Boolean,
    default: false
  },

  // 加载提示文字
  text: {
    type: String,
    default: ''
  },

  // 是否显示文字
  showText: {
    type: Boolean,
    default: false
  },

  // 自定义颜色
  color: {
    type: String,
    default: ''
  }
})

const emit = defineEmits(['close'])

// SVG尺寸映射
const svgSize = computed(() => {
  const sizes = {
    small: 24,
    medium: 36,
    large: 48,
    xlarge: 64
  }
  return sizes[props.size] || 36
})

// 圆圈颜色
const circleColor = computed(() => {
  if (props.color) return props.color

  const colors = {
    default: '#666',
    primary: 'var(--color-primary, #1890ff)',
    light: '#fff',
    dark: '#333'
  }

  return colors[props.variant] || colors.default
})

// 虚线数组（控制圆圈长度）
const dashArray = computed(() => {
  const base = Math.PI * 2 * 20 // 2πr (r=20)

  switch (props.size) {
    case 'small':
      return `${base * 0.7} ${base * 0.3}`
    case 'medium':
      return `${base * 0.75} ${base * 0.25}`
    case 'large':
    case 'xlarge':
      return `${base * 0.8} ${base * 0.2}`
    default:
      return `${base * 0.75} ${base * 0.25}`
  }
})

// 虚线偏移（动画用）
const dashOffset = computed(() => {
  return 0 // CSS动画会动态改变这个值
})
</script>

<style scoped>
.loading-spinner {
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}

/* 全屏模式 */
.loading-spinner.full-screen {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 9999;
  background-color: rgba(255, 255, 255, 0.9);
}

.spinner-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: transparent;
}

/* 内联模式 */
.loading-spinner.inline {
  display: inline-flex;
  width: auto;
  height: auto;
}

/* 容器 */
.spinner-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-3, 12px);
  padding: var(--space-4, 16px);
}

/* SVG旋转动画 */
.spinner-svg {
  animation: spin 1s linear infinite;
  transition: all var(--transition-normal);
}

.spinner-svg.with-text {
  margin-bottom: var(--space-2, 8px);
}

.spinner-circle {
  animation: circle-dash 1.5s ease-in-out infinite;
  transition: stroke 0.3s ease;
}

/* 文字样式 */
.spinner-text {
  font-size: var(--font-sm, 14px);
  font-weight: 500;
  color: var(--color-text-light, #666);
  text-align: center;
  line-height: 1.4;
  max-width: 200px;
}

/* 尺寸变体 */
.loading-spinner.small .spinner-container {
  gap: var(--space-2, 8px);
  padding: var(--space-2, 8px);
}

.loading-spinner.small .spinner-text {
  font-size: var(--font-xs, 12px);
}

.loading-spinner.medium .spinner-container {
  gap: var(--space-3, 12px);
  padding: var(--space-4, 16px);
}

.loading-spinner.large .spinner-container {
  gap: var(--space-4, 16px);
  padding: var(--space-6, 24px);
}

.loading-spinner.large .spinner-text {
  font-size: var(--font-base, 16px);
}

.loading-spinner.xlarge .spinner-container {
  gap: var(--space-5, 20px);
  padding: var(--space-8, 32px);
}

.loading-spinner.xlarge .spinner-text {
  font-size: var(--font-lg, 18px);
  max-width: 280px;
}

/* 变体样式 */
.loading-spinner.default .spinner-text {
  color: var(--color-text-light, #666);
}

.loading-spinner.primary .spinner-text {
  color: var(--color-primary, #1890ff);
}

.loading-spinner.light .spinner-text {
  color: #fff;
}

.loading-spinner.dark .spinner-text {
  color: #333;
}

/* 全屏模式的特殊样式 */
.loading-spinner.full-screen .spinner-container {
  background-color: white;
  border-radius: var(--radius-lg, 12px);
  box-shadow: var(--shadow-lg, 0 10px 40px rgba(0, 0, 0, 0.15));
  padding: var(--space-8, 32px);
}

.loading-spinner.full-screen.dark-mode .spinner-container {
  background-color: #1f2937;
}

/* 动画定义 */
@keyframes spin {
  100% {
    transform: rotate(360deg);
  }
}

@keyframes circle-dash {
  0% {
    stroke-dasharray: 1, 150;
    stroke-dashoffset: 0;
  }

  50% {
    stroke-dasharray: 90, 150;
    stroke-dashoffset: -35;
  }

  100% {
    stroke-dasharray: 90, 150;
    stroke-dashoffset: -124;
  }
}

/* 响应式调整 */
@media (max-width: 768px) {
  .loading-spinner.full-screen .spinner-container {
    padding: var(--space-6, 24px);
    max-width: 90vw;
  }

  .loading-spinner.xlarge .spinner-svg {
    width: 48px !important;
    height: 48px !important;
  }

  .loading-spinner.xlarge .spinner-text {
    font-size: var(--font-base, 16px);
  }
}

/* 减少动画（无障碍） */
@media (prefers-reduced-motion: reduce) {
  .spinner-svg {
    animation-duration: 3s; /* 减慢速度 */
  }

  .spinner-circle {
    animation-duration: 3s;
  }
}
</style>
