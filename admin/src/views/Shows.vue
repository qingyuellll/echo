<template>
  <div class="page-container">
    <div class="page-header">
      <h1>演出管理</h1>
      <p class="subtitle">管理所有演出信息</p>
    </div>

    <!-- 搜索和操作 -->
    <Card class="filter-card">
      <div class="filter-row">
        <div class="search-box">
          <select v-model="selectedTypeId" class="type-select">
            <option :value="0">全部分类</option>
            <option v-for="type in showTypes" :key="type.id" :value="type.id">{{ type.name }}</option>
          </select>
          <select v-model="searchField" class="search-select">
            <option value="name">名称</option>
            <option value="id">ID</option>
          </select>
          <input v-model="keyword" type="text" :placeholder="'搜索演出' + (searchField === 'name' ? '名称' : 'ID') + '...'" @keyup.enter="handleSearch" />
          <Button @click="handleSearch" variant="primary">搜索</Button>
        </div>
        <Button @click="resetFilters" variant="secondary">重置</Button>
        <Button @click="showCreate = true" variant="primary" class="add-btn">+ 新增演出</Button>
      </div>
    </Card>

    <!-- 批量操作栏(选中项目时显示） -->
    <Card v-if="selectedIds.length > 0" class="batch-action-bar">
      <div class="batch-action-content">
        <div class="selection-info">
          <span class="selection-count">已选择 <strong>{{ selectedIds.length }}</strong> 项</span>
          <Button variant="text" size="sm" @click="clearSelection">清空选择</Button>
        </div>
        <div class="batch-actions">
          <Button
            variant="primary"
            size="sm"
            :disabled="batchLoading"
            @click="handleBatchOnline"
          >
            批量上架
          </Button>
          <Button
            variant="secondary"
            size="sm"
            :disabled="batchLoading"
            @click="handleBatchOffline"
          >
            批量下架
          </Button>
          <Button
            variant="danger"
            size="sm"
            :disabled="batchLoading"
            @click="handleBatchDelete"
          >
            批量删除
          </Button>
        </div>
      </div>
    </Card>

    <!-- 数据表格 -->
    <Card>
      <Table
        :columns="columnsWithCheckbox"
        :data="showList"
        :loading="loading"
        :pagination="pagination"
        @sort="handleSort"
        @page-change="handlePageChange"
        @page-size-change="handlePageSizeChange"
      >
        <template #checkbox="{ row }">
          <input
            type="checkbox"
            :value="row.id"
            v-model="selectedIds"
            class="row-checkbox"
          />
        </template>
        <template #status="{ row }">
          <span :class="['status-tag', row.status === 1 ? 'status-online' : 'status-offline']">
            {{ row.status === 1 ? '上架' : '下架' }}
          </span>
        </template>
        <template #showTime="{ row }">
          <div class="show-time-cell">
            <div class="time-range">{{ row.openHours || '-' }}</div>
            <div v-if="row.endTime" class="time-end">结束: {{ formatDateTime(row.endTime) }}</div>
          </div>
        </template>
        <template #actions="{ row }">
          <div class="action-buttons">
            <Button @click="editShow(row)" variant="primary" size="sm">编辑</Button>
            <Button v-if="row.status === 1" @click="toggleShowStatus(row, false)" variant="secondary" size="sm">下架</Button>
            <Button v-else @click="toggleShowStatus(row, true)" variant="secondary" size="sm">上架</Button>
            <Button @click="deleteShow(row)" variant="danger" size="sm">删除</Button>
          </div>
        </template>
      </Table>
    </Card>

    <!-- 新增/编辑演出弹窗 -->
    <Modal :visible="showCreate || showEdit" @close="closeForm" :title="showEdit ? '编辑演出' : '新增演出'">
      <div class="form-grid" ref="formGridRef">
        <div class="form-item">
          <label>演出名称 <span class="required">*</span></label>
          <input v-model="form.name" type="text" placeholder="例：周杰伦2025巡回演唱会" />
        </div>
        <div class="form-item">
          <label>演出类型 <span class="required">*</span></label>
          <select v-model="form.typeId">
            <option :value="null">请选择类型</option>
            <option v-for="type in showTypes" :key="type.id" :value="type.id">{{ type.name }}</option>
          </select>
        </div>
        <div class="form-item form-item-full">
          <label>演出地点 <span class="required">*</span></label>
          <div class="location-wrapper">
            <div class="address-main">
              <input
                v-model="form.address"
                type="text"
                placeholder="请输入详细地址，如：北京市朝阳区国家体育场（鸟巢）"
                @keyup.enter="geocodeAddress"
                class="address-field"
                :class="{ 'has-location': form.x && form.y }"
              />
              <button
                @click="geocodeAddress"
                :disabled="!form.address || geocodingLoading"
                :class="['locate-btn', { loading: geocodingLoading, located: form.x && form.y }]"
              >
                <span v-if="geocodingLoading" class="spinner"></span>
                <span v-else-if="form.x && form.y" class="icon-check">✓</span>
                <span v-else class="icon-locate">定位</span>
              </button>
            </div>
            <div v-if="form.x && form.y" class="location-status">
              <span class="status-dot"></span>
              <span class="status-text">已获取位置信息</span>
              <button @click="clearCoordinates" class="reset-link">重新定位</button>
            </div>
            <div v-else class="location-hint">
              <span>提示：输入地址后点击"定位"自动获取地理位置</span>
            </div>
          </div>
        </div>

        <div class="form-item">
          <label>所在区域</label>
          <input v-model="form.area" type="text" placeholder="例：天河区" />
        </div>

        <input v-model="form.x" type="hidden" />
        <input v-model="form.y" type="hidden" />
        <div class="form-item form-item-full">
          <label>演出时间范围 <span class="required">*</span></label>
          <div class="date-range">
            <input v-model="form.startTime" type="datetime-local" placeholder="开始时间" />
            <span class="date-separator">至</span>
            <input v-model="form.endTime" type="datetime-local" placeholder="结束时间" />
          </div>
          <span class="field-hint">演出的完整时间范围，包含具体时分秒（格式：yyyy-MM-dd HH:mm）</span>
        </div>
        <div class="form-item form-item-full">
          <label>演出详情描述</label>
          <textarea
            v-model="form.description"
            rows="6"
            placeholder="请输入演出的详细介绍，包括：&#10;1. 演出内容介绍&#10;2. 演出亮点&#10;3. 观演须知&#10;4. 其他说明..."
            ref="textareaRef"
          ></textarea>
          <span class="field-hint">支持多行文本，会在用户端"演出详情"板块展示(必填项，建议100字以上）</span>
          <div v-if="form.description" class="char-count">
            字符数: {{ form.description.length }} / 10000
          </div>
        </div>
        <div class="form-item">
          <label>定时上架</label>
          <input v-model="form.scheduledPublishTime" type="datetime-local" placeholder="留空表示立即上架" />
          <span class="field-hint">设置未来的时间点自动公开演出，留空则创建后立即对用户可见</span>
        </div>
        <div class="form-item">
          <label>演出海报 <span class="required">*</span></label>
          <div class="field-hint">建议尺寸：800x600px，支持JPG、PNG格式，大小不超过5MB</div>
          <div class="upload-area" @click="$refs.fileInput.click()">
            <input ref="fileInput" type="file" accept="image/*" style="display:none" @change="handleFileUpload" />
            <div v-if="!form.images" class="upload-placeholder">
              <span class="upload-icon">[图]</span>
              <span>点击上传演出海报</span>
              <span class="upload-hint">或拖拽图片到此处</span>
            </div>
            <div v-else class="upload-preview">
              <img :src="form.images" class="preview-image" />
              <button class="remove-btn" @click.stop="form.images = ''">✕ 移除</button>
            </div>
          </div>
          <div v-if="uploading" class="upload-status">
            <span class="loading-spinner"></span>
            上传中...
          </div>
        </div>
      </div>
      <template #footer>
        <Button @click="closeForm" variant="secondary">取消</Button>
        <Button @click="submitForm" variant="primary">保存</Button>
      </template>
    </Modal>
  </div>
</template>

<script>
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { api } from '../utils/api'
import Card from '../components/common/Card.vue'
import Button from '../components/common/Button.vue'
import Table from '../components/common/Table.vue'
import Modal from '../components/common/Modal.vue'
import MAP_CONFIG from '../config/map'

export default {
  name: 'Shows',
  components: { Card, Button, Table, Modal },
  setup() {
    const loading = ref(false)
    const keyword = ref('')
    const searchField = ref('name')
    const selectedTypeId = ref(0)
    const showList = ref([])
    const showCreate = ref(false)
    const showEdit = ref(false)

    const sortBy = ref('')
    const sortOrder = ref('')

    const pagination = reactive({
      current: 1,
      total: 0,
      pageSize: 20
    })

    const showTypes = ref([])

    const formGridRef = ref(null)
    const textareaRef = ref(null)

    const fetchShowTypes = async () => {
      try {
        const res = await api.get('/show-type/list')
        if (res.data && Array.isArray(res.data)) {
          showTypes.value = res.data
        }
      } catch (err) {
        console.error('获取演出类型失败:', err)
        showTypes.value = [
          { id: 1, name: '演唱会' },
          { id: 2, name: '话剧' },
          { id: 3, name: '音乐节' },
          { id: 4, name: '展览' },
          { id: 5, name: '体育赛事' }
        ]
      }
    }

    const form = reactive({
      id: null,
      name: '',
      typeId: null,
      address: '',
      area: '',
      x: '',
      y: '',
      startTime: '',
      endTime: '',
      images: '',
      description: '',
      scheduledPublishTime: ''
    })

    const geocodingLoading = ref(false)

    const geocodeAddress = async () => {
      if (!form.address || !form.address.trim()) {
        return
      }

      geocodingLoading.value = true

      try {
        const address = form.address.trim()
        console.log('[定位] 解析地址:', address)

        const response = await fetch(
          `https://restapi.amap.com/v3/geocode/geo?key=${MAP_CONFIG.amap.key}&address=${encodeURIComponent(address)}&output=JSON`
        )

        if (!response.ok) {
          throw new Error('服务暂不可用')
        }

        const data = await response.json()

        if (data.status === '1' && data.geocodes && data.geocodes.length > 0) {
          const location = data.geocodes[0].location
          const [lng, lat] = location.split(',')

          form.x = parseFloat(lng)
          form.y = parseFloat(lat)

          console.log('[定位] 成功:', { 地址: address, 坐标: `${lng}, ${lat}` })
        } else {
          throw new Error('未找到该地址，请尝试更详细的描述')
        }
      } catch (error) {
        console.error('[定位] 失败:', error)
        alert(`定位失败: ${error.message}`)
      } finally {
        geocodingLoading.value = false
      }
    }

    const clearCoordinates = () => {
      form.x = ''
      form.y = ''
      console.log('[定位] 已清除')
    }

    const uploading = ref(false)

    const handleFileUpload = async (event) => {
      const file = event.target.files[0]
      if (!file) return

      const allowedTypes = ['image/jpeg', 'image/jpg', 'image/png', 'image/gif', 'image/webp']

      if (!allowedTypes.includes(file.type)) {
        alert(`[错误] 不支持的文件格式：${file.type}\n\n允许的格式：JPG、PNG、GIF、WebP`)
        return
      }

      const maxSize = 5 * 1024 * 1024
      if (file.size > maxSize) {
        const sizeMB = (file.size / (1024 * 1024)).toFixed(2)
        alert(`[错误] 文件过大：${sizeMB}MB\n\n最大允许大小：5MB\n请压缩图片后重试`)
        return
      }

      const originalName = file.name
      const safeName = sanitizeFileName(originalName)
      if (safeName !== originalName) {
        console.warn('[文件上传] 文件名已清理:', originalName, '→', safeName)
      }

      const ext = getFileExtension(safeName).toLowerCase()
      const allowedExts = ['jpg', 'jpeg', 'png', 'gif', 'webp']
      if (!allowedExts.includes(ext)) {
        alert(`[错误] 不支持的文件扩展名：.${ext}\n\n允许的扩展名：.jpg, .jpeg, .png, .gif, .webp`)
        return
      }

      try {
        const isValidImage = await validateImageFile(file)
        if (!isValidImage.valid) {
          alert(`[错误] 文件验证失败：${isValidImage.reason}\n\n该文件可能不是有效的图片文件，或已被篡改。\n请使用正规图片文件。`)
          return
        }
      } catch (validationError) {
        console.error('[文件上传] 验证过程出错:', validationError)
        alert('[警告] 文件验证失败，请稍后重试或更换文件')
        return
      }

      uploading.value = true
      try {
        const formData = new FormData()
        formData.append('file', file, safeName)

        formData.append('originalName', originalName)
        formData.append('uploadType', 'show-poster')
        formData.append('timestamp', Date.now().toString())

        console.log('[文件上传] 开始上传:', {
          name: safeName,
          type: file.type,
          size: `${(file.size / 1024).toFixed(2)}KB`
        })

        // ✅ 使用封装的 api.uploadFile 方法（自动携带认证信息）
        const result = await api.uploadFile('/upload/blog', formData)

        if (result.success && result.data) {
          form.images = result.data
          console.log('[文件上传] 成功:', result.data)

          console.log(`[成功] 海报上传成功: ${safeName} (${(file.size / 1024).toFixed(2)}KB)`)
        } else {
          const errorMsg = result.errorMsg || result.message || '未知错误'
          throw new Error(errorMsg)
        }
      } catch (err) {
        console.error('[文件上传] 失败:', err)

        let userMessage = '上传失败'
        if (err.message.includes('网络')) {
          userMessage = '网络连接失败，请检查网络后重试'
        } else if (err.message.includes('超时')) {
          userMessage = '上传超时，请稍后重试'
        } else if (err.message.includes('服务器')) {
          userMessage = '服务器繁忙，请稍后重试'
        } else if (err.message) {
          userMessage = `上传失败: ${err.message}`
        }

        alert(`[错误] ${userMessage}`)
      } finally {
        uploading.value = false
      }
    }

    function sanitizeFileName(filename) {
      if (!filename) return 'unnamed.jpg'

      let name = filename.split(/[\\/]/).pop()

      name = name.replace(/[^a-zA-Z0-9\u4e00-\u9fa5._\-]/g, '_')

      if (name.startsWith('.') || name.toLowerCase() === 'thumbs.db') {
        name = '_' + name
      }

      if (name.length > 200) {
        const ext = getFileExtension(name)
        name = name.substring(0, 195 - ext.length) + '.' + ext
      }

      if (!name || name.trim() === '') {
        name = `upload_${Date.now()}.jpg`
      }

      return name
    }

    function getFileExtension(filename) {
      if (!filename || !filename.includes('.')) return ''
      const parts = filename.split('.')
      return parts[parts.length - 1].toLowerCase()
    }

    async function validateImageFile(file) {
      return new Promise((resolve) => {
        const reader = new FileReader()

        reader.onload = (e) => {
          try {
            const arr = new Uint8Array(e.target.result).subarray(0, 8)
            let header = ''

            for (let i = 0; i < arr.length; i++) {
              header += arr[i].toString(16)
            }

            console.log('[文件验证] Magic Number:', header)

            const magicNumbers = {
              'ffd8ff': { ext: 'jpg', valid: true },
              '89504e47': { ext: 'png', valid: true },
              '47494638': { ext: 'gif', valid: true },
              '52494646': { ext: 'webp', valid: true }
            }

            let isValid = false
            for (const [magic, info] of Object.entries(magicNumbers)) {
              if (header.toLowerCase().startsWith(magic)) {
                console.log(`[文件验证] 检测到${info.ext.toUpperCase()}格式`)
                isValid = true
                break
              }
            }

            if (!isValid) {
              resolve({
                valid: false,
                reason: `文件头不匹配任何支持的图片格式 (Header: ${header})`
              })
              return
            }

            resolve({ valid: true })
          } catch (error) {
            console.error('[文件验证] 解析错误:', error)
            resolve({
              valid: false,
              reason: '文件解析失败，可能已损坏'
            })
          }
        }

        reader.onerror = () => {
          resolve({
            valid: false,
            reason: '无法读取文件内容'
          })
        }

        reader.readAsArrayBuffer(file.slice(0, 8))
      })
    }

    const selectedIds = ref([])
    const batchLoading = ref(false)

    const columns = [
      { label: '', slot: 'checkbox', width: '50px' },
      { label: 'ID', prop: 'id', sortable: true, sortKey: 'id', width: '80px' },
      { label: '演出名称', prop: 'name', sortable: true, sortKey: 'name' },
      { label: '类型', prop: 'typeId', sortable: true, sortKey: 'typeId', width: '80px' },
      { label: '地点', prop: 'address', sortable: false },
      { label: '演出时间', slot: 'showTime', sortable: false, width: '180px' },
      { label: '状态', slot: 'status', sortable: true, sortKey: 'status', width: '80px' },
      { label: '创建时间', prop: 'createTime', sortable: true, sortKey: 'createTime', width: '160px' },
      { label: '更新时间', prop: 'updateTime', sortable: true, sortKey: 'updateTime', width: '160px' },
      { label: '操作', slot: 'actions', width: '180px' }
    ]

    const columnsWithCheckbox = computed(() => columns)

    const formatDateTime = (dateStr) => {
      if (!dateStr) return '-'
      try {
        const date = new Date(dateStr)
        if (isNaN(date.getTime())) return dateStr
        const month = String(date.getMonth() + 1).padStart(2, '0')
        const day = String(date.getDate()).padStart(2, '0')
        const hours = String(date.getHours()).padStart(2, '0')
        const minutes = String(date.getMinutes()).padStart(2, '0')
        return `${month}-${day} ${hours}:${minutes}`
      } catch (e) {
        return dateStr
      }
    }

    const fetchShows = async () => {
      loading.value = true
      try {
        // 分类、关键词（名称/ID）、排序自由组合查询（后端支持任意条件叠加）
        // 旧逻辑：选了分类时走 else 分支引用未定义的 params 变量 → 抛异常被 catch 吞掉 → 搜索失效
        const params = { current: pagination.current, pageSize: pagination.pageSize }
        if (keyword.value) {
          if (searchField.value === 'id') {
            params.id = keyword.value
          } else {
            params.name = keyword.value
          }
        }
        if (selectedTypeId.value !== 0) {
          params.typeId = selectedTypeId.value
        }
        if (sortBy.value) {
          params.sortBy = sortBy.value
          params.sortOrder = sortOrder.value
        }
        const res = await api.getShowList(params)

        if (res.data) {
          const data = res.data
          if (data.records) {
            showList.value = data.records
            pagination.total = Number(data.total) || 0
          } else if (Array.isArray(data)) {
            showList.value = data
            pagination.total = data.length
          } else {
            showList.value = []
            pagination.total = 0
          }
        }
      } catch (err) {
        console.error('获取演出列表失败:', err)
      } finally {
        loading.value = false
      }
    }

    const handleSearch = () => {
      // ID 搜索要求纯数字，避免传非数字导致后端 400
      if (keyword.value && searchField.value === 'id' && !/^\d+$/.test(keyword.value.trim())) {
        alert('按 ID 搜索请输入纯数字')
        return
      }
      pagination.current = 1
      fetchShows()
    }

    const resetFilters = () => {
      keyword.value = ''
      searchField.value = 'name'
      selectedTypeId.value = 0
      sortBy.value = ''
      sortOrder.value = ''
      pagination.current = 1
      fetchShows()
    }

    const handleSort = ({ sortKey, order }) => {
      sortBy.value = sortKey || ''
      sortOrder.value = order || ''
      pagination.current = 1
      fetchShows()
    }

    const handlePageChange = (page) => {
      pagination.current = page
      fetchShows()
    }

    const handlePageSizeChange = (size) => {
      pagination.pageSize = size
      pagination.current = 1
      fetchShows()
    }

    const editShow = (row) => {
      form.id = row.id
      form.name = row.name || ''
      form.typeId = row.typeId
      form.address = row.address || ''
      form.area = row.area || ''

      if (row.x) {
        form.x = row.x
      } else {
        form.x = ''
      }

      if (row.y) {
        form.y = row.y
      } else {
        form.y = ''
      }

      if (row.openTime) {
        const timeStr = String(row.openTime)
        form.startTime = timeStr.slice(0, 16)
      } else if (row.openHours && row.openHours.includes('~')) {
        const parts = row.openHours.split('~')
        form.startTime = parts[0] ? `${parts[0]}T00:00` : ''
      } else {
        form.startTime = ''
      }

      if (row.endTime) {
        const timeStr = String(row.endTime)
        form.endTime = timeStr.slice(0, 16)
      } else if (row.openHours && row.openHours.includes('~')) {
        const parts = row.openHours.split('~')
        form.endTime = parts[1] ? `${parts[1]}T23:59` : ''
      } else {
        form.endTime = ''
      }

      form.images = row.images || ''
      form.description = row.description || ''

      if (row.scheduledPublishTime) {
        const timeStr = String(row.scheduledPublishTime)
        form.scheduledPublishTime = timeStr.slice(0, 16)
      } else {
        form.scheduledPublishTime = ''
      }

      showEdit.value = true
    }

    const closeForm = () => {
      showCreate.value = false
      showEdit.value = false
      form.id = null
      form.name = ''
      form.typeId = null
      form.address = ''
      form.area = ''
      form.x = ''
      form.y = ''
      form.startTime = ''
      form.endTime = ''
      form.images = ''
      form.description = ''
      form.scheduledPublishTime = ''
    }

    const submitForm = async () => {
      const isEditMode = showEdit.value

      // 基础验证（新建和编辑都必须满足）
      if (!form.name || !form.name.trim()) {
        alert('[警告] 请输入演出名称')
        return
      }

      if (form.name.length > 200) {
        alert(`[警告] 演出名称过长(${form.name.length}字符)，请控制在200字符以内`)
        return
      }

      // 编辑模式下放宽部分验证（允许保留原有值）
      if (!isEditMode) {
        // 新建模式：严格验证
        if (!form.typeId) {
          alert('[警告] 请选择演出类型')
          return
        }

        if (!form.images) {
          alert('[警告] 请上传演出海报\n\n海报是演出的重要展示内容，必须上传')
          return
        }

        if (!form.description || !form.description.trim()) {
          alert('[警告] 请填写演出详情描述\n\n描述信息对用户了解演出非常重要')
          return
        }
      } else {
        // 编辑模式：宽松验证（只检查是否为空，不强制要求）
        console.log('[Shows] 编辑模式 - 放宽非关键字段验证')

        if (!form.typeId) {
          console.warn('[Shows] 编辑时缺少演出类型，使用默认值或保留原值')
        }

        if (!form.images) {
          console.warn('[Shows] 编辑时缺少海报URL，将清空海报')
        }
      }

      // 时间验证（两种模式都需要）
      if (!form.startTime || !form.endTime) {
        alert('[警告] 请填写完整的演出时间范围\n\n需要提供开始时间和结束时间')
        return
      }

      const startTime = new Date(form.startTime)
      const endTime = new Date(form.endTime)

      if (isNaN(startTime.getTime()) || isNaN(endTime.getTime())) {
        alert('[警告] 时间格式无效')
        return
      }

      if (startTime >= endTime) {
        alert(`[警告] 结束时间必须晚于开始时间\n\n开始时间: ${form.startTime}\n结束时间: ${form.endTime}`)
        return
      }

      if (!form.description || !form.description.trim()) {
        alert('[警告] 请填写演出详情描述\n\n描述信息对用户了解演出非常重要')
        return
      }

      if (form.description.length > 10000) {
        alert(`[警告] 描述过长(${form.description.length}字符)，请控制在10000字符以内\n\n建议精简内容或分段展示`)
        return
      }

      const sanitizeInput = (input, maxLength = 1000) => {
        if (typeof input !== 'string') return ''

        let sanitized = input.trim()

        if (sanitized.length > maxLength) {
          console.warn(`[Shows] 输入超长，已截断: ${sanitized.length} → ${maxLength}`)
          sanitized = sanitized.substring(0, maxLength)
        }

        sanitized = sanitized
          .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
          .replace(/\s*on\w+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, '')
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/"/g, '&quot;')
          .replace(/'/g, '&#039;')

        sanitized = sanitized.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, '')

        return sanitized
      }

      let openHours = null
      if (form.startTime && form.endTime) {
        const startDate = new Date(form.startTime)
        const endDate = new Date(form.endTime)
        const formatDate = (date) => {
          const year = date.getFullYear()
          const month = String(date.getMonth() + 1).padStart(2, '0')
          const day = String(date.getDate()).padStart(2, '0')
          return `${year}-${month}-${day}`
        }
        openHours = `${formatDate(startDate)}~${formatDate(endDate)}`
      }

      const startTimeDate = new Date(form.startTime)
      const endTimeDate = new Date(form.endTime)

      const formatLocalDateTime = (date) => {
        const year = date.getFullYear()
        const month = String(date.getMonth() + 1).padStart(2, '0')
        const day = String(date.getDate()).padStart(2, '0')
        const hours = String(date.getHours()).padStart(2, '0')
        const minutes = String(date.getMinutes()).padStart(2, '0')
        const seconds = String(date.getSeconds()).padStart(2, '0')
        return `${year}-${month}-${day}T${hours}:${minutes}:${seconds}`
      }

      console.log('[Shows] 📍 坐标检查 - form.x:', form.x, 'type:', typeof form.x, ', form.y:', form.y, 'type:', typeof form.y)
      console.log('[Shows] 📍 坐标检查 - form.x 是否为空:', !form.x, ', form.y 是否为空:', !form.y)

      if (!form.x || !form.y) {
        console.warn('[Shows] ⚠️ 缺少经纬度坐标，使用默认值(0,0)')
        console.warn('[Shows] ⚠️ 原因分析:')
        console.warn('  - form.x 值:', form.x, '(期望: 数字或数字字符串)')
        console.warn('  - form.y 值:', form.y, '(期望: 数字或数字字符串)')
        console.warn('  - 可能原因1: 定位成功但表单数据未更新')
        console.warn('  - 可能原因2: 表单重置时清空了坐标')
        console.warn('  - 可能原因3: reactive 对象响应式丢失')
      }

      const x = parseFloat(form.x) || 0
      const y = parseFloat(form.y) || 0

      console.log('[Shows] 📍 解析后坐标 - x:', x, '(', typeof x, '), y:', y, '(', typeof y, ')')

      if (isNaN(x) || isNaN(y)) {
        console.error('[Shows] ❌ 坐标格式无效，使用默认值(0,0)')
        console.error('  - parseFloat(form.x):', parseFloat(form.x))
        console.error('  - parseFloat(form.y):', parseFloat(form.y))
      } else if (x === 0 && y === 0) {
        console.warn('[Shows] ⚠️ 使用了默认坐标(0,0)，可能导致LBS位置查询异常')
      } else {
        console.log('[Shows] ✅ 坐标有效 - x:', x, ', y:', y)
      }

      if (x < -180 || x > 180) {
        alert('[警告] 经度超出范围（-180 ~ 180），请重新定位')
        return
      }

      if (y < -90 || y > 90) {
        alert('[警告] 纬度超出范围（-90 ~ 90），请重新定位')
        return
      }

      const openTimeStr = formatLocalDateTime(startTimeDate)
      const endTimeStr = formatLocalDateTime(endTimeDate)

      console.log('[Shows] ⏰ 时间格式化（本地时间）:')
      console.log('  - startTime 原始值:', form.startTime)
      console.log('  - openTime 提交值:', openTimeStr)
      console.log('  - endTime 原始值:', form.endTime)
      console.log('  - endTime 提交值:', endTimeStr)

      const data = {
        name: sanitizeInput(form.name, 200),
        typeId: Number(form.typeId),
        address: sanitizeInput(form.address, 500),
        area: sanitizeInput(form.area, 100),
        openHours: openHours,
        openTime: openTimeStr,
        images: form.images || '',
        description: sanitizeInput(form.description, 10000),
        x: x,
        y: y,
        endTime: endTimeStr
      }

      if (form.scheduledPublishTime) {
        const scheduledDate = new Date(form.scheduledPublishTime)
        const now = new Date()

        if (isNaN(scheduledDate.getTime())) {
          alert('[警告] 定时发布时间格式无效')
          return
        }

        if (scheduledDate <= now) {
          alert(`[警告] 定时发布时间不能是过去的时间\n\n当前时间: ${now.toLocaleString()}\n设定时间: ${scheduledDate.toLocaleString()}`)
          return
        }

        // ✅ 使用本地时间格式（ISO 8601 无时区后缀），确保后端正确解析为服务器本地时间
        const year = scheduledDate.getFullYear()
        const month = String(scheduledDate.getMonth() + 1).padStart(2, '0')
        const day = String(scheduledDate.getDate()).padStart(2, '0')
        const hours = String(scheduledDate.getHours()).padStart(2, '0')
        const minutes = String(scheduledDate.getMinutes()).padStart(2, '0')

        data.scheduledPublishTime = `${year}-${month}-${day}T${hours}:${minutes}:00`
        data.status = 0

        console.log(`[Shows] 定时发布时间 - 本地时间: ${form.scheduledPublishTime}, 提交值: ${data.scheduledPublishTime}`)
      } else {
        data.status = 1
      }

      console.log('[Shows] 提交数据:', JSON.stringify(data, null, 2))

      try {
        let response

        if (showEdit.value) {
          data.id = form.id
          console.log('[Shows] 更新演出:', data.id)
          response = await api.updateShow(data)
          console.log('[Shows] 更新成功')

          alert(`[成功] 演出更新成功！\n\n演出名称: ${data.name}\n时间: ${openHours || '未设置'}\n地址: ${data.address || '未设置'}`)
        } else {
          console.log('[Shows] 创建新演出')
          response = await api.createShow(data)
          console.log('[Shows] 创建成功')

          alert(`[成功] 演出创建成功！\n\n演出名称: ${data.name}\n类型: ${getTypeName(data.typeId)}\n时间: ${openHours || '未设置'}\n状态: ${data.status === 0 ? '定时发布' : '已上架'}`)
        }

        closeForm()
        await fetchShows()

      } catch (err) {
        console.error('[错误] [Shows] 操作失败:', err.response?.data || err.message)

        let errorMsg = '操作失败'

        if (err.response?.data) {
          const responseData = err.response.data
            errorMsg =
            responseData.errorMsg ||
            responseData.msg ||
            responseData.message ||
            responseData.error ||
            errorMsg

          if (err.response.status === 409) {
            errorMsg = '数据冲突：该演出名称可能已存在'
          } else if (err.response.status === 413) {
            errorMsg = '上传文件过大'
          } else if (err.response.status === 403) {
            errorMsg = '权限不足：您没有执行此操作的权限'
          } else if (err.response.status === 400) {
            errorMsg = `参数错误: ${errorMsg}`
          }
        } else if (err.message) {
          errorMsg = err.message.includes('网络') ? '网络连接失败，请检查网络后重试' : errorMsg
        }

        alert(`[错误] ${showEdit.value ? '更新' : '创建'}失败！\n\n错误原因: ${errorMsg}\n\n请检查输入信息后重试`)
      }
    }

    function getTypeName(typeId) {
      const type = showTypes.value.find(t => t.id === Number(typeId))
      return type ? type.name : '未知类型'
    }

    const toggleShowStatus = async (row, online) => {
      const action = online ? '上架' : '下架'
      if (!confirm(`确定要${action}演出「${row.name}」吗？`)) return
      try {
        const endpoint = online
          ? `/admin/show/${row.id}/online`
          : `/admin/show/${row.id}/offline`
        await api.put(endpoint)
        alert(`演出已${action}`)
        await fetchShows()
      } catch (err) {
        alert('操作失败: ' + (err.response?.data?.msg || err.message))
      }
    }

    const deleteShow = async (row) => {
      if (!row || !row.id || row.id === 'null' || row.id === 'undefined') {
        alert('错误：演出ID无效，无法删除')
        console.error('[删除演出] ID无效:', row?.id)
        return
      }

      if (!confirm(`确定要删除演出"${row.name}"吗？此操作不可恢复！`)) {
        return
      }

      try {
        console.log('删除演出, ID:', row.id)
        const res = await api.delete(`/admin/show/${row.id}`)
        console.log('删除演出响应:', res)
        alert('演出删除成功')
        await fetchShows()
      } catch (err) {
        console.error('删除演出失败:', err)
        alert('删除失败: ' + (err.response?.data?.msg || err.message || '未知错误'))
      }
    }

    const clearSelection = () => {
      selectedIds.value = []
    }

    const handleBatchOnline = async () => {
      if (selectedIds.value.length === 0) {
        alert('请先选择要上架的演出')
        return
      }

      if (!confirm(`确定要上架选中的 ${selectedIds.value.length} 个演出吗？`)) {
        return
      }

      batchLoading.value = true
      try {
        const result = await api.batchOperationShows(selectedIds.value, 'online')

        if (result.data) {
          const { successCount, failCount } = result.data

          if (failCount > 0) {
            alert(`批量上架完成：成功 ${successCount} 个，失败 ${failCount} 个`)
          } else {
            alert(`成功上架 ${successCount} 个演出`)
          }
        }

        clearSelection()
        await fetchShows()
      } catch (err) {
        console.error('批量上架失败:', err)
        alert('批量上架失败: ' + (err.message || '未知错误'))
      } finally {
        batchLoading.value = false
      }
    }

    const handleBatchOffline = async () => {
      if (selectedIds.value.length === 0) {
        alert('请先选择要下架的演出')
        return
      }

      if (!confirm(`确定要下架选中的 ${selectedIds.value.length} 个演出吗？`)) {
        return
      }

      batchLoading.value = true
      try {
        const result = await api.batchOperationShows(selectedIds.value, 'offline')

        if (result.data) {
          const { successCount, failCount } = result.data

          if (failCount > 0) {
            alert(`批量下架完成：成功 ${successCount} 个，失败 ${failCount} 个`)
          } else {
            alert(`成功下架 ${successCount} 个演出`)
          }
        }

        clearSelection()
        await fetchShows()
      } catch (err) {
        console.error('批量下架失败:', err)
        alert('批量下架失败: ' + (err.message || '未知错误'))
      } finally {
        batchLoading.value = false
      }
    }

    const handleBatchDelete = async () => {
      if (selectedIds.value.length === 0) {
        alert('请先选择要删除的演出')
        return
      }

      if (!confirm(`确定要删除选中的 ${selectedIds.value.length} 个演出吗？此操作不可恢复！`)) {
        return
      }

      batchLoading.value = true
      try {
        const result = await api.batchOperationShows(selectedIds.value, 'delete')

        if (result.data) {
          const { successCount, failCount } = result.data

          if (failCount > 0) {
            alert(`批量删除完成：成功 ${successCount} 个，失败 ${failCount} 个`)
          } else {
            alert(`成功删除 ${successCount} 个演出`)
          }
        }

        clearSelection()
        await fetchShows()
      } catch (err) {
        console.error('批量删除失败:', err)
        alert('批量删除失败: ' + (err.message || '未知错误'))
      } finally {
        batchLoading.value = false
      }
    }

    onMounted(() => {
      fetchShowTypes()
      fetchShows()
    })

    return {
      loading,
      keyword,
      searchField,
      selectedTypeId,
      showList,
      showCreate,
      showEdit,
      uploading,
      pagination,
      columns,
      columnsWithCheckbox,
      showTypes,
      form,
      sortBy,
      sortOrder,
      selectedIds,
      batchLoading,
      geocodingLoading,
      formGridRef,
      textareaRef,
      formatDateTime,
      handleSearch,
      resetFilters,
      handleSort,
      handlePageChange,
      handlePageSizeChange,
      editShow,
      deleteShow,
      closeForm,
      submitForm,
      toggleShowStatus,
      handleFileUpload,
      clearSelection,
      handleBatchOnline,
      handleBatchOffline,
      handleBatchDelete,
      geocodeAddress,
      clearCoordinates
    }
  }
}
</script>

<style scoped>
.page-container {
  padding: var(--space-6);
}

.page-header {
  margin-bottom: var(--space-6);
}

.page-header h1 {
  font-family: var(--font-heading);
  font-size: var(--font-xl);
  font-weight: 600;
  margin: 0 0 var(--space-1) 0;
  color: var(--color-text);
}

.subtitle {
  color: var(--color-text-light);
  margin: 0;
  font-size: var(--font-sm);
}

.filter-card {
  margin-bottom: var(--space-6);
}

.batch-action-bar {
  margin-bottom: var(--space-4);
  background-color: rgba(166, 186, 174, 0.08);
  border: 1px solid rgba(166, 186, 174, 0.25);
}

.batch-action-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-4);
}

