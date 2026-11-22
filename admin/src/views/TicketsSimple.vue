<template>
  <div class="page-container">
    <div class="page-header">
      <h1>票种管理</h1>
      <p class="subtitle">管理所有演出票种</p>
    </div>

    <Card class="filter-card">
      <div class="filter-row">
        <div class="search-box">
          <input v-model="keyword" type="text" placeholder="搜索票种名称..." @keyup.enter="handleSearch" />
          <Button @click="handleSearch" variant="primary">搜索</Button>
        </div>
        <div class="show-filter">
          <label>演出筛选：</label>
          <div class="custom-select">
            <input
              v-model="showSearchText"
              type="text"
              placeholder="输入关键词搜索演出..."
              @input="filterShows"
              @focus="showDropdown = true"
            />
            <ul v-if="showDropdown && filteredShowList.length > 0" class="dropdown-list">
              <li
                v-for="show in filteredShowList"
                :key="show.id"
                @click="selectShow(show)"
                :class="{ active: showIdFilter === show.id }"
              >
                {{ show.name }}
              </li>
            </ul>
          </div>
        </div>
        <Button @click="resetFilters" variant="secondary">重置</Button>
        <Button @click="debugFetch" variant="secondary" size="sm">调试</Button>
        <Button @click="showCreate = true" variant="primary" class="add-btn">+ 新增票种</Button>
      </div>
    </Card>

    <Card>
      <Table
        :columns="columns"
        :data="ticketList"
        :loading="loading"
        :pagination="pagination"
        @sort="handleSort"
      @page-change="handlePageChange"
      @page-size-change="handlePageSizeChange"
    >
        <template #ticketType="{ row }">
          <span v-if="row.isSeckill" class="type-badge type-seckill">秒杀票</span>
          <span v-else class="type-badge type-normal">普通票</span>
        </template>

        <!-- 座位信息（简洁提示 + 显示数量 - 支持悬浮提示） -->
        <template #seatInfo="{ row }">
          <div
            v-if="row.hasSeats"
            class="seat-info-cell seat-required"
            @mouseenter="showSeatTooltip($event, row)"
            @mouseleave="hideSeatTooltip"
            @mousemove="updateTooltipPosition($event)"
          >
            <span class="seat-icon"></span>
            <span class="seat-text">{{ row.seatCount || 0 }}座</span>
          </div>
          <div v-else class="seat-info-cell seat-not-required">
            <span class="seat-text">免选座</span>
          </div>
        </template>

        <template #priceInfo="{ row }">
          <div class="price-info">
            <div class="price-row">
              <span class="price-label">售价:</span>
              <span class="price-value">¥{{ formatPrice(row.payValue) }}</span>
            </div>
            <div class="price-row">
              <span class="price-label price-actual">原价:</span>
              <span class="price-value price-actual-value">¥{{ formatPrice(row.actualValue) }}</span>
            </div>
          </div>
        </template>
        <template #stockInfo="{ row }">
          <div class="stock-info">
            <!-- 普通票：显示剩余（主）、总/已售（副） -->
            <div v-if="!row.isSeckill" class="normal-stock-info">
              <div class="stock-main">
                <span class="stock-badge stock-normal">
                  剩余 {{ row.remainingStock ?? row.stock ?? 0 }}
                </span>
              </div>
              <div class="stock-sub">
                (总{{ row.totalStock ?? row.stock ?? 0 }} / 已售{{ row.soldCount ?? 0 }})
              </div>
            </div>

            <!-- 秒杀票：剩余（Redis 真相值，已按总库存封顶）、已售 = 总-剩余（自洽推导） -->
            <div v-else class="seckill-stock-info">
              <div class="stock-main">
                <span class="stock-badge stock-seckill">
                  剩余 {{ row.seckillStock ?? 0 }}
                </span>
              </div>
              <div class="stock-sub">
                (总{{ row.seckillTotalStock ?? 0 }} / 已售{{ row.seckillSoldCount ?? 0 }})
              </div>
            </div>
          </div>
        </template>
        <template #statusInfo="{ row }">
          <div class="status-info-container">
            <div class="status-main">
              <span :class="['status-tag', row.status === 1 ? 'status-online' : 'status-offline']">
                {{ row.status === 1 ? '上架' : '下架' }}
              </span>
              <span v-if="row.type === 1" :class="['seckill-status-tag', getSeckillStatusClass(row)]">
                {{ getSeckillStatusText(row) }}
              </span>
            </div>
            <div v-if="row.locked === 1" class="locked-mini" title="所属演出已删除">
              [锁]
            </div>
          </div>
        </template>
        <template #actions="{ row }">
          <div class="action-buttons">
            <!-- 编辑按钮：普通管理员在票种可售卖后禁用；超级管理员始终可编辑 -->
            <Button
              @click="editTicket(row)"
              variant="primary"
              size="sm"
              :disabled="!isSuperAdmin() && isTicketActive(row)"
              :title="getEditButtonTitle(row)"
            >
              编辑
            </Button>

            <!-- 修复座位按钮（仅在座位数据确实缺失时显示：用于修复损坏的历史数据） -->
            <Button
              v-if="row.hasSeats && (!row.seatCount || row.seatCount === 0)"
              @click="openSeatGenerator(row)"
              variant="ghost"
              size="sm"
              title="仅用于修复损坏的座位数据，正常创建请使用'新建票种'功能"
            >
              修复座位
            </Button>

            <!-- 查看座位列表按钮 -->
            <Button
              v-if="row.hasSeats && (row.seatCount || 0) > 0"
              @click="viewSeatList(row)"
              variant="secondary"
              size="sm"
              :title="`查看 ${row.seatCount || 0} 个座位的详细信息`"
            >
              座位详情
            </Button>

            <!-- 上架按钮：未上架时可操作（所有管理员） -->
            <Button
              v-if="row.locked === 0 && row.status !== 1"
              @click="toggleTicketStatus(row)"
              variant="primary"
              size="sm"
            >
              上架
            </Button>

            <!-- 下架按钮：普通管理员在票种可售卖后禁止；超级管理员始终可下架 -->
            <Button
              v-if="row.locked === 0 && row.status === 1"
              @click="toggleTicketStatus(row)"
              variant="secondary"
              size="sm"
              :disabled="!isSuperAdmin() && isTicketActive(row)"
              :title="getOfflineButtonTitle(row)"
            >
              下架
            </Button>

            <!-- 票种可售卖状态提示（普通管理员看到） -->
            <Button
              v-if="!isSuperAdmin() && isTicketActive(row) && row.locked === 0"
              variant="secondary"
              size="sm"
              disabled
              :title="getActiveStatusTitle(row)"
            >
              {{ getActiveStatusText(row) }}
            </Button>

            <!-- 锁定状态提示 -->
            <Button
              v-if="row.locked === 1"
              variant="secondary"
              size="sm"
              disabled
              title="所属演出已删除，请更改所属演出"
            >
              已锁定
            </Button>

            <!-- 删除按钮：普通管理员在票种可售卖后禁止；超级管理员始终可删除 -->
            <Button
              v-if="row.locked !== 1"
              @click="deleteTicket(row)"
              variant="danger"
              size="sm"
              :disabled="!isSuperAdmin() && isTicketActive(row)"
              :title="getDeleteButtonTitle(row)"
            >
              删除
            </Button>
          </div>
        </template>
      </Table>
    </Card>

    <Modal :visible="showCreate || showEdit" @close="closeForm" :title="showEdit ? '编辑票种' : '新增票种'">
      <div class="form-grid">
        <div class="form-item">
          <label>所属演出 <span class="required">*</span></label>
          <select v-model="form.showId">
            <option :value="null">请选择演出</option>
            <option v-for="show in showList" :key="show.id" :value="show.id">{{ show.name }}</option>
          </select>
        </div>
        <div class="form-item">
          <label>票种名称 <span class="required">*</span></label>
          <input v-model="form.title" type="text" placeholder="例：VIP票" />
        </div>

        <!-- 票种类型选择器（新增） -->
        <div class="form-item form-item-type-selector">
          <label>票种类型 <span class="required">*</span></label>
          <select v-model="form.type" class="type-select">
            <option :value="null" disabled>-- 请选择票种类型 --</option>
            <option :value="0">普通票（可重复购买，一次可买多张）</option>
            <option :value="1">秒杀票（一客一单，限时特惠）</option>
          </select>
          <div v-if="form.type === 0" class="type-hint type-hint-normal">
            普通票：用户可以多次购买，每次可购买多张
          </div>
          <div v-if="form.type === 1" class="type-hint type-hint-seckill">
            秒杀票：每个用户限购1张，需设置秒杀时间
          </div>
          <div v-if="form.type === null" class="type-warning">
            请先选择票种类型（上方）
          </div>
        </div>

        <!-- 动态显示的库存字段 -->
        <!-- 普通票：显示普通库存 -->
        <div v-if="form.type === 0" class="form-item">
          <label>普通库存数量 <span class="required">*</span></label>
          <input
            v-model.number="form.stock"
            type="number"
            min="0"
            placeholder="例：1000"
            :readonly="form.hasSeats === true && !showEdit"
          />
          <span v-if="form.hasSeats === true && !showEdit" class="field-hint">
            座位票一票一座，库存与座位数一致，已自动同步为 {{ totalFormSeats }} 张
          </span>
        </div>

        <!-- 秒杀票：显示完整的秒杀配置 -->
        <template v-if="form.type === 1">
          <div class="form-item">
            <label>秒杀库存数量 <span class="required">*</span></label>
            <input
              v-model.number="form.seckillStock"
              type="number"
              min="0"
              placeholder="例：100"
              :readonly="form.hasSeats === true && !showEdit"
            />
            <span v-if="form.hasSeats === true && !showEdit" class="field-hint">
              座位票一票一座，秒杀库存与座位数一致，已自动同步为 {{ totalFormSeats }} 张
            </span>
          </div>
          <div class="form-item">
            <label>秒杀开始时间 <span class="required">*</span></label>
            <input v-model="form.beginTime" type="datetime-local" />
          </div>
          <div class="form-item">
            <label>秒杀结束时间 <span class="required">*</span></label>
            <input v-model="form.endTime" type="datetime-local" />
          </div>
        </template>

        <div class="form-item">
          <label>售价（元）<span class="required">*</span></label>
          <input v-model.number="form.payValueYuan" type="number" step="0.01" min="0" placeholder="用户实际支付的价格，例：199.00" />
          <span class="field-hint">用户端展示价、下单与支付金额均使用此价格</span>
        </div>
        <div class="form-item">
          <label>原价（元）<span class="required">*</span></label>
          <input v-model.number="form.actualValueYuan" type="number" step="0.01" min="0" placeholder="划线展示的原价，例：299.00" />
          <span class="field-hint">仅作展示参考（用户端显示为划线原价），不参与实际支付</span>
        </div>
        <div class="form-item">
          <label>定时上架时间</label>
          <div class="datetime-picker">
            <input v-model="form.scheduledPublishTime" type="datetime-local" />
            <span class="field-hint">留空表示立即上架</span>
          </div>
        </div>

        <!-- ===== 审计信息 (v4.0) ===== -->
        <div class="form-section-title">审计信息</div>

        <div class="form-item form-item-full-width">
          <label>操作备注</label>
          <textarea
            v-model="form.remark"
            placeholder="可选，记录操作原因（如：首次创建、批量导入等）"
            rows="3"
            maxlength="500"
          ></textarea>
          <span class="field-hint">{{ form.remark.length }}/500字符</span>
        </div>

        <!-- ===== 座位配置区域（符合设计规范 - 简洁无emoji）===== -->
        <div class="form-section-title">座位配置</div>

        <div class="form-item form-item-full-width">
          <label>是否需要选座 <span class="required">*</span></label>
          <div class="seat-toggle-group">
            <button
              type="button"
              :class="['toggle-btn', { active: form.hasSeats === false }]"
              @click="form.hasSeats = false"
            >
              <svg class="toggle-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="3" y="3" width="18" height="18" rx="2"/>
                <path d="M9 9h6M9 13h6M9 17h4"/>
              </svg>
              <span class="toggle-text">无需选座</span>
              <span class="toggle-desc">站票/通票/入场券</span>
            </button>
            <button
              type="button"
              :class="['toggle-btn', { active: form.hasSeats === true }]"
              @click="form.hasSeats = true"
            >
              <svg class="toggle-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="4" y="6" width="16" height="12" rx="2"/>
                <line x1="8" y1="10" x2="16" y2="10"/>
                <line x1="8" y1="14" x2="14" y2="14"/>
              </svg>
              <span class="toggle-text">需要选座</span>
              <span class="toggle-desc">剧院/VIP席/指定座位</span>
            </button>
          </div>
        </div>

        <!-- 座位配置区域（新建票种和编辑时都显示） -->
        <div v-if="form.hasSeats === true" class="seat-config-wrapper">

          <!-- 步骤1：选择配置方式 -->
          <div class="config-mode-selector">
            <label class="mode-label">座位配置方式</label>
            <div class="mode-options">
              <button
                type="button"
                :class="['mode-btn', { active: form.selectedTemplate !== '' }]"
                @click="switchToTemplateMode"
              >
                <span class="mode-icon"></span>
                <span class="mode-text">使用预设模板</span>
                <span class="mode-desc">快速应用常用布局</span>
              </button>

              <button
                type="button"
                :class="['mode-btn', { active: form.selectedTemplate === '' }]"
                @click="switchToCustomMode"
              >
                <span class="mode-icon custom"></span>
                <span class="mode-text">自定义区域</span>
                <span class="mode-desc">手动设置每个区域</span>
              </button>
            </div>
          </div>

          <!-- 方式A：预设模板模式 -->
          <div v-if="form.selectedTemplate !== '' && getCurrentTemplate" class="template-preview-panel">

            <!-- 当前选中的模板 -->
            <div class="current-template-card">
              <div class="template-header">
                <h4 class="template-name">{{ getCurrentTemplate.name }}</h4>
                <span class="template-badge">{{ getCurrentTemplate.totalSeats }} 个座位</span>
              </div>

              <!-- 模板区域列表（只读展示） -->
              <div class="template-areas-list">
                <div
                  v-for="(area, idx) in getCurrentTemplate.areas"
                  :key="idx"
                  class="template-area-item"
                >
                  <div class="area-info">
                    <span class="area-name">{{ area.name }}</span>
                    <span class="area-detail">{{ area.rows }} 排 × {{ area.cols }} 座 = {{ area.rows * area.cols }} 座</span>
                  </div>
                </div>
              </div>

              <!-- 统计信息 -->
              <div class="template-stats">
                <div class="stat-item">
                  <span class="stat-value">{{ getCurrentTemplate.areas.length }}</span>
                  <span class="stat-label">个区域</span>
                </div>
                <div class="stat-divider"></div>
                <div class="stat-item">
                  <span class="stat-value">{{ getTotalTemplateRows }}</span>
                  <span class="stat-label">总行数</span>
                </div>
                <div class="stat-divider"></div>
                <div class="stat-item">
                  <span class="stat-value highlight">{{ getCurrentTemplate.totalSeats }}</span>
                  <span class="stat-label">总座位数</span>
                </div>
              </div>
            </div>

            <!-- 切换模板按钮 -->
            <div class="template-actions">
              <button type="button" class="btn-change-template" @click="form.showTemplateSelector = !form.showTemplateSelector">
                切换其他模板
              </button>
            </div>

            <!-- 模板下拉选择器（展开时显示） -->
            <div v-if="form.showTemplateSelector" class="template-dropdown">
              <label class="dropdown-label">选择预设模板：</label>
              <select v-model.number="form.selectedTemplate" @change="onTemplateChange" class="template-select-styled">
                <option v-for="(tpl, idx) in seatTemplates" :key="idx" :value="idx">
                  {{ tpl.name }} ({{ tpl.totalSeats }}座, {{ tpl.areas.length }}个区域)
                </option>
              </select>
              <p class="dropdown-hint">提示：选择后将自动应用该模板的座位布局</p>
            </div>
          </div>

          <!-- 方式B：自定义区域模式 -->
          <div v-else class="custom-config-panel">

            <!-- 区域列表 -->
            <div class="areas-container-custom">
              <div v-for="(area, index) in form.seatAreas" :key="index" class="area-card-custom">
                <div class="area-card-header">
                  <span class="area-number">区域 {{ index + 1 }}</span>
                  <button
                    v-if="form.seatAreas.length > 1"
                    type="button"
                    class="btn-delete-area"
                    @click="removeSeatArea(index)"
                    title="删除此区域"
                  ></button>
                </div>

                <div class="area-card-body">
                  <div class="field-row-custom">
                    <div class="field-item">
                      <label class="field-label">区域名称</label>
                      <input
                        v-model="area.name"
                        type="text"
                        class="field-input"
                        placeholder="例：VIP区、A区"
                        maxlength="20"
                      />
                    </div>
                  </div>

                  <div class="field-row-custom">
                    <div class="field-item half">
                      <label class="field-label">行数</label>
                      <input
                        v-model.number="area.rows"
                        type="number"
                        class="field-input"
                        min="1"
                        max="50"
                        placeholder="1-50"
                      />
                    </div>
                    <div class="field-item half">
                      <label class="field-label">每排座位数</label>
                      <input
                        v-model.number="area.cols"
                        type="number"
                        class="field-input"
                        min="1"
                        max="100"
                        placeholder="1-100"
                      />
                    </div>
                  </div>

                  <div class="area-subtotal">
                    小计：{{ (area.rows || 0) * (area.cols || 0) }} 个座位
                  </div>
                </div>
              </div>
            </div>

            <!-- 添加区域按钮 -->
            <button
              type="button"
              class="btn-add-area-custom"
              @click="addSeatArea"
              :disabled="form.seatAreas.length >= 10"
            >
              <span class="add-icon">+</span>
              添加区域（当前 {{ form.seatAreas.length }}/10）
            </button>
          </div>

          <!-- 底部统计栏（两种模式共用） -->
          <div class="config-summary-bar">
            <div class="summary-content">
              <span class="summary-icon"></span>
              <span class="summary-text">
                配置完成：
                <strong>{{ totalFormSeats }}</strong> 个座位，
                <strong>{{ form.selectedTemplate !== '' ? getCurrentTemplate?.areas?.length : form.seatAreas.length }}</strong> 个区域
              </span>
            </div>
          </div>
        </div>

      </div>
      <template #footer>
        <Button @click="closeForm" variant="secondary">取消</Button>
        <Button @click="submitForm" variant="primary">保存</Button>
      </template>
    </Modal>

    <!-- 座位生成对话框（增强版：支持多区域 + 预设模板） -->
    <Modal :visible="showSeatModal" @close="showSeatModal = false" :title="'批量生成座位 - 高级配置'">
      <div class="seat-generator-advanced">

        <!-- 票种信息 -->
        <div class="ticket-info-header">
          <span class="ticket-label">当前票种：</span>
          <strong>{{ currentTicket?.title || '未选择' }}</strong>
        </div>

        <!-- 预设模板选择 -->
        <div class="template-section">
          <label class="section-title">快速模板（可选）</label>
          <div class="template-grid">
            <button
              v-for="tmpl in seatTemplates"
              :key="tmpl.id"
              class="template-card"
              :class="{ active: selectedTemplate === tmpl.id }"
              @click="applyTemplate(tmpl)"
            >
              <span class="template-name">{{ tmpl.name }}</span>
              <span class="template-desc">{{ tmpl.desc }}</span>
            </button>
          </div>
        </div>

        <!-- 区域列表 -->
        <div class="areas-section">
          <label class="section-title">区域配置</label>

          <div
            v-for="(area, index) in seatForm.areas"
            :key="index"
            class="area-config-card"
          >
            <div class="area-header">
              <span class="area-index">区域 #{{ index + 1 }}</span>
              <button
                v-if="seatForm.areas.length > 1"
                class="btn-remove-area"
                @click="removeArea(index)"
                title="删除此区域"
              >✕</button>
            </div>

            <div class="area-fields">
              <div class="field-row">
                <div class="field-item field-name">
                  <label>区域名称 *</label>
                  <input
                    v-model="area.name"
                    type="text"
                    :placeholder="'如：' + getAreaPlaceholder(index)"
                    maxlength="20"
                  />
                </div>
              </div>

              <div class="field-row">
                <div class="field-item">
                  <label>行数 *</label>
                  <input
                    v-model.number="area.rows"
                    type="number"
                    min="1"
                    max="50"
                    placeholder="1-50"
                  />
                </div>
                <div class="field-item">
                  <label>每行座位数 *</label>
                  <input
                    v-model.number="area.cols"
                    type="number"
                    min="1"
                    max="100"
                    placeholder="1-100"
                  />
                </div>
              </div>
            </div>
          </div>

          <!-- 添加区域按钮 -->
          <button class="btn-add-area" @click="addArea" :disabled="seatForm.areas.length >= 10">
            + 添加新区域（最多10个）
          </button>
        </div>

        <!-- 汇总信息 -->
        <div class="summary-section">
          <div class="summary-box">
            <div class="summary-details">
              <div class="summary-main">
                共 <strong>{{ totalSeatsAcrossAreas }}</strong> 个座位
              </div>
              <div class="summary-sub">
                {{ seatForm.areas.length }} 个区域 |
                {{ seatForm.areas.reduce((sum, a) => sum + (a.rows || 0), 0) }} 排 |
                平均每排 {{ avgSeatsPerRow }} 座
              </div>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <Button @click="showSeatModal = false" variant="secondary">取消</Button>
        <Button
          @click="generateSeatsAdvanced"
          variant="primary"
          :loading="generating"
          :disabled="!isSeatFormValid"
        >
          确认生成全部座位
        </Button>
      </template>
    </Modal>

    <!-- 全局座位提示框（fixed定位，避免被表格overflow裁剪） -->
    <Teleport to="body">
      <div
        v-if="seatTooltip.visible"
        class="seat-tooltip-global"
        :style="{
          left: seatTooltip.x + 'px',
          top: seatTooltip.y + 'px'
        }"
      >
        <div class="seat-tooltip-content">
          <strong>座位信息</strong>
          <p>{{ seatTooltip.text }}</p>
        </div>
        <div class="seat-tooltip-arrow"></div>
      </div>
    </Teleport>

    <!-- 座位列表详情对话框（加宽到760px，保证6列信息完整可见，无需横向滚动） -->
    <Modal
      :visible="showSeatListModal"
      @close="showSeatListModal = false"
      :title="`座位列表 - ${seatListTicket?.title || ''} (共 ${seatListData.length} 个座位)`"
      width="760px"
    >
      <div class="seat-list-container" v-if="!seatListLoading">
        <!-- 座位统计 -->
        <div class="seat-list-stats">
          <span class="stat-item stat-total">总计: {{ seatListData.length }} 个</span>
          <span class="stat-item stat-available">空闲: {{ seatListData.filter(s => s.status === 0).length }} 个</span>
          <span class="stat-item stat-locked">预占: {{ seatListData.filter(s => s.status === 1).length }} 个</span>
          <span class="stat-item stat-sold">已售: {{ seatListData.filter(s => s.status === 2).length }} 个</span>
        </div>

        <!-- 座位表格 -->
        <div class="seat-table-wrapper">
          <table class="seat-detail-table" v-if="seatListData.length > 0">
            <thead>
              <tr>
                <th>ID</th>
                <th>区域</th>
                <th>排号</th>
                <th>座号</th>
                <th>状态</th>
                <th>预订单ID</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="seat in seatListData" :key="seat.id" :class="`status-${seat.status}`">
                <td>{{ seat.id }}</td>
                <td>{{ seat.areaName || '-' }}</td>
                <td>{{ seat.rowNum || '-' }}</td>
                <td>{{ seat.colNum || '-' }}</td>
                <td>
                  <span :class="['seat-status-badge', `status-${seat.status}`]">
                    {{ getSeatStatusText(seat.status) }}
                  </span>
                </td>
                <td>{{ seat.preOrderId || '-' }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty-seat-list">
            <p>暂无座位数据</p>
          </div>
        </div>
      </div>

      <!-- 加载状态 -->
      <div class="seat-list-loading" v-else>
        <p>正在加载座位数据...</p>
      </div>

      <template #footer>
        <Button @click="showSeatListModal = false" variant="secondary">关闭</Button>
        <Button @click="refreshSeatList" variant="primary" :loading="seatListLoading">刷新</Button>
      </template>
    </Modal>
  </div>
</template>

<script>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { api } from '../utils/api'
import Card from '../components/common/Card.vue'
import Button from '../components/common/Button.vue'
import Table from '../components/common/Table.vue'
import Modal from '../components/common/Modal.vue'

export default {
  name: 'Tickets',
  components: { Card, Button, Table, Modal },
  setup() {
    const loading = ref(false)
    const keyword = ref('')
    const showIdFilter = ref(null)
    const showSearchText = ref('')
    const showDropdown = ref(false)
    const showList = ref([])
    const ticketList = ref([])
    const showCreate = ref(false)
    const showEdit = ref(false)

    // 座位生成相关状态（增强版：支持多区域 + 预设模板）
    const showSeatModal = ref(false)
    const currentTicket = ref(null)
    const generating = ref(false)
    const selectedTemplate = ref(null)

    // 座位表单（多区域数组）
    const seatForm = reactive({
      areas: [
        { name: '普通区', rows: 10, cols: 20 }
      ]
    })

    // 全局座位提示框状态（fixed定位，避免被父元素overflow裁剪）
    const seatTooltip = reactive({
      visible: false,
      x: 0,
      y: 0,
      text: ''
    })

    // 座位列表详情状态
    const showSeatListModal = ref(false)
    const seatListTicket = ref(null)
    const seatListData = ref([])
    const seatListLoading = ref(false)

    /**
     * 显示座位提示框
     * @param {MouseEvent} event - 鼠标事件
     * @param {Object} row - 当前行数据
     */
    const showSeatTooltip = (event, row) => {
      const target = event.currentTarget
      const rect = target.getBoundingClientRect()

      // 计算提示框位置（在元素上方居中显示）
      seatTooltip.x = rect.left + (rect.width / 2)
      seatTooltip.y = rect.top - 10  // 距离元素上方10px
      seatTooltip.text = row.seatCount
        ? `共 ${row.seatCount} 个座位`
        : '尚未生成座位，点击"生成座位"配置'
      seatTooltip.visible = true
    }

    /**
     * 隐藏座位提示框
     */
    const hideSeatTooltip = () => {
      seatTooltip.visible = false
    }

    /**
     * 查看座位列表详情
     * @param {Object} row - 票种行数据
     */
    const viewSeatList = async (row) => {
      if (!row || !row.id) {
        alert('票种信息异常，无法查看座位')
        return
      }

      console.log('[座位列表] 开始加载 - ticketId:', row.id, ', title:', row.title)

      seatListTicket.value = row
      showSeatListModal.value = true
      seatListLoading.value = true
      seatListData.value = []

      try {
        const res = await api.getSeatList(row.id)

        console.log('[座位列表] API响应:', res)

        if (res.data && res.data.records) {
          seatListData.value = res.data.records
          console.log(`[座位列表] ✅ 加载成功 - 共 ${res.data.records.length} 个座位`)
        } else {
          console.warn('[座位列表] ⚠️ 响应格式异常:', res)
          seatListData.value = []
        }
      } catch (err) {
        console.error('[座位列表] ❌ 加载失败:', err)
        alert('获取座位列表失败: ' + (err.message || '未知错误'))
        seatListData.value = []
      } finally {
        seatListLoading.value = false
      }
    }

    /**
     * 刷新座位列表
     */
    const refreshSeatList = async () => {
      if (!seatListTicket.value) return

      seatListLoading.value = true

      try {
        const res = await api.getSeatList(seatListTicket.value.id)

        if (res.data && res.data.records) {
          seatListData.value = res.data.records
          console.log(`[座位列表] 🔄 刷新成功 - 共 ${res.data.records.length} 个座位`)
        }
      } catch (err) {
        console.error('[座位列表] 刷新失败:', err)
        alert('刷新座位列表失败: ' + (err.message || '未知错误'))
      } finally {
        seatListLoading.value = false
      }
    }

    /**
     * 获取座位状态文本
     * @param {number} status - 座位状态码
     * @returns {string} 状态文本
     */
    const getSeatStatusText = (status) => {
      switch (status) {
        case 0: return '空闲'
        case 1: return '预占'
        case 2: return '已售'
        default: return '未知'
      }
    }

    /**
     * 更新提示框位置（跟随鼠标微调）
     * @param {MouseEvent} event - 鼠标事件
     */
    const updateTooltipPosition = (event) => {
      if (!seatTooltip.visible) return

      const target = event.currentTarget
      const rect = target.getBoundingClientRect()

      // 实时更新位置，确保跟随元素
      seatTooltip.x = rect.left + (rect.width / 2)
      seatTooltip.y = rect.top - 10
    }

    // 预设座位模板
    const seatTemplates = [
      {
        id: 'cinema',
        name: '电影院模式',
        desc: '15排 x 20座，共300座',
        areas: [{ name: '观影区', rows: 15, cols: 20 }]
      },
      {
        id: 'theater',
        name: '剧院模式',
        desc: 'VIP(5x10) + 普通(10x20)，共250座',
        areas: [
          { name: 'VIP区', rows: 5, cols: 10 },
          { name: '普通区', rows: 10, cols: 20 }
        ]
      },
      {
        id: 'stadium',
        name: '体育馆模式',
        desc: '东(8x15) + 西(8x15) + 南(10x30)，共540座',
        areas: [
          { name: '东看台', rows: 8, cols: 15 },
          { name: '西看台', rows: 8, cols: 15 },
          { name: '南看台', rows: 10, cols: 30 }
        ]
      },
      {
        id: 'concert',
        name: '演唱会模式',
        desc: '内场(5x20) + 看台A(10x15) + 看台B(10x15)，共400座',
        areas: [
          { name: '内场VIP', rows: 5, cols: 20 },
          { name: '看台A区', rows: 10, cols: 15 },
          { name: '看台B区', rows: 10, cols: 15 }
        ]
      },
      {
        id: 'small',
        name: '小型会场',
        desc: '5排 x 10座，共50座',
        areas: [{ name: '全场', rows: 5, cols: 10 }]
      },
      {
        id: 'custom',
        name: '自定义配置',
        desc: '手动配置区域和布局参数',
        areas: [{ name: '区域1', rows: 10, cols: 20 }]
      }
    ]

    const sortBy = ref('')
    const sortOrder = ref('')

    const pagination = reactive({
      current: 1,
      total: 0,
      pageSize: 20
    })

    const form = reactive({
      id: null,
      showId: null,
      title: '',
      type: null, // 票种类型：0=普通票, 1=秒杀票（必填）
      payValueYuan: 0,
      actualValueYuan: 0,
      stock: 0, // 普通库存（普通票时使用）
      seckillStock: null, // 秒杀库存（秒杀票时使用）
      beginTime: '', // 秒杀开始时间
      endTime: '', // 秒杀结束时间
      scheduledPublishTime: '', // 定时上架时间（用户端可见性控制）

      // ===== 座位配置（简化版）=====
      hasSeats: false, // 是否需要选座（默认不需要）

      // 新建票种时的座位区域配置（仅新建时使用）
      seatAreas: [
        { name: 'A区', rows: 10, cols: 20 }
      ],

      // 新建票种时的模板选择（默认空，表示自定义配置）
      selectedTemplate: '',

      // 控制模板下拉选择器的显示
      showTemplateSelector: false,

      // ===== 审计字段 (v4.0) =====
      remark: '' // 操作备注（可选，≤500字符）
    })

    // 缓存幂等键（用于可能的后续重试）
    let cachedIdempotencyKey = null

    /**
     * 获取当前选中的模板对象
     */
    const getCurrentTemplate = computed(() => {
      if (form.selectedTemplate === '' || form.selectedTemplate === null || form.selectedTemplate === undefined) {
        return null
      }
      const idx = Number(form.selectedTemplate)
      return seatTemplates[idx] || null
    })

    /**
     * 获取当前模板的总行数
     */
    const getTotalTemplateRows = computed(() => {
      if (!getCurrentTemplate.value) return 0
      return getCurrentTemplate.value.areas.reduce((total, area) => total + (area.rows || 0), 0)
    })

    /**
     * 切换到模板模式（自动选择第一个模板）
     */
    const switchToTemplateMode = () => {
      if (form.selectedTemplate === '' && seatTemplates.length > 0) {
        form.selectedTemplate = 0  // 默认选择第一个模板
        form.showTemplateSelector = false
        console.log('[座位配置] 已切换到模板模式:', seatTemplates[0].name)
      }
    }

    /**
     * 切换到自定义模式（清空模板选择）
     */
    const switchToCustomMode = () => {
      form.selectedTemplate = ''
      form.showTemplateSelector = false
      // 恢复默认的自定义配置
      form.seatAreas = [
        { name: 'A区', rows: 10, cols: 20 }
      ]
      console.log('[座位配置] 已切换到自定义模式')
    }

    /**
     * 模板切换事件处理
     */
    const onTemplateChange = () => {
      const template = getCurrentTemplate.value
      if (template) {
        console.log(`[座位配置] 已切换模板: ${template.name}`)
        form.showTemplateSelector = false  // 选择后自动收起下拉框
      }
    }

    // ===== 新建票种时的座位配置相关方法 =====

    /**
     * 计算表单中的座位总数（根据当前模式动态计算）
     */
    const totalFormSeats = computed(() => {
      if (form.selectedTemplate !== '' && getCurrentTemplate.value) {
        // 模板模式：使用模板的totalSeats
        return getCurrentTemplate.value.totalSeats
      } else {
        // 自定义模式：手动计算
        return form.seatAreas.reduce((total, area) => {
          return total + ((area.rows || 0) * (area.cols || 0))
        }, 0)
      }
    })

    /**
     * 计算表单中的总行数（根据当前模式动态计算）
     */
    const totalFormRows = computed(() => {
      if (form.selectedTemplate !== '' && getCurrentTemplate.value) {
        // 模板模式：使用模板的总行数
        return getTotalTemplateRows.value
      } else {
        // 自定义模式：手动计算
        return form.seatAreas.reduce((total, area) => {
          return total + (area.rows || 0)
        }, 0)
      }
    })

    // ===== 座位票库存联动 =====
    // 座位票的总库存由座位数决定（一张票对应一个座位），库存数与座位数必须一致。
    // 新建模式下座位数变化时自动同步库存字段，避免"库存数 ≠ 座位数"造成误解。
    // （编辑模式不联动：历史票种的库存可能已随售出变化，不覆盖）
    watch(totalFormSeats, (seats) => syncStockWithSeats(seats))
    watch(() => [form.hasSeats, form.type], () => syncStockWithSeats(totalFormSeats.value))

    function syncStockWithSeats(seats) {
      if (showEdit.value || form.hasSeats !== true || !seats || seats <= 0) return
      if (form.type === 0) {
        form.stock = seats
      } else if (form.type === 1 && form.seckillStock != null) {
        form.seckillStock = seats
      }
    }

    /**
     * 添加座位区域
     */
    const addSeatArea = () => {
      if (form.seatAreas.length >= 10) {
        alert('最多支持10个区域')
        return
      }
      const areaNum = form.seatAreas.length + 1
      // 自动生成区域名称（A区、B区、C区...）
      const areaName = String.fromCharCode(64 + areaNum) + '区'
      form.seatAreas.push({
        name: areaName,
        rows: 10,
        cols: 20
      })
    }

    /**
     * 删除座位区域
     */
    const removeSeatArea = (index) => {
      if (form.seatAreas.length <= 1) {
        alert('至少需要保留1个区域')
        return
      }
      form.seatAreas.splice(index, 1)
    }

    /**
     * 验证座位配置是否有效
     */
    const isSeatConfigValid = () => {
      if (!form.hasSeats) return true // 不需要选座时跳过验证

      if (!form.seatAreas || form.seatAreas.length === 0) {
        alert('[警告] 请至少配置1个座位区域')
        return false
      }

      for (let i = 0; i < form.seatAreas.length; i++) {
        const area = form.seatAreas[i]

        if (!area.name || !area.name.trim()) {
          alert(`[警告] 请输入第 ${i + 1} 个区域的名称`)
          return false
        }

        if (!area.rows || area.rows < 1 || area.rows > 50) {
          alert(`[警告] 区域"${area.name}"的行数必须在1-50之间`)
          return false
        }

        if (!area.cols || area.cols < 1 || area.cols > 100) {
          alert(`[警告] 区域"${area.name}"的每排座位数必须在1-100之间`)
          return false
        }
      }

      return true
    }

    /**
     * 应用模板到新建票种的表单（从下拉选择模板时调用）
     */
    const applyTemplateToForm = () => {
      const templateIndex = form.selectedTemplate

      if (templateIndex === '' || templateIndex === null || templateIndex === undefined) {
        // 选择"自定义配置"，恢复默认值
        form.seatAreas = [
          { name: 'A区', rows: 10, cols: 20 }
        ]
        console.log('[新建票种] 已恢复自定义配置')
        return
      }

      // 获取选中的模板
      const template = seatTemplates[templateIndex]

      if (!template) {
        console.error('[新建票种] 模板不存在，index:', templateIndex)
        return
      }

      // 将模板的areas复制到表单（深拷贝）
      form.seatAreas = template.areas.map(area => ({
        name: area.name,
        rows: area.rows,
        cols: area.cols
      }))

      console.log(`[新建票种] 已应用模板: ${template.name}, 共${form.seatAreas.length}个区域`)
    }

    // 列宽总和控制在 ~850px：1366 宽屏幕下内容区约 1030px（侧边栏240 + 页面/Card内边距），
    // 保证不出现横向滚动条，所有信息一屏可见
    const columns = [
      { label: '票种名称', prop: 'title', sortable: true, sortKey: 'title', width: '110px' },
      { label: '所属演出', prop: 'showName', sortable: false, width: '90px' },
      { label: '类型', slot: 'ticketType', sortable: true, sortKey: 'type', width: '80px' },
      { label: '座位', slot: 'seatInfo', sortable: false, width: '76px' },
      { label: '价格', slot: 'priceInfo', sortable: false, width: '105px' },
      { label: '库存', slot: 'stockInfo', sortable: false, width: '140px' },
      { label: '状态', slot: 'statusInfo', sortable: false, width: '95px' },
      { label: '操作', slot: 'actions', width: '170px', fixed: 'right' }
    ]

    console.log('Tickets组件setup开始执行...')

    const fetchShows = async () => {
      try {
        console.log('开始获取演出列表...')
        const res = await api.getShowList({ current: 1, pageSize: 1000 })
        if (res.data) {
          showList.value = res.data.records || res.data || []
          console.log('获取演出列表成功, 数量:', showList.value.length)
        }
      } catch (err) {
        console.error('获取演出列表失败:', err)
        showList.value = []
      }
    }

    const fetchTickets = async () => {
      loading.value = true
      try {
        console.log('开始获取票种列表...')
        const params = {
          current: pagination.current,
          pageSize: pagination.pageSize,
          showId: showIdFilter.value
        }

        if (keyword.value) {
          params.keyword = keyword.value
        }
        if (sortBy.value) {
          params.sortBy = sortBy.value
          params.sortOrder = sortOrder.value
        }

        console.log('请求票种列表参数:', params)

        const res = await api.get('/admin/ticket/list', { params })

        console.log('票种列表响应:', res)

        if (res.data) {
          if (res.data.records) {
            ticketList.value = res.data.records
            pagination.total = res.data.total || 0
            console.log('设置票种列表成功, 总数:', res.data.total)
            console.log('票种数据示例:', res.data.records[0])
          } else if (Array.isArray(res.data)) {
            ticketList.value = res.data
            pagination.total = res.data.length
          } else {
            ticketList.value = []
            pagination.total = 0
          }
        } else {
          ticketList.value = []
          pagination.total = 0
        }
      } catch (err) {
        console.error('获取票种列表失败:', err)
        alert('获取票种列表失败: ' + err.message)
        ticketList.value = []
        pagination.total = 0
      } finally {
        loading.value = false
      }
    }

    const handleSearch = () => {
      pagination.current = 1
      fetchTickets()
    }

    // 演出筛选相关函数
    const filteredShowList = computed(() => {
      if (!showSearchText.value) return showList.value
      return showList.value.filter(show =>
        show.name && typeof show.name === 'string' &&
        show.name.toLowerCase().includes(showSearchText.value.toLowerCase())
      )
    })

    const filterShows = () => {
      showDropdown.value = true
    }

    const selectShow = (show) => {
      showIdFilter.value = show.id
      showSearchText.value = show.name
      showDropdown.value = false
      pagination.current = 1
      fetchTickets()
    }

    const resetFilters = () => {
      keyword.value = ''
      showIdFilter.value = null
      showSearchText.value = ''
      sortBy.value = ''
      sortOrder.value = ''
      pagination.current = 1
      fetchTickets()
    }

    const debugFetch = async () => {
      console.log('=== 开始调试票种数据获取 ===')
      console.log('1. 检查Token:', sessionStorage.getItem('token') ? '存在' : '不存在')
      console.log('2. 当前过滤条件:', { keyword: keyword.value, showId: showIdFilter.value })

      try {
        const testRes = await api.get('/admin/ticket/list', {
          params: { current: 1, pageSize: 5 }
        })
        console.log('3. API原始响应:', testRes)
        console.log('4. 响应data:', testRes.data)
        console.log('5. 响应data.records:', testRes.data?.records)
        console.log('6. 响应data.total:', testRes.data?.total)

        if (testRes.data?.records) {
          alert(`调试成功！获取到 ${testRes.data.records.length} 条票种数据，总共 ${testRes.data.total || 0} 条`)
        } else {
          alert('调试失败：响应格式异常\n' + JSON.stringify(testRes, null, 2))
        }
      } catch (err) {
        console.error('7. API请求失败:', err)
        alert('调试失败：API请求错误\n' + err.message)
      }
    }

    const handleSort = ({ sortKey, order }) => {
      sortBy.value = sortKey || ''
      sortOrder.value = order || ''
      pagination.current = 1
      fetchTickets()
    }

    const handlePageChange = (page) => {
      pagination.current = page
      fetchTickets()
    }

    const handlePageSizeChange = (size) => {
      pagination.pageSize = size
      pagination.current = 1
      fetchTickets()
    }

    /**
     * 判断当前用户是否是超级管理员
     * 超级管理员拥有所有权限，可无视业务规则限制
     *
     * 实现方式（按优先级）：
     *   1. 从adminInfo读取（管理端登录后存储）
     *   2. 从userInfo读取（通用用户信息）
     *   3. 尝试解析JWT token（仅标准格式）
     *   4. 默认返回false（普通管理员）
     *
     * @returns {boolean}
     */
    const isSuperAdmin = () => {
      try {

        // 方式1: 从adminInfo中读取（管理端专用存储）
        const adminInfo = sessionStorage.getItem('adminInfo') || localStorage.getItem('adminInfo')
        if (adminInfo) {
          try {
            const admin = JSON.parse(adminInfo)

            // ✅ 修复：roleLevel === 0 表示超级管理员（之前错误地写成 === 1）
            const isAdmin = admin.roleLevel === 0 || admin.role_level === 0 ||
                           admin.role === 'super_admin' || admin.role === 'superadmin' ||
                           admin.isSuperAdmin === true

            if (isAdmin) {
              console.log('[Tickets] ✅ 检测到超级管理员（来自adminInfo），roleLevel:', admin.roleLevel)
              return true
            }
          } catch (parseError) {
            console.warn('[Tickets] 解析adminInfo失败:', parseError)
          }
        }

        // 方式2: 从userInfo中读取（通用用户信息）
        const userInfo = sessionStorage.getItem('userInfo') || localStorage.getItem('userInfo')
        if (userInfo) {
          try {
            const user = JSON.parse(userInfo)

            // ✅ 修复：roleLevel === 0 表示超级管理员
            const isAdmin = user.role === 'super_admin' || user.role === 'superadmin' ||
                           user.isSuperAdmin === true || user.roleLevel === 0

            if (isAdmin) {
              console.log('[Tickets] ✅ 检测到超级管理员（来自userInfo），roleLevel:', user.roleLevel)
              return true
            }
          } catch (parseError) {
            console.warn('[Tickets] 解析userInfo失败:', parseError)
          }
        }

        // 方式3: 尝试从token解析（仅对标准JWT格式有效）
        // ⚠️ F-E修复：roleLevel=0 表示超级管理员（与后端 AppMessages.ROLE_LEVEL_SUPER_ADMIN 一致）
        const token = sessionStorage.getItem('token') || localStorage.getItem('token')
        if (token && token.includes('.')) {
          try {
            const parts = token.split('.')
            if (parts.length === 3) {
              const payload = JSON.parse(atob(parts[1]))
              const isAdmin = payload.role === 'super_admin' ||
                             payload.authority === 'ROLE_SUPER_ADMIN' ||
                             payload.roleLevel === 0

              if (isAdmin) {
                console.log('[Tickets] ✅ 检测到超级管理员（来自token），roleLevel:', payload.roleLevel)
                return true
              }
            }
          } catch (tokenError) {
            console.debug('[Tickets] Token非标准JWT格式，跳过解析')
          }
        }

        // 默认返回false（普通管理员）
        console.log('[Tickets] 当前用户角色: 普通管理员')
        return false

      } catch (e) {
        console.error('[Tickets] 判断超级管理员失败:', e)
        return false
      }
    }

    /**
     * 判断票种是否处于"可售卖状态"
     * 可售卖状态定义：
     *   - 普通票：status=1（已上架）后即可售卖
     *   - 秒杀票：beginTime <= 当前时间（秒杀已开始）后即可售卖
     *
     * 处于此状态的票种，普通管理员不能下架/删除/编辑
     * @param {Object} row - 票种数据行
     * @returns {boolean}
     */
    const isTicketActive = (row) => {
      if (!row) return false

      // 必须先上架
      if (row.status !== 1) return false

      // 普通票：上架即视为可售卖
      if (!row.isSeckill && row.type !== 1) {
        return true
      }

      // 秒杀票：检查开始时间
      if (row.isSeckill || row.type === 1) {
        if (!row.beginTime) {
          // 如果没有开始时间，默认视为不可售卖（数据异常）
          console.warn('[Tickets] 秒杀票缺少开始时间, ticketId:', row.id)
          return false
        }

        const now = new Date()
        const beginTime = new Date(row.beginTime)

        // 秒杀已开始 → 视为可售卖
        if (now >= beginTime) {
          return true
        }
      }

      return false
    }

    /**
     * 获取编辑按钮的提示文字
     */
    const getEditButtonTitle = (row) => {
      if (isSuperAdmin()) {
        return '超级管理员：可随时编辑'
      }

      if (isTicketActive(row)) {
        if (row.isSeckill || row.type === 1) {
          return '秒杀已开始，禁止编辑'
        }
        return '票种正在销售中，禁止编辑'
      }

      return '编辑票种'
    }

    /**
     * 获取下架按钮的提示文字
     */
    const getOfflineButtonTitle = (row) => {
      if (isSuperAdmin()) {
        return '超级管理员：强制下架（可能影响正在购买的用户）'
      }

      if (isTicketActive(row)) {
        if (row.isSeckill || row.type === 1) {
          return '秒杀进行中，无法下架'
        }
        return '销售中，无法下架'
      }

      return '下架票种'
    }

    /**
     * 获取可售卖状态提示的标题文字
     */
    const getActiveStatusTitle = (row) => {
      if (row.isSeckill || row.type === 1) {
        const endTime = row.endTime ? new Date(row.endTime).toLocaleString() : '未知时间'
        return `秒杀活动进行中（结束时间: ${endTime}）`
      }
      return '该票种正在销售中，普通管理员无法操作'
    }

    /**
     * 获取可售卖状态提示的显示文字
     */
    const getActiveStatusText = (row) => {
      if (row.isSeckill || row.type === 1) {
        return '秒杀中'
      }
      return '销售中'
    }

    /**
     * 获取删除按钮的提示文字
     */
    const getDeleteButtonTitle = (row) => {
      if (isSuperAdmin()) {
        return '超级管理员：强制删除（不可恢复！）'
      }

      if (isTicketActive(row)) {
        if (row.isSeckill || row.type === 1) {
          return '秒杀进行中，无法删除'
        }
        return '销售中，无法删除'
      }

      if (row.soldCount && row.soldCount > 0) {
        return `已有 ${row.soldCount} 个已售订单，建议保留记录`
      }

      return '删除票种（不可恢复）'
    }

    // ===== 座位生成相关方法（增强版） =====

    /**
     * 打开座位生成对话框（重置为默认状态）
     */
    const openSeatGenerator = (ticket) => {
      currentTicket.value = ticket

      // 重置表单为默认状态（单个区域）
      seatForm.areas = [{ name: '普通区', rows: 10, cols: 20 }]
      selectedTemplate.value = null

      showSeatModal.value = true
      console.log('[座位生成] 打开对话框, ticket:', ticket.title)
    }

    /**
     * 应用预设模板
     * @param {Object} template - 模板对象
     */
    const applyTemplate = (template) => {
      selectedTemplate.value = template.id

      // 深拷贝模板的区域配置
      seatForm.areas = JSON.parse(JSON.stringify(template.areas))

      console.log('[座位生成] 应用模板:', template.name, '- 共', template.areas.length, '个区域')
    }

    /**
     * 添加新区域
     */
    const addArea = () => {
      if (seatForm.areas.length >= 10) return

      const areaNum = seatForm.areas.length + 1
      seatForm.areas.push({
        name: `区域${areaNum}`,
        rows: 5,
        cols: 10
      })

      console.log('[座位生成] 添加区域 #', areaNum)
    }

    /**
     * 删除指定区域
     * @param {number} index - 区域索引
     */
    const removeArea = (index) => {
      if (seatForm.areas.length <= 1) return

      const removed = seatForm.areas.splice(index, 1)[0]
      console.log('[座位生成] 删除区域:', removed.name)

      // 如果删除的是当前选中的模板，清除模板选择
      if (selectedTemplate.value) {
        selectedTemplate.value = null
      }
    }

    /**
     * 获取区域名称占位符
     * @param {number} index - 区域索引
     * @returns {string}
     */
    const getAreaPlaceholder = (index) => {
      const placeholders = ['VIP区', '普通区', '看台A', '看台B', '内场', '包厢', '贵宾厅', '站立区', '露台', '特别区']
      return placeholders[index] || `区域${index + 1}`
    }

    /**
     * 计算总座位数（所有区域）
     */
    const totalSeatsAcrossAreas = computed(() => {
      return seatForm.areas.reduce((total, area) => {
        return total + ((area.rows || 0) * (area.cols || 0))
      }, 0)
    })

    /**
     * 计算平均每排座位数
     */
    const avgSeatsPerRow = computed(() => {
      const totalRows = seatForm.areas.reduce((sum, area) => sum + (area.rows || 0), 0)
      if (totalRows === 0) return 0

      const totalSeats = totalSeatsAcrossAreas.value
      return Math.round(totalSeats / totalRows)
    })

    /**
     * 验证表单是否有效
     */
    const isSeatFormValid = computed(() => {
      if (!seatForm.areas || seatForm.areas.length === 0) return false

      return seatForm.areas.every(area =>
        area.name && area.name.trim() &&
        area.rows && area.rows >= 1 &&
        area.cols && area.cols >= 1
      )
    })

    /**
     * 批量生成座位（增强版 - 支持多区域）
     */
    const generateSeatsAdvanced = async () => {
      if (!currentTicket.value) return

      // 表单验证（多区域）
      for (let i = 0; i < seatForm.areas.length; i++) {
        const area = seatForm.areas[i]
        if (!area.name || !area.name.trim()) {
          alert(`请输入第 ${i + 1} 个区域的名称`)
          return
        }
        if (!area.rows || area.rows < 1 || area.rows > 50) {
          alert(`区域"${area.name}"的行数必须在1-50之间`)
          return
        }
        if (!area.cols || area.cols < 1 || area.cols > 100) {
          alert(`区域"${area.name}"的每排座位数必须在1-100之间`)
          return
        }
      }

      const totalSeats = totalSeatsAcrossAreas.value
      const areaDetails = seatForm.areas.map(a =>
        `${a.name}: ${a.rows}行 × ${a.cols}列`
      ).join('\n')

      const confirmMsg =
`确定要为"${currentTicket.value.title}"生成座位吗？

配置信息：
${areaDetails}

总计：${seatForm.areas.length} 个区域 / ${totalSeats} 个座位

注意：此操作将在数据库中创建座位记录，请确认参数无误。`

      if (!confirm(confirmMsg)) return

      try {
        generating.value = true
        console.log('[座位生成-高级版] 开始生成...')
        console.log('[座位生成-高级版] ticketId:', currentTicket.value.id)
        console.log('[座位生成-高级版] 区域配置:', JSON.stringify(seatForm.areas, null, 2))

        // 构建请求参数（每个区域都添加startRow默认值）
        const requestData = {
          areas: seatForm.areas.map(area => ({
            name: area.name.trim(),
            rows: area.rows,
            cols: area.cols,
            startRow: 1  // 默认从第1排开始
          }))
        }

        const res = await api.batchCreateSeats(currentTicket.value.id, requestData)

        console.log('[座位生成-高级版] 响应:', res)
        console.log('[座位生成-高级版] 响应数据:', JSON.stringify(res.data, null, 2))

        if (res.code === 200 || res.success) {
          const count = res.data?.totalSeats || res.data?.createdCount || totalSeats

          // 构建成功提示（无emoji版本）
          const templateName = selectedTemplate.value
            ? seatTemplates.find(t => t.id === selectedTemplate.value)?.name || '自定义'
            : '自定义'

          const successMsg =
`[SUCCESS] 座位生成成功！

=== 统计信息 ===
票种名称：${currentTicket.value.title}
使用模板：${templateName}
区域数量：${seatForm.areas.length} 个
座位总数：${count} 个

=== 区域明细 ===
${seatForm.areas.map((a, idx) =>
  `  [${idx + 1}] ${a.name}: ${a.rows}排 × ${a.cols}座 = ${a.rows * a.cols}个`
).join('\n')}

=== 后续操作 ===
* 用户端现在可以选座购票
* 票种列表已自动刷新
* 座位数据已写入数据库`

          alert(successMsg)
          showSeatModal.value = false

          // 刷新票种列表（更新seatCount）
          await fetchTickets()

          console.log('[座位生成-高级版] 完成, 已生成', count, '个座位')
        } else {
          const errorMsg =
`[ERROR] 座位生成失败

错误信息：${res.message || res.msg || '未知错误'}

可能原因：
* 票种ID无效或不存在
* 数据库连接异常
* 参数格式错误
* 座位数据已存在（重复生成）

建议操作：
1. 打开浏览器开发者工具(F12)查看详细日志
2. 刷新页面后重试
3. 检查后端服务是否正常运行
4. 联系技术支持并提供错误截图`

          alert(errorMsg)
          console.error('[座位生成-高级版] 失败, 响应:', res)
        }
      } catch (err) {
        console.error('[座位生成-高级版] 异常:', err)
        console.error('[座位生成-高级版] 错误详情:', {
          message: err.message,
          response: err.response?.data,
          status: err.response?.status || err.statusCode
        })

        let errorDetail = err.message || '网络错误'

        if (err.isBusinessError) {
          errorDetail = err.message
        } else if (err.isHttpError && err.response?.data) {
          const responseData = err.response.data
          if (responseData.errorMsg) {
            errorDetail = responseData.errorMsg
          } else if (responseData.msg) {
            errorDetail = responseData.msg
          } else if (responseData.message && responseData.message.length < 200) {
            errorDetail = responseData.message
          }
        }

        const catchErrorMsg =
`[ERROR] 座位生成异常

错误详情：${errorDetail}

HTTP状态码：${err.response?.status || 'N/A'}
请求时间：${new Date().toLocaleString()}

调试信息（请提供给技术支持）：
* Ticket ID: ${currentTicket.value.id}
* Error Message: ${err.message}
* Response Data: ${JSON.stringify(err.response?.data)}

建议操作：
1. 按 F12 打开开发者工具查看完整错误
2. 截图控制台和Network面板
3. 联系技术支持`

        alert(catchErrorMsg)
      } finally {
        generating.value = false
      }
    }

    const editTicket = (row) => {
      console.log('====================================')
      console.log('[编辑票种] 开始回填数据')
      console.log('[编辑票种] 完整的row对象:', JSON.stringify(row, null, 2))
      console.log('[编辑票种] row的所有字段:', Object.keys(row))
      console.log('[编辑票种] row.hasSeats:', row.hasSeats, '类型:', typeof row.hasSeats)
      console.log('====================================')

      form.id = row.id
      form.showId = row.showId
      form.title = row.title

      // 回填票种类型（关键修复！）
      if (row.type !== undefined && row.type !== null) {
        form.type = Number(row.type)
        console.log('[编辑票种] 票种类型:', form.type, form.type === 0 ? '普通票' : '秒杀票')
      } else {
        form.type = null
        console.warn('[编辑票种] 票种类型为空，使用默认值null')
      }

      // 回填价格信息
      form.payValueYuan = row.payValue ? (row.payValue / 100) : 0
      form.actualValueYuan = row.actualValue ? (row.actualValue / 100) : 0

      // 回填库存信息（根据票种类型）
      if (form.type === 0) {
        // 普通票：回填普通库存
        form.stock = row.stock || 0
        console.log('[编辑票种] 普通票库存:', form.stock)

        // 回填总库存与状态（后端@Valid要求必填，编辑时保持原值）
        form.totalStock = row.totalStock || row.stock || 0
        form.status = row.status ?? 1

        // 清空秒杀相关字段（防止数据残留）
        form.seckillStock = null
        form.beginTime = ''
        form.endTime = ''
      } else if (form.type === 1) {
        // 秒杀票：回填秒杀相关配置
        form.seckillStock = row.seckillStock ?? 0

        // 回填总库存与状态（秒杀票的总库存即秒杀放票量）
        form.totalStock = row.totalStock || row.seckillStock || 1
        form.status = row.status ?? 1

        // 格式化秒杀开始时间（支持多种时间格式）
        if (row.beginTime) {
          try {
            const beginDate = new Date(row.beginTime)
            if (!isNaN(beginDate.getTime())) {
              form.beginTime = formatDateTimeForInput(beginDate)
              console.log('[编辑票种] 秒杀开始时间:', form.beginTime)
            } else {
              console.warn('[编辑票种] 秒杀开始时间无效:', row.beginTime)
              form.beginTime = ''
            }
          } catch (e) {
            console.error('[编辑票种] 解析秒杀开始时间失败:', e)
            form.beginTime = ''
          }
        } else {
          form.beginTime = ''
          console.warn('[编辑票种] 秒杀开始时间为空')
        }

        // 格式化秒杀结束时间（支持多种时间格式）
        if (row.endTime) {
          try {
            const endDate = new Date(row.endTime)
            if (!isNaN(endDate.getTime())) {
              form.endTime = formatDateTimeForInput(endDate)
              console.log('[编辑票种] 秒杀结束时间:', form.endTime)
            } else {
              console.warn('[编辑票种] 秒杀结束时间无效:', row.endTime)
              form.endTime = ''
            }
          } catch (e) {
            console.error('[编辑票种] 解析秒杀结束时间失败:', e)
            form.endTime = ''
          }
        } else {
          form.endTime = ''
          console.warn('[编辑票种] 秒杀结束时间为空')
        }

        // 清空普通库存（防止数据残留）
        form.stock = 0
      }

      // 格式化定时上架时间（通用）
      if (row.scheduledPublishTime) {
        try {
          const date = new Date(row.scheduledPublishTime)
          if (!isNaN(date.getTime())) {
            form.scheduledPublishTime = formatDateTimeForInput(date)
          } else {
            form.scheduledPublishTime = ''
          }
        } catch (e) {
          form.scheduledPublishTime = ''
        }
      } else {
        form.scheduledPublishTime = ''
      }

      // 回填座位配置信息
      // 注意：后端现已支持hasSeats字段，这里应该能正确获取到值
      console.log('[编辑票种] 原始hasSeats值:', row.hasSeats, '类型:', typeof row.hasSeats)

      if (row.hasSeats === true || row.hasSeats === 1 || row.hasSeats === '1' || row.hasSeats === 'true') {
        form.hasSeats = true
        console.log('[编辑票种] ✅ 回填成功 - 需要选座')
      } else if (row.hasSeats === false || row.hasSeats === 0 || row.hasSeats === '0' || row.hasSeats === 'false') {
        form.hasSeats = false
        console.log('[编辑票种] ✅ 回填成功 - 不需要选座')
      } else {
        // 兼容旧数据或异常情况
        form.hasSeats = false
        console.warn('[编辑票种] ⚠️ hasSeats值为:', row.hasSeats, ', 使用默认值false (可能是旧数据)')
      }

      console.log('[编辑票种] 最终配置:', {
        hasSeats: form.hasSeats,
        原始值: row.hasSeats,
        类型: typeof row.hasSeats
      })

      console.log('[编辑票种] 数据回填完成，打开编辑弹窗')
      console.log('[编辑票种] 回填后的form数据:', JSON.stringify({...form}, null, 2))
      showEdit.value = true
    }

    // 格式化日期时间为datetime-local输入框格式（YYYY-MM-DDTHH:mm）
    const formatDateTimeForInput = (date) => {
      if (!(date instanceof Date) || isNaN(date.getTime())) return ''

      const year = date.getFullYear()
      const month = String(date.getMonth() + 1).padStart(2, '0')
      const day = String(date.getDate()).padStart(2, '0')
      const hours = String(date.getHours()).padStart(2, '0')
      const minutes = String(date.getMinutes()).padStart(2, '0')

      return `${year}-${month}-${day}T${hours}:${minutes}`
    }

    /**
     * ✅ 格式化Date为本地时间的ISO字符串（修复时区问题）
     *
     * 问题背景：
     * - toISOString() 会转换为UTC时间（北京时间会少8小时）
     * - 后端使用LocalDateTime.now()（服务器本地时间）进行比较
     * - 如果发送UTC时间，会导致时间偏差8小时
     *
     * 解决方案：
     * - 直接输出本地时间的ISO格式字符串
     * - 格式：YYYY-MM-DDTHH:mm:ss （不带Z后缀，不带时区偏移）
     * - 后端LocalDateTime会按服务器本地时区解析，保证一致性
     *
     * @param {Date} date - JavaScript Date对象
     * @returns {string} 本地时间的ISO格式字符串
     */
    const formatLocalDateTime = (date) => {
      if (!(date instanceof Date) || isNaN(date.getTime())) return ''

      const year = date.getFullYear()
      const month = String(date.getMonth() + 1).padStart(2, '0')
      const day = String(date.getDate()).padStart(2, '0')
      const hours = String(date.getHours()).padStart(2, '0')
      const minutes = String(date.getMinutes()).padStart(2, '0')
      const seconds = String(date.getSeconds()).padStart(2, '0')

      // ✅ 返回纯本地时间字符串，不包含时区信息
      return `${year}-${month}-${day}T${hours}:${minutes}:${seconds}`
    }

    const deleteTicket = async (row) => {
      if (!row || !row.id || row.id === 'null' || row.id === 'undefined') {
        alert('错误：票种ID无效，无法删除')
        console.error('[删除票种] ID无效:', row?.id, '\n调用栈:', new Error().stack)
        return
      }

      // ✅ 安全日志：记录删除操作的调用来源
      console.log('[删除票种] 用户主动点击删除按钮', {
        ticketId: row.id,
        ticketTitle: row.title,
        timestamp: new Date().toISOString(),
        caller: new Error().stack?.split('\n')?.[2]?.trim()
      })

      const superAdmin = isSuperAdmin()
      const active = isTicketActive(row)

      // ⚠️ 删除操作的特殊检查
      if (superAdmin && active) {
        let warningMsg = '⚠️ 【超级管理员强制操作】\n\n'
        warningMsg += `您正在强制删除票种："${row.title}"\n\n`
        warningMsg += '⚠️ 此操作不可恢复！\n'

        if (row.isSeckill || row.type === 1) {
          warningMsg += '⚠️ 该票种为秒杀票，且秒杀活动已开始！\n'
          warningMsg += '⚠️ 强制删除可能导致数据不一致！\n'
        } else if (row.soldCount && row.soldCount > 0) {
          warningMsg += `⚠️ 该票种已有 ${row.soldCount} 个已售订单！\n`
          warningMsg += '⚠️ 删除后订单记录将变为孤儿数据！\n'
        } else {
          warningMsg += '⚠️ 该票种目前正在销售中！\n'
        }

        warningMsg += '\n请确认您了解此操作的严重后果。'

        if (!confirm(warningMsg)) {
          return
        }

        // 第二次确认
        if (!confirm('🚨 最后确认：确定要永久删除此票种吗？')) {
          return
        }
      } else if (!superAdmin && active) {
        // 普通管理员尝试删除可售卖的票种
        alert('权限不足\n该票种目前处于可售卖状态，普通管理员无法删除')
        return
      } else {
        // 普通管理员正常删除（非可售卖状态）
        let confirmMsg = `确定要删除票种"${row.title}"吗？此操作不可恢复！`

        if (row.soldCount && row.soldCount > 0) {
          confirmMsg += `\n\n⚠️ 注意：该票种已有 ${row.soldCount} 个已售订单`
        }

        if (!confirm(confirmMsg)) {
          return
        }
      }

      try {
        console.log('删除票种, ID:', row.id)
        const res = await api.deleteTicket(row.id)
        console.log('删除票种响应:', res)
        alert('票种删除成功')
        await fetchTickets()
      } catch (err) {
        console.error('删除票种失败:', err)

        let errorDetail = err.message || '未知错误'

        if (err.isBusinessError) {
          errorDetail = err.message
        } else if (err.isHttpError && err.response?.data) {
          const responseData = err.response.data
          if (responseData.errorMsg) {
            errorDetail = responseData.errorMsg
          } else if (responseData.message && responseData.message.length < 200) {
            errorDetail = responseData.message
          }
        }

        alert(`删除失败: ${errorDetail}`)
      }
    }

    const toggleTicketStatus = async (row) => {
      const newStatus = row.status === 1 ? 0 : 1
      const actionText = newStatus === 1 ? '上架' : '下架'

      // ⚠️ 下架操作的特殊检查
      if (newStatus === 0) {
        const superAdmin = isSuperAdmin()
        const active = isTicketActive(row)

        // 超级管理员强制下架：需要二次确认
        if (superAdmin && active) {
          let warningMsg = '⚠️ 【超级管理员强制操作】\n\n'
          warningMsg += `您正在强制下架票种："${row.title}"\n\n`

          if (row.isSeckill || row.type === 1) {
            warningMsg += '⚠️ 该票种为秒杀票，且秒杀活动已开始！\n'
            warningMsg += '⚠️ 强制下架可能导致正在购买的用户订单失败！\n\n'
          } else {
            warningMsg += '⚠️ 该票种目前正在销售中！\n'
            warningMsg += '⚠️ 强制下架可能影响用户体验！\n\n'
          }

          warningMsg += '请确认您了解此操作的后果。'

          if (!confirm(warningMsg)) {
            return
          }

          // 第三次确认（防止误操作）
          if (!confirm('🚨 最后确认：确定要强制下架吗？此操作不可逆！')) {
            return
          }
        }

        // 普通管理员尝试下架可售卖的票种（理论上按钮应该被禁用，但双重保险）
        if (!superAdmin && active) {
          alert('权限不足\n该票种目前处于可售卖状态，普通管理员无法下架')
          return
        }

        // 普通管理员正常下架（非可售卖状态）
        if (!superAdmin && !active) {
          if (!confirm(`确定要下架票种"${row.title}"吗？`)) {
            return
          }
        }
      } else {
        // 上架操作
        if (!confirm(`确定要上架票种"${row.title}"吗？`)) {
          return
        }
      }

      try {
        console.log(`${actionText}票种, ID:`, row.id, '新状态:', newStatus)

        const res = await api.toggleTicketStatus(row.id, newStatus)
        console.log(`${actionText}票种响应:`, res)
        alert(`票种${actionText}成功`)
        await fetchTickets()
      } catch (err) {
        console.error(`${actionText}票种失败:`, err)

        let errorDetail = err.message || '未知错误'

        if (err.isBusinessError) {
          errorDetail = err.message
        } else if (err.isHttpError && err.response?.data) {
          const responseData = err.response.data
          if (responseData.errorMsg) {
            errorDetail = responseData.errorMsg
          } else if (responseData.message && responseData.message.length < 200) {
            errorDetail = responseData.message
          }
        }

        alert(`${actionText}失败: ${errorDetail}`)
      }
    }

    const closeForm = () => {
      showCreate.value = false
      showEdit.value = false
      form.id = null
      form.showId = null
      form.title = ''
      form.type = null // 重置票种类型
      form.payValueYuan = 0
      form.actualValueYuan = 0
      form.stock = 0 // 重置普通库存
      form.seckillStock = null // 重置秒杀库存
      form.beginTime = '' // 重置秒杀开始时间
      form.endTime = '' // 重置秒杀结束时间
      form.scheduledPublishTime = '' // 重置定时上架时间
      form.totalStock = null // 重置总库存（后端@NotNull必填）
      form.status = null // 重置状态（后端@NotNull必填）

      // 重置座位配置（简化版）
      form.hasSeats = false

      // 重置座位区域配置（恢复默认值）
      form.seatAreas = [
        { name: 'A区', rows: 10, cols: 20 }
      ]

      // 重置模板选择
      form.selectedTemplate = ''

      // 重置模板选择器显示状态
      form.showTemplateSelector = false

      // 重置审计字段 (v4.0)
      form.remark = ''
      cachedIdempotencyKey = null
    }

    const submitForm = async () => {
      // 基础字段验证
      if (!form.showId) {
        alert('[警告] 请选择所属演出')
        return
      }

      if (!form.title || !form.title.trim()) {
        alert('[警告] 请输入票种名称')
        return
      }

      // 名称长度限制（防XSS和数据库溢出）
      if (form.title.length > 100) {
        alert(`[警告] 票种名称过长（${form.title.length}字符），请控制在100字符以内`)
        return
      }

      if (form.type === null || form.type === '') {
        alert('[警告] 请选择票种类型（普通票或秒杀票）')
        return
      }

      // 价格验证（必须大于0）
      if (form.payValueYuan == null || form.payValueYuan <= 0) {
        alert('[警告] 请输入有效的售价（必须大于0）')
        return
      }

      if (form.actualValueYuan == null || form.actualValueYuan < 0) {
        alert('[警告] 请输入有效的原价（不能为负数）')
        return
      }

      // 价格精度处理（保留2位小数，避免浮点数精度问题）
      form.payValueYuan = Math.round(form.payValueYuan * 100) / 100
      form.actualValueYuan = Math.round(form.actualValueYuan * 100) / 100

      // 价格合理性检查（售价应 <= 原价：原价是划线展示价，售价是成交价）
      if (form.payValueYuan > form.actualValueYuan && form.actualValueYuan > 0) {
        alert(`[提示] 售价(¥${form.payValueYuan})高于原价(¥${form.actualValueYuan})\n\n原价是划线展示用的参考价，通常应不低于售价。如确认无优惠意图，请把两个价格填成一样。`)
        return
      }

      // 库存验证
      if (form.type === 0) {
        // 普通票：验证普通库存
        if (form.stock == null || form.stock === '' || form.stock < 0) {
          alert('[警告] 请输入有效的普通库存数量（不能为负数）')
          return
        }
        if (form.stock > 999999) {
          alert('[警告] 普通库存数量过大，最大允许999,999张')
          return
        }
      } else if (form.type === 1) {
        // 秒杀票：严格验证

        // 秒杀库存
        if (!form.seckillStock || form.seckillStock <= 0) {
          alert('[警告] 请输入有效的秒杀库存数量（必须大于0）')
          return
        }
        if (form.seckillStock > 99999) {
          alert('[警告] 秒杀库存过大，最大允许99,999张\n\n提示：秒杀票通常限量销售，建议设置较小数量')
          return
        }

        // 时间配置验证
        if (!form.beginTime) {
          alert('[警告] 请设置秒杀开始时间')
          return
        }

        if (!form.endTime) {
          alert('[警告] 请设置秒杀结束时间')
          return
        }

        const beginDate = new Date(form.beginTime)
        const endDate = new Date(form.endTime)
        const now = new Date()

        // 时间逻辑检查
        if (beginDate >= endDate) {
          alert('[警告] 秒杀结束时间必须晚于开始时间\n\n建议活动持续时间至少5分钟')
          return
        }

        // 最小持续时间检查（至少1分钟）
        const durationMinutes = (endDate - beginDate) / (1000 * 60)
        if (durationMinutes < 1) {
          alert('[警告] 秒杀活动持续时间太短（少于1分钟）\n\n请调整结束时间，建议至少5-30分钟')
          return
        }

        // 检查是否已过期
        if (endDate <= now) {
          alert(`[警告] 秒杀结束时间已过期！\n\n当前时间: ${now.toLocaleString()}\n结束时间: ${endDate.toLocaleString()}\n\n请设置未来的时间`)
          return
        }
      }

      // 座位配置验证（新建票种且需要选座时）
      if (!showEdit.value && form.hasSeats === true) {
        if (!isSeatConfigValid()) {
          return // 验证失败，终止提交
        }
      }

      const data = {
        showId: form.showId,
        title: form.title.trim(),
        type: form.type, // 票种类型：0=普通票, 1=秒杀票
        payValue: Math.round(form.payValueYuan * 100),
        actualValue: Math.round(form.actualValueYuan * 100),
        scheduledPublishTime: form.scheduledPublishTime ? formatLocalDateTime(new Date(form.scheduledPublishTime)) : null,

        // 是否需要选座（已支持后端保存）
        hasSeats: form.hasSeats === true
      }

      console.log('[提交表单] 完整数据:', {
        ...data,
        hasSeats: data.hasSeats,
        hasSeats类型: typeof data.hasSeats
      })

      // 根据票种类型设置不同的库存字段
      if (form.type === 0) {
        // 普通票：使用普通库存
        // 座位票兜底：新建时库存强制等于座位数（一张票对应一个座位）
        data.stock = (form.hasSeats === true && !showEdit.value)
          ? totalFormSeats.value
          : form.stock
        // 后端Ticket实体的totalStock为@NotNull+@Min(1)必填字段
        // 新建：总库存=初始库存；编辑：只增不减，防止破坏 total_stock = stock + soldCount
        data.totalStock = showEdit.value
          ? Math.max(form.totalStock || 0, form.stock || 0)
          : Math.max(data.stock || 0, 1)
      } else if (form.type === 1) {
        // 秒杀票：使用秒杀配置
        // 座位票兜底：新建时秒杀库存强制等于座位数
        data.seckillStock = (form.hasSeats === true && !showEdit.value)
          ? totalFormSeats.value
          : form.seckillStock

        // ✅ 修复时区问题：不使用toISOString()（会转为UTC，少8小时）
        // 直接使用本地时间的ISO格式字符串（不带Z后缀）
        const beginDate = new Date(form.beginTime)
        const endDate = new Date(form.endTime)

        data.beginTime = formatLocalDateTime(beginDate)  // "2026-08-24T20:05:00"
        data.endTime = formatLocalDateTime(endDate)      // "2026-08-24T23:59:00"
        data.stock = 0 // 秒杀票的普通库存为0
        // 秒杀票的总库存即秒杀放票量（秒杀库存存于tb_seckill_ticket）
        data.totalStock = showEdit.value
          ? (form.totalStock || Math.max(form.seckillStock || 0, 1))
          : Math.max(form.seckillStock || 0, 1)

        console.log('[提交表单] 秒杀票数据:', {
          seckillStock: data.seckillStock,
          totalStock: data.totalStock,
          beginTime: data.beginTime,  // 本地时间 ✅
          endTime: data.endTime,      // 本地时间 ✅
          _debug_now: new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }),
          _debug_begin: beginDate.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }),
          _debug_end: endDate.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })
        })
      }

      // 后端Ticket实体的status为@NotNull必填字段（0=下架, 1=上架）
      // 新建：有定时上架时间→0（下架待发布），否则→1（立即上架），与后端createTicket默认逻辑一致
      // 编辑：保持数据库当前状态，避免编辑下架票种时被意外上架
      data.status = form.scheduledPublishTime ? 0 : (showEdit.value ? (form.status ?? 1) : 1)

      try {
        console.log('提交票种表单, 数据:', data)

        if (showEdit.value) {
          data.id = form.id
          const res = await api.put('/admin/ticket', data)
          console.log('更新票种响应:', res)
          alert('票种更新成功')
        } else {
          // 创建新票种 - 根据是否需要选座选择不同接口
          let res

          if (form.hasSeats === true) {
            console.log('[新建票种] 🔥 使用事务性接口创建票种+座位（原子操作）')

            let areasToGenerate = []

            if (form.selectedTemplate !== '' && getCurrentTemplate.value) {
              areasToGenerate = getCurrentTemplate.value.areas.map(area => ({
                name: area.name.trim(),
                rows: area.rows,
                cols: area.cols,
                startRow: 1
              }))
              console.log(`[新建票种] 使用模板数据: ${getCurrentTemplate.value.name}`)
            } else {
              areasToGenerate = form.seatAreas.map(area => ({
                name: area.name.trim(),
                rows: area.rows,
                cols: area.cols,
                startRow: 1
              }))
              console.log('[新建票种] 使用自定义配置数据')
            }

            const requestData = {
          ticket: data,
          seatConfig: {
            areas: areasToGenerate
          },
          remark: form.remark.trim() || undefined
        }

        console.log('[新建票种] 📝 请求数据（v4.0）:', {
          hasRemark: !!requestData.remark,
          remarkPreview: requestData.remark ? requestData.remark.substring(0, 20) + '...' : '(无)'
        })

        try {
          res = await api.createTicketWithSeats(requestData)

          // 缓存幂等键用于可能的后续重试
          cachedIdempotencyKey = res._idempotencyKey || null

          if (cachedIdempotencyKey) {
            console.log('[新建票种] 🔑 幂等键已缓存（用于安全重试）')
          }

          console.log('[新建票种] ✅ 事务性创建响应:', res)
        } catch (firstError) {
          console.warn('[新建票种] ⚠️ 首次请求失败（可能是预检请求或token刷新）:', firstError.message)

          if (firstError.isHttpError && firstError.statusCode === 403) {
            console.log('[新建票种] 🔄 检测到403，等待500ms后重试一次...')
            await new Promise(resolve => setTimeout(resolve, 500))

            try {
              res = await api.createTicketWithSeats(requestData)
              console.log('[新建票种] ✅ 重试成功:', res)
            } catch (retryError) {
              console.error('[新建票种] ❌ 重试也失败:', retryError)
              throw retryError
            }
          } else {
            throw firstError
          }
        }

            if (res.code === 200 || res.success) {
              const resultData = res.data || {}
              const totalSeats = resultData.totalSeats || totalFormSeats.value
              const seatCount = resultData.seatCount || 0
              const ticketId = resultData.ticketId || resultData.id

              console.log('[新建票种] 📊 座位统计 - totalSeats:', totalSeats, ', seatCount:', seatCount)

              alert(`票种和座位创建成功\n名称：${form.title}\nID：${ticketId}\n座位数：${seatCount}个`)

              if (seatCount > 0) {
                console.log('[新建票种] ✅ 座位已生成，无需再次生成')
              } else {
                console.warn('[新建票种] ⚠️ 座位数为0，可能需要检查')
              }
            } else {
              throw new Error(res.msg || '事务性创建失败，已自动回滚')
            }
          } else {
            console.log('[新建票种] 使用传统接口创建无需选座的票种')
            res = await api.post('/admin/ticket', data)
            console.log('创建票种响应:', res)
            alert('票种创建成功')
          }
        }

        const currentShowId = form.showId
        closeForm()
        console.log('关闭弹窗，开始刷新列表...')

        // 关键：自动清理该演出的缓存，让用户端立即看到新数据
        if (currentShowId) {
          try {
            const cacheRes = await api.delete(`/ticket/cache/${currentShowId}`)
            console.log('[自动清理缓存] 成功, showId:', currentShowId, '响应:', cacheRes)
          } catch (cacheErr) {
            console.warn('[自动清理缓存] 失败（不影响主流程）, showId:', currentShowId, '错误:', cacheErr.message)
          }
        } else {
          console.warn('[自动清理缓存] 跳过 - showId 为空')
        }

        await fetchTickets()
        console.log('列表刷新完成（用户端已可立即看到最新票种数据）')
      } catch (err) {
        console.error('提交表单失败:', err)

        let errorDetail = err.message || '未知错误'

        if (err.isBusinessError) {
          errorDetail = err.message
        } else if (err.isHttpError && err.response?.data) {
          const responseData = err.response.data
          if (responseData.errorMsg) {
            errorDetail = responseData.errorMsg
          } else if (responseData.message && responseData.message.length < 200) {
            errorDetail = responseData.message
          }
        }

        // 判断是否为网络超时错误（可重试）
        const isTimeoutError =
          err.isNetworkError ||
          err.name === 'TypeError' ||
          (err.statusCode >= 500 && err.statusCode < 600) ||
          err.message.includes('超时') ||
          err.message.includes('timeout')

        // 如果有缓存的幂等键且是超时错误，提供安全重试选项
        if (isTimeoutError && cachedIdempotencyKey) {
          console.warn('[新建票种] ⏰ 检测到超时错误，幂等键已缓存，可进行安全重试')
          console.warn('[新建票种] 🔑 缓存的幂等键:', cachedIdempotencyKey.substring(0, 8) + '...')

          const shouldRetry = confirm(
            `⚠️ 网络请求超时或服务器无响应\n\n` +
            `错误信息：${errorDetail}\n\n` +
            `✅ 您可以选择"确定"使用相同的安全凭证重试\n` +
            `   （不会重复创建票种，后端会自动识别）\n\n` +
            `❌ 选择"取消"放弃本次操作`
          )

          if (shouldRetry) {
            try {
              console.log('[新建票种] 🔄 用户选择安全重试...')

              const retryData = {
                ticket: data,
                seatConfig: { areas: areasToGenerate },
                remark: form.remark.trim() || '安全重试'
              }

              const retryRes = await api.retryCreateTicketWithSeats(retryData, cachedIdempotencyKey)

              if (retryRes.code === 200 || retryRes.success) {
                const resultData = retryRes.data || {}
                const seatCount = resultData.seatCount || 0
                const ticketId = resultData.ticketId || resultData.id

                alert(`✅ 重试成功！票种和座位创建成功\n名称：${form.title}\nID：${ticketId}\n座位数：${seatCount}个`)

                cachedIdempotencyKey = null
                closeForm()

                const currentShowId = form.showId
                await fetchTickets()

                if (currentShowId) {
                  try {
                    await api.delete(`/ticket/cache/${currentShowId}`)
                  } catch (cacheErr) {
                    console.warn('[自动清理缓存] 失败:', cacheErr)
                  }
                }

                return
              } else {
                throw new Error(retryRes.msg || '重试失败')
              }
            } catch (retryErr) {
              console.error('[新建票种] ❌ 安全重试也失败:', retryErr)
              errorDetail = `首次请求和重试均失败:\n首次: ${errorDetail}\n重试: ${retryErr.message}`
            }
          } else {
            console.log('[新建票种] ❌ 用户取消重试')
            errorDetail = `${errorDetail}（用户未选择重试）`
          }

          cachedIdempotencyKey = null
        }

        alert(`操作失败: ${errorDetail}`)
      }
    }

    const formatPrice = (val) => {
      if (val == null) return '0.00'
      return (val / 100).toFixed(2)
    }

    // 获取秒杀票的状态文本
    const getSeckillStatusText = (row) => {
      if (!row || row.type !== 1) return ''

      const now = new Date()

      // 检查是否有时间设置
      if (!row.beginTime && !row.endTime) {
        return '未设置时间'
      }

      // 检查是否未开始
      if (row.beginTime) {
        const beginTime = new Date(row.beginTime)
        if (now < beginTime) {
          return '未开始'
        }
      }

      // 检查是否已结束
      if (row.endTime) {
        const endTime = new Date(row.endTime)
        if (now >= endTime) {
          return '已结束'
        }
      }

      // 进行中
      return '进行中'
    }

    // 获取秒杀状态的CSS类名
    const getSeckillStatusClass = (row) => {
      if (!row || row.type !== 1) return ''

      const now = new Date()

      if (row.beginTime && now < new Date(row.beginTime)) {
        return 'seckill-status-pending'  // 未开始
      }

      if (row.endTime && now >= new Date(row.endTime)) {
        return 'seckill-status-ended'   // 已结束
      }

      return 'seckill-status-active'     // 进行中
    }

    onMounted(() => {
      console.log('Tickets组件已挂载，开始加载数据...')
      fetchShows()
      fetchTickets()
    })

    console.log('Tickets组件setup执行完成，返回数据')

    return {
      loading,
      keyword,
      showIdFilter,
      showSearchText,
      showDropdown,
      showList,
      ticketList,
      showCreate,
      showEdit,
      pagination,
      columns,
      form,
      sortBy,
      sortOrder,
      handleSearch,
      resetFilters,
      debugFetch,
      handleSort,
      filteredShowList,
      filterShows,
      selectShow,
      handlePageChange,
      handlePageSizeChange,
      getEditButtonTitle,
      isSuperAdmin,
      isTicketActive,
      getOfflineButtonTitle,
      getActiveStatusTitle,
      getActiveStatusText,
      getDeleteButtonTitle,
      editTicket,
      deleteTicket,
      toggleTicketStatus,
      openSeatGenerator,
      generateSeatsAdvanced,
      applyTemplate,
      addArea,
      removeArea,
      getAreaPlaceholder,
      totalSeatsAcrossAreas,
      avgSeatsPerRow,
      isSeatFormValid,
      seatTemplates,
      selectedTemplate,
      closeForm,
      submitForm,
      formatPrice,
      getSeckillStatusText,
      getSeckillStatusClass,

      // 座位生成相关（必须返回才能在模板中使用）
      showSeatModal,
      currentTicket,
      seatForm,
      generating,

      // 座位提示框相关
      seatTooltip,
      showSeatTooltip,
      hideSeatTooltip,
      updateTooltipPosition,

      // 座位列表详情
      showSeatListModal,
      seatListTicket,
      seatListData,
      seatListLoading,
      viewSeatList,
      refreshSeatList,
      getSeatStatusText,

      // 新建票种座位配置（必须返回才能在模板中使用）
      totalFormSeats,
      totalFormRows,
      addSeatArea,
      removeSeatArea,
      applyTemplateToForm,

      // 新增的模板相关方法
      getCurrentTemplate,
      getTotalTemplateRows,
      switchToTemplateMode,
      switchToCustomMode,
      onTemplateChange
    }
  }
}
</script>

