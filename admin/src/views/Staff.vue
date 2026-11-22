<script setup>
import { ref, onMounted, watch, computed } from 'vue'
import Card from '@/components/common/Card.vue'
import Button from '@/components/common/Button.vue'
import Modal from '@/components/common/Modal.vue'
import Table from '@/components/common/Table.vue'
import { Plus, Ban, UserCheck, UserX, Search } from 'lucide-vue-next'
import { api } from '@/utils/api.js'
import { useSearch } from '@/composables/useSearch.js'

const globalSearch = useSearch()
watch(globalSearch, (v) => {
  keyword.value = v
  applyFilter()
})

const staff = ref([])
const loading = ref(false)
const modalVisible = ref(false)
const formLoading = ref(false)
const keyword = ref('')
const searchInput = ref('')
const searchField = ref('name')
const sortBy = ref('id')
const sortOrder = ref('asc')

const currentAdminId = ref(null)
const currentAdminRoleLevel = ref(1)

const isAdminSelf = computed(() => (row) => {
  return currentAdminId.value && currentAdminId.value === row.id
})

const isSuperAdmin = computed(() => {
  return currentAdminRoleLevel.value === 0
})

const form = ref({
  userId: '',
  name: '',
  phone: '',
  roleLevel: 1
})

const columns = [
  { label: 'ID', prop: 'id', sortable: true, sortKey: 'id', width: '60px' },
  { label: '用户ID', prop: 'userId', sortable: true, sortKey: 'userId', width: '80px' },
  { label: '昵称', prop: 'name', sortable: true, sortKey: 'name' },
  { label: '手机号', prop: 'phone', sortable: true, sortKey: 'phone' },
  { label: '状态', prop: 'status', sortable: true, sortKey: 'status', width: '80px' },
  { label: '操作', slot: 'actions', width: '140px' }
]

const filteredStaff = ref([])

async function loadStaff() {
  loading.value = true
  try {
    const res = await api.getStaffList()
    staff.value = res.data || []

    try {
      const adminInfoRes = await api.getAdminInfo()
      if (adminInfoRes.data && adminInfoRes.data.id) {
        currentAdminId.value = adminInfoRes.data.id
        currentAdminRoleLevel.value = adminInfoRes.data.roleLevel ?? 1
      }
    } catch (e) {
      console.warn('获取当前管理员信息失败:', e)
    }

    applyFilter()
  } catch (e) {
    staff.value = []
  } finally {
    loading.value = false
  }
}

function applyFilter() {
  let result = [...staff.value]

  if (keyword.value.trim()) {
    const kw = keyword.value.trim().toLowerCase()
    result = result.filter(
      s => {
        if (searchField.value === 'name') {
          return (s.name || '').toLowerCase().includes(kw)
        } else if (searchField.value === 'phone') {
          return (s.phone || '').includes(kw)
        } else {
          return String(s.userId).includes(kw) || String(s.id).includes(kw)
        }
      }
    )
  }

  if (sortBy.value) {
    result.sort((a, b) => {
      let valA = a[sortBy.value]
      let valB = b[sortBy.value]
      if (typeof valA === 'string') valA = valA.toLowerCase()
      if (typeof valB === 'string') valB = valB.toLowerCase()
      if (valA < valB) return sortOrder.value === 'asc' ? -1 : 1
      if (valA > valB) return sortOrder.value === 'asc' ? 1 : -1
      return 0
    })
  }

  filteredStaff.value = result
}

function onSearch() {
  keyword.value = searchInput.value
  applyFilter()
}

function handleSort({ sortKey, order }) {
  sortBy.value = sortKey || 'id'
  sortOrder.value = order || 'asc'
  applyFilter()
}

async function handleAdd() {
  if (!form.value.userId.toString().trim()) {
    alert('请输入用户ID')
    return
  }
  formLoading.value = true
  try {
    await api.addStaff({
      userId: Number(form.value.userId),
      name: form.value.name || '',
      phone: form.value.phone || '',
      roleLevel: form.value.roleLevel ?? 1
    })
    modalVisible.value = false
    form.value = { userId: '', name: '', phone: '', roleLevel: 1 }
    await loadStaff()
  } catch (e) {
    alert(e.message)
  } finally {
    formLoading.value = false
  }
}

async function handleDisable(staffId) {
  try {
    await api.disableStaff(staffId)
    await loadStaff()
  } catch (e) {
    alert(e.message)
  }
}

async function handleEnable(staffId) {
  try {
    await api.enableStaff(staffId)
    await loadStaff()
  } catch (e) {
    alert(e.message)
  }
}

async function handleRemove(targetUserId) {
  if (!confirm('确认移除该员工?')) return
  try {
    await api.removeStaff(targetUserId)
    await loadStaff()
  } catch (e) {
    alert(e.message)
  }
}