.selection-info {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.selection-count {
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.selection-count strong {
  color: var(--color-primary);
  font-weight: 600;
}

.batch-actions {
  display: flex;
  gap: var(--space-2);
}

.row-checkbox {
  width: 16px;
  height: 16px;
  cursor: pointer;
  accent-color: var(--color-primary);
}

.filter-row {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.search-box {
  display: flex;
  gap: var(--space-2);
}

.search-select,
.type-select,
.search-box input {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  background: var(--color-white);
  transition: all var(--transition-fast);
  outline: none;
}

.search-select:focus,
.type-select:focus,
.search-box input:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(150, 116, 98, 0.08);
}

.type-select {
  min-width: 120px;
}

.search-box input {
  width: 200px;
}

.add-btn {
  margin-left: auto;
}

.status-tag {
  display: inline-block;
  padding: var(--space-1) calc(var(--space-2) + var(--space-1));
  border-radius: var(--radius-md);
  font-size: var(--font-xs);
  font-weight: 500;
}

.status-online {
  background-color: rgba(166, 186, 174, 0.25);
  color: var(--color-text);
  border: 1px solid rgba(166, 186, 174, 0.4);
}

.status-offline {
  background-color: rgba(196, 135, 123, 0.15);
  color: var(--color-secondary);
  border: 1px solid rgba(196, 135, 123, 0.3);
}

.show-time-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: var(--font-sm);
  line-height: 1.4;
}

.time-start {
  color: var(--color-text);
  font-weight: 500;
}

.time-end {
  color: var(--color-text-light);
  font-size: var(--font-xs, 11px);
}

.action-buttons {
  display: flex;
  gap: var(--space-2);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-4);
  width: 100%;
  max-width: 100%;
  overflow-x: hidden;
  box-sizing: border-box;
}

