<script setup>
import { ref, onMounted } from 'vue'
import { api, parseImages, getImageUrl, formatPrice } from '../utils/api.js'

const showTypes = ref([])
const activeTypeId = ref(null)
const shows = ref([])
const loading = ref(false)
const current = ref(1)
const total = ref(0)
const searchKeyword = ref('')
const searchMode = ref(false)

// 新增：位置相关状态
const userLocation = ref(null)  // 用户位置 {x: 经度, y: 纬度}
const locationLoading = ref(false)  // 定位中
const sortByDistance = ref(false)  // 是否按距离排序
const locationError = ref('')  // 定位错误信息

async function loadShowTypes() {
  try {
    const res = await api.getShowTypes()
    showTypes.value = res.data || []
  } catch {
    // types load silently
  }
}

// 新增：获取用户地理位置
function getUserLocation() {
  if (!navigator.geolocation) {
    locationError.value = '您的浏览器不支持地理定位'
    alert(locationError.value)
    return
  }

  locationLoading.value = true
  locationError.value = ''

  navigator.geolocation.getCurrentPosition(
      // 成功回调
      (position) => {
        userLocation.value = {
          x: position.coords.longitude,  // 经度
          y: position.coords.latitude     // 纬度
        }
        locationLoading.value = false
        console.log('[定位成功] 经度:', userLocation.value.x, '纬度:', userLocation.value.y)

        // 自动切换到距离排序模式
        if (!sortByDistance.value) {
          toggleSortByDistance()
        }
      },
      // 失败回调
      (error) => {
        locationLoading.value = false
        switch(error.code) {
          case error.PERMISSION_DENIED:
            locationError.value = '用户拒绝了地理定位请求'
            break
          case error.POSITION_UNAVAILABLE:
            locationError.value = '无法获取位置信息'
            break
          case error.TIMEOUT:
            locationError.value = '获取位置信息超时'
            break
          default:
            locationError.value = '获取位置信息失败'
        }
        console.warn('[定位失败]', locationError.value)
        alert(locationError.value + '\n\n请检查浏览器权限设置，或手动输入地址')
      },
      // 配置选项
      {
        enableHighAccuracy: true,   // 高精度模式
        timeout: 10000,             // 10秒超时
        maximumAge: 300000           // 5分钟内缓存有效
      }
  )
}

// 新增：切换距离排序模式
function toggleSortByDistance() {
  sortByDistance.value = !sortByDistance.value

  if (sortByDistance.value) {
    // 开启距离排序：需要先获取位置
    if (!userLocation.value) {
      getUserLocation()
    } else {
      // 已有位置，重新加载
      loadShows()
    }
  } else {
    // 关闭距离排序，恢复正常加载
    loadShows()
  }
}

