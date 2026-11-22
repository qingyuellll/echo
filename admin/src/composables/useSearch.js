import { ref, computed } from 'vue'

const searchQuery = ref('')

export function useSearch() {
  const hasSearchText = computed(() => searchQuery.value.trim().length > 0)

  function clearSearch() {
    searchQuery.value = ''
  }

  return {
    searchQuery,
    hasSearchText,
    clearSearch
  }
}