.form-item-full {
  grid-column: 1 / -1;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  min-width: 0;
  overflow-x: hidden;
}

.form-item label {
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-text);
}

.required {
  color: var(--color-danger, #c4877b);
}

.form-item input,
.form-item select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  width: 100%;
  box-sizing: border-box;
  transition: all var(--transition-fast);
  outline: none;
  background-color: var(--color-white);
}

.form-item input:focus,
.form-item select:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(150, 116, 98, 0.08);
}

.date-range {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  max-width: 600px;
}

.date-separator {
  font-weight: 500;
  color: var(--color-text);
  white-space: nowrap;
}

.date-range input[type="date"] {
  flex: 1;
  padding: 12px 10px;
  font-size: var(--font-base);
  min-width: 0;
}

.datetime-picker-inline {
  display: flex;
  align-items: center;
  gap: 16px;
  width: 100%;
  max-width: 400px;
}

.datetime-picker-inline input[type="datetime-local"] {
  flex: 1;
  padding: 12px 10px;
  font-size: var(--font-base);
}

.upload-area {
  width: 100%;
  min-height: 200px;
  border: 3px dashed var(--color-border);
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all var(--transition-base);
  position: relative;
  background-color: var(--color-bg-card);
}

.upload-area:hover {
  border-color: var(--color-primary);
  background-color: var(--color-bg-alt);
}

