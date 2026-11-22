<script setup>
import Navbar from './Navbar.vue'
import Footer from './Footer.vue'
</script>

<template>
  <div class="layout">
    <Navbar />
    <main class="layout-main">
      <router-view />
    </main>
    <Footer />
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background-color: var(--color-bg);
}

.layout-main {
  flex: 1;
}
</style>