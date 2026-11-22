<script setup>
defineProps({
  hoverable: {
    type: Boolean,
    default: false
  },
  padding: {
    type: String,
    default: 'md'
  }
})

defineEmits(['click'])
</script>

<template>
  <div
    class="card"
    :class="[
      `card-padding-${padding}`,
      { 'card-hoverable': hoverable }
    ]"
    @click="$emit('click')"
  >
    <slot />
  </div>
</template>

<style scoped>
.card {
  position: relative; /* 为左上角装饰圆点定位 */
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  transition: var(--transition-base);
}

/* 左上角 4px 装饰圆点（品牌色） */
.card::before {
  content: '';
  position: absolute;
  top: var(--space-3);
  left: var(--space-3);
  width: 4px;
  height: 4px;
  border-radius: var(--radius-full);
  background-color: var(--color-primary);
  opacity: 0.9;
}

.card-padding-sm {
  padding: var(--space-3);
}

.card-padding-md {
  padding: var(--space-6);
}

.card-hoverable {
  cursor: pointer;
}

/* hover：仅 translateY(-2px)，禁止 scale */
.card-hoverable:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}
</style>