async function loadShows() {
  loading.value = true
  try {
    let res

    const isSearching = searchMode.value && searchKeyword.value.trim()
    const hasTypeFilter = activeTypeId.value !== null

    if (isSearching && hasTypeFilter) {
      // 同时有搜索关键词和分类筛选
      const params = {
        current: current.value,
        name: searchKeyword.value.trim(),
        typeId: activeTypeId.value
      }

      if (sortByDistance.value && userLocation.value) {
        params.x = userLocation.value.x
        params.y = userLocation.value.y
      }

      res = await api.searchShows(params)
    } else if (hasTypeFilter) {
      // 只有分类筛选（无搜索）
      const params = {
        current: current.value,
        typeId: activeTypeId.value,
        pageSize: 50
      }

      if (sortByDistance.value && userLocation.value) {
        params.x = userLocation.value.x
        params.y = userLocation.value.y
      }

      res = await api.getShowsByType(params)
    } else if (isSearching) {
      // 只有搜索（无分类筛选）
      const params = {
        current: current.value,
        name: searchKeyword.value.trim()
      }

      if (sortByDistance.value && userLocation.value) {
        params.x = userLocation.value.x
        params.y = userLocation.value.y
      }

      res = await api.searchShows(params)
    } else {
      // 全部演出：尝试多种API（支持距离排序）
      const baseParams = { current: current.value, pageSize: 50 }

      // 如果开启距离排序且有位置，传递坐标参数
      if (sortByDistance.value && userLocation.value) {
        baseParams.x = userLocation.value.x
        baseParams.y = userLocation.value.y
        console.log('[距离排序] 传递位置参数:', baseParams.x, baseParams.y)
      }

      try {
        // 方法1: 使用热门演出接口获取更多数据
        res = await api.getHotShows(50, sortByDistance.value ? userLocation.value : null)

        // 如果返回的是数组，直接使用
        if (Array.isArray(res)) {
          shows.value = res
          total.value = res.length

          // 如果开启了距离排序且前端有位置信息，在前端进行二次排序
          if (sortByDistance.value && userLocation.value && Array.isArray(res)) {
            console.log('[前端排序] 对', res.length, '条数据进行距离排序')
            shows.value = sortShowsByDistance([...res], userLocation.value)
          }

          loading.value = false
          return
        }

        // 方法2: 使用search接口（带位置参数）
        if (!res || !Array.isArray(res)) {
          res = await api.searchShows(baseParams)
        }
      } catch (searchErr) {
        console.error('搜索接口失败，尝试其他方式:', searchErr)
        // 方法3: 使用show/of/name接口不传参数
        res = await api.searchShows(baseParams)
      }
    }

    console.log('演出数据响应:', res)

    // 统一处理响应数据
    let rawShows = []
    if (Array.isArray(res)) {
      rawShows = res
      total.value = res.length
    } else if (res && res.data) {
      if (Array.isArray(res.data)) {
        rawShows = res.data
        total.value = res.total || res.data.length
      } else if (res.data.records) {
        rawShows = res.data.records
        total.value = res.data.total || 0
      } else if (typeof res.data === 'object' && !Array.isArray(res.data)) {
        rawShows = [res.data]
        total.value = 1
      } else {
        rawShows = []
        total.value = 0
      }
    } else if (res && res.records) {
      rawShows = res.records
      total.value = res.total || 0
    } else {
      rawShows = []
      total.value = 0
    }

    // 如果开启了距离排序且后端未排序（或数据中没有距离字段），在前端进行排序
    if (sortByDistance.value && userLocation.value && rawShows.length > 0) {
      const firstShow = rawShows[0]

      if (!firstShow._distance && !firstShow.distance) {
        console.log('[统一处理] 后端未返回距离信息，使用前端排序')
        shows.value = sortShowsByDistance(rawShows, userLocation.value)
      } else {
        console.log('[统一处理] 后端已返回距离信息，直接使用')
        shows.value = rawShows
      }
    } else {
      shows.value = rawShows
    }

    console.log('处理后演出列表:', shows.value.length, '条')
  } catch (e) {
    console.error('加载演出失败:', e)
    shows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function selectType(id) {
  if (activeTypeId.value === id) {
    activeTypeId.value = null
  } else {
    activeTypeId.value = id
  }
  current.value = 1
  loadShows()
}

function search() {
  searchMode.value = true
  current.value = 1
  loadShows()
}

// 计算两点之间的距离（Haversine公式，单位：公里）
function calculateDistance(lat1, lon1, lat2, lon2) {
  const R = 6371 // 地球半径（公里）
  const dLat = (lat2 - lat1) * Math.PI / 180
  const dLon = (lon2 - lon1) * Math.PI / 180
  const a =
    Math.sin(dLat/2) * Math.sin(dLat/2) +
    Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
    Math.sin(dLon/2) * Math.sin(dLon/2)
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
  return R * c
}

// 按距离对演出列表进行排序
function sortShowsByDistance(showsArray, location) {
  if (!showsArray || !location || !Array.isArray(showsArray)) {
    return showsArray || []
  }

  console.log('[距离排序] 用户位置:', location.x, location.y)
  console.log('[距离排序] 开始排序，共', showsArray.length, '条数据')

  const sorted = showsArray.map(show => {
    let distance = Infinity

    if (show.x && show.y) {
      distance = calculateDistance(
        location.y, location.x,
        show.y, show.x
      )
    } else if (show.venueX && show.venueY) {
      distance = calculateDistance(
        location.y, location.x,
        show.venueY, show.venueX
      )
    }

    return { ...show, _distance: distance }
  })
  .sort((a, b) => a._distance - b._distance)
  .map(({ _distance, ...show }) => show)

  console.log('[距离排序] 排序完成，前3条距离:')
  sorted.slice(0, 3).forEach((show, index) => {
    if (show.x && show.y) {
      const dist = calculateDistance(location.y, location.x, show.y, show.x)
      console.log(`  ${index + 1}. ${show.name}: ${dist.toFixed(2)}km`)
    }
  })

  return sorted
}

function clearSearch() {
  searchKeyword.value = ''
  searchMode.value = false
  current.value = 1
  loadShows()
}

// 新增：格式化距离显示（单位：公里 -> 友好显示）
function formatDistance(distanceInMeters) {
  if (distanceInMeters == null) return ''

  // Redis GEO 返回的距离单位是米
  if (distanceInMeters < 1000) {
    return Math.round(distanceInMeters) + '米'
  } else if (distanceInMeters < 10000) {
    return (distanceInMeters / 1000).toFixed(1) + '公里'
  } else {
    return Math.round(distanceInMeters / 1000) + '公里'
  }
}

function prevPage() {
  if (current.value > 1) {
    current.value--
    loadShows()
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
}

function nextPage() {
  if (current.value * 20 < total.value) {
    current.value++
    loadShows()
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
}

const placeholderCount = 6

onMounted(() => {
  loadShowTypes()
  loadShows()
})
</script>

<template>
  <div class="shows-page">
    <div class="shows-inner">
      <h1 class="shows-title">探索演出</h1>

      <!-- 搜索栏 -->
      <div class="shows-search">
        <input
          v-model="searchKeyword"
          type="text"
          placeholder="搜索演出名称..."
          @keyup.enter="search"
        />
        <button class="shows-search-btn" @click="search">搜索</button>
        <button v-if="searchMode" class="shows-clear-btn" @click="clearSearch">清除</button>
      </div>

      <!-- 类型筛选 + 附近功能 -->
      <div class="shows-toolbar">
        <div class="shows-types">
          <button
            :class="['shows-type-btn', { active: !activeTypeId && !searchMode }]"
            @click="selectType(null)"
          >
            全部
          </button>
          <button
            v-for="t in showTypes"
            :key="t.id"
            :class="['shows-type-btn', { active: activeTypeId === t.id && !searchMode }]"
            @click="selectType(t.id)"
          >
            {{ t.name }}
          </button>
        </div>

        <!-- 附近演出功能 -->
        <div class="nearby-section">
          <button
            :class="['nearby-btn', {
              active: sortByDistance,
              loading: locationLoading,
              located: userLocation && sortByDistance
            }]"
            @click="toggleSortByDistance"
            :disabled="locationLoading"
          >
            <span v-if="locationLoading" class="btn-spinner"></span>
            <span v-else-if="userLocation && sortByDistance" class="btn-icon-active">✓</span>
            <span v-else class="btn-icon">定位</span>

            <span v-if="locationLoading">获取位置中</span>
            <span v-else-if="sortByDistance">距离优先</span>
            <span v-else>附近演出</span>
          </button>

          <div v-if="userLocation && sortByDistance" class="nearby-status">
            <span class="status-indicator"></span>
            <span class="status-label">已定位</span>
            <button @click="toggleSortByDistance" class="status-reset">取消</button>
          </div>
        </div>
      </div>

      <!-- 结果 -->
      <div v-if="loading" class="shows-grid">
        <div v-for="i in placeholderCount" :key="i" class="show-card-placeholder" />
      </div>

      <div v-else-if="!shows.length" class="shows-empty">
        暂无演出
      </div>

      <div v-else class="shows-grid">
        <router-link
          v-for="show in shows"
          :key="show.id"
          :to="`/show/${show.id}`"
          class="show-card"
        >
          <div class="show-card-img">
            <img
              v-if="show.images && parseImages(show.images).length"
              :src="getImageUrl(parseImages(show.images)[0])"
              :alt="show.name"
              loading="lazy"
            />
            <div v-else class="show-card-img-placeholder" />
          </div>
          <div class="show-card-body">
            <h3 class="show-card-name">{{ show.name }}</h3>
            <p class="show-card-area">{{ show.area }} · {{ show.address }}</p>

            <div v-if="sortByDistance && show.distance != null" class="distance-badge">
              距您 {{ formatDistance(show.distance) }}
            </div>

            <div class="show-card-meta">
              <span class="show-card-price">{{ formatPrice(show.avgPrice) }}起</span>
              <span class="show-card-sold">已售 {{ show.sold || 0 }}</span>
            </div>
          </div>
        </router-link>
      </div>

      <!-- 分页 -->
      <div v-if="total > 20 && !searchMode" class="shows-pagination">
        <button :disabled="current <= 1" @click="prevPage">上一页</button>
        <span>{{ current }} / {{ Math.ceil(total / 20) || 1 }}</span>
        <button :disabled="current * 20 >= total" @click="nextPage">下一页</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.shows-page {
  padding: var(--space-12) var(--space-6);
  background-color: var(--color-bg);
  min-height: calc(100vh - 60px);
}

.shows-inner {
  max-width: 1200px;
  margin: 0 auto;
}

.shows-title {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-text);
  margin-bottom: var(--space-8);
  text-align: center;
}

.shows-search {
  display: flex;
  gap: var(--space-2);
  margin-bottom: var(--space-6);
  justify-content: center;
}

.shows-search input {
  width: 320px;
  height: 40px;
}

.shows-search-btn {
  height: 40px;
  padding: 0 var(--space-4);
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: var(--radius-sm);
  background-color: var(--color-primary);
  color: var(--color-white);
}

.shows-search-btn:hover {
  background-color: var(--color-secondary);
}

.shows-clear-btn {
  height: 40px;
  padding: 0 var(--space-4);
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-alt);
  color: var(--color-text);
}