<!-- 座位生成高级对话框专用样式（符合日系轻亚风设计系统） -->
<style scoped>
/* ===== 座位生成器容器 ===== */
.seat-generator-advanced {
  max-height: 70vh;
  overflow-y: auto;
  padding-right: var(--space-2);
}

/* 票种信息头部 */
.ticket-info-header {
  padding: var(--space-3) var(--space-4);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-sm);
  margin-bottom: var(--space-4);
  font-size: var(--font-sm);
  color: var(--color-text-light);
}

.ticket-label {
  margin-right: var(--space-2);
}

/* ===== 模板选择区域 ===== */
.template-section {
  margin-bottom: var(--space-6);
}

.section-title {
  display: block;
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-3);
}

.template-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: var(--space-3);
}

.template-card {
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background-color: #ffffff;
  cursor: pointer;
  transition: all var(--transition-fast);
  text-align: left;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.template-card:hover {
  border-color: var(--color-primary);
  box-shadow: var(--shadow-sm);
  transform: translateY(-1px);
}

.template-card.active {
  background-color: var(--color-bg-alt);
  border-color: var(--color-primary);
  border-width: 2px;
}

.template-name {
  font-weight: 600;
  color: var(--color-text);
  font-size: var(--font-sm);
}

.template-desc {
  font-size: var(--font-xs);
  color: var(--color-text-light);
  line-height: 1.4;
}

/* ===== 区域配置区域 ===== */
.areas-section {
  margin-bottom: var(--space-6);
}

.area-config-card {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  margin-bottom: var(--space-3);
  background-color: #ffffff;
  transition: all var(--transition-fast);
}

.area-config-card:hover {
  box-shadow: var(--shadow-sm);
}

.area-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-3);
  padding-bottom: var(--space-2);
  border-bottom: 1px solid var(--color-border);
}

