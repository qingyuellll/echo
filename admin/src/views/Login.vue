               <script setup>
import { ref, onMounted, computed, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import Card from '@/components/common/Card.vue'
import Button from '@/components/common/Button.vue'
import { LogIn, RefreshCw, Eye, EyeOff } from 'lucide-vue-next'
import { api } from '@/utils/api.js'
import {
  validatePhone,
  checkSqlInjection,
  detectXssAttack,
  throttle
} from '../../../shared/utils/security.js'
import {
  generateSecureRandom,
  secureStoreToken,
  createSignature
} from '../../../shared/utils/crypto.js'

const router = useRouter()
const phone = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const showPassword = ref(false)

// 登录方式：password=密码登录，code=短信验证码登录
const loginMode = ref('password')
const smsCode = ref('')
const smsSending = ref(false)
const smsCountdown = ref(0)
const smsTip = ref('')
let smsTimer = null

// 🔐 验证码相关
const captchaCode = ref('')
const captchaCanvas = ref(null)
const generatedCaptcha = ref('')
const captchaLoading = ref(false)

// 📊 设备指纹
const deviceFingerprint = ref('')

// 🔒 登录安全配置
const LOGIN_CONFIG = {
  maxAttempts: 5,
  lockoutDuration: 60 * 1000,
  cooldownTime: 1000,
  requireCaptchaAfterFailures: 3,
  sessionTimeout: 30 * 60 * 1000,
  maxSessionsPerAccount: 1
}

let loginAttempts = 0
let isLockedOut = false
let lockoutEndTime = null
let lockoutTimer = null

/**
 * 生成图形验证码（符合日系轻亚风配色）
 */
function generateCaptcha() {
  const canvas = captchaCanvas.value
  if (!canvas) return

  const ctx = canvas.getContext('2d')
  const width = canvas.width
  const height = canvas.height

  // 使用暖白背景
  ctx.fillStyle = '#f9f8f4'
  ctx.fillRect(0, 0, width, height)

  // 灰绿色系字符
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
  let code = ''

  for (let i = 0; i < 4; i++) {
    const char = chars[Math.floor(Math.random() * chars.length)]
    code += char

    // 使用深绿/褐色系文字
    ctx.font = `${18 + Math.random() * 8}px "Source Sans 3", sans-serif`
    ctx.fillStyle = `rgb(${45 + Math.random() * 30}, ${58 + Math.random() * 20}, ${49 + Math.random() * 20})`
    ctx.textBaseline = 'middle'

    const x = 10 + i * (width - 20) / 4 + Math.random() * 5
    const y = height / 2 + Math.random() * 8 - 4
    const angle = (Math.random() - 0.5) * 0.35

    ctx.save()
    ctx.translate(x, y)
    ctx.rotate(angle)
    ctx.fillText(char, 0, 0)
    ctx.restore()
  }

  // 柔和的干扰线（灰绿/棕色系）
  for (let i = 0; i < 3; i++) {
    ctx.strokeStyle = `rgba(${166 + Math.random() * 40}, ${186 - Math.random() * 30}, ${174 - Math.random() * 30}, 0.25)`
    ctx.lineWidth = 1
    ctx.beginPath()
    ctx.moveTo(Math.random() * width, Math.random() * height)
    ctx.lineTo(Math.random() * width, Math.random() * height)
    ctx.stroke()
  }

  // 细腻的干扰点
  for (let i = 0; i < 25; i++) {
    ctx.fillStyle = `rgba(${150 + Math.random() * 50}, ${116 + Math.random() * 50}, ${98 + Math.random() * 50}, 0.15)`
    ctx.beginPath()
    ctx.arc(Math.random() * width, Math.random() * height, 1, 0, 2 * Math.PI)
    ctx.fill()
  }

  generatedCaptcha.value = code.toLowerCase()

  console.log('[Login] 验证码已生成:', code)
}

function refreshCaptcha() {
  captchaCode.value = ''
  generateCaptcha()
}

function validateCaptcha() {
  if (loginAttempts < LOGIN_CONFIG.requireCaptchaAfterFailures) {
    return true
  }

  if (!captchaCode.value || captchaCode.value.trim().length === 0) {
    error.value = '请输入验证码'
    return false
  }

  if (captchaCode.value.trim().toLowerCase() !== generatedCaptcha.value) {
    error.value = '验证码错误，请重新输入'
    refreshCaptcha()
    return false
  }

  return true
}

function maskPhone(phone) {
  if (!phone || typeof phone !== 'string') return '***'
  if (phone.length >= 7) {
    return phone.substring(0, 3) + '****' + phone.substring(phone.length - 4)
  }
  return '***'
}

async function generateDeviceFingerprint() {
  try {
    const components = [
      navigator.userAgent,
      navigator.language,
      screen.width + 'x' + screen.height,
      screen.colorDepth,
      new Date().getTimezoneOffset(),
      navigator.hardwareConcurrency || '',
      navigator.platform
    ]

    const fingerprint = components.join('|')
    deviceFingerprint.value = await createSignature(fingerprint, 'echo-device-secret')

    console.log('[Login] 设备指纹已生成')

  } catch (e) {
    console.warn('[Login] 设备指纹生成失败:', e)
    deviceFingerprint.value = generateSecureRandom(16)
  }
}

/**
 * 🔒 检查账号锁定状态
 */
function checkLockoutStatus() {
  if (isLockedOut && lockoutEndTime) {
    if (Date.now() < lockoutEndTime) {
      return true
    } else {
      // 锁定时间已过，解除锁定
      isLockedOut = false
      lockoutEndTime = null
      loginAttempts = 0
      localStorage.removeItem('login_attempts')
      localStorage.removeItem('lockout_end_time')
      return false
    }
  }
  return false
}

/**
 * 获取剩余锁定时间（毫秒）
 */
function getRemainingLockoutTime() {
  if (!lockoutEndTime) return 0
  return Math.max(0, lockoutEndTime - Date.now())
}

/**
 * 📊 格式化剩余时间为可读字符串
 */
function formatRemainingTime(ms) {
  const seconds = Math.ceil(ms / 1000)
  if (seconds < 60) {
    return `${seconds}秒`
  }
  const minutes = Math.floor(seconds / 60)
  const remainingSeconds = seconds % 60
  return `${minutes}分${remainingSeconds}秒`
}

/**
 * 🔒 设置账号锁定
 */
function setLockout() {
  isLockedOut = true
  lockoutEndTime = Date.now() + LOGIN_CONFIG.lockoutDuration

  // 持久化锁定状态
  localStorage.setItem('lockout_end_time', lockoutEndTime.toString())
  localStorage.setItem('login_attempts', LOGIN_CONFIG.maxAttempts.toString())

  console.warn(`[Login] 账号已锁定 ${formatRemainingTime(LOGIN_CONFIG.lockoutDuration)}`)
}

/**
 * 🔓 清除账号锁定（登录成功时调用）
 */
function clearLockout() {
  isLockedOut = false
  lockoutEndTime = null
  loginAttempts = 0

  // 清除持久化的锁定状态
  localStorage.removeItem('lockout_end_time')
  localStorage.removeItem('login_attempts')

  // 清除定时器
  if (lockoutTimer) {
    clearInterval(lockoutTimer)
    lockoutTimer = null
  }

  console.log('[Login] 锁定已清除，登录成功')
}

/**
 * 表单输入验证
 */
function validateInput() {
  error.value = ''

  if (checkLockoutStatus()) {
    const remaining = getRemainingLockoutTime()
    error.value = `账号已锁定，请 ${formatRemainingTime(remaining)} 后重试`
    return false
  }

  const phoneValidation = validatePhone(phone.value)
  if (!phoneValidation.valid) {
    error.value = phoneValidation.message
    return false
  }
  phone.value = phoneValidation.value

  if (loginMode.value === 'code') {
    if (!/^\d{6}$/.test(smsCode.value.trim())) {
      error.value = '请输入 6 位短信验证码'
      return false
    }
    return validateCaptcha()
  }

  if (!password.value || password.value.trim().length === 0) {
    error.value = '请输入密码'
    return false
  }

  if (password.value.length < 6 || password.value.length > 32) {
    error.value = '密码长度应在6-32位之间'
    return false
  }

  if (!checkSqlInjection(password.value).safe) {
    error.value = '输入包含非法字符'
    return false
  }

  if (!detectXssAttack(password.value).safe) {
    error.value = '输入包含非法内容'
    return false
  }

  return validateCaptcha();


}

/**
 * 切换登录方式时清空上一次的输入与提示，避免两种凭证混用
 */
function switchLoginMode(mode) {
  if (loading.value || loginMode.value === mode) return
  loginMode.value = mode
  error.value = ''
  smsTip.value = ''
  smsCode.value = ''
}

/**
 * 请求发送登录验证码
 */
const sendSmsCode = throttle(async () => {
  if (smsCountdown.value > 0 || smsSending.value) return

  error.value = ''
  smsTip.value = ''

  const phoneValidation = validatePhone(phone.value)
  if (!phoneValidation.valid) {
    error.value = phoneValidation.message
    return
  }
  phone.value = phoneValidation.value

  smsSending.value = true
  try {
    const res = await api.sendLoginCode(phone.value)
    if (res && res.success === false) {
      error.value = res.errorMsg || res.message || '验证码发送失败'
      return
    }
    startSmsCountdown()
    smsTip.value = '验证码已发送，2 分钟内有效'
  } catch (e) {
    error.value = e?.message || '验证码发送失败，请稍后重试'
  } finally {
    smsSending.value = false
  }
}, 1500)

function startSmsCountdown() {
  smsCountdown.value = 60
  if (smsTimer) clearInterval(smsTimer)
  smsTimer = setInterval(() => {
    smsCountdown.value -= 1
    if (smsCountdown.value <= 0) {
      clearInterval(smsTimer)
      smsTimer = null
      smsCountdown.value = 0
    }
  }, 1000)
}

function recordLoginLog(success, details = {}) {
  const logEntry = {
    timestamp: new Date().toISOString(),
    success,
    phone: maskPhone(phone.value),
    attempt: loginAttempts + 1,
    deviceFingerprint: deviceFingerprint.value?.substring(0, 16),
    userAgent: navigator.userAgent?.substring(0, 50),
    ...details
  }

  let logs = []
  try {
    logs = JSON.parse(localStorage.getItem('login_logs') || '[]')
  } catch (e) {
    logs = []
  }

  logs.push(logEntry)

  if (logs.length > 100) {
    logs = logs.slice(-100)
  }

  localStorage.setItem('login_logs', JSON.stringify(logs))

  if (success) {
    console.log(`[Login 成功] 登录成功`, logEntry)
  } else {
    console.warn(`[Login 失败] 登录失败`, logEntry)
  }
}

/**
 * 登录凭证按原值提交，传输机密性由 HTTPS 保证；
 * 服务端与用户端使用同一套校验规则，避免两端编码差异造成登录失败。
 */
function buildCredential(plainPassword) {
  return plainPassword
}

const handleLogin = throttle(async () => {
  if (!validateInput()) return

  if (loading.value) {
    error.value = '正在处理中，请勿重复点击'
    return
  }

  loading.value = true
  error.value = ''

  try {
    console.log('[Login] 开始登录验证...', {
      phone: maskPhone(phone.value),
      attempt: loginAttempts + 1,
      hasCaptcha: loginAttempts >= LOGIN_CONFIG.requireCaptchaAfterFailures
    })

    const res = loginMode.value === 'code'
      ? await api.loginByCode(phone.value, smsCode.value.trim())
      : await api.login(
          phone.value,
          buildCredential(password.value),
          {
            captcha: captchaCode.value,
            deviceFingerprint: deviceFingerprint.value,
            timestamp: Date.now(),
            clientType: 'web_admin'
          }
        )

    console.log('[Login] 登录成功')

    clearLockout()

    // ✅ 兼容新旧两种响应格式
    let token = ''
    let adminInfo = null

    if (typeof res.data === 'string') {
      // 旧格式：只返回token字符串
      token = res.data
    } else if (typeof res.data === 'object' && res.data !== null) {
      // 新格式：返回 { token, adminId, name, phone, roleLevel, isSuperAdmin }
      token = res.data.token || ''
      adminInfo = {
        id: res.data.adminId,
        name: res.data.name,
        phone: res.data.phone,
        roleLevel: res.data.roleLevel,
        isSuperAdmin: res.data.isSuperAdmin
      }

      console.log('[Login] 管理员信息:', {
        adminId: adminInfo.id,
        name: adminInfo.name,
        roleLevel: adminInfo.roleLevel,
        isSuperAdmin: adminInfo.isSuperAdmin
      })
    } else {
      token = res.token || res || ''
    }

    if (token) {
      await secureStoreToken(token)

      // 使用sessionStorage实现标签页隔离（避免多账号登录冲突）
      sessionStorage.setItem('token', token)
    }

    // ✅ 保存CSRF Token（用于后续POST/PUT/DELETE请求的CSRF校验）
    if (res.data && res.data.csrfToken) {
      localStorage.setItem('csrf_token', res.data.csrfToken)
      localStorage.setItem('csrf_token_time', Date.now().toString())
      console.log('[Login] CSRF Token已保存:', res.data.csrfToken.substring(0, 8) + '...', '有效期为1小时')
    }

    // ✅ 存储管理员信息（供权限判断使用）
    if (adminInfo) {
      sessionStorage.setItem('adminInfo', JSON.stringify(adminInfo))
      console.log('[Login] ✅ 管理员信息已存储到sessionStorage')
    }

    sessionStorage.setItem('phone', maskPhone(phone.value))
    sessionStorage.setItem('login_time', Date.now().toString())
    sessionStorage.setItem('last_login_device', deviceFingerprint.value?.substring(0, 8))

    recordLoginLog(true, { tokenLength: (res.data || res.token || '').length })

    await router.push('/dashboard')

  } catch (e) {
    const businessErrorMsg = e?.response?.data?.errorMsg
                         || e?.response?.data?.msg
                         || e?.response?.data?.message
                         || e?.message
                         || '登录失败，请重试'

    console.error('[Login] 登录失败:', businessErrorMsg)

    loginAttempts++
    localStorage.setItem('login_attempts', loginAttempts.toString())

    recordLoginLog(false, {
      errorMessage: businessErrorMsg,
      errorCode: e?.code || e?.response?.status || null
    })

    if (loginAttempts >= LOGIN_CONFIG.maxAttempts) {
      setLockout()

      const remaining = formatRemainingTime(LOGIN_CONFIG.lockoutDuration / 1000)
      error.value = `错误次数过多！账号已锁定 ${remaining}`

      startLockoutCountdown()

    } else {
      const remain = LOGIN_CONFIG.maxAttempts - loginAttempts

      if (businessErrorMsg && !businessErrorMsg.includes('登录失败，请重试')) {
        error.value = businessErrorMsg
      } else if (remain <= 2) {
        error.value = `手机号或密码错误（剩余${remain}次，失败${LOGIN_CONFIG.requireCaptchaAfterFailures}次后将需验证码）`
      } else {
        error.value = `手机号或密码错误（剩余${remain}次）`
      }

      if (loginAttempts >= LOGIN_CONFIG.requireCaptchaAfterFailures) {
        refreshCaptcha()
      }
    }
  } finally {
    loading.value = false
  }
}, LOGIN_CONFIG.cooldownTime)

function startLockoutCountdown() {
  if (lockoutTimer) {
    clearInterval(lockoutTimer)
  }

  lockoutTimer = setInterval(() => {
    if (!isLockedOut) {
      clearInterval(lockoutTimer)
      lockoutTimer = null
      return
    }

    const remaining = getRemainingLockoutTime()

    if (remaining <= 0) {
      clearLockout()
      error.value = ''
      clearInterval(lockoutTimer)
      lockoutTimer = null
    } else {
      error.value = `账号已锁定，剩余时间: ${formatRemainingTime(remaining)}`
    }
  }, 1000)
}

onMounted(async () => {
  await generateDeviceFingerprint()

  // 恢复锁定状态（从localStorage）
  const savedLockoutTime = localStorage.getItem('lockout_end_time')
  if (savedLockoutTime) {
    const lockoutTime = parseInt(savedLockoutTime)
    if (lockoutTime > Date.now()) {
      isLockedOut = true
      lockoutEndTime = lockoutTime
      loginAttempts = LOGIN_CONFIG.maxAttempts
    }
  }

  if (checkLockoutStatus()) {
    const remaining = getRemainingLockoutTime()
    error.value = `账号已锁定，剩余时间: ${formatRemainingTime(remaining)}`
    startLockoutCountdown()
  }

  const oldAttempts = localStorage.getItem('login_attempts')
  if (oldAttempts) {
    loginAttempts = parseInt(oldAttempts)

    if (loginAttempts >= LOGIN_CONFIG.requireCaptchaAfterFailures) {
      generateCaptcha()
    }
  }

  generateCaptcha()

  console.log('[Login] 登录页面初始化完成', {
    attempts: loginAttempts,
    isLockedOut,
    requireCaptcha: loginAttempts >= LOGIN_CONFIG.requireCaptchaAfterFailures
  })
})

onUnmounted(() => {
  if (lockoutTimer) {
    clearInterval(lockoutTimer)
    lockoutTimer = null
  }
  if (smsTimer) {
    clearInterval(smsTimer)
    smsTimer = null
  }
})
</script>

<template>
  <div class="login-page">
    <div class="login-container">
      <Card padding="md">
        <!-- 头部 -->
        <div class="login-header">
          <div class="login-logo">
            <span class="logo-letter">E</span>
          </div>
          <h1 class="login-title">Echo 管理端</h1>
          <p class="login-desc">管理员登录</p>
        </div>

        <!-- 登录方式切换 -->
        <div class="login-mode-switch">
          <button
            type="button"
            class="mode-tab"
            :class="{ active: loginMode === 'password' }"
            :disabled="loading"
            @click="switchLoginMode('password')"
          >
            密码登录
          </button>
          <button
            type="button"
            class="mode-tab"
            :class="{ active: loginMode === 'code' }"
            :disabled="loading"
            @click="switchLoginMode('code')"
          >
            验证码登录
          </button>
        </div>

        <!-- 登录表单 -->
        <form class="login-form" @submit.prevent="handleLogin">
          <!-- 手机号 -->
          <div class="form-group">
            <label class="form-label">手机号</label>
            <input
              v-model="phone"
              type="tel"
              class="form-input"
              placeholder="请输入手机号"
              autocomplete="tel"
              maxlength="11"
              :disabled="loading || isLockedOut"
            />
          </div>

          <!-- 密码 -->
          <div v-if="loginMode === 'password'" class="form-group">
            <label class="form-label">密码</label>
            <div class="password-wrapper">
              <input
                v-model="password"
                :type="showPassword ? 'text' : 'password'"
                class="form-input password-input"
                placeholder="请输入密码"
                autocomplete="current-password"
                maxlength="32"
                :disabled="loading || isLockedOut"
              />
              <button
                type="button"
                class="password-toggle"
                @click="showPassword = !showPassword"
                tabindex="-1"
                :title="showPassword ? '隐藏密码' : '显示密码'"
              >
                <EyeOff v-if="showPassword" :size="18" />
                <Eye v-else :size="18" />
              </button>
            </div>
          </div>

          <!-- 短信验证码 -->
          <div v-else class="form-group">
            <label class="form-label">短信验证码</label>
            <div class="sms-code-wrapper">
              <input
                v-model="smsCode"
                type="text"
                class="form-input sms-code-input"
                placeholder="请输入 6 位验证码"
                autocomplete="one-time-code"
                inputmode="numeric"
                maxlength="6"
                :disabled="loading || isLockedOut"
                @keyup.enter="handleLogin"
              />
              <button
                type="button"
                class="sms-code-button"
                :disabled="smsSending || smsCountdown > 0 || loading || isLockedOut"
                @click="sendSmsCode"
              >
                {{ smsCountdown > 0 ? `${smsCountdown}s 后重发` : (smsSending ? '发送中' : '获取验证码') }}
              </button>
            </div>
            <p v-if="smsTip" class="form-hint">{{ smsTip }}</p>
          </div>

          <!-- 验证码 -->
          <div
            v-if="loginAttempts >= LOGIN_CONFIG.requireCaptchaAfterFailures"
            class="form-group captcha-group"
          >
            <label class="form-label">
              验证码
              <span class="required-mark">*</span>
            </label>
            <div class="captcha-wrapper">
              <input
                v-model="captchaCode"
                type="text"
                class="form-input captcha-input"
                placeholder="请输入验证码"
                maxlength="4"
                autocomplete="off"
                :disabled="loading || isLockedOut"
                @keyup.enter="handleLogin"
              />

              <!-- 验证码画布 -->
              <canvas
                ref="captchaCanvas"
                class="captcha-canvas"
                width="120"
                height="40"
                title="点击刷新验证码"
                @click="refreshCaptcha"
              />

              <!-- 刷新按钮 -->
              <button
                type="button"
                class="captcha-refresh"
                @click="refreshCaptcha"
                title="刷新验证码"
                :disabled="loading"
              >
                <RefreshCw :size="16" :class="{ spinning: captchaLoading }" />
              </button>
            </div>
            <p class="form-hint">为了账户安全，请输入图形验证码</p>
          </div>

          <!-- 错误提示 -->
          <div v-if="error" class="form-error" :class="error.includes('锁定') ? 'error-locked' : ''">
            {{ error }}
          </div>

          <!-- 登录按钮 -->
          <Button
            variant="primary"
            :loading="loading"
            :disabled="isLockedOut"
            block
            class="login-button"
            @click="handleLogin"
          >
            <LogIn :size="16" />
            {{ isLockedOut ? '账号已锁定' : '登 录' }}
          </Button>
        </form>
      </Card>

      <!-- 页脚信息 -->
      <div class="login-footer">
        <p>Echo票务系统 © 2026</p>
        <p class="version-info">v1.0.0 (安全增强版)</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ========== 页面布局（日系轻亚风 × 有机自然感） ========== */
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--color-bg); /* 灰绿背景 #a6baae */
  padding: var(--space-4);
  position: relative;
  overflow: hidden;
}