.shows-clear-btn:hover {
  background-color: var(--color-border);
}

.shows-types {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
  justify-content: center;
  margin-bottom: var(--space-8);
}

/* ===== 工具栏（类型筛选 + 附近功能）===== */
.shows-toolbar {
  display: flex;
  gap: var(--space-6);
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-8);
  flex-wrap: wrap;
}

/* ===== 附近演出功能区（用户端设计规范）===== */
.nearby-section {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--space-2);
}

.nearby-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  height: 42px;
  padding: 0 var(--space-5);
  font-size: var(--font-sm);
  font-weight: 600;
  font-family: var(--font-body);
  letter-spacing: 0.01em;
  border-radius: var(--radius-md);
  background-color: transparent;
  color: var(--color-primary);
  border: 2px solid var(--color-primary);
  cursor: pointer;
  transition: all 0.3s ease-out;
  white-space: nowrap;
  position: relative;
  overflow: hidden;
}

.nearby-btn::before {
  content: '';
  position: absolute;
  top: 0;
  left: -100%;
  width: 100%;
  height: 100%;
  background-color: var(--color-primary);
  transition: left 0.3s ease-out;
  z-index: 0;
}

.nearby-btn span {
  position: relative;
  z-index: 1;
}

.nearby-btn:hover:not(:disabled):not(.active):not(.located) {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
  border-color: var(--color-secondary);
  color: var(--color-secondary);
}

