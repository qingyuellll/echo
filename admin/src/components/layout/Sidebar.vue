<script setup>
import { useRoute, useRouter } from 'vue-router'
import {
  LayoutDashboard,
  Clapperboard,
  Ticket,
  ShoppingCart,
  Users,
  UserCog,
  Menu,
  FlaskConical,
  Server,
  CheckCircle
} from 'lucide-vue-next'

defineProps({
  collapsed: Boolean
})

const emit = defineEmits(['toggle'])

const route = useRoute()
const router = useRouter()

const menuItems = [
  { path: '/dashboard', label: '仪表盘', icon: LayoutDashboard },
  { path: '/shows', label: '演出管理', icon: Clapperboard },
  { path: '/tickets', label: '票种管理', icon: Ticket },
  { path: '/orders', label: '订单管理', icon: ShoppingCart },
  { path: '/verification', label: '票务核销', icon: CheckCircle },
  { path: '/users', label: '用户管理', icon: Users },
  { path: '/staff', label: '员工管理', icon: UserCog }
]

function isActive(path) {
  return route.path.startsWith(path)
}

function navigate(path) {
  router.push(path)
}
</script>

<template>
  <aside class="sidebar" :class="{ collapsed }">
    <div class="sidebar-header">
      <div class="sidebar-logo">
        <span class="logo-icon">E</span>
        <span v-show="!collapsed" class="logo-text">Echo Admin</span>
      </div>
      <button class="toggle-btn" @click="emit('toggle')" :title="collapsed ? '展开' : '收起'">
        <Menu :size="20" />
      </button>
    </div>

    <nav class="sidebar-nav">
      <ul>
        <template v-for="(item, index) in menuItems" :key="index">
          <li v-if="item.type === 'divider'" class="menu-divider"></li>
          <li v-else>
            <button
              class="nav-item"
              :class="{ active: isActive(item.path) }"
              @click="navigate(item.path)"
              :title="collapsed ? item.label : ''"
            >
              <component :is="item.icon" :size="20" class="nav-icon" />
              <span v-show="!collapsed" class="nav-label">{{ item.label }}</span>
            </button>
          </li>
        </template>
      </ul>
    </nav>

    <div class="sidebar-footer" v-show="!collapsed">
      <span class="footer-text">Echo 票务管理系统</span>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  width: var(--sidebar-width);
  background-color: var(--color-primary);
  /* 细微有机纸张纹理（repeating-linear-gradient 模拟） */
  background-image:
    repeating-linear-gradient(45deg, rgba(255, 255, 255, 0.025) 0, rgba(255, 255, 255, 0.025) 1px, transparent 1px, transparent 6px),
    repeating-linear-gradient(-45deg, rgba(0, 0, 0, 0.03) 0, rgba(0, 0, 0, 0.03) 1px, transparent 1px, transparent 8px);
  color: var(--color-white);
  display: flex;
  flex-direction: column;
  z-index: 100;
  transition: width var(--transition-base);
  overflow: hidden;
}

.sidebar.collapsed {
  width: 64px;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4);
  min-height: var(--space-16);
  border-bottom: 1px solid rgba(255, 255, 255, 0.12);
}

.sidebar-logo {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  overflow: hidden;
  white-space: nowrap;
}

.logo-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: var(--space-8);
  height: var(--space-8);
  background-color: var(--color-primary);
  color: var(--color-white);
  border-radius: var(--radius-md);
  font-family: var(--font-heading);
  font-size: var(--font-lg);
  font-weight: 700;
  flex-shrink: 0;
}

.logo-text {
  font-family: var(--font-heading);
  font-size: var(--font-base);
  font-weight: 600;
  white-space: nowrap;
}

.toggle-btn {
  background: none;
  color: var(--color-white);
  padding: var(--space-1);
  border-radius: var(--radius-sm);
  flex-shrink: 0;
  transition: background-color var(--transition-base);
}

.toggle-btn:hover {
  background-color: rgba(255, 255, 255, 0.12);
}

.sidebar-nav {
  flex: 1;
  padding: var(--space-3) 0;
  overflow-y: auto;
}

.menu-divider {
  height: 1px;
  background-color: rgba(255, 255, 255, 0.12);
  margin: var(--space-2) var(--space-4);
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-3);
  width: 100%;
  padding: var(--space-3) var(--space-4);
  color: rgba(255, 255, 255, 0.78);
  background: none;
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: 0;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  transition: color var(--transition-base);
}

/* 从左到右的淡色滑入效果（::before 伪元素） */
.nav-item::before {
  content: '';
  position: absolute;
  /* 覆盖整条导航项，作为滑入的淡色层 */
  inset: 0;
  background-color: rgba(255, 255, 255, 0.10);
  transform: scaleX(0);
  transform-origin: left center;
  transition: transform var(--transition-base);
  z-index: 0;
  pointer-events: none;
}

/* 激活项左侧 3px 宽的灰绿竖条（::after 伪元素） */
.nav-item::after {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background-color: var(--color-accent);
  opacity: 0;
  transition: opacity var(--transition-base);
  z-index: 1;
  pointer-events: none;
}

/* 内容层置于滑入层之上 */
.nav-item > * {
  position: relative;
  z-index: 2;
}

.nav-item:hover {
  color: var(--color-white);
}

.nav-item:hover::before {
  transform: scaleX(1);
}

.nav-item.active {
  color: var(--color-white);
}

.nav-item.active::before {
  transform: scaleX(1);
  background-color: rgba(255, 255, 255, 0.14);
}

.nav-item.active::after {
  opacity: 1;
}

.nav-icon {
  flex-shrink: 0;
}

.nav-label {
  overflow: hidden;
}

.sidebar-footer {
  padding: var(--space-4);
  border-top: 1px dashed rgba(255, 255, 255, 0.25);
}

.footer-text {
  font-size: var(--font-xs);
  color: rgba(255, 255, 255, 0.5);
}

@media (max-width: 768px) {
  .sidebar {
    width: 64px;
  }

  .sidebar:not(.collapsed) {
    width: var(--sidebar-width);
  }

  .toggle-btn {
    display: block;
  }
}
</style>