/* 有机自然纹理背景 */
.login-page::before {
  content: '';
  position: absolute;
  top: -50%;
  left: -50%;
  width: 200%;
  height: 200%;
  background-image:
    radial-gradient(circle at 20% 30%, rgba(166, 186, 174, 0.12) 0%, transparent 50%),
    radial-gradient(circle at 80% 70%, rgba(150, 116, 98, 0.06) 0%, transparent 40%);
  pointer-events: none;
  animation: organicFloat 30s ease-in-out infinite;
}

@keyframes organicFloat {
  0%, 100% { transform: translate(0, 0) rotate(0deg); }
  33% { transform: translate(12px, -8px) rotate(0.4deg); }
  66% { transform: translate(-8px, 12px) rotate(-0.4deg); }
}

.login-container {
  width: 100%;
  max-width: 420px;
  position: relative;
  z-index: 1;
}

/* ========== 头部样式（温和专业） ========== */
.login-header {
  text-align: center;
  margin-bottom: var(--space-8);
}

.login-logo {
  margin-bottom: var(--space-4);
}

.logo-letter {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  background-color: var(--color-primary); /* 陶土棕 #967462 */
  color: var(--color-white);
  border-radius: var(--radius-md); /* 严格遵循8px圆角限制 */
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  font-weight: 700;
  box-shadow: var(--shadow-md); /* 使用设计令牌阴影 */
  transition: transform var(--transition-base);
}

