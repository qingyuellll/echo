<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Menu, X, User, LogOut } from 'lucide-vue-next'
import { isLoggedIn, getUser, api } from '@/utils/api.js'

const router = useRouter()
const route = useRoute()
const mobileOpen = ref(false)
const loggedIn = ref(false)
const user = ref(null)

function checkAuth() {
  loggedIn.value = isLoggedIn()
  user.value = getUser()
}

onMounted(() => {
  checkAuth()
  window.addEventListener('auth-changed', checkAuth)
})

onUnmounted(() => {
  window.removeEventListener('auth-changed', checkAuth)
})

function toggleMenu() {
  mobileOpen.value = !mobileOpen.value
}

function closeMenu() {
  mobileOpen.value = false
}

function navigate(path) {
  router.push(path)
  closeMenu()
}

async function handleLogout() {
  try {
    await api.logout()
  } catch {
    // server-side logout may fail but we still clear frontend state
  }
  sessionStorage.removeItem('token')
  sessionStorage.removeItem('user')
  loggedIn.value = false
  user.value = null
  window.dispatchEvent(new Event('auth-changed'))
  await router.push('/')
}

const navLinks = [
  { label: '首页', path: '/' },
  { label: '演出', path: '/shows' },
  { label: '评测', path: '/reviews' },
  { label: '购票助手', path: '/guide' },
  { label: '关于', path: '/about' }
]
</script>

<template>
  <nav class="navbar">
    <div class="navbar-inner">
      <a href="/" class="navbar-brand" @click.prevent="navigate('/')">
        Echo
      </a>

      <ul class="navbar-links" :class="{ open: mobileOpen }">
        <li v-for="link in navLinks" :key="link.path">
          <a
            :href="link.path"
            class="navbar-link"
            :class="{ active: route.path === link.path }"
            @click.prevent="navigate(link.path)"
          >
            {{ link.label }}
          </a>
        </li>
        <li class="navbar-auth mobile-only">
          <template v-if="loggedIn && user">
            <span class="navbar-user-name">{{ user.nickName }}</span>
            <button class="navbar-logout-btn" @click="handleLogout">
              <LogOut :size="18" />
              <span>退出</span>
            </button>
          </template>
          <button v-else class="navbar-login-btn" @click="navigate('/login')">
            <User :size="18" />
            <span>登录</span>
          </button>
        </li>
      </ul>

      <div class="navbar-actions">
        <template v-if="loggedIn && user">
          <router-link to="/profile" class="navbar-user-area">
            <span class="navbar-user-name desktop-only">{{ user.nickName }}</span>
          </router-link>
          <button class="navbar-logout-btn desktop-only" @click="handleLogout">
            <LogOut :size="18" />
          </button>
        </template>
        <button v-else class="navbar-login-btn desktop-only" @click="navigate('/login')">
          <User :size="18" />
          <span>登录</span>
        </button>
        <button class="navbar-toggle" @click="toggleMenu" :aria-expanded="mobileOpen">
          <Menu v-if="!mobileOpen" :size="22" />
          <X v-else :size="22" />
        </button>
      </div>
    </div>
  </nav>
</template>

<style scoped>
.navbar {
  position: sticky;
  top: 0;
  z-index: 100;
  background-color: var(--color-bg-alt);
  border-bottom: 1px solid var(--color-border);
}

.navbar-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 var(--space-6);
  height: 64px; /* 导航栏高度 64px */
}

/* 品牌文字：Playfair Display + 灰绿 */
.navbar-brand {
  font-family: var(--font-heading);
  font-size: var(--font-xl);
  font-weight: 700;
  color: var(--color-primary);
  letter-spacing: 0.5px;
}

.navbar-links {
  display: flex;
  align-items: center;
  gap: var(--space-8);
}

.navbar-link {
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-text);
  padding: var(--space-2) 0;
  position: relative;
}

/* hover 下划线：从中间向两侧展开 */
.navbar-link::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 50%;
  width: 100%;
  height: 2px;
  background-color: var(--color-primary);
  transform: translateX(-50%) scaleX(0);
  transform-origin: center;
  transition: transform var(--transition-base);
}

.navbar-link:hover,
.navbar-link.active {
  color: var(--color-primary);
}

.navbar-link:hover::after,
.navbar-link.active::after {
  transform: translateX(-50%) scaleX(1);
}

.navbar-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.navbar-user-area {
  text-decoration: none;
}

.navbar-user-name {
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-text);
}

/* 登录按钮：灰绿背景 + 白字 */
.navbar-login-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  background-color: var(--color-primary);
  color: var(--color-white);
  font-size: var(--font-sm);
  font-weight: 500;
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-sm);
  transition: var(--transition-base);
}

.navbar-login-btn:hover {
  background-color: var(--color-secondary);
}

.navbar-logout-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  background: none;
  color: var(--color-text-light);
  font-size: var(--font-sm);
  font-weight: 500;
  padding: var(--space-1);
  transition: var(--transition-base);
}

.navbar-logout-btn:hover {
  color: var(--color-danger);
}

.navbar-toggle {
  display: none;
  background: none;
  color: var(--color-text);
  padding: var(--space-1);
  transition: var(--transition-base);
}

.mobile-only {
  display: none;
}

@media (max-width: 768px) {
  .desktop-only {
    display: none;
  }

  .mobile-only {
    display: block;
  }

  .navbar-toggle {
    display: flex;
    align-items: center;
    justify-content: center;
  }

  /* 移动端菜单：全屏淡入淡出（opacity + transform） */
  .navbar-links {
    position: fixed;
    top: 64px;
    left: 0;
    right: 0;
    bottom: 0;
    flex-direction: column;
    align-items: stretch;
    gap: 0;
    background-color: var(--color-bg);
    padding: var(--space-6);
    z-index: 99;
    opacity: 0;
    transform: translateY(-8px);
    pointer-events: none;
    transition: opacity var(--transition-base), transform var(--transition-base);
  }

  .navbar-links.open {
    opacity: 1;
    transform: translateY(0);
    pointer-events: auto;
  }

  .navbar-link {
    display: block;
    padding: var(--space-4) 0;
    font-size: var(--font-lg);
    border-bottom: 1px solid var(--color-border);
  }

  /* 移动端隐藏中间展开下划线，改用底部细线 */
  .navbar-link::after {
    display: none;
  }

  .navbar-auth {
    padding-top: var(--space-6);
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    align-items: stretch;
  }

  .navbar-auth .navbar-login-btn,
  .navbar-auth .navbar-logout-btn {
    width: 100%;
    justify-content: center;
  }

  .navbar-auth .navbar-user-name {
    text-align: center;
    font-size: var(--font-base);
    padding: var(--space-4) 0;
  }
}
</style>
