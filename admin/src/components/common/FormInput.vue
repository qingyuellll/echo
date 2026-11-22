<script setup>
/**
 * Design System Form Input Component (v2.0)
 *
 * 完全符合日系轻亚风 × 有机自然感设计规范
 * 统一表单元素样式
 * 聚焦效果柔和（品牌色光晕）
 * 错误状态温暖不刺眼
 * 所有尺寸基于间距系统
 */

defineProps({
  modelValue: {
    type: [String, Number],
    default: ''
  },
  type: {
    type: String,
    default: 'text'
  },
  placeholder: {
    type: String,
    default: ''
  },
  label: {
    type: String,
    default: ''
  },
  error: {
    type: String,
    default: ''
  },
  disabled: Boolean,
  required: Boolean,
  maxlength: {
    type: [String, Number],
    default: ''
  }
})

defineEmits(['update:modelValue', 'blur', 'focus', 'enter'])
</script>

<template>
  <div class="ds-form-group" :class="{ 'has-error': error, 'is-disabled': disabled }">
    <!-- Label -->
    <label v-if="label" class="ds-form__label">
      {{ label }}
      <span v-if="required" class="ds-form__required">*</span>
    </label>

    <!-- Input Wrapper -->
    <div class="ds-input-wrapper">
      <!-- 前置插槽 -->
      <span v-if="$slots.prefix" class="ds-input__prefix">
        <slot name="prefix" />
      </span>

      <!-- Input Element -->
      <input
        :type="type"
        :value="modelValue"
        :placeholder="placeholder"
        :disabled="disabled"
        :maxlength="maxlength"
        :class="[
          'ds-form-input',
          { 'has-prefix': $slots.prefix, 'has-suffix': $slots.suffix }
        ]"
        @input="$emit('update:modelValue', $event.target.value)"
        @blur="$emit('blur', $event)"
        @focus="$emit('focus', $event)"
        @keyup.enter="$emit('enter')"
      />

      <!-- 后置插槽 -->
      <span v-if="$slots.suffix" class="ds-input__suffix">
        <slot name="suffix" />
      </span>
    </div>

    <!-- Error Message -->
    <p v-if="error" class="ds-form__error">{{ error }}</p>

    <!-- Helper Text -->
    <p v-else-if="$slots.hint" class="ds-form__hint">
      <slot name="hint" />
    </p>
  </div>
</template>

<style scoped>
/* ========== 表单组容器 ========== */
.ds-form-group {
  display: flex;
  flex-direction: column;
  gap: var(--space-2); /* 8px间距 */
}

/* ========== 标签 ========== */
.ds-form__label {
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-text);
}

.ds-form__required {
  color: var(--color-danger, #c4877b); /* 温暖的红色 */
  margin-left: var(--space-1);
}

/* ========== 输入框包装器 ========== */
.ds-input-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}

/* ========== 输入框基础样式 ========== */
.ds-form-input {
  width: 100%;
  padding: var(--space-3) var(--space-4); /* 12px 16px */
  font-size: var(--font-base); /* 16px */
  font-family: var(--font-body);
  line-height: 1.5;
  color: var(--color-text);
  background-color: var(--color-white);
  border: 1px solid var(--color-border); /* 统一边框色 */
  border-radius: var(--radius-md); /* 严格8px圆角 */
  transition: all var(--transition-fast); /* 0.15s ease-out */
  outline: none;
}

/* Placeholder样式 */
.ds-form-input::placeholder {
  color: var(--color-text-light);
  opacity: 0.7;
}

/* 聚焦状态 - 使用柔和的品牌色光晕 */
.ds-form-input:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px rgba(150, 116, 98, 0.12); /* 极淡陶土棕 */
}

/* 禁用状态 */
.is-disabled .ds-form-input {
  background-color: var(--color-bg-alt); /* 米白背景 */
  opacity: 0.6;
  cursor: not-allowed;
}

/* 错误状态 - 温暖的错误提示 */
.has-error .ds-form-input {
  border-color: var(--color-danger, #c4877b);
}

.has-error .ds-form-input:focus {
  box-shadow: 0 0 0 3px rgba(196, 135, 123, 0.12); /* 极淡暖红 */
}

/* 前后缀图标时调整内边距 */
.ds-form-input.has-prefix {
  padding-left: calc(var(--space-4) + var(--space-8)); /* 左侧留出图标空间 */
}

.ds-form-input.has-suffix {
  padding-right: calc(var(--space-4) + var(--space-8));
}

/* ========== 前后缀图标 ========== */
.ds-input__prefix,
.ds-input__suffix {
  position: absolute;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-light);
  pointer-events: none;
  z-index: 1;
}

.ds-input__prefix {
  left: var(--space-3); /* 12px */
}

.ds-input__suffix {
  right: var(--space-3);
}

/* ========== 错误提示信息 ========== */
.ds-form__error {
  font-size: var(--font-xs);
  color: var(--color-danger, #c4877b);
  animation: shakeError 0.3s ease-in-out;
}

@keyframes shakeError {
  0%, 100% { transform: translateX(0); }
  25% { transform: translateX(-4px); } /* 减小抖动幅度 */
  75% { transform: translateX(4px); }
}

/* ========== 提示文字 ========== */
.ds-form__hint {
  font-size: var(--font-xs);
  color: var(--color-text-light);
}
</style>