.nearby-btn:hover:not(:disabled):not(.active):not(.located)::before {
  left: 0;
}

.nearby-btn:hover:not(:disabled):not(.active):not(.located) span {
  color: #ffffff;
}

.nearby-btn.active,
.nearby-btn.located {
  background-color: var(--color-primary);
  color: #ffffff;
  border-color: var(--color-primary);
}

.nearby-btn.active::before,
.nearby-btn.located::before {
  left: 0;
}

.nearby-btn.loading {
  opacity: 0.65;
  cursor: not-allowed;
  border-color: var(--color-text-light);
  color: var(--color-text-light);
}

.nearby-btn.loading::before {
  display: none;
}

.btn-spinner {
  display: inline-block;
  width: 15px;
  height: 15px;
  border: 3px solid rgba(166, 186, 174, 0.35);
  border-top-color: currentColor;
  border-radius: 50%;
  animation: spin 0.75s linear infinite;
}

.btn-icon-active {
  font-weight: 700;
  font-size: var(--font-base);
}

.btn-icon {
  font-weight: 500;
}

.nearby-status {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background-color: rgba(166, 186, 174, 0.12);
  border-radius: var(--radius-sm);
  border-left: 3px solid var(--color-primary);
}

.status-indicator {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background-color: var(--color-primary);
  animation: breathe 2.5s ease-in-out infinite;
}