.area-index {
  font-weight: 600;
  color: var(--color-primary);
  font-size: var(--font-sm);
}

.btn-remove-area {
  width: 24px;
  height: 24px;
  border: none;
  background-color: transparent;
  color: var(--color-text-light);
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
  font-size: var(--font-sm);
  line-height: 1;
}

.btn-remove-area:hover {
  background-color: rgba(196, 135, 123, 0.1);
  color: var(--color-danger);
}

.area-fields {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.field-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--space-3);
}

.field-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.field-item label {
  font-size: var(--font-xs);
  font-weight: 500;
  color: var(--color-text-light);
}

.field-item input {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-base);
  transition: all var(--transition-fast);
}

.field-item input:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px rgba(150, 116, 98, 0.1);
}

.field-name {
  grid-column: 1 / -1;
}

/* 添加区域按钮 */
.btn-add-area {
  width: 100%;
  padding: var(--space-3) var(--space-4);
  border: 2px dashed var(--color-border);
  border-radius: var(--radius-md);
  background-color: transparent;
  color: var(--color-primary);
  font-size: var(--font-sm);
  font-weight: 500;
  cursor: pointer;
  transition: all var(--transition-fast);
  margin-top: var(--space-2);
}

.btn-add-area:hover:not(:disabled) {
  border-color: var(--color-primary);
  background-color: var(--color-bg-alt);
}

