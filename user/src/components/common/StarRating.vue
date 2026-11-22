<template>
  <div class="star-rating" :class="{ 'is-interactive': interactive }">
    <button
      v-for="star in 5"
      :key="star"
      type="button"
      class="star-btn"
      :class="{
        active: star <= displayValue,
        hover: interactive && star <= hoverValue
      }"
      @mouseenter="interactive && (hoverValue = star)"
      @mouseleave="interactive && (hoverValue = 0)"
      @click="interactive && handleSelect(star)"
      :disabled="!interactive"
      :aria-label="`${star}星`"
    >
      <svg
        class="star-svg"
        :width="size"
        :height="size"
        viewBox="0 0 24 24"
        xmlns="http://www.w3.org/2000/svg"
      >
        <path
          class="star-path"
          d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"
          :fill="getFill(star)"
        />
      </svg>
    </button>

    <span v-if="showValue && !hideScoreText" class="score-text">
      {{ displayValue.toFixed(1) }}
    </span>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  modelValue: {
    type: Number,
    default: 0
  },
  interactive: {
    type: Boolean,
    default: false
  },
  size: {
    type: Number,
    default: 20
  },
  showValue: {
    type: Boolean,
    default: true
  },
  hideScoreText: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:modelValue', 'change'])

const hoverValue = ref(0)

const displayValue = computed(() => props.modelValue || 0)

const isStarActive = (star) => {
  const threshold = props.interactive
    ? (hoverValue.value || displayValue.value)
    : displayValue.value
  return star <= threshold
}

const getFill = (star) => {
  return isStarActive(star) ? '#ffffff' : '#a6baae'
}

const handleSelect = (value) => {
  emit('update:modelValue', value)
  emit('change', value)
}
</script>

<style>
.star-rating {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1, 4px);
}

.is-interactive .star-btn {
  cursor: pointer;
}

.star-btn {
  background: none;
  border: none;
  padding: 0;
  transition: all 0.3s ease-out;
  line-height: 1;
  outline: none;
  position: relative;
}

.star-btn:hover,
.star-btn.active {
  transform: scale(1.15);
}

.star-btn.active .star-svg path {
  filter: drop-shadow(0 1px 2px rgba(166, 186, 174, 0.3))
         drop-shadow(0 2px 4px rgba(166, 186, 174, 0.15));
}

.star-btn.hover:not(.active) {
  transform: scale(1.1);
}

.star-btn.hover:not(.active) .star-svg path {
  fill: #b8ccbf !important;
}

.star-btn:focus-visible {
  outline: 2px solid var(--color-primary, #a6baae);
  outline-offset: 2px;
  border-radius: var(--radius-sm, 6px);
}

.star-svg {
  display: block;
}

.score-text {
  font-size: var(--font-sm, 14px);
  font-weight: 600;
  color: var(--color-text, #2d3a31);
  margin-left: var(--space-2, 8px);
  min-width: 32px;
}
</style>