.status-label {
  font-size: var(--font-sm);
  color: var(--color-text);
  font-weight: 500;
}

.status-reset {
  font-size: var(--font-xs);
  color: var(--color-secondary);
  background: none;
  border: none;
  cursor: pointer;
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  font-weight: 600;
  text-decoration: underline;
  transition: all 0.2s ease;
}

.status-reset:hover {
  background-color: rgba(150, 116, 98, 0.08);
  color: var(--color-accent);
}

.shows-type-btn {
  padding: var(--space-2) var(--space-4);
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-alt);
  color: var(--color-text-light);
}

.shows-type-btn.active {
  background-color: var(--color-primary);
  color: var(--color-white);
}

.shows-type-btn:hover:not(.active) {
  background-color: var(--color-border);
}

.shows-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-6);
}

.shows-empty {
  text-align: center;
  padding: var(--space-12) 0;
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.show-card {
  display: block;
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
  transition: all var(--transition-base);
  text-decoration: none;
}

.show-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}

.show-card-img {
  aspect-ratio: 16 / 9;
  overflow: hidden;
  background-color: var(--color-bg-alt);
}

.show-card-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform var(--transition-base);
}

.show-card:hover .show-card-img img {
  transform: scale(1.02);
}

.show-card-img-placeholder {
  width: 100%;
  height: 100%;
  background-color: var(--color-bg-alt);
}

.show-card-body {
  padding: var(--space-4);
}

.show-card-name {
  font-family: var(--font-heading);
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.show-card-area {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  margin-bottom: var(--space-3);
}

.show-card-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.show-card-price {
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--color-primary);
}

.show-card-sold {
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

/* ===== 距离徽章（用户端设计规范）===== */
.distance-badge {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-xs);
  color: var(--color-primary);
  font-weight: 600;
  padding: var(--space-1) var(--space-3);
  background-color: rgba(166, 186, 174, 0.15);
  border-radius: 20px;
  margin-bottom: var(--space-2);
  letter-spacing: 0.02em;
  transition: all 0.3s ease-out;
}

.distance-badge:hover {
  background-color: rgba(166, 186, 174, 0.25);
  transform: scale(1.02);
}

.show-card-placeholder {
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  animation: shimmer 1.5s infinite;
  aspect-ratio: 1 / 1;
}

@keyframes shimmer {
  0% { opacity: 0.6; }
  50% { opacity: 0.9; }
  100% { opacity: 0.6; }
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@keyframes breathe {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(0.9); }
}

.shows-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-4);
  margin-top: var(--space-8);
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.shows-pagination button {
  padding: var(--space-2) var(--space-4);
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-alt);
  color: var(--color-text);
}

.shows-pagination button:hover:not(:disabled) {
  background-color: var(--color-primary);
  color: var(--color-white);
}

.shows-pagination button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

@media (max-width: 992px) {
  .shows-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .nearby-section {
    align-items: stretch;
    width: 100%;
  }

  .nearby-btn {
    justify-content: center;
    flex: 1;
  }

  .nearby-status {
    justify-content: center;
  }
}

@media (max-width: 576px) {
  .shows-grid {
    grid-template-columns: 1fr;
  }
  .shows-search {
    flex-wrap: wrap;
  }
  .shows-search input {
    width: 100%;
  }

  .shows-toolbar {
    flex-direction: column;
    gap: var(--space-4);
  }

  .nearby-section {
    width: 100%;
  }

  .nearby-btn {
    width: 100%;
    height: 46px;
  }

  .nearby-status {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-1);
  }

  .status-reset {
    align-self: flex-end;
  }
}
</style>