.btn-add-area:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ===== 汇总信息区域 ===== */
.summary-section {
  position: sticky;
  bottom: 0;
  background-color: #ffffff;
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-border);
  margin-top: var(--space-4);
}

.summary-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4);
  background-color: var(--color-bg);
  border-radius: var(--radius-md);
}

.summary-details {
  flex: 1;
}

.summary-main {
  font-size: var(--font-lg);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-1);
}

.summary-sub {
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

/* 响应式调整 */
@media (max-width: 768px) {
  .template-grid {
    grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
    gap: var(--space-2);
  }

  .field-row {
    grid-template-columns: 1fr;
  }

  .seat-generator-advanced {
    max-height: 60vh;
  }
}
</style>

<style scoped>
/**
 * TicketsSimple Management Page (v2.0)
 * 完全符合日系轻亚风 x 有机自然感设计规范
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

.search-box input {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  width: 180px;
  font-size: var(--font-sm);
  background-color: var(--color-white);
  outline: none;
  transition: all var(--transition-fast);
}

.search-box input:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(150, 116, 98, 0.08);
}

/* 统一表单元素 */
.show-filter select,
.type-filter select,
.status-filter select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  background: var(--color-white);
  transition: all var(--transition-fast);
  outline: none;
}

.show-filter select:focus,
.type-filter select:focus,
.status-filter select:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(150, 116, 98, 0.08);
}

