import { createRouter, createWebHashHistory } from 'vue-router'
import { api } from '@/utils/api.js'

const routes = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { noLayout: true }
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/views/Dashboard.vue'),
    meta: { title: '仪表盘', requiresAdmin: true }
  },
  {
    path: '/shows',
    name: 'Shows',
    component: () => import('@/views/Shows.vue'),
    meta: { title: '演出管理', requiresAdmin: true }
  },
  {
    path: '/tickets',
    name: 'Tickets',
    component: () => import('@/views/TicketsSimple.vue'),
    meta: { title: '票种管理', requiresAdmin: true }
  },
  {
    path: '/tickets/seckill',
    name: 'SeckillTicket',
    component: () => import('@/views/SeckillTicket.vue'),
    meta: { title: '添加秒杀票', requiresAdmin: true }
  },
  {
    path: '/orders',
    name: 'Orders',
    component: () => import('@/views/Orders.vue'),
    meta: { title: '订单管理', requiresAdmin: true }
  },
  {
    path: '/users',
    name: 'Users',
    component: () => import('@/views/Users.vue'),
    meta: { title: '用户管理', requiresAdmin: true }
  },
  {
    path: '/staff',
    name: 'Staff',
    component: () => import('@/views/Staff.vue'),
    meta: { title: '员工管理', requiresAdmin: true }
  },
  {
    path: '/verification',
    name: 'Verification',
    component: () => import('@/views/Verification.vue'),
    meta: { title: '票务核销', requiresAdmin: true }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

let adminInfoCache = null
let adminInfoPromise = null

async function verifyAdminRole() {
  try {
    const res = await api.getAdminInfo()
    if (res && res.data) {
      adminInfoCache = res.data
      sessionStorage.setItem('admin_roleLevel', res.data.roleLevel ?? 1)
      sessionStorage.setItem('admin_name', res.data.name ?? '')
      return true
    }
    return false
  } catch (error) {
    console.error('[路由守卫] 管理员身份验证失败:', error.message)
    return false
  } finally {
    adminInfoPromise = null
  }
}

router.beforeEach(async (to, from, next) => {
  const token = sessionStorage.getItem('token')

  // 未登录 → 跳转登录页
  if (to.name !== 'Login' && !token) {
    next({ name: 'Login' })
    return
  }

  // 已登录访问登录页 → 跳转首页
  if (to.name === 'Login' && token) {
    next({ name: 'Dashboard' })
    return
  }

  // 需要管理员身份的页面（除登录页外的所有页面）
  if (to.meta.requiresAdmin) {
    // 如果已有缓存的管理员信息且未过期，直接放行
    if (adminInfoCache && sessionStorage.getItem('admin_roleLevel')) {
      next()
      return
    }

    // 正在请求中，等待结果
    if (adminInfoPromise) {
      try {
        await adminInfoPromise
        if (adminInfoCache) {
          next()
        } else {
          console.warn('[路由守卫] 管理员身份验证失败，清除无效token')
          sessionStorage.removeItem('token')
          sessionStorage.removeItem('admin_roleLevel')
          adminInfoCache = null
          next({ name: 'Login' })
        }
      } catch (e) {
        console.error('[路由守卫] 等待管理员验证时出错:', e)
        next({ name: 'Login' })
      }
      return
    }

    // 发起管理员身份验证请求
    adminInfoPromise = verifyAdminRole()

    try {
      const isValid = await adminInfoPromise

      if (isValid) {
        next()
      } else {
        console.warn('[路由守卫] Token无效或非管理员Token，重定向到登录页')
        sessionStorage.removeItem('token')
        sessionStorage.removeItem('admin_roleLevel')
        adminInfoCache = null
        next({ name: 'Login' })
      }
    } catch (error) {
      console.error('[路由守卫] 管理员验证异常:', error)
      adminInfoCache = null
      next({ name: 'Login' })
    }

    return
  }

  // 其他页面直接放行
  next()
})

export default router