.logo-letter:hover {
  transform: scale(1.03) rotate(-2deg); /* 轻微交互，不过度 */
}

.login-title {
  font-family: var(--font-heading);
  font-size: var(--font-2xl);
  color: var(--color-text); /* 深绿 #2d3a31 */
  margin-bottom: var(--space-1);
  letter-spacing: -0.02em;
}

.login-desc {
  font-size: var(--font-base);
  color: var(--color-text-light); /* 次级灰 #5d5e5a */
  margin-top: var(--space-2);
  font-weight: 400;
}

/* ========== 表单样式（统一设计令牌） ========== */
.login-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.form-label {
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--color-text);
  display: flex;
  align-items: center;
  gap: var(--space-1);
}

.required-mark {
  color: var(--color-danger); /* 使用状态色变量 */
  font-weight: 700;
}

.form-input {
  width: 100%;
  padding: var(--space-3) var(--space-4);
  font-size: var(--font-base);
  border: 1px solid var(--color-border); /* 统一边框色 #d6d0c4 */
  border-radius: var(--radius-md); /* 严格8px圆角 */
  transition: all var(--transition-fast); /* 使用过渡令牌 */
  outline: none;
  background-color: var(--color-white); /* 纯白背景 */
}

.form-input:focus {
  border-color: var(--color-primary); /* 陶土棕聚焦 */
  box-shadow: 0 0 0 2px rgba(150, 116, 98, 0.08); /* 极柔和阴影 */
}

