<script setup>
import { X } from 'lucide-vue-next'
import { watch } from 'vue'

const props = defineProps({
  visible: Boolean,
  title: String,
  width: {
    type: String,
    default: '480px'
  }
})

const emit = defineEmits(['close'])

watch(() => props.visible, (val) => {
  if (val) {
    document.body.style.overflow = 'hidden'
  } else {
    document.body.style.overflow = ''
  }
})

function onOverlayClick(e) {
  if (e.target === e.currentTarget) {
    emit('close')
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="modal-overlay" @click="onOverlayClick">
      <div class="modal-container" :style="{ maxWidth: width }">
        <div class="modal-header">
          <h3 class="modal-title">{{ title }}</h3>
          <button class="modal-close" @click="emit('close')">
            <X :size="18" />
          </button>
        </div>
        <div class="modal-body">
          <slot />
        </div>
        <div class="modal-footer" v-if="$slots.footer">
          <slot name="footer" />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background-color: rgba(45, 58, 49, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  animation: fadeIn 0.15s ease-out;
}

.modal-container {
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
  width: 90%;
  max-width: 800px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  animation: slideUp 0.2s ease-out;
  overflow-x: hidden; /* 禁止水平滚动 */
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-6);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.modal-title {
  font-family: var(--font-heading);
  font-size: var(--font-lg);
  color: var(--color-text);
}

.modal-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: none;
  color: var(--color-text-light);
  border-radius: var(--radius-sm);
}

.modal-close:hover {
  background-color: var(--color-bg-card);
  color: var(--color-text);
}

.modal-body {
  padding: var(--space-6);
  overflow-y: auto;   /* 允许垂直滚动 */
  overflow-x: hidden; /* 禁止水平滚动 */
  flex: 1;
  -webkit-overflow-scrolling: touch; /* iOS平滑滚动 */
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-6);
  border-top: 1px solid var(--color-border);
  flex-shrink: 0;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