.add-btn {
  margin-left: auto;
}

.show-filter {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.show-filter label {
  font-size: var(--font-sm);
  color: var(--color-text);
  font-weight: 500;
}

.custom-select {
  position: relative;
  width: 250px;
}

.custom-select input {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  width: 100%;
  box-sizing: border-box;
  background-color: var(--color-white);
  outline: none;
  transition: all var(--transition-fast);
}

.custom-select input:focus {
  border-color: var(--color-primary);
}

.dropdown-list {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  background-color: var(--color-white);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm); /* 6px替代4px */
  max-height: 200px;
  overflow-y: auto;
  z-index: 1000;
  box-shadow: var(--shadow-md); /* 使用设计令牌 */
  list-style: none;
  margin: var(--space-1) 0 0 0; /* 2px → 变量 */
  padding: 0;
}

.dropdown-list li {
  padding: var(--space-2) var(--space-3);
  cursor: pointer;
  transition: background-color var(--transition-fast); /* 0.2s → 变量 */
}

.dropdown-list li:hover,
.dropdown-list li.active {
  background-color: rgba(166, 186, 174, 0.15);
  color: var(--color-secondary);
}

.type-badge {
  display: inline-block;
  padding: var(--space-1) calc(var(--space-3) + var(--space-2));
  border-radius: var(--radius-md);
  font-size: var(--font-sm);
  font-weight: 600;
  letter-spacing: 0px;
}

