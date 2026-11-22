 <template>
  <div class="page-container">
    <div class="page-header">
      <h1>订单管理</h1>
      <p class="subtitle">管理所有演出订单</p>
    </div>

    <!-- 搜索和过滤 -->
    <Card class="filter-card">
      <div class="filter-row">
        <div class="search-box">
          <select v-model="searchField" class="search-select">
            <option value="keyword">订单ID</option>
            <option value="userName">用户名</option>
          </select>
          <input v-model="keyword" type="text" :placeholder="'搜索' + (searchField === 'userName' ? '用户名' : '订单ID') + '...'" @keyup.enter="handleSearch" />
          <Button @click="handleSearch" variant="primary">搜索</Button>
        </div>
        <div class="status-filter">
          <label>状态筛选：</label>
          <select v-model="statusFilter" @change="handleSearch">
            <option :value="null">全部</option>
            <option :value="0">预占</option>
            <option :value="2">已支付</option>
            <option :value="3">已核销</option>
            <option :value="4">已取消</option>
            <option :value="5">已退款</option>
            <option :value="6">申请退款中</option>
          </select>
        </div>
        <Button @click="resetFilters" variant="secondary">重置</Button>
      </div>
    </Card>

    <!-- 批量操作栏（选中退款申请时显示） -->
    <Card v-if="selectedOrderIds.length > 0" class="batch-action-bar">
      <div class="batch-action-content">
        <div class="selection-info">
          <span class="selection-count">已选择 <strong>{{ selectedOrderIds.length }}</strong> 个退款申请</span>
          <Button variant="text" size="sm" @click="clearSelection">清空选择</Button>
        </div>
        <div class="batch-actions">
          <Button
            variant="primary"
            size="sm"
            :disabled="batchLoading"
            @click="handleBatchApproveRefund"
          >
            批量审批通过
          </Button>
          <Button
            variant="danger"
            size="sm"
            :disabled="batchLoading"
            @click="handleBatchRejectRefund"
          >
            批量拒绝退款
          </Button>
        </div>
      </div>
    </Card>

    <!-- 数据表格 -->
    <Card>
      <Table
        :columns="columnsWithCheckbox"
        :data="orderList"
        :loading="loading"
        :pagination="pagination"
        @sort="handleSort"
        @page-change="handlePageChange"
      >
        <template #checkbox="{ row }">
          <!-- 只对申请退款的订单显示复选框 -->
          <input
            v-if="row.status === 6"
            type="checkbox"
            :value="row.id"
            v-model="selectedOrderIds"
            class="row-checkbox"
          />
        </template>
        <template #status="{ row }">
          <span :class="['status-tag', 'status-' + row.status]">
            {{ statusMap[row.status] || '未知' }}
          </span>
        </template>
        <template #actions="{ row }">
          <div class="action-buttons">
            <Button v-if="row.status === 6" @click="handleApproveRefund(row)" variant="secondary" size="sm">
              同意退款
            </Button>
            <Button v-if="row.status === 6" @click="handleRejectRefund(row)" variant="danger" size="sm">
              拒绝退款
            </Button>
            <Button @click="viewDetail(row)" variant="primary" size="sm">
              详情
            </Button>
          </div>
        </template>
      </Table>
    </Card>

    <!-- 订单详情弹窗 -->
    <Modal :visible="showDetail" @close="showDetail = false" title="订单详情">
      <div class="detail-grid">
        <div class="detail-item"><label>订单ID</label><span>{{ currentOrder.id }}</span></div>
        <div class="detail-item"><label>用户</label><span>{{ currentOrder.userName }}</span></div>
        <div class="detail-item"><label>票种</label><span>{{ currentOrder.ticketTitle }}</span></div>
        <div class="detail-item"><label>演出</label><span>{{ currentOrder.showName }}</span></div>
        <div class="detail-item"><label>支付金额</label><span>¥{{ formatPrice(currentOrder.payValue) }}</span></div>
        <div class="detail-item"><label>实付金额</label><span>¥{{ formatPrice(currentOrder.actualValue) }}</span></div>
        <div class="detail-item"><label>支付方式</label><span>{{ currentOrder.payType === 1 ? '模拟支付' : '未知' }}</span></div>
        <div class="detail-item"><label>状态</label><span :class="['status-tag', 'status-' + currentOrder.status]">{{ statusMap[currentOrder.status] }}</span></div>
        <div class="detail-item"><label>创建时间</label><span>{{ formatTime(currentOrder.createTime) }}</span></div>
        <div class="detail-item"><label>支付时间</label><span>{{ formatTime(currentOrder.payTime) }}</span></div>
        <div class="detail-item"><label>核销时间</label><span>{{ formatTime(currentOrder.useTime) }}</span></div>
        <div class="detail-item"><label>退款时间</label><span>{{ formatTime(currentOrder.refundTime) }}</span></div>
        <div v-if="currentOrder.verificationCode && currentOrder.status >= 2" class="detail-item detail-verification-code">
          <label>核销码</label>
          <span class="verification-code-value">{{ currentOrder.verificationCode }}</span>
          <button
            v-if="currentOrder.verificationCode"
            class="copy-btn"
            @click="copyVerificationCode(currentOrder.verificationCode)"
            title="复制核销码"
          >
            复制
          </button>
        </div>
      </div>
    </Modal>
  </div>