.form-input:disabled {
  background-color: var(--color-white-off); /* 暖白禁用态 */
  cursor: not-allowed;
  opacity: 0.65;
}

.form-input::placeholder {
  color: var(--color-text-light); /* 文本色变量 */
}

/* 登录方式切换 */
.login-mode-switch {
  display: flex;
  gap: var(--space-1);
  padding: 4px;
  margin-bottom: var(--space-4);
  background-color: var(--color-white-off);
  border-radius: var(--radius-md);
}

.mode-tab {
  flex: 1;
  padding: var(--space-2) var(--space-3);
  font-size: var(--font-sm);
  color: var(--color-text-light);
  background: none;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.mode-tab:hover:not(:disabled) {
  color: var(--color-text);
}

.mode-tab.active {
  color: var(--color-text);
  background-color: var(--color-white);
  font-weight: 500;
}

.mode-tab:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

/* 短信验证码 */
.sms-code-wrapper {
  display: flex;
  gap: var(--space-2);
}

.sms-code-input {
  flex: 1;
  letter-spacing: 2px;
}

.sms-code-button {
  flex-shrink: 0;
  padding: 0 var(--space-4);
  font-size: var(--font-sm);
  color: var(--color-primary);
  background-color: var(--color-white);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  white-space: nowrap;
  transition: all var(--transition-fast);
}

.sms-code-button:hover:not(:disabled) {
  border-color: var(--color-primary);
}

.sms-code-button:disabled {
  color: var(--color-text-light);
  cursor: not-allowed;
  opacity: 0.65;
}

/* 密码输入框 */
.password-wrapper {
  position: relative;
}

.password-input {
  padding-right: 48px;
}

.password-toggle {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  background: none;
  border: none;
  color: var(--color-text-light);
  cursor: pointer;
  padding: var(--space-1);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: color var(--transition-fast);
}

.password-toggle:hover {
  color: var(--color-text);
}

/* ========== 验证码样式（自然柔和） ========== */
.captcha-group {
  animation: slideDown 0.3s ease-out;
}

@keyframes slideDown {
  from {
    opacity: 0;
    transform: translateY(-8px);
    max-height: 0;
  }

  to {
    opacity: 1;
    transform: translateY(0);
    max-height: 200px;
  }
}

.captcha-wrapper {
  display: flex;
  gap: var(--space-3);
  align-items: center;
}

.captcha-input {
  flex: 1;
  min-width: 0;
}

.captcha-canvas {
  width: 120px !important;
  height: 40px !important;
  border: 1px solid var(--color-border); /* 统一边框 */
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--transition-fast);
  background-color: var(--color-white-off); /* 暖白底色 */
}