/* 票种类型标签（灰绿/米白配色） */
.type-normal {
  background-color: rgba(166, 186, 174, 0.3);
  color: var(--color-text);
  border: 1px solid rgba(166, 186, 174, 0.5);
}

.type-seckill {
  background-color: rgba(236, 224, 202, 0.45);
  color: var(--color-secondary);
  border: 1px solid rgba(150, 116, 98, 0.35);
}

/* ===== 座位信息单元格（简洁设计）===== */
.seat-info-cell {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  font-size: var(--font-xs);
  font-weight: 500;
  position: relative;
  transition: all var(--transition-fast);
  overflow: visible; /* 🔥 确保tooltip不被裁剪 */
}

/* 需选座（灰绿背景，突出显示） */
.seat-required {
  background-color: rgba(166, 186, 174, 0.2);
  color: var(--color-secondary);
  border: 1px solid rgba(166, 186, 174, 0.4);
}

.seat-required .seat-icon {
  width: 12px;
  height: 12px;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%235d3e3f' stroke-width='2'%3E%3Crect x='4' y='6' width='16' height='12' rx='2'/%3E%3Cline x1='8' y1='10' x2='16' y2='10'/%3E%3Cline x1='8' y1='14' x2='14' y2='14'/%3E%3C/svg%3E");
  background-size: contain;
  background-repeat: no-repeat;
  flex-shrink: 0;
}

