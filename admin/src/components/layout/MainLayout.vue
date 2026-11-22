<script setup>
import { ref, computed } from 'vue'
import Sidebar from './Sidebar.vue'
import Header from './Header.vue'

const sidebarCollapsed = ref(false)

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

const contentStyle = computed(() => ({
  marginLeft: sidebarCollapsed.value ? '64px' : 'var(--sidebar-width)'
}))
</script>

<template>
  <div class="layout">
    <Sidebar :collapsed="sidebarCollapsed" @toggle="toggleSidebar" />
    <div class="layout-main" :style="contentStyle">
      <Header />
      <main class="layout-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  min-height: 100vh;
  background-color: var(--color-bg-card); /* 暖白内容区背景 */
}

.layout-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background-color: var(--color-bg-card);
  transition: margin-left var(--transition-base);
}

.layout-content {
  flex: 1;
  padding: var(--space-6);
  overflow-y: auto;
}

@media (max-width: 768px) {
  .layout-content {
    padding: var(--space-4);
  }
}
</style>