</template>

<script>
import { ref, reactive, computed, onMounted } from 'vue'
import { api } from '../utils/api'
import Card from '../components/common/Card.vue'
import Button from '../components/common/Button.vue'
import Table from '../components/common/Table.vue'
import Modal from '../components/common/Modal.vue'

export default {
  name: 'Orders',
  components: { Card, Button, Table, Modal },
  setup() {
    const loading = ref(false)
    const keyword = ref('')
    const searchField = ref('keyword')
    const statusFilter = ref(null)
    const showDetail = ref(false)
    const currentOrder = ref({})
    const orderList = ref([])

    const sortBy = ref('')
    const sortOrder = ref('')

    const pagination = reactive({
      current: 1,
      total: 0,
      pageSize: 20
    })

    const statusMap = {
      0: '待支付',
      1: '未支付(秒杀预占)',
      2: '已支付',
      3: '已核销',
      4: '已取消',
      5: '已退款',
      6: '申请退款中',
      7: '已拒绝'
    }

    // ==================== 批量操作相关状态 ====================
    const selectedOrderIds = ref([])
    const batchLoading = ref(false)

    // 带复选框的列配置（在第一列添加checkbox）
    const columnsWithCheckbox = computed(() => {
      return [
        { label: '', slot: 'checkbox', width: '50px' },
        ...columns
      ]
    })

    /**
     * 清空选择
     */
    const clearSelection = () => {
      selectedOrderIds.value = []
    }

    /**
     * 单条拒绝退款
     */
    const handleRejectRefund = async (row) => {
      console.log('[管理退款] 开始拒绝，订单ID:', row.id, '当前状态:', row.status)

      if (row.status !== 6) {
        const currentStatusText = statusMapExtended[row.status] || `未知状态(${row.status})`
        alert(`[警告] 无法拒绝退款！\n\n` +
          `订单号：#${row.id}\n` +
          `当前状态：${currentStatusText}\n\n` +
          `错误原因：只有"申请退款中"(status=6)的订单才能拒绝退款。\n` +
          `提示：请刷新页面获取最新数据。`
        )
        return
      }

      if (!confirm(`确定要拒绝订单 #${row.id} 的退款申请吗？\n\n用户：${row.userName || '未知'}\n金额：¥${formatPrice(row.payValue)}`)) {
        return
      }

      try {
        const result = await api.batchOperationOrders([row.id], 'reject-refund')

        if (result.data) {
          const { successCount, failCount } = result.data

          if (successCount > 0) {
            alert(`成功拒绝退款\n订单号：#${row.id}`)
          } else {
            alert(`拒绝退款失败\n订单号：#${row.id}`)
          }
        }

        await fetchOrders()

      } catch (err) {
        console.error('单条拒绝失败:', err)
        alert(`拒绝退款异常\n${err.message || '未知错误'}`)
      }
    }

    /**
     * 批量审批通过退款
     */
    const handleBatchApproveRefund = async () => {
      if (selectedOrderIds.value.length === 0) {
        alert('请先选择要处理的退款申请')
        return
      }

      if (!confirm(`确定要批量通过 ${selectedOrderIds.value.length} 个退款申请吗？`)) {
        return
      }

      batchLoading.value = true
      try {
        const result = await api.batchOperationOrders(selectedOrderIds.value, 'approve_refund')

        if (result.data) {
          const { successCount, failCount } = result.data

          if (failCount > 0) {
            alert(`批量审批完成：成功 ${successCount} 个，失败 ${failCount} 个`)
          } else {
            alert(`成功审批通过 ${successCount} 个退款申请`)
          }
        }

        clearSelection()
        await fetchOrders()

      } catch (err) {
        console.error('批量审批失败:', err)
        alert('批量审批失败: ' + (err.message || '未知错误'))
      } finally {
        batchLoading.value = false
      }
    }

    /**
     * 批量拒绝退款
     */
    const handleBatchRejectRefund = async () => {
      if (selectedOrderIds.value.length === 0) {
        alert('请先选择要拒绝的退款申请')
        return
      }

      if (!confirm(`确定要批量拒绝 ${selectedOrderIds.value.length} 个退款申请吗？`)) {
        return
      }

      batchLoading.value = true
      try {
        const result = await api.batchOperationOrders(selectedOrderIds.value, 'reject-refund')

        if (result.data) {
          const { successCount, failCount } = result.data

          if (failCount > 0) {
            alert(`批量拒绝完成：成功 ${successCount} 个，失败 ${failCount} 个`)
          } else {
            alert(`成功拒绝 ${successCount} 个退款申请`)
          }
        }

        clearSelection()
        await fetchOrders()

      } catch (err) {
        console.error('批量拒绝失败:', err)
        alert('批量拒绝失败: ' + (err.message || '未知错误'))
      } finally {
        batchLoading.value = false
      }
    }

    // ==================== 表格列配置 ====================
    const columns = [
      { label: '订单ID', prop: 'id', sortable: true, sortKey: 'id' },
      { label: '用户名', prop: 'userName', sortable: true, sortKey: 'userId' },
      { label: '票种', prop: 'ticketTitle', sortable: false, width: '120px' },
      { label: '演出', prop: 'showName', sortable: false },
      { label: '金额', prop: 'payValue', sortable: false, width: '100px' },
      { label: '状态', prop: 'status', slot: 'status', sortable: true, sortKey: 'status', width: '130px' },
      { label: '核销码', prop: 'verificationCode', sortable: false, width: '120px' },
      { label: '创建时间', prop: 'createTime', sortable: true, sortKey: 'createTime', width: '160px' },
      { label: '支付时间', prop: 'payTime', sortable: true, sortKey: 'payTime', width: '160px' },
      { label: '操作', slot: 'actions', width: '150px' }
    ]

    const fetchOrders = async (options = {}) => {
      const { forceRefresh = false, _timestamp = null } = options

      loading.value = true
      try {
        const params = {
          current: pagination.current,
          pageSize: pagination.pageSize,
          status: statusFilter.value
        }

        // ✅ 强制刷新：添加时间戳破坏缓存
        if (forceRefresh || _timestamp) {
          params._t = _timestamp || Date.now()
          params._force = true  // 告诉后端强制刷新
        }

        if (keyword.value) {
          if (searchField.value === 'userName') {
            params.userName = keyword.value
          } else {
            params.keyword = keyword.value
          }
        }
        if (sortBy.value) {
          params.sortBy = sortBy.value
          params.sortOrder = sortOrder.value
        }

        console.log('[fetchOrders] 请求参数:', { ...params, _force: !!params._force })

        const res = await api.getOrderList(params)
        if (res.data && res.data.records) {
          orderList.value = res.data.records.map(item => ({
            ...item,
            createTime: formatTime(item.createTime),
            payTime: formatTime(item.payTime),
            useTime: formatTime(item.useTime),
            refundTime: formatTime(item.refundTime)
          }))
          pagination.total = res.data.total || 0
        } else if (res.data && Array.isArray(res.data)) {
          orderList.value = res.data.map(item => ({
            ...item,
            createTime: formatTime(item.createTime),
            payTime: formatTime(item.payTime)
          }))
          pagination.total = res.total || 0
        } else {
          orderList.value = []
          pagination.total = 0
        }
      } catch (err) {
        console.error('获取订单列表失败:', err)
      } finally {
        loading.value = false
      }
    }

    const handleSearch = () => {
      pagination.current = 1
      fetchOrders()
    }

    const resetFilters = () => {
      keyword.value = ''
      searchField.value = 'keyword'
      statusFilter.value = null
      sortBy.value = ''
      sortOrder.value = ''
      pagination.current = 1
      fetchOrders()
    }

    const handleSort = ({ sortKey, order }) => {
      sortBy.value = sortKey || ''
      sortOrder.value = order || ''
      pagination.current = 1
      fetchOrders()
    }

    const handlePageChange = (page) => {
      pagination.current = page
      fetchOrders()
    }

    const viewDetail = (row) => {
      currentOrder.value = row
      showDetail.value = true
    }

    /**
     * 复制核销码到剪贴板
     */
    const copyVerificationCode = async (code) => {
      try {
        await navigator.clipboard.writeText(code)
        alert(`✅ 核销码已复制：${code}`)
      } catch (err) {
        console.error('复制失败:', err)
        // 降级方案：使用传统复制方法
        const textArea = document.createElement('textarea')
        textArea.value = code
        document.body.appendChild(textArea)
        textArea.select()
        document.execCommand('copy')
        document.body.removeChild(textArea)
        alert(`✅ 核销码已复制：${code}`)
      }
    }

    /**
 * 🔄 完整的订单状态机定义
 *
 * 定义所有合法的状态转换规则
 * 格式: { 当前状态: [允许转换到的目标状态列表] }
 */
const ORDER_STATE_MACHINE = {
  0: [2, 4],  // 待支付 → 已支付/已取消
  1: [1, 4],  // 未支付(秒杀预占) → 保持/已取消
  2: [3, 6],  // 已支付 → 已核销/申请退款中
  3: [],       // 已核销 → 终态（不允许转换）
  4: [],       // 已取消 → 终态
  5: [],       // 已退款 → 终态
  6: [5, 7]    // 申请退款中 → 已退款/退款驳回
}

// 扩展状态映射（包含新增状态）
const statusMapExtended = {
  ...statusMap,
  7: '退款驳回'  // 新增状态：退款被驳回
}

/**
 * 验证状态转换是否合法
 */
function isValidStateTransition(currentStatus, targetStatus) {
  const allowedTransitions = ORDER_STATE_MACHINE[currentStatus]

  if (!allowedTransitions) {
    return {
      valid: false,
      reason: `未知的状态(${currentStatus})`
    }
  }

  if (!allowedTransitions.includes(targetStatus)) {
    const currentName = statusMapExtended[currentStatus] || `状态${currentStatus}`
    const targetName = statusMapExtended[targetStatus] || `状态${targetStatus}`
    return {
      valid: false,
      reason: `不允许从"${currentName}"转换为"${targetName}"`
    }
  }

  return { valid: true }
}

/**
 * 记录操作日志（本地 + 可上报）
 */
function recordOperationLog(action, orderData, success, details = {}) {
  const logEntry = {
    timestamp: new Date().toISOString(),
    action,
    orderId: orderData?.id,
    userId: orderData?.userId,
    userName: orderData?.userName,
    statusBefore: orderData?.status,
    statusAfter: details.newStatus,
    success,
    operator: 'admin',
    ...details
  }

  // 存储到本地
  let logs = []
  try {
    logs = JSON.parse(localStorage.getItem('order_operation_logs') || '[]')
  } catch (e) {
    logs = []
  }

  logs.push(logEntry)

  // 保留最近200条
  if (logs.length > 200) {
    logs = logs.slice(-200)
  }

  localStorage.setItem('order_operation_logs', JSON.stringify(logs))

  // 控制台输出
  if (success) {
    console.log(`[Order 成功] ${action}`, logEntry)

    try {
      if (import.meta.env.PROD) {
        const auditPayload = {
          ...logEntry,
          timestamp: new Date().toISOString(),
          userAgent: navigator.userAgent,
          url: window.location.href
        }

        fetch('/api/admin/audit-log', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(auditPayload),
          keepalive: true
        }).catch(err => {
          console.warn('[审计] 上报失败（不影响主流程）:', err.message)
        })
      }
    } catch (auditErr) {
      console.warn('[审计] 异常（不影响主流程）:', auditErr.message)
    }
  } else {
    console.error(`[Order 失败] ${action}`, logEntry)
  }
}

/**
 * 处理退款审批（增强版）
 */
const handleApproveRefund = async (row) => {
  console.log('[管理退款] 开始审批，订单ID:', row.id, '当前状态:', row.status)

  // ========== 第1层：状态机验证 ==========
  const stateCheck = isValidStateTransition(row.status, 5) // 5=已退款

  // ✅ 特殊处理：已退款的订单（幂等性，视为成功）
  if (row.status === 5) {
    recordOperationLog('APPROVE_REFUND_SUCCESS', row, true, {
      note: '订单已退款完成（幂等处理）'
    })

    alert('✅ 该订单已退款完成')
    return
  }

  if (!stateCheck.valid) {
    const currentStatusText = statusMapExtended[row.status] || `未知状态(${row.status})`

    const errorDetails = {
      reason: stateCheck.reason,
      suggestion: '请刷新页面获取最新数据或联系技术支持'
    }

    recordOperationLog('APPROVE_REFUND_FAILED', row, false, errorDetails)

    alert(`⚠️ 退款审批失败\n\n原因：${stateCheck.reason}\n\n建议：${errorDetails.suggestion}`)
    return
  }

  // ========== 第2层：业务规则验证 ==========
  const businessChecks = []

  // 检查1：是否已超过退款时限（例如：演出开始后24小时内不退）
  if (row.showTime) {
    const showTime = new Date(row.showTime)
    const now = new Date()
    const hoursUntilShow = (showTime - now) / (1000 * 60 * 60)

    if (hoursUntilShow < 24 && hoursUntilShow > -48) {
      businessChecks.push({
        valid: false,
        warning: `[警告] 距离演出开始仅${Math.abs(Math.round(hoursUntilShow))}小时，是否确认退款？`
      })
    }
  }

  // 检查2：金额合理性
  if (row.payValue <= 0) {
    businessChecks.push({
      valid: false,
      error: '退款金额异常（≤0），请联系财务核实'
    })
  }

  // 如果有严重错误，直接阻止
  const criticalErrors = businessChecks.filter(c => !c.valid && c.error)
  if (criticalErrors.length > 0) {
    recordOperationLog('APPROVE_REFUND_FAILED', row, false, {
      reason: criticalErrors[0].error
    })

    alert(`[错误] 退款审批失败！\n\n订单号：#${row.id}\n${criticalErrors[0].error}`)
    return
  }

  // ========== 第3层：二次确认 ==========

  // 构建确认信息
  let confirmMsg = `⚠️ 确认同意该订单退款吗？\n\n`
  confirmMsg += `━━━━━━ 订单信息 ━━━━━━\n`
  confirmMsg += `📋 订单号：#${row.id}\n`
  confirmMsg += `👤 用户：${row.userName || '未知'}\n`
  confirmMsg += `🎫 票种：${row.ticketTitle || '未知'}\n`
  confirmMsg += `🎭 演出：${row.showName || '未知'}\n`
  confirmMsg += `💰 退款金额：¥${formatPrice(row.payValue)}\n`
  confirmMsg += `📊 当前状态：申请退款中\n`
  confirmMsg += `📅 创建时间：${row.createTime || '未知'}\n`

  // 添加业务警告
  const warnings = businessChecks.filter(c => !c.valid && c.warning)
  if (warnings.length > 0) {
    confirmMsg += `\n⚠️ 警告：${warnings[0].warning}\n`
  }

  confirmMsg += `\n❗ 此操作不可撤销！款项将原路退回给用户。`
  confirmMsg += `\n\n点击"确定"继续，点击"取消"返回。`

  if (!confirm(confirmMsg)) {
    recordOperationLog('APPROVE_REFUND_CANCELLED', row, false, {
      reason: '用户取消操作'
    })
    return
  }

  recordOperationLog('APPROVE_REFUND_CONFIRMED', row, true)

  // ========== 第4层：防重复提交 ==========
  if (row._approving) {
    alert('⏳ 正在处理中，请勿重复点击')
    return
  }

  // 设置处理标记
  row._approving = true

  try {
    // ========== 第5层：发送请求（带乐观锁） ==========
    const version = row.version || row.updateTime || Date.now()

    console.log('[管理退款] 发送请求...', {
      orderId: row.id,
      version,
      timestamp: Date.now(),
      operator: 'admin'
    })

    const res = await api.post(`/admin/order/${row.id}/approve-refund`, {
      version: version,
      operator: 'admin',
      deviceInfo: navigator.userAgent?.substring(0, 50),
      timestamp: Date.now(),
      clientType: 'web_admin'
    })

    console.log('[管理退款] 接口返回:', res)

    // ========== 第6层：验证响应 ==========
    if (res.data && res.data.success === false) {
      throw new Error(res.data.errorMsg || '退款操作失败')
    }

    // ========== 第6层：成功处理 ==========

    // ✅ 智能判断后端返回的消息（支持字符串和布尔值）
    const successMessage = res.data?.data || '退款审批成功'

    recordOperationLog('APPROVE_REFUND_SUCCESS', row, true, {
      newStatus: 5, // 已退款
      refundAmount: row.payValue,
      serverMessage: successMessage
    })

    // ✅ 先刷新列表（确保数据最新）
    console.log('[管理退款] 开始强制刷新订单列表...')

    try {
      await fetchOrders({
        forceRefresh: true,
        _timestamp: Date.now()
      })

      console.log('[管理退款] ✅ 列表刷新成功')

    } catch (refreshError) {
      console.error('[管理退款] ⚠️ 列表刷新失败，尝试普通刷新...', refreshError)

      // 失败时降级为普通刷新
      try {
        await fetchOrders()
        console.log('[管理退款] ✅ 普通刷新成功')
      } catch (e) {
        console.error('[管理退款] ❌ 所有刷新方式都失败', e)
      }
    }

    // ✅ 最后显示成功提示（让用户知道操作完成）
    alert(`✅ ${successMessage}\n\n` +
          `━━━━━━ 退款详情 ━━━━━━\n` +
          `📋 订单号：#${row.id}\n` +
          `💰 退款金额：¥${formatPrice(row.payValue)}\n` +
          `👤 用户：${row.userName || '未知'}\n\n` +
          `ℹ️ 款项将在3-5个工作日内原路退回给用户。\n` +
          `✅ 订单列表已自动更新。\n` +
          `系统将自动发送退款通知给用户。`)

  } catch (err) {
    console.error('[管理退款失败]', err)

    // 解析错误信息
    let errorMsg = err.message || '未知错误'
    let errorCode = null

    if (err.response?.data) {
      const responseData = err.response.data
      errorMsg = responseData.errorMsg ||
                 responseData.msg ||
                 responseData.message ||
                 errorMsg
      errorCode = responseData.code
    }

    // 特定错误码处理
    switch (errorCode) {
      case 409:
        errorMsg = '数据冲突：该订单可能已被其他管理员处理，请刷新页面重试'
        break
      case 400:
        errorMsg = '参数错误：' + errorMsg
        break
      case 403:
        errorMsg = '权限不足：您没有执行此操作的权限'
        break
      case 404:
        errorMsg = '订单不存在或已被删除'
        break
      default:
        if (err.message.includes('timeout')) {
          errorMsg = '请求超时，服务器响应较慢，请稍后重试'
        } else if (err.message.includes('network')) {
          errorMsg = '网络连接失败，请检查网络后重试'
        }
    }

    // 记录失败日志
    recordOperationLog('APPROVE_REFUND_ERROR', row, false, {
      errorMessage: errorMsg,
      errorCode,
      originalError: err.message
    })

    // ✅ 显示错误提示（使用原生alert）
    alert(`❌ 退款审批失败！\n\n` +
          `━━━━━━ 错误信息 ━━━━━━\n` +
          `📋 订单号：#${row.id}\n` +
          `👤 用户：${row.userName || '未知'}\n` +
          `📊 当前状态：${statusMapExtended[row.status]}\n\n` +
          `❌ 原因：${errorMsg}\n\n` +
          `可能的原因及解决方案：\n` +
          `1. 并发冲突 → 刷新页面(F5)后重试\n` +
          `2. 状态不符 → 该订单可能已被其他管理员操作\n` +
          `3. 网络问题 → 检查网络连接后重试\n` +
          `4. 权限不足 → 联系超级管理员开通权限\n` +
          `5. 系统异常 → 联系技术支持并提供订单号`)

    // ✅ 输出详细错误信息到控制台（便于调试）
    console.group('[管理退款] 详细错误信息')
    console.error('订单ID:', row.id)
    console.error('当前状态:', row.status)
    console.error('错误消息:', errorMsg)
    console.error('错误代码:', errorCode)
    console.error('原始异常:', err)
    console.groupEnd()

  } finally {
    // 释放处理标记
    row._approving = false
  }
}

    const formatPrice = (val) => {
      if (val == null) return '0.00'
      return (val / 100).toFixed(2)
    }

    const formatTime = (time) => {
      if (!time) return '--'
      if (typeof time === 'string') {
        try {
          const d = new Date(time)
          if (!isNaN(d.getTime())) {
            const Y = d.getFullYear()
            const M = String(d.getMonth() + 1).padStart(2, '0')
            const D = String(d.getDate()).padStart(2, '0')
            const h = String(d.getHours()).padStart(2, '0')
            const m = String(d.getMinutes()).padStart(2, '0')
            return `${Y}-${M}-${D} ${h}:${m}`
          }
        } catch (_) {}
        return time
      }
      return time
    }

    onMounted(() => {
      fetchOrders()
    })

    return {
      loading,
      keyword,
      searchField,
      statusFilter,
      showDetail,
      currentOrder,
      orderList,
      pagination,
      columns,
      columnsWithCheckbox,
      statusMap,
      sortBy,
      sortOrder,
      // 批量操作相关
      selectedOrderIds,
      batchLoading,
      // 方法
      handleSearch,
      resetFilters,
      handleSort,
      handlePageChange,
      viewDetail,
      copyVerificationCode,
      handleApproveRefund,
      handleRejectRefund,
      clearSelection,
      handleBatchApproveRefund,
      handleBatchRejectRefund,
      formatPrice,
      formatTime
    }
  }
}
</script>