/* 免选座（浅色，低调显示） */
.seat-not-required {
  color: var(--color-text-light);
  font-weight: 400;
}

/* ===== 全局座位提示框（fixed定位，避免被表格overflow裁剪）===== */
.seat-tooltip-global {
  position: fixed;
  z-index: 99999; /* 最高层级，确保在所有元素之上 */
  transform: translate(-50%, -100%); /* 居中并向上偏移 */
  pointer-events: none; /* 不影响鼠标事件 */
  animation: tooltipFadeIn 0.2s ease-out;
}

@keyframes tooltipFadeIn {
  from {
    opacity: 0;
    transform: translate(-50%, -90%);
  }
  to {
    opacity: 1;
    transform: translate(-50%, -100%);
  }
}

.seat-tooltip-content {
  background-color: var(--color-secondary);
  color: #ffffff;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--font-xs);
  white-space: nowrap;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.25);
  line-height: 1.4;
}

.seat-tooltip-content strong {
  display: block;
  margin-bottom: var(--space-1);
  font-weight: 600;
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 1px;
  opacity: 0.9;
}

.seat-tooltip-content p {
  margin: 0;
  font-size: var(--font-xs);
}

.seat-tooltip-arrow {
  position: absolute;
  left: 50%;
  bottom: -5px;
  transform: translateX(-50%);
  width: 0;
  height: 0;
  border-left: 6px solid transparent;
  border-right: 6px solid transparent;
  border-top: 6px solid var(--color-secondary);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-4);
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-1); /* 6px → 变量 */
}

.form-item label {
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-text);
}

.required {
  color: var(--color-danger);
}

.form-item input,
.form-item select {
  padding: var(--space-2) calc(var(--space-2) + var(--space-1));
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  width: 100%;
  box-sizing: border-box;
  transition: border-color var(--transition-fast);
}

.form-item input:focus,
.form-item select:focus {
  outline: none;
  border-color: var(--color-accent);
}

.action-buttons {
  display: flex;
  gap: var(--space-2); /* 8px → 变量 */
}

/* 秒杀标签（米白色系） */
.seckill-badge {
  background-color: rgba(236, 224, 202, 0.45);
  color: var(--color-secondary);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  font-size: var(--font-xs);
  font-weight: 500;
}

.stock-badge {
  display: inline-block;
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  font-weight: 600;
}

/* 库存状态（灰绿/米白配色） */
.stock-normal {
  background-color: rgba(166, 186, 174, 0.25);
  color: var(--color-text);
  border: 1px solid rgba(166, 186, 174, 0.4);
}

.stock-seckill {
  background-color: rgba(236, 224, 202, 0.35);
  color: var(--color-secondary);
  border: 1px solid rgba(150, 116, 98, 0.3);
}