.captcha-canvas:hover {
  border-color: var(--color-primary); /* 陶土棕悬停 */
  box-shadow: var(--shadow-sm); /* 仅sm级阴影 */
}

.captcha-refresh {
  background: none;
  border: 1px solid var(--color-border); /* 次要按钮风格 */
  color: var(--color-text-light);
  cursor: pointer;
  padding: var(--space-2);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
}

.captcha-refresh:hover:not(:disabled) {
  background-color: var(--color-bg-alt); /* 米白悬停 */
  color: var(--color-secondary); /* 深褐色 */
  border-color: var(--color-primary);
}

.captcha-refresh:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.captcha-refresh .spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.form-hint {
  font-size: var(--font-xs);
  color: var(--color-text-light);
  margin-top: calc(var(--space-1) * -0.5);
  font-style: italic;
}

/* ========== 错误提示（温暖不刺眼） ========== */
.form-error {
  font-size: var(--font-sm);
  color: var(--color-danger); /* 使用状态色变量 */
  padding: var(--space-3) var(--space-4);
  background-color: #fef9f7; /* 极淡暖红背景 */
  border: 1px solid rgba(196, 135, 123, 0.22); /* 柔和边框 */
  border-radius: var(--radius-md);
  line-height: 1.5;
  animation: shake 0.5s ease-in-out;
}