<style scoped>
/**
 * Orders Management Page (v2.0)
 * 完全符合日系轻亚风 × 有机自然感设计规范
 */

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

/* 统一表单元素 */
.search-select,
.search-box input,
.status-filter select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  background: var(--color-white);
  transition: all var(--transition-fast);
  outline: none;
}

.search-select:focus,
.search-box input:focus,
.status-filter select:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(150, 116, 98, 0.08);
}

.search-box input {
  width: 180px;
}

.status-filter {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.status-filter label {
  font-size: var(--font-sm);
  color: var(--color-text);
  font-weight: 500;
}

.status-tag {
  display: inline-block;
  padding: var(--space-1) calc(var(--space-2) + var(--space-1));
  border-radius: var(--radius-md);
  font-size: var(--font-xs);
  font-weight: 500;
}

/* 订单状态配色（灰绿/暖红/米白系） */
.status-0 { /* 待支付 */
  background-color: rgba(236, 224, 202, 0.45);
  color: var(--color-secondary);
  border: 1px solid rgba(150, 116, 98, 0.35);
}
.status-1 { /* 未支付(秒杀预占) */
  background-color: rgba(166, 186, 174, 0.25);
  color: var(--color-text);
  border: 1px solid rgba(166, 186, 174, 0.4);
}
.status-2 { /* 已支付 */
  background-color: rgba(166, 186, 174, 0.35);
  color: var(--color-text);
  border: 1px solid rgba(166, 186, 174, 0.5);
}
.status-3 { /* 已核销 */
  background-color: var(--color-bg-success);
  color: var(--color-success-dark);
  border: 1px solid var(--color-border-success);
}
.status-4 { /* 已取消 */
  background-color: rgba(214, 208, 196, 0.4);
  color: var(--color-text-light);
  border: 1px solid rgba(214, 208, 196, 0.6);
}
.status-5 { /* 已退款 */
  background-color: rgba(196, 135, 123, 0.15);
  color: var(--color-secondary);
  border: 1px solid rgba(196, 135, 123, 0.3);
}
.status-6 { /* 申请退款中 */
  background-color: rgba(212, 183, 130, 0.35);
  color: var(--color-secondary);
  border: 1px solid rgba(212, 183, 130, 0.45);
}
.status-7 { /* 已拒绝 */
  background-color: var(--color-bg-alt);
  color: var(--color-text-light);
  border: 1px solid var(--color-border);
}

.action-buttons {
  display: flex;
  gap: var(--space-2);
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-3);
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.detail-item label {
  font-size: var(--font-xs);
  color: var(--color-text-light);
  font-weight: 500;
}

.detail-item span {
  font-size: var(--font-sm);
  color: var(--color-text);
}

/* 核销码显示区域 */
.detail-verification-code {
  grid-column: 1 / -1; /* 占据整行 */
  background-color: var(--color-bg-alt);
  padding: var(--space-4);
  border-radius: var(--radius-md);
  border: 2px dashed var(--color-primary);
}

.verification-code-value {
  font-size: var(--font-xl) !important;
  font-weight: 700 !important;
  font-family: 'Courier New', monospace;
  letter-spacing: 4px;
  color: var(--color-primary);
  text-align: center;
  padding: var(--space-3) 0;
}

.copy-btn {
  display: block;
  margin: var(--space-2) auto 0;
  padding: var(--space-2) var(--space-6);
  background-color: var(--color-primary);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--font-xs);
  font-weight: 600;
  transition: all 0.3s ease-out;
}

.copy-btn:hover {
  background-color: var(--color-secondary);
  transform: translateY(-1px);
}

/* 响应式 */
@media (max-width: 768px) {
  .filter-row {
    flex-direction: column;
    align-items: stretch;
  }

  .detail-grid {
    grid-template-columns: 1fr;
  }
}

/* ==================== 批量操作栏样式 ==================== */
.batch-action-bar {
  margin-bottom: var(--space-4);
  background-color: rgba(166, 186, 174, 0.06);
  border-left: 4px solid rgba(166, 186, 174, 0.5);
}

.batch-action-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-3) var(--space-4);
}

.selection-info {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.selection-count strong {
  color: var(--color-text);
  font-weight: 600;
  margin: 0 var(--space-1);
}

.batch-actions {
  display: flex;
  gap: var(--space-2);
}

/* 复选框样式 */
.row-checkbox {
  width: 16px;
  height: 16px;
  cursor: pointer;
  accent-color: rgba(150, 116, 98, 0.7);
}
</style>
