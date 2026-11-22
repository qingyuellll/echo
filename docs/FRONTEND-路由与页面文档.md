# Echo 票务平台 前端文档

## 一、技术栈与约定

| 项 | 用户端 | 管理端 |
|---|---|---|
| 框架 | Vue 3 + Vite | Vue 3 + Vite |
| 路由 | `createWebHistory()` | `createWebHashHistory()` |
| 开发端口 | 3002 | 3001 |
| 登录态 | `sessionStorage.token` | `sessionStorage.token` |
| CSRF | `localStorage.csrfToken` + `csrfTokenTime` | `localStorage.csrf_token` + `csrf_token_time` |
| 请求入口 | `src/utils/api.js` 的 `request` | `src/utils/api.js` 的 `requestWithRetry` |
| 接口前缀 | `/api`，由 Vite 代理剥离 | `/api`，由 Vite 代理剥离 |

两端共享 `shared/` 目录中的工具，用户端的 Vite 配置把 `shared` 别名指向 `../shared`。

## 二、用户端路由

基础路径 `http://127.0.0.1:3002`，除登录页外均挂在 `MainLayout` 下（Header + Footer）。

| 路径 | name | 组件 | 需登录 | 说明 |
|---|---|:--:|:--:|---|
| `/` | Home | `Home.vue` | 否 | 首页聚合 |
| `/shows` | Shows | `Shows.vue` | 否 | 演出列表 |
| `/show/:id` | ShowDetail | `ShowDetail.vue` | 否 | 演出详情 |
| `/reviews` | Reviews | `Reviews.vue` | 否 | 评测广场 |
| `/review/:id` | ReviewDetail | `ReviewDetail.vue` | 否 | 评测详情 |
| `/user/:id` | User | `User.vue` | 否 | 他人主页 |
| `/about` | About | `About.vue` | 否 | 关于 |
| `/reviews/publish` | PublishReview | `PublishReview.vue` | 是 | 发布评测 |
| `/order` | Order | `Order.vue` | 是 | 我的订单 |
| `/pay-order` | PayOrder | `PayOrder.vue` | 是 | 支付页 |
| `/seat-selection` | SeatSelection | `SeatSelection.vue` | 是 | 选座 |
| `/profile` | Profile | `Profile.vue` | 是 | 个人中心 |
| `/notifications` | Notifications | `Notifications.vue` | 是 | 消息通知 |
| `/lottery` | Lottery | `Lottery.vue` | 是 | 积分抽奖 |
| `/login` | Login | `Login.vue` | 否 | 登录，独立布局 |

路由守卫读取 `meta.requiresAuth`，未登录时跳转 `/login?redirect=<原路径>`：

```javascript
router.beforeEach((to, from, next) => {
  const token = sessionStorage.getItem('token')
  if (to.meta.requiresAuth && !token) {
    next({ name: 'Login', query: { redirect: to.fullPath } })
  } else {
    next()
  }
})
```

## 三、管理端路由

基础路径 `http://127.0.0.1:3001/#/`，使用 Hash 模式，`/` 重定向到 `/dashboard`。

| 路径 | name | 组件 | 说明 |
|---|---|---|---|
| `/dashboard` | Dashboard | `Dashboard.vue` | 仪表盘 |
| `/shows` | Shows | `Shows.vue` | 演出管理 |
| `/tickets` | Tickets | `TicketsSimple.vue` | 票种管理 |
| `/tickets/seckill` | SeckillTicket | `SeckillTicket.vue` | 秒杀票配置 |
| `/orders` | Orders | `Orders.vue` | 订单管理 |
| `/users` | Users | `Users.vue` | 用户管理 |
| `/staff` | Staff | `Staff.vue` | 员工管理 |
| `/verification` | Verification | `Verification.vue` | 票务核销 |
| `/login` | Login | `Login.vue` | 登录，`meta.noLayout` |

守卫通过 `GET /admin/info` 验证身份，成功后把 `roleLevel` 与 `name` 写入 `sessionStorage` 供页面判断按钮可见性：

```javascript
// 简化后的行为
// 1. 无 token 且目标非登录页 → 跳转 /login
// 2. 有 token 且目标为登录页 → 跳转 /dashboard
// 3. 其他情况调用 verifyAdminRole()，失败则清除 token 并跳转 /login
```

`verifyAdminRole` 内部缓存管理员信息与进行中的请求，避免并发导航重复调用接口。

## 四、页面与接口映射

### 4.1 用户端

