<script setup>
defineProps({
  variant: {
    type: String,
    default: 'primary',
    validator: (v) => ['primary', 'secondary', 'outline', 'ghost'].includes(v)
  },
  size: {
    type: String,
    default: 'md',
    validator: (v) => ['sm', 'md', 'lg'].includes(v)
  },
  disabled: Boolean,
  loading: Boolean,
  block: Boolean
})

defineEmits(['click'])
</script>

<template>
  <button
    class="btn"
    :class="[`btn-${variant}`, `btn-${size}`, { 'btn-block': block, 'btn-loading': loading }]"
    :disabled="disabled || loading"
    @click="$emit('click')"
  >
    <span v-if="loading" class="btn-spinner"></span>
    <slot />
  </button>
</template>

<style scoped>
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  font-family: var(--font-body);
  font-weight: 500;
  border-radius: var(--radius-sm);
  transition: all var(--transition-base);
  white-space: nowrap;
  cursor: pointer;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Sizes */
.btn-sm {
  padding: var(--space-1) var(--space-3);
  font-size: var(--font-xs);
  height: 28px;
}

.btn-md {
  padding: var(--space-2) var(--space-4);
  font-size: var(--font-sm);
  height: 36px;
}

.btn-lg {
  padding: var(--space-3) var(--space-6);
  font-size: var(--font-base);
  height: 44px;
}

/* Variants */
.btn-primary {
  background-color: var(--color-primary);
  color: var(--color-white);
}

.btn-primary:hover:not(:disabled) {
  background-color: var(--color-secondary);
}

.btn-secondary {
  background-color: var(--color-secondary);
  color: var(--color-white);
}

.btn-secondary:hover:not(:disabled) {
  opacity: 0.85;
}

.btn-outline {
  background-color: transparent;
  color: var(--color-primary);
  border: 1px solid var(--color-primary);
}

.btn-outline:hover:not(:disabled) {
  background-color: var(--color-primary);
  color: var(--color-white);
}

.btn-ghost {
  background-color: transparent;
  color: var(--color-text-light);
}

.btn-ghost:hover:not(:disabled) {
  background-color: var(--color-bg-alt);
  color: var(--color-text);
}

.btn-block {
  width: 100%;
}

.btn-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid transparent;
  border-top-color: currentColor;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>