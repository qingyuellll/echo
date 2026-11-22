<script setup>
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { api } from '../utils/api.js'

const router = useRouter()
const route = useRoute()

const tab = ref('code')
const phone = ref('')
const code = ref('')
const password = ref('')
const errorMsg = ref('')
const loading = ref(false)
const codeSent = ref(false)
const countdown = ref(0)

let timer = null

const title = computed(() => tab.value === 'code' ? '验证码登录' : '密码登录')

function switchTab(t) {
  tab.value = t
  errorMsg.value = ''
}

async function sendCode() {
  if (!phone.value || phone.value.length !== 11) {
    errorMsg.value = '请输入正确的手机号'
    return
  }
  if (countdown.value > 0) return
  try {
    errorMsg.value = ''
    await api.sendCode(phone.value)
    codeSent.value = true
    countdown.value = 60
    timer = setInterval(() => {
      countdown.value--
      if (countdown.value <= 0) {
        clearInterval(timer)
        timer = null
      }
    }, 1000)
  } catch (e) {
    errorMsg.value = e.message
  }
}

async function login() {
  if (!phone.value) {
    errorMsg.value = '请输入手机号'
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    let res
    if (tab.value === 'code') {
      if (!code.value) {
        errorMsg.value = '请输入验证码'
        loading.value = false
        return
      }
      res = await api.loginByCode(phone.value, code.value)
    } else {
      if (!password.value) {
        errorMsg.value = '请输入密码'
        loading.value = false
        return
      }
      res = await api.loginByPassword(phone.value, password.value)
    }
    sessionStorage.setItem('token', res.data.token)
    // 获取用户信息
    try {
      const userRes = await api.getMe()
      sessionStorage.setItem('user', JSON.stringify(userRes.data))
    } catch {
      // user info may fail but login already succeeded
    }
    const redirect = route.query.redirect || '/'
    await router.push(redirect)
  } catch (e) {
    errorMsg.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <h1 class="login-title">{{ title }}</h1>

      <div class="login-tabs">
        <button
          :class="['login-tab', { active: tab === 'code' }]"
          @click="switchTab('code')"
        >验证码登录</button>
        <button
          :class="['login-tab', { active: tab === 'password' }]"
          @click="switchTab('password')"
        >密码登录</button>
      </div>

      <form class="login-form" @submit.prevent="login">
        <label class="login-field">
          <span>手机号</span>
          <input
            v-model="phone"
            type="tel"
            placeholder="请输入手机号码"
            maxlength="11"
            required
          />
        </label>

        <label v-if="tab === 'code'" class="login-field">
          <span>验证码</span>
          <div class="login-code-row">
            <input
              v-model="code"
              type="text"
              placeholder="请输入验证码"
              maxlength="6"
              required
            />
            <button
              type="button"
              class="login-code-btn"
              :disabled="countdown > 0"
              @click="sendCode"
            >
              {{ countdown > 0 ? `${countdown}s` : (codeSent ? '重新发送' : '获取验证码') }}
            </button>
          </div>
        </label>

        <label v-else class="login-field">
          <span>密码</span>
          <input
            v-model="password"
            type="password"
            placeholder="请输入密码"
            required
          />
        </label>

        <p v-if="errorMsg" class="login-error">{{ errorMsg }}</p>

        <button type="submit" class="login-submit" :disabled="loading">
          {{ loading ? '登录中...' : '登录' }}
        </button>
      </form>

      <p class="login-link">
        <router-link to="/">返回首页</router-link>
      </p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: var(--space-6);
  background-color: var(--color-bg);
}

.login-card {
  width: 100%;
  max-width: 400px;
  background-color: var(--color-white);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
  padding: var(--space-8);
}

.login-title {
  font-family: var(--font-heading);
  font-size: var(--font-xl);
  font-weight: 700;
  text-align: center;
  margin-bottom: var(--space-6);
  color: var(--color-text);
}

.login-tabs {
  display: flex;
  gap: 0;
  margin-bottom: var(--space-6);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.login-tab {
  flex: 1;
  padding: var(--space-2) 0;
  font-size: var(--font-sm);
  font-weight: 500;
  text-align: center;
  background: var(--color-bg);
  color: var(--color-text-light);
  cursor: pointer;
  border-radius: 0;
}

.login-tab.active {
  background: var(--color-primary);
  color: var(--color-white);
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.login-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.login-field span {
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--color-text);
}

.login-field input {
  height: 40px;
}

.login-code-row {
  display: flex;
  gap: var(--space-2);
}

.login-code-row input {
  flex: 1;
}

.login-code-btn {
  flex-shrink: 0;
  height: 40px;
  padding: 0 var(--space-3);
  font-size: var(--font-xs);
  font-weight: 500;
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-alt);
  color: var(--color-text);
  white-space: nowrap;
}

.login-code-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.login-error {
  font-size: var(--font-sm);
  color: var(--color-danger);
  text-align: center;
}

.login-submit {
  margin-top: var(--space-2);
  height: 40px;
  font-size: var(--font-sm);
  font-weight: 500;
  border-radius: var(--radius-sm);
  background-color: var(--color-primary);
  color: var(--color-white);
  cursor: pointer;
}

.login-submit:hover:not(:disabled) {
  background-color: var(--color-secondary);
}

.login-submit:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.login-link {
  text-align: center;
  margin-top: var(--space-6);
  font-size: var(--font-sm);
}
</style>