| 页面 | 主要接口 |
|---|---|
| Home | `/statistics/dashboard`、`/statistics/hot-shows`、`/show-review/hot` |
| Shows | `/show-type/list`、`/show/of/type`、`/show/search`、`/statistics/hot-shows` |
| ShowDetail | `/show/{id}`、`/ticket/list/{showId}`、`/show-review/of/show`、`/show-review/like/{id}`、`/show/{id}/follow` |
| Reviews | `/show-review/recommended`、`/show-review/latest`、`/show-review/hot`、`/show-review/latest-commented`、`/show-review/of/follow`、`/show-review/search` |
| ReviewDetail | `/show-review/{id}`、`/show-review-comments/of/{reviewId}`、`/show-review/like/{id}`、`/show-review-comments/save` |
| PublishReview | `/show/search`、`/show-review`、`/upload/blog` |
| Order | `/ticket-order/my`、`/ticket-order/{id}`、`/ticket-order/pay/{id}`、`/ticket-order/refund/{id}`、`/ticket-order/seckill/{id}`、`/ticket-order/seckill/queue/status/{id}` |
| PayOrder | `/ticket-order/{id}`、`/ticket-order/pay/{id}`、`/ticket-order/cancel/{id}`、`/points/my` |
| SeatSelection | `/ticket/detail/{id}`、`/ticket/{id}/seats`、`/ticket/{id}/seat/lock/{seatId}`、`/ticket/batch-release`、`/ticket-order` |
| Profile | `/user/me`、`/user/info`、`/ticket-order/my`、`/show-review/of/me`、`/points/my`、`/points/records`、`/upload/avatar` |
| Notifications | `/notification/my`、`/notification/{id}/read`、`/notification/read-all` |
| Lottery | `/points/my`、`/points/lottery`、`/points/lottery/records` |
| User | `/user/profile/{id}`、`/show-review/of/user`、`/follow/status/{id}`、`/follow/{id}/{isFollow}` |
| Login | `/user/code`、`/user/login`、`/user/me` |

### 4.2 管理端

| 页面 | 主要接口 |
|---|---|
| Dashboard | `/admin/dashboard` |
| Shows | `/admin/show/list`、`/admin/show`、`/admin/show/{id}`、`/admin/show/{id}/online`、`/admin/show/{id}/offline`、`/admin/show/batch`、`/upload/blog` |
| TicketsSimple | `/admin/tickets/list`、`/admin/tickets`、`/admin/tickets/with-seats`、`/admin/tickets/{id}/status`、`/admin/tickets/{ticketId}/seats`、`/admin/tickets/{ticketId}/seats/batch` |
| SeckillTicket | `/admin/tickets/seckill` |
| Orders | `/admin/order/list`、`/admin/order/batch` |
| Users | `/admin/user/list`、`/admin/user/{id}/disable`、`/admin/user/{id}/enable` |
| Staff | `/admin/staff/list`、`/admin/staff/add`、`/admin/staff/remove`、`/admin/staff/{id}/disable`、`/admin/staff/{id}/enable`、`/admin/info` |
| Verification | `/admin/verification/statistics`、`/admin/verification/recent`、`/admin/order/{id}`、`/ticket-order/verify/status/{orderId}/{ticketIndex}`、`/ticket-order/verify/{code}` |
| Login | `/admin/login` |

## 五、请求层约定

**统一响应处理**：`api.js` 判断 `data.success === false` 时抛出 `BusinessError`，网络异常抛出 `NetworkError`，管理端在此基础上按错误类型决定是否重试。

**CSRF 自动维护**：响应拦截器优先读取响应头 `X-CSRF-Token`，其次读响应体 `csrfToken` 或 `data.csrfToken`，写入 `localStorage`；请求拦截器在写操作时附加该头。

**登录态隔离**：Token 存在 `sessionStorage`，同一浏览器的多个标签页各自持有一份登录态。

**管理端重试**：`requestWithRetry` 对可重试错误按 `API_CONFIG.retryDelay` 递增等待后重发，`options._noRetry` 可关闭单个请求的重试。

**本地限流提示**：管理端在发请求前用 `checkRateLimit()` 做前端节流，避免用户连续点击产生大量重复请求。

## 六、路由维护

新增用户端页面：

```javascript
{
  path: 'new-page',
  name: 'NewPage',
  component: () => import('../views/NewPage.vue'),
  meta: { requiresAuth: true }
}
```

新增管理端页面：

```javascript
{
  path: '/new-page',
  name: 'NewPage',
  component: () => import('@/views/NewPage.vue'),
  meta: { title: '新页面', requiresAdmin: true }
}
```

约定：子路由的 `path` 使用相对写法，跳转时使用完整路径；`meta.title` 是管理端侧边栏与面包屑的文案来源；管理端页面所需的角色等级差异通过 `sessionStorage.admin_roleLevel` 判断按钮可见性，服务端仍以 `@RequireRole` 做最终校验。