/* 库存信息容器 */
.normal-stock-info,
.seckill-stock-info {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

/* 库存主信息（剩余） */
.stock-main {
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 库存副信息（总/已售）- 显示在下方 */
.stock-sub {
  font-size: var(--font-xs);
  color: var(--color-text-light);
  line-height: 1.2;
  text-align: center;
}

.no-seckill,
.no-stock {
  color: var(--color-text-light);
  font-size: var(--font-sm); /* 13px → 变量 */
}

.datetime-picker {
  display: flex;
  flex-direction: column;
  gap: var(--space-1); /* 4px → 变量 */
}

.datetime-picker input[type="datetime-local"] {
  padding: var(--space-2) var(--space-3); /* 10px 12px → 变量 */
  border: 1px solid var(--color-border); /* #ddd → 变量 */
  border-radius: var(--radius-sm); /* 6px → 变量 */
  font-size: var(--font-sm); /* 14px → 变量 */
  width: 100%;
  box-sizing: border-box;
  outline: none;
  transition: all var(--transition-fast);
}

.datetime-picker input[type="datetime-local"]:focus {
  border-color: var(--color-primary);
}

.field-hint {
  font-size: var(--font-xs); /* 12px → 变量 */
  color: var(--color-text-light); /* #888 → 变量 */
}

/* 状态标签（使用设计系统规范） */
.status-tag {
  padding: var(--space-1) calc(var(--space-2) + var(--space-1)); /* 2px 8px → 变量 */
  border-radius: var(--radius-sm); /* 4px → 6px */
  font-size: var(--font-xs);
  font-weight: 500;
}

/* 上架/下架状态（灰绿/暖红配色） */
.status-online {
  background-color: rgba(166, 186, 174, 0.25);
  color: var(--color-text);
}

.status-offline {
  background-color: rgba(196, 135, 123, 0.15);
  color: var(--color-secondary);
}

/* 列宽优化：文本截断 */
:deep(td) {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

:deep(.cell-title),
:deep(.cell-showName) {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 价格信息容器 */
.price-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.price-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

/* 价格信息样式 */
.price-label {
  font-size: var(--font-xs); /* 12px → 变量 */
  color: var(--color-text-light); /* #666 → 变量 */
  min-width: calc(var(--space-8) + var(--space-0)); /* ~32px */
}

.price-actual {
  color: var(--color-text-light); /* #999 → 变量 */
}

.price-value {
  font-size: var(--font-sm); /* 13px → 变量 */
  font-weight: 600;
  color: var(--color-text); /* #333 → 变量 */
}

.price-actual-value {
  color: var(--color-text-light); /* #666 → 变量 */
  font-weight: normal;
}

/* 库存信息容器 */
.stock-info {
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 状态信息容器 */
.status-info-container {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}

.status-main {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.locked-mini {
  font-size: 14px;
  line-height: 1;
}

/* 操作按钮优化（使用设计系统规范） */
.action-buttons {
  display: flex;
  gap: var(--space-1); /* 6px → 变量 */
  flex-wrap: wrap; /* 允许换行：配合窄列宽，按钮多时折行而不是撑出横向滚动 */
  justify-content: flex-start;
}

.action-buttons button {
  white-space: nowrap;
  padding: var(--space-1) calc(var(--space-2) + var(--space-2)) !important; /* 4px 10px → 变量 */
  font-size: var(--font-xs) !important; /* 12px → 变量 */
}

/* 禁用按钮样式（已上架票种的编辑按钮） */
.action-buttons button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background-color: var(--color-bg-alt) !important;
  color: var(--color-text-light) !important;
  border-color: var(--color-border) !important;
}

.action-buttons button:disabled:hover {
  background-color: var(--color-bg-alt) !important;
  transform: none !important;
}

/* 状态容器 - 支持多状态并排显示 */
.status-container {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}

/* 秒杀状态标签 - 粉彩柔和风 */
.seckill-status-tag {
  display: inline-block;
  padding: var(--space-1) calc(var(--space-2) + var(--space-1));
  border-radius: var(--radius-sm);
  font-size: var(--font-xs);
  font-weight: 600;
  letter-spacing: 0px;
  white-space: nowrap;
  transition: all var(--transition-base);
}

/* 秒杀状态（米白/灰绿/暖红配色） */
.seckill-status-pending {
  /* 未开始 - 米白色 */
  background-color: rgba(236, 224, 202, 0.45);
  color: var(--color-secondary); /* 陶土棕 */
  border: 1px solid rgba(150, 116, 98, 0.35);
}

.seckill-status-active {
  /* 进行中 - 灰绿色 */
  background-color: rgba(166, 186, 174, 0.3);
  color: var(--color-text); /* 深绿 */
  border: 1px solid rgba(166, 186, 174, 0.45);
}

.seckill-status-ended {
  /* 已结束 - 暖红色 */
  background-color: rgba(196, 135, 123, 0.15);
  color: var(--color-secondary); /* 深褐 */
  border: 1px solid rgba(196, 135, 123, 0.3);
}

.locked-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  align-items: flex-start;
}

/* 锁定徽章（米白色系） */
.locked-badge {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  color: var(--color-secondary); /* #a16207 → 变量 */
  font-weight: 600;
  font-size: var(--font-xs);
  padding: var(--space-1) var(--space-2);
  background-color: rgba(236, 224, 202, 0.45); /* 奶油黄 → 米白 */
  border: 1px solid rgba(150, 116, 98, 0.35); /* 金色边框 → 陶土棕 */
  border-radius: var(--radius-sm);
}

.locked-tooltip {
  color: var(--color-text-light);
  font-size: var(--font-xs);
  line-height: 1.3;
}

.unlocked-text {
  color: var(--color-success-dark);
  font-size: var(--font-xs);
}

/* 票种类型选择器样式 - 与其他页面保持一致 */
.form-item-type-selector {
  /* flex布局下无需特殊处理 */
}

/* 票种类型选择器（统一表单样式） */
.type-select {
  padding: var(--space-2) var(--space-3); /* 10px 12px → 变量 */
  border: 1px solid var(--color-border); /* #ddd → 变量 */
  border-radius: var(--radius-sm); /* 6px → 变量 */
  font-size: var(--font-sm); /* 14px → 变量 */
  background-color: var(--color-white); /* white → 变量 */
  width: 100%;
  box-sizing: border-box;
  cursor: pointer;
  outline: none;
  transition: all var(--transition-fast);
}

.type-select:focus {
  border-color: var(--color-primary);
}

.type-hint {
  margin-top: var(--space-1); /* 4px → 变量 */
  font-size: var(--font-xs); /* 12px → 变量 */
  color: var(--color-text-light); /* #888 → 变量 */
  line-height: 1.5;
}

.type-warning {
  margin-top: var(--space-1); /* 4px → 变量 */
  font-size: var(--font-xs); /* 12px → 变量 */
  color: var(--color-danger, #c4877b); /* #e74c3c → 温暖红 */
  line-height: 1.5;
}

/* ===== 座位配置区域样式（符合设计规范）===== */

.form-section-title {
  grid-column: 1 / -1;
  margin-top: var(--space-6);
  padding-bottom: var(--space-3);
  margin-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-border);
  font-family: var(--font-heading);
  font-size: var(--font-lg);
  font-weight: 600;
  color: var(--color-primary);
}

.form-item-full-width {
  grid-column: 1 / -1;
}

/* 切换按钮组（符合管理端设计规范）*/
.seat-toggle-group {
  display: flex;
  gap: var(--space-3);
  margin-top: var(--space-2);
}

.toggle-btn {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-4) var(--space-3);
  background-color: var(--color-white);
  border: 2px solid var(--color-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-base);
}

.toggle-btn:hover {
  border-color: var(--color-primary);
  transform: translateY(-2px);
  box-shadow: var(--shadow-sm);
}

.toggle-btn.active {
  border-color: var(--color-primary);
  background-color: rgba(166, 186, 174, 0.08);
  box-shadow: var(--shadow-md);
}

.toggle-icon {
  width: 24px;
  height: 24px;
  stroke: currentColor;
  transition: all var(--transition-fast);
}

.toggle-btn.active .toggle-icon {
  stroke: var(--color-primary);
}

.toggle-text {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
}

.toggle-desc {
  font-size: var(--font-xs);
  color: var(--color-text-light);
}

/* 信息提示框（简洁风格 - 无emoji）*/
.seat-info-box {
  margin-top: var(--space-3);
  padding: var(--space-4);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-md);
  border-left: 3px solid var(--color-primary);
}

.seat-info-box.inactive {
  border-left-color: var(--color-text-light);
  opacity: 0.7;
}

.info-content {
  display: flex;
  gap: var(--space-3);
  align-items: flex-start;
}

/* SVG 图标容器 */
.info-icon-wrapper {
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  background-size: contain;
  background-repeat: no-repeat;
  background-position: center;
  margin-top: var(--space-1);
}

/* 座位图标（SVG data URI） */
.info-icon-seat {
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23967462' stroke-width='2'%3E%3Crect x='4' y='6' width='16' height='12' rx='2'/%3E%3Cline x1='8' y1='10' x2='16' y2='10'/%3E%3Cline x1='8' y1='14' x2='14' y2='14'/%3E%3C/svg%3E");
}

/* 票务图标（SVG data URI） */
.info-icon-ticket {
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%235d5e5a' stroke-width='2'%3E%3Crect x='3' y='3' width='18' height='18' rx='2'/%3E%3Cpath d='M9 9h6M9 13h6M9 17h4'/%3E%3C/svg%3E");
}

/* 网格图标（用于座位预览） */
.info-icon-grid {
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23967462' stroke-width='2'%3E%3Crect x='3' y='3' width='7' height='7' rx='1'/%3E%3Crect x='14' y='3' width='7' height='7' rx='1'/%3E%3Crect x='3' y='14' width='7' height='7' rx='1'/%3E%3Crect x='14' y='14' width='7' height='7' rx='1'/%3E%3C/svg%3E");
}

/* ===== 新建票种时的座位配置区域样式（占满整宽 + 跨越Grid两列） ===== */

.seat-config-wrapper {
  margin-top: var(--space-4, 16px);
  /* 关键：在grid布局中跨越所有列，占满整宽 */
  grid-column: 1 / -1;
  width: 100%;
  box-sizing: border-box;
}

/* 配置方式选择器（占满整宽 + 按钮等长） */
.config-mode-selector {
  margin-bottom: var(--space-4, 16px);
  width: 100%;
}

.mode-label {
  display: block;
  font-size: var(--font-sm, 14px);
  font-weight: 600;
  color: var(--color-text, #2d3a31);
  margin-bottom: var(--space-2, 8px);
}

.mode-options {
  display: grid;
  grid-template-columns: 1fr 1fr; /* 强制两列等分，绝对等宽 */
  gap: var(--space-3, 12px);
  width: 100%;
}

.mode-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2, 8px);
  padding: var(--space-2, 8px) var(--space-3, 12px);
  background-color: white;
  border: 1px solid var(--color-border, #d6d0c4);
  border-radius: var(--radius-sm, 6px);
  cursor: pointer;
  transition: all 0.3s ease-out;
  text-align: center;
  min-height: 40px;
  box-sizing: border-box;
  width: 100%; /* 确保填满grid单元格 */
}

.mode-btn:hover {
  border-color: var(--color-primary, #967462);
}

.mode-btn.active {
  border-color: var(--color-primary, #967462);
  background-color: var(--color-bg-card, #f9f8f4);
  color: var(--color-primary, #967462);
  font-weight: 600;
}

.mode-icon {
  width: 20px;
  height: 20px;
  background-color: var(--color-accent, #a6baae);
  border-radius: var(--radius-sm, 6px);
  flex-shrink: 0;
}

.mode-icon.custom {
  background-color: var(--color-secondary, #5d3e3f);
}

.mode-text {
  font-size: var(--font-sm, 14px);
  font-weight: 600;
  color: inherit;
}

.mode-desc {
  display: none; /* 紧凑模式不显示描述 */
}

/* 模板预览面板（紧凑风格 + 占满整宽） */
.template-preview-panel {
  animation: fadeIn 0.2s ease-out;
  width: 100%;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.current-template-card {
  background-color: var(--color-bg-card, #f9f8f4);
  border-radius: var(--radius-sm, 6px);
  padding: var(--space-3, 12px) var(--space-4, 16px);
  border: 1px solid var(--color-border, #d6d0c4);
  margin-bottom: var(--space-3, 12px);
}

.template-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-2, 8px);
  padding-bottom: var(--space-2, 8px);
  border-bottom: 1px solid var(--color-border, #d6d0c4);
}

.template-name {
  font-size: var(--font-sm, 14px);
  font-weight: 600;
  color: var(--color-text, #2d3a31);
  margin: 0;
}

.template-badge {
  display: inline-block;
  padding: var(--space-1, 4px) var(--space-2, 8px);
  background-color: var(--color-primary, #967462);
  color: white;
  font-size: var(--font-xs, 12px);
  font-weight: 500;
  border-radius: var(--radius-sm, 6px);
}

/* 模板区域列表（紧凑风格） */
.template-areas-list {
  margin-bottom: var(--space-2, 8px);
}

.template-area-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-1, 4px) var(--space-2, 8px);
  background-color: white;
  border-radius: var(--radius-sm, 6px);
  margin-bottom: var(--space-1, 4px);
  font-size: var(--font-xs, 12px);
  border-left: 2px solid var(--color-accent, #a6baae);
}

.area-info {
  display: flex;
  align-items: center;
  gap: var(--space-2, 8px);
}

.area-name {
  font-size: var(--font-xs, 12px);
  font-weight: 600;
  color: var(--color-text, #2d3a31);
}

.area-detail {
  font-size: var(--font-xs, 12px);
  color: var(--color-text-light, #5d5e5a);
}

/* 模板统计信息（紧凑风格） */
.template-stats {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: var(--space-3, 12px);
  padding: var(--space-2, 8px) var(--space-3, 12px);
  background-color: var(--color-bg-alt, #ece0ca);
  border-radius: var(--radius-sm, 6px);
  font-size: var(--font-xs, 12px);
}

.stat-item {
  display: flex;
  align-items: baseline;
  gap: var(--space-1, 4px);
}

.stat-value {
  font-size: var(--font-sm, 14px);
  font-weight: 700;
  color: var(--color-text, #2d3a31);
}

.stat-value.highlight {
  color: var(--color-primary, #967462);
}

.stat-label {
  font-size: var(--font-xs, 12px);
  color: var(--color-text-light, #5d5e5a);
  font-weight: 400;
}

.stat-divider {
  width: 1px;
  height: 16px;
  background-color: var(--color-border, #d6d0c4);
}

/* 模板操作按钮（紧凑风格） */
.template-actions {
  margin-bottom: var(--space-2, 8px);
}

.btn-change-template {
  padding: var(--space-1, 4px) var(--space-3, 12px);
  background-color: transparent;
  color: var(--color-text-light, #5d5e5a);
  border: none;
  border-radius: var(--radius-sm, 6px);
  font-size: var(--font-xs, 12px);
  font-weight: 500;
  cursor: pointer;
  transition: all 0.3s ease-out;
  text-decoration: underline;
}

.btn-change-template:hover {
  color: var(--color-primary, #967462);
  text-decoration: none;
}

/* 模板下拉选择器（紧凑风格） */
.template-dropdown {
  background-color: var(--color-bg-card, #f9f8f4);
  padding: var(--space-2, 8px) var(--space-3, 12px);
  border-radius: var(--radius-sm, 6px);
  border: 1px solid var(--color-border, #d6d0c4);
  animation: fadeIn 0.2s ease-out;
}

.dropdown-label {
  display: block;
  font-size: var(--font-xs, 12px);
  font-weight: 600;
  color: var(--color-text-light, #5d5e5a);
  margin-bottom: var(--space-1, 4px);
}

.template-select-styled {
  width: 100%;
  padding: var(--space-1, 4px) var(--space-2, 8px);
  border: 1px solid var(--color-border, #d6d0c4);
  border-radius: var(--radius-sm, 6px);
  font-size: var(--font-sm, 14px);
  color: var(--color-text, #2d3a31);
  background-color: white;
  cursor: pointer;
  transition: all 0.3s ease-out;
  margin-bottom: var(--space-1, 4px);
}

.template-select-styled:focus {
  outline: none;
  border-color: var(--color-primary, #967462);
}

.dropdown-hint {
  font-size: var(--font-xs, 11px);
  color: var(--color-text-light, #5d5e5a);
  margin: 0;
  line-height: 1.4;
}

/* 自定义配置面板（占满整宽 + 左对齐） */
.custom-config-panel {
  animation: fadeIn 0.2s ease-out;
  width: 100%;
  box-sizing: border-box;
}

.areas-container-custom {
  display: flex;
  flex-direction: column;
  gap: var(--space-2, 8px);
  margin-bottom: var(--space-2, 8px);
}

.area-card-custom {
  background-color: white;
  border-radius: var(--radius-sm, 6px);
  overflow: hidden;
  border: 1px solid var(--color-border, #d6d0c4);
  transition: all 0.3s ease-out;
}

.area-card-custom:hover {
  border-color: var(--color-accent, #a6baae);
}

.area-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-2, 8px) var(--space-3, 12px);
  background-color: var(--color-bg-alt, #ece0ca);
  border-bottom: 1px solid var(--color-border, #d6d0c4);
}

.area-number {
  font-size: var(--font-xs, 12px);
  font-weight: 600;
  color: var(--color-primary, #967462);
}

.btn-delete-area {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: transparent;
  color: var(--color-text-light, #5d5e5a);
  border: none;
  border-radius: var(--radius-sm, 6px);
  font-size: 16px;
  cursor: pointer;
  transition: all 0.3s ease-out;
}

.btn-delete-area::before {
  content: '×';
}

.btn-delete-area:hover {
  color: var(--color-secondary, #5d3e3f);
}

.area-card-body {
  padding: var(--space-2, 8px) var(--space-3, 12px);
}

.field-row-custom {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--space-2, 8px);
  margin-bottom: var(--space-2, 8px);
}

.field-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-1, 4px);
}

.field-item.half {
  grid-column: span 1;
}

.field-row-custom:has(.field-item.half) {
  grid-template-columns: 1fr 1fr;
}

.field-label {
  font-size: var(--font-xs, 11px);
  font-weight: 500;
  color: var(--color-text-light, #5d5e5a);
}

.field-input {
  padding: var(--space-1, 4px) var(--space-2, 8px);
  border: 1px solid var(--color-border, #d6d0c4);
  border-radius: var(--radius-sm, 6px);
  font-size: var(--font-sm, 14px);
  color: var(--color-text, #2d3a31);
  background-color: white;
  transition: all 0.3s ease-out;
}

.field-input:focus {
  outline: none;
  border-color: var(--color-primary, #967462);
}

.field-input:hover:not(:focus) {
  border-color: var(--color-accent, #a6baae);
}

.area-subtotal {
  font-size: var(--font-xs, 11px);
  font-weight: 500;
  color: var(--color-text-light, #5d5e5a);
  padding-top: var(--space-1, 4px);
  border-top: 1px solid var(--color-border, #d6d0c4);
  text-align: right;
}

/* 添加区域按钮（紧凑风格） */
.btn-add-area-custom {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-1, 4px);
  padding: var(--space-2, 8px) var(--space-3, 12px);
  background-color: transparent;
  color: var(--color-text-light, #5d5e5a);
  border: 1px dashed var(--color-border, #d6d0c4);
  border-radius: var(--radius-sm, 6px);
  font-size: var(--font-xs, 12px);
  font-weight: 500;
  cursor: pointer;
  transition: all 0.3s ease-out;
}

.btn-add-area-custom:hover:not(:disabled) {
  color: var(--color-primary, #967462);
  border-color: var(--color-primary, #967462);
}

.btn-add-area-custom:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.add-icon {
  font-size: var(--font-sm, 14px);
  font-weight: 300;
}

/* 底部统计栏（占满整宽 + 左对齐） */
.config-summary-bar {
  margin-top: var(--space-3, 12px);
  padding: var(--space-2, 8px) var(--space-3, 12px);
  background-color: var(--color-bg-alt, #ece0ca);
  border-radius: var(--radius-sm, 6px);
  font-size: var(--font-xs, 12px);
  width: 100%;
  box-sizing: border-box;
}

.summary-content {
  display: flex;
  align-items: center;
  gap: var(--space-2, 8px);
}

.summary-icon {
  width: 14px;
  height: 14px;
  background-color: var(--color-primary, #967462);
  border-radius: 50%;
  flex-shrink: 0;
}

.summary-text {
  font-size: var(--font-xs, 12px);
  color: var(--color-text, #2d3a31);
  font-weight: 400;
}

.summary-text strong {
  font-weight: 600;
  color: var(--color-primary, #967462);
}

.section-label {
  display: block;
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-2);
}

.section-label.required-mark::before {
  content: '*';
  color: var(--color-danger);
  margin-right: var(--space-1);
}

.section-desc {
  font-size: var(--font-xs);
  color: var(--color-text-light);
  margin-bottom: var(--space-4);
  line-height: 1.5;
}

.areas-container {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.area-item {
  padding: var(--space-3);
  background-color: white;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.area-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-3);
}

.area-index {
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--color-primary);
}

.btn-remove-area {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--color-bg-danger);
  color: var(--color-danger-dark);
  border: 1px solid var(--color-border-danger);
  border-radius: 50%;
  font-size: 16px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-remove-area:hover {
  background-color: var(--color-danger);
  color: white;
  border-color: #ff4d4f;
}

.area-fields {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.field-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-3);
}

.field-group {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.field-group label {
  font-size: var(--font-xs);
  font-weight: 500;
  color: var(--color-text-light);
}

.field-group input {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  transition: border-color 0.2s ease;
}

.field-group input:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(22, 119, 255, 0.1);
}

.btn-add-area {
  width: 100%;
  padding: var(--space-3) var(--space-4);
  background-color: var(--color-bg-alt);
  color: var(--color-primary);
  border: 1px dashed var(--color-primary);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-add-area:hover:not(:disabled) {
  background-color: var(--color-primary-alpha);
  border-color: var(--color-primary);
}

.btn-add-area:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.seat-summary {
  margin-top: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background-color: var(--color-bg-alt);
  border-radius: var(--radius-sm);
  text-align: center;
}

.summary-text {
  font-size: var(--font-xs);
  font-weight: 600;
  color: var(--color-primary);
}

/* ===== 座位生成表单样式 ===== */
.seat-generator-form {
  padding: var(--space-4) 0;
}

.seat-generator-form .form-hint {
  font-size: var(--font-sm);
  color: var(--color-text-light);
  margin-bottom: var(--space-4);
  padding: var(--space-2) var(--space-3);
  background-color: rgba(166, 186, 174, 0.1);
  border-radius: var(--radius-sm);
}

.seat-generator-form .form-row {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-4);
}

.seat-preview-info {
  margin-top: var(--space-4);
  background-color: rgba(150, 116, 98, 0.05);
  border-left-color: var(--color-accent);
}

.seat-preview-info strong {
  color: var(--color-primary);
}

.info-text {
  flex: 1;
  font-size: var(--font-sm);
  color: var(--color-text);
  line-height: 1.6;
}

.info-text strong {
  display: block;
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: var(--space-1);
}

.info-text p {
  margin: var(--space-1) 0;
  color: var(--color-text-light);
  line-height: 1.6;
}

/* 信息列表样式 */
.info-list {
  margin: var(--space-2) 0 0 0;
  padding-left: var(--space-4);
  list-style: none;
}

.info-list li {
  position: relative;
  font-size: var(--font-xs);
  color: var(--color-text-light);
  line-height: 1.6;
  margin-bottom: var(--space-1);
}

.info-list li::before {
  content: '•';
  position: absolute;
  left: calc(-1 * var(--space-3));
  color: var(--color-primary);
}

/* 响应式适配 */
@media (max-width: 768px) {
  .seat-toggle-group {
    flex-direction: column;
    gap: var(--space-2);
  }

  .form-section-title {
    font-size: var(--font-base);
  }
}

/* ===== 座位列表详情样式 ===== */
.seat-list-container {
  max-height: 500px;
  overflow-y: auto;
}

.seat-list-stats {
  display: flex;
  gap: var(--space-4);
  margin-bottom: var(--space-3);
  padding: var(--space-2) var(--space-3);
  background: var(--color-bg-secondary, #f8f9fa);
  border-radius: var(--radius-md, 6px);
  font-size: var(--font-sm, 13px);
}

.stat-item {
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm, 4px);
  font-weight: 500;
}

.stat-total {
  background: var(--color-bg-alt, #ece0ca);
  color: var(--color-text);
}

.stat-available {
  background: var(--color-bg-success, #f0f7f2);
  color: var(--color-success-dark, #5d8a6b);
}

.stat-locked {
  background: var(--color-warning-pale, #eef0ec);
  color: var(--color-warning-dark, #6b7267);
}

.stat-sold {
  background: var(--color-bg-danger, #fef2f2);
  color: var(--color-danger-dark, #d9363e);
}

.seat-table-wrapper {
  border: 1px solid var(--color-border, #e0e0e0);
  border-radius: var(--radius-md, 6px);
  overflow: hidden;
}

.seat-detail-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--font-sm, 13px);
}

.seat-detail-table thead {
  background: var(--color-bg-secondary, #f5f5f5);
}

.seat-detail-table th,
.seat-detail-table td {
  padding: var(--space-2) var(--space-3);
  text-align: left;
  border-bottom: 1px solid var(--color-border-light, #eee);
}

/* 预订单ID等长字符串完整换行显示，避免被截断（雪花ID约18-19位） */
.seat-detail-table td {
  word-break: break-all;
  white-space: normal;
}

.seat-detail-table tbody tr:hover {
  background: var(--color-hover, #fafafa);
}

.seat-detail-table tbody tr.status-1 {
  background: rgba(255, 152, 0, 0.05);
}

.seat-detail-table tbody tr.status-2 {
  background: rgba(244, 67, 54, 0.05);
}

.seat-status-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 500;
}

.seat-status-badge.status-0 {
  background: var(--color-bg-success, #f0f7f2);
  color: var(--color-success-dark, #5d8a6b);
}

.seat-status-badge.status-1 {
  background: var(--color-warning-pale, #eef0ec);
  color: var(--color-warning-dark, #6b7267);
}

.seat-status-badge.status-2 {
  background: var(--color-bg-danger, #fef2f2);
  color: var(--color-danger-dark, #d9363e);
}

.empty-seat-list {
  text-align: center;
  padding: var(--space-8, 40px);
  color: var(--color-text-light, #999);
  font-size: var(--font-sm, 13px);
}

.seat-list-loading {
  text-align: center;
  padding: var(--space-6, 30px);
  color: var(--color-text-light, #666);
}
</style>
