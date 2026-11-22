<script setup>
/**
 * Design System Table Component
 * 日系轻亚风 × 有机自然感
 * - 移除所有硬编码颜色，统一使用 CSS 变量
 * - 表格边框仅底部（--color-border）
 * - 行悬停：极淡灰绿背景
 * - 过渡动画统一 0.3s ease-out（--transition-base）
 */
import { ref, computed } from 'vue'
import { ChevronLeft, ChevronRight, ChevronUp, ChevronDown } from 'lucide-vue-next'

const props = defineProps({
  columns: {
    type: Array,
    required: true
  },
  data: {
    type: Array,
    default: () => []
  },
  loading: Boolean,
  total: {
    type: Number,
    default: null
  },
  current: {
    type: Number,
    default: null
  },
  pageSize: {
    type: Number,
    default: null
  },
  pagination: {
    type: Object,
    default: null
  }
})

const emit = defineEmits(['page-change', 'sort', 'page-size-change'])

const sortState = ref({ key: '', order: '' })

const totalCount = computed(() => {
  if (props.total != null) return props.total
  if (props.pagination && props.pagination.total != null) return props.pagination.total
  return props.data.length
})

const currentPage = computed(() => {
  if (props.current != null) return props.current
  if (props.pagination && props.pagination.current != null) return props.pagination.current
  return 1
})

const pageSizeVal = computed(() => {
  if (props.pageSize != null) return props.pageSize
  if (props.pagination && props.pagination.pageSize != null) return props.pagination.pageSize
  return 20
})

function totalPages() {
  return Math.max(1, Math.ceil(totalCount.value / pageSizeVal.value))
}

function goTo(page) {
  if (page >= 1 && page <= totalPages()) {
    emit('page-change', page)
  }
}

function handlePageSizeChange(e) {
  const newSize = parseInt(e.target.value)
  emit('page-size-change', newSize)
}

const visiblePages = computed(() => {
  const total = totalPages()
  const current = currentPage.value
  const pages = []

  if (total <= 7) {
    for (let i = 1; i <= total; i++) {
      pages.push(i)
    }
  } else {
    if (current <= 4) {
      for (let i = 1; i <= 5; i++) pages.push(i)
      pages.push('...')
      pages.push(total)
    } else if (current >= total - 3) {
      pages.push(1)
      pages.push('...')
      for (let i = total - 4; i <= total; i++) pages.push(i)
    } else {
      pages.push(1)
      pages.push('...')
      for (let i = current - 1; i <= current + 1; i++) pages.push(i)
      pages.push('...')
      pages.push(total)
    }
  }

  return pages
})

function handleSort(col) {
  if (!col.sortable) return
  const key = col.sortKey || col.prop || col.key
  if (sortState.value.key === key) {
    if (sortState.value.order === 'asc') {
      sortState.value.order = 'desc'
    } else if (sortState.value.order === 'desc') {
      sortState.value.key = ''
      sortState.value.order = ''
    } else {
      sortState.value.order = 'asc'
    }
  } else {
    sortState.value.key = key
    sortState.value.order = 'asc'
  }
  emit('sort', { sortKey: sortState.value.key, order: sortState.value.order })
}

function getSortIcon(col) {
  if (!col.sortable) return null
  const key = col.sortKey || col.prop || col.key
  if (sortState.value.key !== key) return 'none'
  return sortState.value.order || 'none'
}

function getColKey(col) {
  return col.prop || col.slot || col.key || col.label
}
</script>

<template>
  <div class="ds-table-wrapper">
    <div class="ds-table-container" v-if="!loading">
      <table>
        <thead>
          <tr>
            <th
              v-for="col in columns"
              :key="getColKey(col)"
              :style="{ width: col.width }"
              :class="{ sortable: col.sortable }"
              @click="handleSort(col)"
            >
              <span class="ds-table__th-label">{{ col.label || col.title }}</span>
              <span v-if="col.sortable" class="ds-table__sort-icons">
                <ChevronUp
                  :size="14"
                  :class="['ds-table__sort-icon', 'ds-table__sort-asc', { active: getSortIcon(col) === 'asc' }]"
                />
                <ChevronDown
                  :size="14"
                  :class="['ds-table__sort-icon', 'ds-table__sort-desc', { active: getSortIcon(col) === 'desc' }]"
                />
              </span>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="data.length === 0">
            <td :colspan="columns.length" class="ds-table__empty-cell">暂无数据</td>
          </tr>
          <tr v-for="(row, idx) in data" :key="row.id || idx">
            <td v-for="col in columns" :key="getColKey(col)">
              <slot
                :name="col.slot || col.prop || col.key"
                :row="row"
                :value="row[col.prop || col.key]"
              >
                {{ row[col.prop || col.key] }}
              </slot>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="ds-table__loading" v-if="loading">
      <span class="ds-table__loading-text">加载中...</span>
    </div>

    <div class="ds-table__pagination" v-if="totalCount > 0">
      <div class="ds-table__pagination-left">
        <span class="ds-table__pagination-info">
          共 {{ totalCount }} 条
        </span>
        <select class="ds-table__page-size-select" :value="pageSizeVal" @change="handlePageSizeChange">
          <option v-for="size in [10, 20, 50, 100]" :key="size" :value="size">{{ size }}条/页</option>
        </select>
        <span class="ds-table__pagination-info">
          第 {{ currentPage }} / {{ totalPages() }} 页
        </span>
      </div>
      <div class="ds-table__pagination-btns">
        <button
          class="ds-table__page-btn"
          :disabled="currentPage <= 1"
          @click="goTo(currentPage - 1)"
        >
          <ChevronLeft :size="16" />
        </button>
        <div class="ds-table__page-numbers">
          <button
            v-for="page in visiblePages"
            :key="page"
            :class="['ds-table__page-num', { active: page === currentPage }]"
            @click="goTo(page)"
          >
            {{ page }}
          </button>
        </div>
        <button
          class="ds-table__page-btn"
          :disabled="currentPage >= totalPages()"
          @click="goTo(currentPage + 1)"
        >
          <ChevronRight :size="16" />
        </button>
        <button
          class="ds-table__page-btn ds-table__jump-btn"
          :disabled="currentPage >= totalPages()"
          @click="goTo(totalPages())"
        >
          末页
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 容器样式 */
.ds-table-wrapper {
  background-color: var(--color-white);
  border-radius: var(--radius-md); /* 8px */
  box-shadow: var(--shadow-sm); /* 仅轻阴影 */
  overflow: hidden;
}

