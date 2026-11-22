import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../components/layout/MainLayout.vue'

const routes = [
  {
    path: '/',
    component: MainLayout,
    children: [
      { path: 'guide', name: 'GuideHub', component: () => import('../views/GuideHub.vue'), meta: { requiresAuth: true } },
      { path: 'guide/checkout/:ticketId', name: 'GuideCheckout', component: () => import('../views/GuideCheckout.vue'), meta: { requiresAuth: true } },
      {
        path: 'pay-order',
        name: 'PayOrder',
        component: () => import('../views/PayOrder.vue'),
        meta: { requiresAuth: true }
      },
      {
        path: '',
        name: 'Home',
        component: () => import('../views/Home.vue')
      },
      {
        path: 'shows',
        name: 'Shows',
        component: () => import('../views/Shows.vue')
      },
      {
        path: 'show/:id',
        name: 'ShowDetail',
        component: () => import('../views/ShowDetail.vue')
      },
      {
        path: 'order',
        name: 'Order',
        component: () => import('../views/Order.vue'),
        meta: { requiresAuth: true }
      },
      {
        path: 'seat-selection',
        name: 'SeatSelection',
        component: () => import('../views/SeatSelection.vue'),
        meta: { requiresAuth: true }
      },
      {
        path: 'reviews',
        name: 'Reviews',
        component: () => import('../views/Reviews.vue')
      },
      {
        path: 'review/:id',
        name: 'ReviewDetail',
        component: () => import('../views/ReviewDetail.vue')
      },
      {
        path: 'reviews/publish',
        name: 'PublishReview',
        component: () => import('../views/PublishReview.vue'),
        meta: { requiresAuth: true }
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('../views/Profile.vue'),
        meta: { requiresAuth: true }
      },
      {
        path: 'notifications',
        name: 'Notifications',
        component: () => import('../views/Notifications.vue'),
        meta: { requiresAuth: true, title: '消息通知' }
      },
      {
        path: 'lottery',
        name: 'Lottery',
        component: () => import('../views/Lottery.vue'),
        meta: { requiresAuth: true }
      },
      {
        path: 'user/:id',
        name: 'User',
        component: () => import('../views/User.vue')
      },
      {
        path: 'about',
        name: 'About',
        component: () => import('../views/About.vue')
      }
    ]
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const token = sessionStorage.getItem('token')

  if (to.meta.requiresAuth && !token) {
    console.warn('[路由守卫] 需要登录但未检测到token，重定向到登录页 - 目标:', to.path)
    next({ name: 'Login', query: { redirect: to.fullPath } })
  } else {
    next()
  }
})

export default router