.upload-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  color: var(--color-text-light);
  font-size: var(--font-sm);
}

.upload-icon {
  font-size: 48px;
  opacity: 0.6;
}

.upload-hint {
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

.upload-preview {
  position: relative;
  width: 100%;
  padding: 12px;
}

.preview-image {
  max-width: 100%;
  max-height: 300px;
  object-fit: contain;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
}

.remove-btn {
  position: absolute;
  top: var(--space-3);
  right: var(--space-3);
  padding: var(--space-1) var(--space-3);
  background-color: var(--color-danger, #c4877b);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--font-xs);
  transition: all var(--transition-fast);
}

.remove-btn:hover {
  opacity: 0.85;
  transform: translateY(-1px);
}

.datetime-picker {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.datetime-picker input[type="datetime-local"] {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  width: 100%;
  box-sizing: border-box;
  outline: none;
  transition: all var(--transition-fast);
}

.datetime-picker input[type="datetime-local"]:focus {
  border-color: var(--color-primary);
}

.field-hint {
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

.preview-image {
  max-width: 100%;
  max-height: 200px;
  object-fit: contain;
  border-radius: var(--radius-sm);
}

.upload-status {
  margin-top: var(--space-2);
  padding: var(--space-2) var(--space-3);
  font-size: var(--font-sm);
  color: var(--color-primary);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.loading-spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid var(--color-primary);
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.char-count {
  font-size: var(--font-xs);
  color: var(--color-text-light);
  margin-top: var(--space-1);
  text-align: right;
}

.form-item textarea {
  resize: vertical;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  overflow-x: hidden;
  word-wrap: break-word;
  overflow-wrap: break-word;
}

.form-item-full {
  grid-column: 1 / -1;
}

.form-item-full textarea {
  width: 100%;
  min-height: 120px;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  line-height: 1.5;
  font-family: var(--font-body);
  outline: none;
  transition: all var(--transition-fast);
}

.form-item-full textarea:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(150, 116, 98, 0.08);
}

@media (max-width: 768px) {
  .form-grid {
    grid-template-columns: 1fr;
    gap: var(--space-3);
    padding: var(--space-3);
  }

  .form-item-full {
    grid-column: 1;
  }

  .date-range {
    flex-direction: column;
    align-items: stretch;
    gap: var(--space-2);
    max-width: 100%;
  }

  .date-separator {
    text-align: center;
  }

  .date-range input[type="date"] {
    width: 100%;
  }

  .datetime-picker-inline {
    flex-direction: column;
    align-items: stretch;
    gap: var(--space-2);
    max-width: 100%;
  }

  .upload-area {
    min-height: 160px;
  }

  .preview-image {
    max-height: 200px;
  }
}

@media (min-width: 1200px) {
  .form-grid {
    max-width: 900px;
    margin: 0 auto;
  }
}

/* ===== 地址定位功能（管理端设计规范） ===== */

.location-wrapper {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.address-main {
  display: flex;
  gap: var(--space-2);
  align-items: stretch;
}

.address-field {
  flex: 1;
  padding: var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: var(--font-base);
  font-family: var(--font-body);
  background-color: var(--color-bg-card);
  color: var(--color-text);
  transition: all 0.3s ease-out;
  outline: none;
  min-width: 0;
}

.address-field::placeholder {
  color: var(--color-text-light);
  opacity: 0.65;
}

.address-field:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px rgba(150, 116, 98, 0.12);
}

.address-field.has-location {
  border-color: var(--color-accent);
  background-color: rgba(166, 186, 174, 0.06);
}

.locate-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 var(--space-5);
  min-width: 90px;
  height: auto;
  font-size: var(--font-sm);
  font-weight: 600;
  font-family: var(--font-body);
  letter-spacing: 0.02em;
  white-space: nowrap;
  border-radius: var(--radius-md);
  background-color: var(--color-primary);
  color: #ffffff;
  border: none;
  cursor: pointer;
  transition: all 0.3s ease-out;
  position: relative;
  overflow: hidden;
}

.locate-btn:hover:not(:disabled):not(.loading):not(.located) {
  background-color: var(--color-secondary);
  transform: translateY(-1px);
  box-shadow: var(--shadow-md);
}

.locate-btn:active:not(:disabled) {
  transform: translateY(0);
}

.locate-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  background-color: var(--color-text-light);
}

.locate-btn.loading {
  background-color: var(--color-accent);
}

.locate-btn.located {
  background-color: var(--color-accent);
  cursor: default;
}

.spinner {
  display: inline-block;
  width: 16px;
  height: 16px;
  border: 3px solid rgba(255, 255, 255, 0.35);
  border-top-color: #ffffff;
  border-radius: 50%;
  animation: spin 0.75s linear infinite;
}

.icon-check {
  font-weight: 700;
  font-size: var(--font-base);
}

.icon-locate {
  font-weight: 500;
}

.location-status {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background-color: rgba(166, 186, 174, 0.12);
  border-radius: var(--radius-sm);
  border-left: 3px solid var(--color-accent);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: var(--color-accent);
  animation: pulse 2s ease-in-out infinite;
}

.status-text {
  font-size: var(--font-sm);
  color: var(--color-text);
  font-weight: 500;
  flex: 1;
}

.reset-link {
  font-size: var(--font-xs);
  color: var(--color-primary);
  background: none;
  border: none;
  cursor: pointer;
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  font-weight: 600;
  text-decoration: underline;
  transition: all 0.2s ease;
}

.reset-link:hover {
  background-color: rgba(150, 116, 98, 0.1);
  color: var(--color-secondary);
}

.location-hint {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background-color: rgba(236, 224, 202, 0.4);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.hint-icon {
  font-size: var(--font-base);
  line-height: 1;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

/* ===== 响应式适配 ===== */

@media (max-width: 768px) {
  .address-main {
    flex-direction: column;
  }

  .locate-btn {
    width: 100%;
    min-height: 44px;
  }

  .location-status,
  .location-hint {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-1);
  }

  .reset-link {
    align-self: flex-end;
    margin-top: calc(var(--space-1) * -1);
  }
}
</style>