.ds-table-container {
  overflow-x: auto;
}

/* 表格基础 */
table {
  width: 100%;
  border-collapse: collapse;
}

/* 表头（完全基于 CSS 变量） */
th {
  background-color: var(--color-bg-alt); /* 米白 */
  padding: var(--space-3) var(--space-4);
  font-weight: 600;
  color: var(--color-text-light); /* 次级文字 */
  font-size: var(--font-sm);
  text-align: left;
  border-bottom: 1px solid var(--color-border); /* 仅底部边框 */
  white-space: nowrap;
  user-select: none;
}

th.sortable {
  cursor: pointer;
  transition: background-color var(--transition-base);
}

th.sortable:hover {
  background-color: var(--color-bg-card);
}

.ds-table__th-label {
  margin-right: var(--space-1);
}

/* 排序图标容器 */
.ds-table__sort-icons {
  display: inline-flex;
  flex-direction: column;
  vertical-align: middle;
  gap: 0;
}

/* 排序图标 */
.ds-table__sort-icon {
  display: block;
  color: var(--color-text-light);
  transition: color var(--transition-base);
}

.ds-table__sort-icon.active {
  color: var(--color-primary);
}

/* 单元格 */
td {
  padding: var(--space-3) var(--space-4);
  font-size: var(--font-sm);
  border-bottom: 1px solid var(--color-border); /* 仅底部边框 */
  color: var(--color-text);
  transition: background-color var(--transition-base);
}

/* 最后一行移除底部边框 */
tr:last-child td {
  border-bottom: none;
}

/* 行悬停（极淡灰绿背景） */
tr:hover td {
  background-color: rgba(166, 186, 174, 0.04);
}

/* 空数据状态 */
.ds-table__empty-cell {
  text-align: center;
  color: var(--color-text-light);
  padding: var(--space-12) var(--space-4);
}

/* 加载状态 */
.ds-table__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-12);
}

.ds-table__loading-text {
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

/* 分页组件 */
.ds-table__pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-4);
  border-top: 1px solid var(--color-border);
  background-color: var(--color-bg-card);
  flex-wrap: wrap;
  gap: var(--space-2);
}

.ds-table__pagination-left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.ds-table__pagination-info {
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

/* 每页条数选择器 */
.ds-table__page-size-select {
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm); /* 6px */
  background-color: var(--color-white);
  color: var(--color-text);
  font-size: var(--font-xs);
  cursor: pointer;
  outline: none;
  transition: border-color var(--transition-base);
}

.ds-table__page-size-select:hover {
  border-color: var(--color-primary);
}

.ds-table__pagination-btns {
  display: flex;
  gap: var(--space-2);
  align-items: center;
}

.ds-table__page-numbers {
  display: flex;
  gap: var(--space-1);
}

/* 页码按钮（圆角 6px） */
.ds-table__page-num {
  min-width: calc(var(--space-6) + var(--space-2)); /* ~32px */
  height: calc(var(--space-6) + var(--space-2));
  padding: 0 var(--space-2);
  border-radius: var(--radius-sm); /* 6px */
  background-color: var(--color-white);
  color: var(--color-text);
  border: 1px solid var(--color-border);
  font-size: var(--font-xs);
  cursor: pointer;
  transition: all var(--transition-base);
  display: flex;
  align-items: center;
  justify-content: center;
}

.ds-table__page-num:hover:not(.active):not(:disabled) {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.ds-table__page-num.active {
  background-color: var(--color-primary); /* 当前页：陶土棕背景 */
  color: var(--color-white); /* 白字 */
  border-color: var(--color-primary);
}

/* 导航按钮 */
.ds-table__jump-btn {
  padding: 0 var(--space-3);
  font-size: var(--font-xs);
}

.ds-table__page-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: calc(var(--space-6) + var(--space-2)); /* 32px */
  height: calc(var(--space-6) + var(--space-2));
  border-radius: var(--radius-sm); /* 6px */
  background-color: var(--color-white);
  color: var(--color-text);
  border: 1px solid var(--color-border);
  transition: all var(--transition-base);
}

.ds-table__page-btn:hover:not(:disabled) {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.ds-table__page-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
