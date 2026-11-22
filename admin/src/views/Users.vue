<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import Card from '@/components/common/Card.vue'
import Button from '@/components/common/Button.vue'
import Table from '@/components/common/Table.vue'
import { Ban, UserCheck, Search } from 'lucide-vue-next'
import { api, formatDateTime } from '@/utils/api.js'
import { useSearch } from '@/composables/useSearch.js'

const globalSearch = useSearch()
watch(globalSearch, (v) => {
  searchKeyword.value = v
  handleSearch()
})

const users = ref([])
const loading = ref(false)
const current = ref(1)
const total = ref(0)
const pageSize = ref(20)
const totalPages = computed(() => Math.ceil(total.value / pageSize.value) || 1)

const searchKeyword = ref('')
const searchField = ref('keyword')
const sortBy = ref('createTime')
const sortOrder = ref('desc')

const columns = [
  { label: 'ID', prop: 'id', sortable: true, sortKey: 'id', width: '80px' },
  { label: '昵称', prop: 'nickName', sortable: true, sortKey: 'nickName' },
  { label: '手机号', prop: 'phone', sortable: true, sortKey: 'phone', width: '140px' },
  { label: '注册时间', prop: 'createTime', sortable: true, sortKey: 'createTime' },
  { label: '状态', prop: 'status', sortable: true, sortKey: 'status', width: '80px' },
  { label: '操作', slot: 'actions', width: '120px' }
]

const STATUS_MAP = {
  0: { label: '正常', color: 'var(--color-success)' },
  1: { label: '禁用', color: 'var(--color-text-light)' }
}

async function fetchUsers() {
  loading.value = true
  try {
    const params = {
      current: current.value,
      pageSize: pageSize.value,
      sortBy: sortBy.value,
      sortOrder: sortOrder.value
    }
    if (searchKeyword.value) {
      if (searchField.value === 'phone') {
        params.phone = searchKeyword.value
      } else if (searchField.value === 'id') {
        params.id = searchKeyword.value
      } else {
        params.keyword = searchKeyword.value
      }
    }
    const res = await api.getUserList(params)
    const data = res.data
    if (data && data.records) {
      users.value = data.records
      total.value = Number(data.total) || 0
    } else if (Array.isArray(data)) {
      users.value = data
      total.value = data.length
    } else {
      users.value = []
      total.value = 0
    }
  } catch (e) {
    console.error('获取用户列表失败:', e)
    users.value = []
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  current.value = 1
  fetchUsers()
}

function handleSort({ sortKey, order }) {
  sortBy.value = sortKey || 'createTime'
  sortOrder.value = order || 'desc'
  current.value = 1
  fetchUsers()
}

function changePage(page) {
  current.value = page
  fetchUsers()
}

async function toggleStatus(user) {
  const action = user.status === 1 ? '启用' : '禁用'
  if (!confirm(`确定要${action}用户 ${user.nickName || user.phone} 吗？`)) return
  try {
    if (user.status === 1) {
      await api.enableUser(user.id)
    } else {
      await api.disableUser(user.id)
    }
    await fetchUsers()
  } catch (e) {
    alert(e.message || '操作失败')
  }
}

function statusLabel(s) {
  return STATUS_MAP[s] || { label: '未知', color: 'var(--color-text-light)' }
}

function formatTime(val) {
  if (!val) return '-'
  if (typeof val === 'string') {
    // support ISO format or array format
    const d = new Date(val)
    if (!isNaN(d.getTime())) return formatDateTime(d)
    // maybe array [2025,1,1,12,0,0]
    try {
      const arr = JSON.parse(val)
      if (Array.isArray(arr) && arr.length >= 3) {
        return `${arr[0]}/${String(arr[1]).padStart(2,'0')}/${String(arr[2]).padStart(2,'0')} ${String(arr[3]||0).padStart(2,'0')}:${String(arr[4]||0).padStart(2,'0')}`
      }
    } catch (_) {}
    return val
  }
  return formatDateTime(val)
}

onMounted(fetchUsers)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h1 class="page-title">用户管理</h1>
      <div class="header-right">
        <select v-model="searchField" class="filter-select">
          <option value="keyword">昵称</option>
          <option value="phone">手机号</option>
          <option value="id">ID</option>
        </select>
        <input
          v-model="searchKeyword"
          type="text"
          class="search-input"
          :placeholder="'搜索' + (searchField === 'phone' ? '手机号' : searchField === 'id' ? 'ID' : '昵称') + '...'"
          @keyup.enter="handleSearch"
        />
        <Button size="sm" @click="handleSearch">
          <Search :size="14" />
        </Button>
      </div>
    </div>

    <Card>
      <Table
        :columns="columns"
        :data="users"
        :loading="loading"
        :total="total"
        :current="current"
        @page-change="changePage"
        @sort="handleSort"
      >
        <template #createTime="{ row }">
          <span>{{ formatTime(row.createTime) }}</span>
        </template>
        <template #status="{ value }">
          <span class="status-tag" :style="{ background: statusLabel(value).color }">
            {{ statusLabel(value).label }}
          </span>
        </template>
        <template #actions="{ row }">
          <div class="action-btns">
            <Button
              size="sm"
              variant="ghost"
              @click="toggleStatus(row)"
            >
              <Ban v-if="row.status === 0" :size="14" />
              <UserCheck v-else :size="14" />
            </Button>
          </div>
        </template>
      </Table>
      <div class="pagination" v-if="total > pageSize">
        <Button size="sm" variant="ghost" :disabled="current === 1" @click="changePage(current - 1)">上一页</Button>
        <span class="page-info">{{ current }} / {{ totalPages }}</span>
        <Button size="sm" variant="ghost" :disabled="current >= totalPages" @click="changePage(current + 1)">下一页</Button>
      </div>
    </Card>
  </div>
</template>

<style scoped>
.page { max-width: 1200px; }
.page-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: var(--space-6); flex-wrap: wrap; gap: var(--space-3);
}
.page-title { font-family: var(--font-heading); font-size: var(--font-2xl); color: var(--color-text); }
.header-right { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }

.filter-select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-white);
  font-size: var(--font-sm);
  color: var(--color-text);
  cursor: pointer;
}

.search-input {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-white);
  font-size: var(--font-sm);
  color: var(--color-text);
  width: 200px;
  outline: none;
}
.search-input:focus { border-color: var(--color-primary); }

.filter-select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-white);
  font-size: var(--font-sm);
  color: var(--color-text);
  cursor: pointer;
}

.status-tag {
  display: inline-flex; align-items: center;
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm); font-size: var(--font-xs); font-weight: 500;
  color: var(--color-white); white-space: nowrap;
}

.action-btns { display: flex; gap: var(--space-1); }

.pagination {
  display: flex; align-items: center; justify-content: center;
  gap: var(--space-4); margin-top: var(--space-6);
}
.page-info { font-size: var(--font-sm); color: var(--color-text-light); }
</style>
