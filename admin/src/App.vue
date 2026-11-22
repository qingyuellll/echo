<script setup>
import { useRoute } from 'vue-router'
import MainLayout from '@/components/layout/MainLayout.vue'

const route = useRoute()
</script>

<template>
  <MainLayout v-if="!route.meta.noLayout" />
  <router-view v-else />
</template>