.form-error.error-locked {
  background-color: #fffbeb; /* 暖黄警告 */
  border-color: rgba(212, 184, 122, 0.3);
  color: var(--color-warning); /* 金棕警告色 */
}

@keyframes shake {
  0%, 100% { transform: translateX(0); }
  25% { transform: translateX(-4px); } /* 减小幅度 */
  75% { transform: translateX(4px); }
}

/* 登录按钮（增加与密码框的距离） */
.login-button {
  margin-top: var(--space-6); /* 24px间距 */
}

/* ========== 页脚（融入整体） ========== */
.login-footer {
  text-align: center;
  margin-top: var(--space-8);
  color: var(--color-text-light);
  opacity: 0.68;
}

.login-footer p {
  font-size: var(--font-xs);
  margin: var(--space-1) 0;
}

.version-info {
  opacity: 0.58;
  font-style: italic;
}

/* ========== 响应式设计 ========== */
@media (max-width: 480px) {
  .login-page {
    padding: var(--space-3);
    align-items: flex-start;
    padding-top: 10vh;
  }

  .login-container {
    max-width: 100%;
  }

  .logo-letter {
    width: 56px;
    height: 56px;
    font-size: var(--font-xl);
  }

  .login-title {
    font-size: var(--font-xl);
  }

  .captcha-wrapper {
    flex-wrap: wrap;
  }

  .captcha-canvas {
    width: 100px !important;
    height: 36px !important;
  }

  .form-input {
    font-size: var(--font-base);
  }
}

/* ========== 无障碍支持（尊重用户偏好） ========== */
@media (prefers-reduced-motion: reduce) {
  .logo-letter,
  .captcha-canvas,
  .form-error {
    animation: none;
  }

  .login-page::before {
    animation-duration: 60s;
  }

  .logo-letter:hover {
    transform: none;
  }
}
</style>
