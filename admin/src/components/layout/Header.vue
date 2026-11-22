<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Search, User, LogOut } from 'lucide-vue-next'
import { api } from '@/utils/api.js'
import { useSearch } from '@/composables/useSearch.js'

const router = useRouter()
const route = useRoute()
const userName = ref('管理员')
const searchQuery = useSearch()
const localSearch = ref('')

// 根据当前路由自动切换搜索框提示
const searchPlaceholders = {
  '/shows': '搜索演出名称...',
  '/orders': '搜索订单号/用户...',
  '/users': '搜索昵称/手机号/ID...',
  '/tickets': '搜索票种名称/演出ID...',
  '/seckill': '搜索秒杀票名称/演出ID...',
  '/staff': '搜索员工姓名/手机号...',
  '/': '搜索演出、订单、用户...'
}

const placeHolder = ref('搜索演出、订单、用户...')

watch(() => route.path, (path) => {
  localSearch.value = ''
  searchQuery.value = ''
  // 匹配最长前缀
  placeHolder.value = searchPlaceholders['/'] || '搜索...'
  // 精确匹配
  for (const key of Object.keys(searchPlaceholders)) {
    if (path === key) {
      placeHolder.value = searchPlaceholders[key]
      break
    }
  }
  // 前缀匹配
  if (!placeHolder.value || placeHolder.value === searchPlaceholders['/']) {
    for (const key of Object.keys(searchPlaceholders)) {
      if (key !== '/' && path.startsWith(key)) {
        placeHolder.value = searchPlaceholders[key]
        break
      }
    }
  }
}, { immediate: true })

function handleSearch() {
  searchQuery.value = localSearch.value.trim()
}

async function handleLogout() {
  try { await api.logout() } catch (e) { /* 即使失败也清除本地 */ }
  sessionStorage.removeItem('token')
  sessionStorage.removeItem('phone')
  localStorage.removeItem('csrf_token')  // 清除CSRF Token
  localStorage.removeItem('csrf_token_time')  // 清除CSRF Token时间戳
  console.log('[Header] 已登出，CSRF Token已清除')
  await router.push('/login')
}

onMounted(() => {
  const stored = sessionStorage.getItem('phone')
  if (stored) {
    userName.value = stored
  }
})
</script>

<template>
  <header class="header">
    <div class="header-left">
      <div class="search-box">
        <Search :size="16" class="search-icon" />
        <input
          v-model="localSearch"
          type="text"
          :placeholder="placeHolder"
          class="search-input"
          @keyup.enter="handleSearch"
        />
      </div>
    </div>

    <div class="header-right">
      <div class="user-info">
        <div class="avatar">
          <User :size="18" />
        </div>
        <span class="user-name">{{ userName }}</span>
      </div>
      <button class="logout-btn" @click="handleLogout" title="退出登录">
        <LogOut :size="18" />
      </button>
    </div>
  </header>
</template>

<style scoped>
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--space-16);
  padding: 0 var(--space-6);
  background-color: var(--color-bg); /* 灰绿 #a6baae */
  box-shadow: var(--shadow-sm);
  flex-shrink: 0;
}

.header-left {
  flex: 1;
  max-width: 360px;
}

.search-box {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  background-color: var(--color-bg-card); /* 暖白 #f9f8f4 */
  border-radius: var(--radius-md); /* 8px */
  padding: var(--space-2) var(--space-3);
}

.search-icon {
  color: var(--color-text-light);
  flex-shrink: 0;
}

.search-input {
  border: none;
  padding: 0;
  font-size: var(--font-sm);
  width: 100%;
  background: transparent;
  color: var(--color-text);
}

.search-input:focus {
  border: none;
}

.search-input::placeholder {
  color: var(--color-text-light);
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--space-4);
}

.user-info {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: var(--space-8);
  height: var(--space-8);
  background-color: var(--color-primary); /* 陶土棕 #967462 */
  color: var(--color-white);
  border-radius: 50%;
}

.user-name {
  font-size: var(--font-sm);
  color: var(--color-white);
  font-weight: 500;
}

.logout-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: var(--space-8);
  height: var(--space-8);
  background: none;
  color: var(--color-white);
  border-radius: var(--radius-sm);
  transition: background-color var(--transition-base);
}

.logout-btn:hover {
  background-color: var(--color-danger);
}

@media (max-width: 768px) {
  .header {
    padding: 0 var(--space-4);
  }

  .header-left {
    max-width: 200px;
  }

  .user-name {
    display: none;
  }
}
</style>