onMounted(loadStaff)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h1 class="page-title">员工管理</h1>
      <div class="header-right">
        <select v-model="searchField" class="filter-select">
          <option value="name">姓名</option>
          <option value="phone">手机号</option>
          <option value="id">ID</option>
        </select>
        <div class="search-bar">
          <input
            v-model="searchInput"
            class="search-input"
            placeholder="搜索员工..."
            @keyup.enter="onSearch"
          />
          <Button variant="ghost" size="sm" @click="onSearch">
            <Search :size="16" />
          </Button>
        </div>
        <Button @click="modalVisible = true">
          <Plus :size="16" />
          添加员工
        </Button>
      </div>
    </div>

    <Card padding="md">
      <Table
        :columns="columns"
        :data="filteredStaff"
        :loading="loading"
        @sort="handleSort"
      >
        <template #status="{ value }">
          <span class="status-tag" :class="value === 0 ? 'status-online' : 'status-offline'">
            {{ value === 0 ? '在职' : '已禁用' }}
          </span>
        </template>
        <template #actions="{ row }">
          <div class="action-btns">
            <Button
              v-if="row.status === 0 && !isAdminSelf(row)"
              size="sm"
              variant="ghost"
              @click="handleDisable(row.id)"
              title="禁用账号"
            >
              <Ban :size="14" />
            </Button>
            <Button
              v-else-if="row.status !== 0"
              size="sm"
              variant="ghost"
              @click="handleEnable(row.id)"
              title="启用账号"
            >
              <UserCheck :size="14" />
            </Button>
            <Button
              v-if="!isAdminSelf(row)"
              size="sm"
              variant="ghost"
              danger
              @click="handleRemove(row.userId)"
              title="移除员工身份"
            >
              <UserX :size="14" />
            </Button>
            <span
              v-else
              class="self-hint"
              title="不能对自己的账号执行此操作"
            >
              当前账号
            </span>
          </div>
        </template>
      </Table>
    </Card>

    <Modal
      :visible="modalVisible"
      title="添加员工"
      @close="modalVisible = false"
    >
      <div class="form-stack">
          <div class="form-group">
            <label class="form-label">用户ID <span class="required">*</span></label>
            <input
              v-model="form.userId"
              type="number"
              class="form-input"
              placeholder="输入要设为员工的用户ID"
            />
          </div>
          <div class="form-group">
            <label class="form-label">姓名</label>
            <input
              v-model="form.name"
              class="form-input"
              placeholder="员工姓名"
            />
          </div>
          <div class="form-group">
            <label class="form-label">手机号</label>
            <input
              v-model="form.phone"
              type="tel"
              class="form-input"
              placeholder="员工手机号"
            />
          </div>
          <div class="form-group">
            <label class="form-label">权限等级</label>
            <select v-model.number="form.roleLevel" class="form-input">
              <option v-if="isSuperAdmin" :value="0">超级管理员</option>
              <option :value="1">普通管理员</option>
            </select>
            <span v-if="!isSuperAdmin" class="form-hint">仅超级管理员可创建超级管理员账号</span>
          </div>
      </div>
      <template #footer>
        <Button variant="secondary" @click="modalVisible = false">取消</Button>
        <Button :loading="formLoading" @click="handleAdd">确认添加</Button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.page { max-width: 1200px; }
.page-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: var(--space-6); flex-wrap: wrap; gap: var(--space-3);
}
.page-title { font-family: var(--font-heading); font-size: var(--font-2xl); color: var(--color-text); }
.header-right { display: flex; align-items: center; gap: var(--space-3); }

.filter-select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-white);
  font-size: var(--font-sm);
  color: var(--color-text);
  cursor: pointer;
}

.search-bar {
  display: flex; align-items: center;
  background: var(--color-white);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.search-input {
  border: none; padding: var(--space-2) var(--space-3);
  font-size: var(--font-sm); color: var(--color-text);
  background: transparent; width: 180px; outline: none;
}

.status-tag {
  display: inline-flex; align-items: center;
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm); font-size: var(--font-xs); font-weight: 500;
}
.status-online { background-color: var(--color-success); color: var(--color-white); }
.status-offline { background-color: var(--color-danger); color: var(--color-white); }

.action-btns { display: flex; gap: var(--space-1); align-items: center; }
.self-hint {
  display: inline-flex;
  align-items: center;
  padding: var(--space-1) calc(var(--space-2) + var(--space-2)); /* 2px 8px → 变量 */
  font-size: var(--font-xs);
  color: var(--color-text-light); /* #8c8c8c → 变量 */
  background-color: var(--color-bg-alt); /* #fafafa → 米白变量 */
  border-radius: var(--radius-sm);
  cursor: not-allowed;
}
.form-stack { display: flex; flex-direction: column; gap: var(--space-4); }
.form-group { display: flex; flex-direction: column; gap: var(--space-1); }
.form-label { font-size: var(--font-sm); font-weight: 500; color: var(--color-text); }
.form-input { width: 100%; }
.required { color: var(--color-danger); }
.form-hint {
  display: block;
  margin-top: var(--space-1);
  font-size: var(--font-xs);
  color: var(--color-text-light);
}
</style>
