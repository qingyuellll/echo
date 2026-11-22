# Echo 票务平台 接口文档

本文件是接口约定的权威说明：通用规范、鉴权方式、完整接口索引与权限矩阵。运行时的完整契约（含每个字段的类型与示例）由 SpringDoc 生成，服务启动后访问 `http://127.0.0.1:8081/swagger-ui.html` 查看，原始描述在 `/v3/api-docs`。

## 一、通用规范

### 1.1 基础信息

| 项 | 值 |
|---|---|
| 本地基础地址 | `http://127.0.0.1:8081` |
| 前端代理前缀 | `/api`（Vite 开发代理与 Nginx 均剥离该前缀后转发） |
| 数据格式 | `application/json;charset=UTF-8` |
| 日期时间 | `yyyy-MM-dd HH:mm:ss`，时区 `Asia/Shanghai` |
| 金额单位 | 分（`int` / `long`），展示时除以 100 |
| 空值策略 | `spring.jackson.default-property-inclusion: non_null`，未赋值的字段不出现在响应中 |

### 1.2 统一响应结构

所有接口返回 `Result<T>`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `success` | boolean | 业务是否成功，前端以此为判断依据 |
| `httpStatus` | int | 业务侧建议的 HTTP 状态码 |
| `message` | string | 提示文案 |
| `errorCode` | string | 业务错误码，成功时为 `null` 且不输出 |
| `data` | T | 业务数据 |
| `total` | long | 传统分页的总条数 |
| `error` / `paginated` / `errorMsg` / `msg` | - | 兼容字段，`errorMsg` 与 `msg` 与 `message` 同值 |

成功响应：

```json
{
  "success": true,
  "httpStatus": 200,
  "message": "操作成功",
  "data": {},
  "error": false,
  "paginated": false,
  "errorMsg": "操作成功",
  "msg": "操作成功"
}
```

业务失败响应（HTTP 状态码为 200，`success` 为 false）：

```json
{
  "success": false,
  "httpStatus": 500,
  "message": "演出不存在",
  "error": true,
  "paginated": false,
  "errorMsg": "演出不存在",
  "msg": "演出不存在"
}
```

### 1.3 HTTP 状态码约定

前端以 `success` 判断业务结果，HTTP 状态码表达了错误的来源层级：

| 状态码 | 触发条件 |
|---|---|
| 200 | 成功，或可展示给用户的业务提示（库存不足、票种已下架、操作频繁等） |
| 400 | 参数格式错误、必填参数缺失、数据不存在、`IllegalArgumentException` |
| 403 | 未登录访问受保护接口、CSRF 校验失败、管理员角色等级不足 |
| 404 | 请求路径没有对应的处理器 |
| 409 | 唯一索引冲突、重复提交 |
| 500 | 未分类的服务端异常（`NullPointerException`、未被归类的 `RuntimeException`） |
| 503 | 限流、连接池耗尽、MQ 或 Redis 不可用等系统繁忙场景 |

异常分类关键字位于 `application.yaml` 的 `exception.*-keywords`，命中 `rate-limit-keywords` 返回 503，命中 `data-error-keywords` 返回 400，命中 `user-friendly-keywords` 返回 200。

### 1.4 鉴权

登录成功后服务端签发随机 Token，客户端在后续请求中通过请求头携带：

```
authorization: {token}
```

- 用户端：`POST /user/login`，`data` 返回 `token`、`csrfToken`、`userId`、`nickName`。
- 管理端：`POST /admin/login`，`data` 返回 `token`、`csrfToken`、`adminId`、`name`、`roleLevel`、`isSuperAdmin`。
- Token 存于 Redis（`login:token:{token}` / `admin:token:{token}`），有效期由服务端配置决定。
- 登出调用 `POST /user/logout` 或 `POST /admin/logout`，服务端删除该 Token。

管理员登录支持两种凭证，请求体二选一：

| 凭证 | 请求体 | 前置动作 |
|---|---|---|
| 密码 | `{ "phone": "...", "password": "..." }` | 无 |
| 短信验证码 | `{ "phone": "...", "code": "123456" }` | 先调用 `POST /admin/code?phone=...` |

验证码存于 `admin:code:{phone}`，有效期 120 秒，60 秒内不可重复发送，登录成功后立即销毁。两种凭证共用同一套失败计数 `admin:login:fail:{phone}`，达到上限后整体锁定。

### 1.4.1 引导通道

全新部署或超级管理员密码遗失时，系统内没有任何可登录凭证。此时用引导令牌重置超管密码：

```
POST /admin/init-password
X-Bootstrap-Token: {ADMIN_BOOTSTRAP_TOKEN}
```

- 令牌由部署方通过环境变量 `ADMIN_BOOTSTRAP_TOKEN` 注入，留空即关闭该通道
- 该路径跳过登录校验、管理员身份校验与 CSRF 校验，授权完全由令牌完成
- 重置结果为随机 12 位临时密码，只在本次响应 `data.temporaryPassword` 返回一次
- 重置同时清空该账号的登录令牌、失败计数与 CSRF Token

已登录的超级管理员轮换自身密码使用 `POST /admin/reset-super-password`（需 `SUPER_ADMIN` 角色，无需引导令牌）。

`RefreshTokenInterceptor` 解析 `authorization` 并填充 `UserHolder`，登录态校验由 `LoginInterceptor` 依据 `echo.mvc` 下的路径白名单执行。

### 1.5 CSRF

写操作（POST / PUT / DELETE / PATCH）需携带：

```
X-CSRF-Token: {csrfToken}
```

- Token 由服务端按登录主体维护，键为 `csrf:token:{role}:{id}`（`role` 取 `user` 或 `admin`），同一 Token 在整个会话内持续有效并滑动续期。
- 管理员与用户各自持有独立 Token：两侧的主键序列互不相干，键中带上身份类型才能保证 `tb_admin.id` 与 `tb_user.id` 相同的主体不互相覆盖。
- 获取途径：登录响应体的 `csrfToken`，或任意已登录 GET 响应头 `X-CSRF-Token`。
- 免校验路径：`/error`、`/user/login`、`/user/code`、`/admin/login`、`/admin/code`、`/admin/init-password`、`/upload`。
- 前端把 Token 存入 `localStorage`，请求拦截器自动附加，响应拦截器自动刷新。

### 1.6 分页

传统分页参数：

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `current` | int | 1 | 页码 |
| `size` | int | 10 | 每页条数 |
| `pageSize` | int | 10 | `size` 的等价写法，两者绑定同一字段 |
| `sortBy` | string | - | 排序字段 |
| `sortOrder` | string | desc | `asc` / `desc` |

`size` 与 `pageSize` 是同一个参数的两种拼写，服务端统一按 `size` 处理，取值上限 100。

传统分页响应 `PageResult<T>`：

```json
{ "records": [], "total": 0, "current": 1, "pageSize": 10 }
```

游标分页用于信息流场景（如 `GET /show/scroll`），响应 `CursorPageResult<T>`：

```json
{ "records": [], "nextCursor": "xxx", "hasMore": true, "count": 10 }
```

请求时把上一次返回的 `nextCursor` 作为 `cursor` 参数传入。

### 1.7 角色

管理员角色定义在 `AdminRoleLevel`，等级数值越小权限越高：

| code | 角色 | 权限范围 |
|---|---|---|
| 0 | 超级管理员 | 全部接口 |
| 1 | 普通管理员 | 除标注 `SUPER_ADMIN` 之外的管理接口 |

方法级权限由 `@RequireRole` 注解配合 `PermissionCheckAspect` 判定，等级不足返回 403。

## 二、用户端接口

鉴权列说明：公开＝无需 Token；可选＝带 Token 时解析身份、无 Token 也可访问；登录＝需要用户 Token。

### 2.1 认证与账号

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/user/code` | 发送手机验证码，参数 `phone` | 公开 |
| POST | `/user/login` | 登录，参数 `phone`、`password` | 公开 |
| POST | `/user/logout` | 退出登录 | 登录 |
| GET | `/user/me` | 当前登录用户信息 | 登录 |
| POST | `/user/password/code` | 发送修改密码验证码 | 登录 |
| PUT | `/user/password` | 修改密码，参数 `oldPassword`、`newPassword`、`code` | 登录 |
| POST | `/user/sign` | 每日签到 | 登录 |
| GET | `/user/sign/count` | 连续签到天数 | 登录 |

用户端密码按明文传输，服务端使用 BCrypt（cost=12）比对。

### 2.2 用户资料

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/user/{id}` | 按 ID 查询用户 | 可选 |
| GET | `/user/info/{id}` | 用户详细资料（性别、城市、生日、简介） | 可选 |
| GET | `/user/profile/{id}` | 用户主页聚合数据 | 可选 |
| GET | `/user/search` | 搜索用户，参数 `keyword` | 可选 |
| PUT | `/user/info` | 更新个人资料 | 登录 |

`/user/info/{id}` 按用户维度做固定窗口限流，60 秒内上限 60 次。

### 2.3 演出

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/show/search` | 快速搜索，支持分页 | 可选 |
| GET | `/show/of/name` | 按名称搜索 | 可选 |
| GET | `/show/of/type` | 按类型浏览，支持距离排序 | 可选 |
| GET | `/show/scroll` | 演出滚动列表（游标分页） | 登录 |
| GET | `/show/{id}` | 演出详情 | 可选 |
| POST | `/show/{id}/follow` | 关注演出 | 登录 |
| DELETE | `/show/{id}/follow` | 取消关注 | 登录 |
| GET | `/show/{id}/follow` | 检查关注状态 | 可选 |
| GET | `/show/follow/my` | 我的关注列表 | 登录 |
| POST | `/show/{id}/favorite` | 收藏 / 取消收藏 | 登录 |
| GET | `/show/favorite/my` | 我的收藏 | 登录 |
| GET | `/show-type/list` | 演出类型列表 | 可选 |

### 2.4 票务

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/ticket/list/{showId}` | 演出的票种列表 | 可选 |
| GET | `/ticket/detail/{id}` | 票种详情 | 可选 |
| GET | `/ticket/{id}/seats` | 座位列表与状态 | 可选 |
| POST | `/ticket/{id}/seat/lock/{seatId}` | 锁定座位 | 登录 |
| POST | `/ticket/{id}/seat/release/{seatId}` | 释放座位 | 登录 |
| POST | `/ticket/batch-release` | 批量释放座位（页面关闭时调用） | 登录 |
| GET | `/ticket/{id}/status` | 票种状态（是否可售、是否售罄） | 登录 |
| GET | `/ticket/seckill/check-purchased/{ticketId}` | 检查当前用户是否已购买该秒杀票 | 登录 |
| DELETE | `/ticket/cache/{showId}` | 刷新票种缓存 | 登录 |

### 2.5 订单

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/ticket-order` | 选座下单 | 登录 |
| POST | `/ticket-order/{id}` | 普通下单（无座票） | 登录 |
| POST | `/ticket-order/seckill/{id}` | 秒杀下单，参数为秒杀票种 ID | 登录 |
| GET | `/ticket-order/seckill/queue/status/{id}` | 查询排队结果 | 登录 |
| POST | `/ticket-order/seckill/check-batch` | 批量检查秒杀票购买状态 | 登录 |
| GET | `/ticket-order/my` | 我的订单列表 | 登录 |
| GET | `/ticket-order/{id}` | 订单详情 | 登录 |
| POST | `/ticket-order/pay/{id}` | 模拟支付 | 登录 |
| POST | `/ticket-order/cancel/{id}` | 取消订单 | 登录 |
| POST | `/ticket-order/refund/{id}` | 申请退款 | 登录 |
| POST | `/ticket-order/verify/{code}` | 核销入场 | 登录 |
| GET | `/ticket-order/verify/status/{orderId}/{ticketIndex}` | 单张票的核销状态 | 登录 |

秒杀下单为异步流程：接口返回 `{"data":{"status":"QUEUED"}}` 表示已入队，客户端随后轮询 `/ticket-order/seckill/queue/status/{id}` 获取成交结果。售罄、已购买、活动未开始或已结束、请求过于频繁均以 `success=false` 返回，属于正常业务分支。

### 2.6 评测与评论

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/show-review` | 发布评测 | 登录 |
| GET | `/show-review/list` | 评测列表（兼容接口） | 登录 |
| GET | `/show-review/{id}` | 评测详情 | 可选 |
| DELETE | `/show-review/{id}` | 删除自己的评测 | 登录 |
| GET | `/show-review/hot` | 热门评测 | 可选 |
| GET | `/show-review/latest` | 最新评测 | 可选 |
| GET | `/show-review/latest-commented` | 最新评论过的评测 | 可选 |
| GET | `/show-review/recommended` | 推荐评测 | 可选 |
| GET | `/show-review/search` | 搜索评测 | 可选 |
| GET | `/show-review/of/show` | 按演出查询评测 | 可选 |
| GET | `/show-review/of/user` | 按用户查询评测 | 可选 |
| GET | `/show-review/of/me` | 我的评测 | 登录 |
| GET | `/show-review/of/follow` | 关注的人的评测 | 登录 |
| PUT | `/show-review/like/{id}` | 点赞 / 取消点赞 | 登录 |
| GET | `/show-review/likes/{id}` | 评测点赞数 | 可选 |
| GET | `/show-review-comments/of/{reviewId}` | 评测的评论列表 | 可选 |
| POST | `/show-review-comments/save` | 发布评论 | 登录 |

### 2.7 关注关系

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| PUT | `/follow/{id}/{isFollow}` | 关注或取关用户，`isFollow` 取 `true` / `false` | 登录 |
| GET | `/follow/status/{id}` | 是否已关注该用户 | 可选 |
| GET | `/follow/common/{id}` | 共同关注列表 | 可选 |

### 2.8 通知与积分

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/notification/my` | 我的通知列表 | 登录 |
| PUT | `/notification/{id}/read` | 单条标记已读 | 登录 |
| PUT | `/notification/read-all` | 全部标记已读 | 登录 |
| GET | `/points/my` | 积分余额 | 登录 |
| GET | `/points/records` | 积分流水 | 登录 |
| POST | `/points/lottery` | 积分抽奖 | 登录 |
| GET | `/points/lottery/records` | 抽奖记录 | 登录 |
| POST | `/points/coupon` | 积分兑换优惠券 | 登录 |

### 2.9 统计与上传

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| GET | `/statistics/dashboard` | 首页统计 | 可选 |
| GET | `/statistics/hot-shows` | 热门演出排行 | 可选 |
| GET | `/statistics/sales-trend` | 销售趋势 | 可选 |
| GET | `/statistics/user-profile` | 用户画像统计 | 登录 |
| POST | `/upload/avatar` | 上传头像，`multipart/form-data` | 登录 |
| POST | `/upload/blog` | 上传演出海报或博客图片 | 登录 |

上传文件写入 `echo.upload.image-root`（默认 `D:/echo/nginx-1.18.0/nginx-1.18.0/html/ssn/imgs`），返回的路径以 `/ssn/imgs/` 开头。

## 三、管理端接口

管理接口统一位于 `/admin` 前缀下，需要管理员 Token。票种接口同时注册了 `/admin/ticket` 与 `/admin/tickets` 两种前缀，二者等价。

### 3.1 认证

| 方法 | 路径 | 说明 | 角色 |
|---|---|---|---|
| POST | `/admin/code` | 发送登录验证码 | 公开 |
| POST | `/admin/login` | 管理员登录 | 公开 |
| POST | `/admin/logout` | 退出登录 | 管理员 |
| GET | `/admin/info` | 当前管理员信息与角色 | 管理员 |
| POST | `/admin/password/code` | 发送修改密码验证码 | 管理员 |
| PUT | `/admin/password` | 修改密码 | 管理员 |
| POST | `/admin/init-password` | 初始化超级管理员密码 | 超级管理员 |

管理端密码按明文传输，与用户端规则一致。

### 3.2 演出管理

| 方法 | 路径 | 说明 | 角色 |
|---|---|---|---|
| GET | `/admin/show/list` | 演出列表 | 管理员 |
| POST | `/admin/show` | 创建演出 | 超级管理员 |
| PUT | `/admin/show/{id}` | 更新演出 | 管理员 |
| DELETE | `/admin/show/{id}` | 删除演出 | 管理员 |
| PUT | `/admin/show/{id}/online` | 上架 | 管理员 |
| PUT | `/admin/show/{id}/offline` | 下架 | 超级管理员 |
| POST | `/admin/show/batch` | 批量操作 | 管理员 |
| POST | `/admin/show/cache/clear` | 清除全部演出缓存 | 超级管理员 |

### 3.3 票种与座位

| 方法 | 路径 | 说明 | 角色 |
|---|---|---|---|
| GET | `/admin/tickets/list` | 票种列表 | 管理员 |
| POST | `/admin/tickets` | 新增普通票种 | 管理员 |
| PUT | `/admin/tickets` | 编辑票种 | 管理员 |
| DELETE | `/admin/tickets/{id}` | 删除票种 | 超级管理员 |
| PUT | `/admin/tickets/{id}/status` | 上架 / 下架 | 超级管理员 |
| PUT | `/admin/tickets/{id}/stock` | 调整库存 | 超级管理员 |
| POST | `/admin/tickets/with-seats` | 创建带座位的票种 | 管理员 |
| GET | `/admin/tickets/{ticketId}/seats` | 座位列表 | 管理员 |
| POST | `/admin/tickets/{ticketId}/seats/batch` | 批量生成座位 | 超级管理员 |
| POST | `/admin/tickets/seckill` | 创建秒杀票 | 管理员 |
| PUT | `/admin/tickets/seckill` | 更新秒杀票 | 管理员 |
| POST | `/admin/ticket/batch` | 批量操作票种 | 管理员 |

### 3.4 订单

| 方法 | 路径 | 说明 | 角色 |
|---|---|---|---|
| GET | `/admin/order/list` | 订单列表，支持状态、关键字、用户名筛选 | 管理员 |
| GET | `/admin/order/{id}` | 订单详情，含核销码 | 管理员 |
| POST | `/admin/order/{id}/approve-refund` | 审批退款 | 管理员 |
| GET | `/admin/order/export` | 导出订单 | 管理员 |
| POST | `/admin/order/batch` | 批量操作订单 | 管理员 |

### 3.5 用户与员工

| 方法 | 路径 | 说明 | 角色 |
|---|---|---|---|
| GET | `/admin/user/list` | 用户列表 | 管理员 |
| GET | `/admin/user/{id}` | 用户详情 | 管理员 |
| PUT | `/admin/user/{id}/disable` | 禁用用户 | 管理员 |
| PUT | `/admin/user/{id}/enable` | 启用用户 | 管理员 |
| POST | `/admin/user/batch` | 批量操作用户 | 管理员 |
| GET | `/admin/staff/list` | 员工列表，凭证字段不出服务层 | 管理员 |
| POST | `/admin/staff/add` | 新增员工 | 管理员 |
| POST | `/admin/staff/remove` | 移除员工 | 管理员 |
| PUT | `/admin/staff/{id}/disable` | 禁用员工 | 管理员 |
| PUT | `/admin/staff/{id}/enable` | 启用员工 | 管理员 |

### 3.6 核销

| 方法 | 路径 | 说明 | 角色 |
|---|---|---|---|
| GET | `/admin/verification/statistics` | 核销统计 | 管理员 |
| GET | `/admin/verification/recent` | 最近核销记录 | 管理员 |

核销动作本身由用户端 `POST /ticket-order/verify/{code}` 完成，管理端页面负责扫码与统计。

### 3.7 日志、回滚与运维

| 方法 | 路径 | 说明 | 角色 |
|---|---|---|---|
| GET | `/admin/operation-logs` | 查询操作日志 | 管理员 |
| GET | `/admin/operation-logs/export` | 导出操作日志 | 管理员 |
| GET | `/admin/rollback/rollable-operations` | 可回滚的操作列表 | 管理员 |
| GET | `/admin/rollback/preview/{logId}` | 预览回滚影响范围 | 管理员 |
| POST | `/admin/rollback/{logId}` | 执行回滚 | 管理员 |
| POST | `/admin/repair-data` | 手动触发数据修复 | 管理员 |
| POST | `/admin/cache/clear-shows` | 清除演出缓存 | 管理员 |
| POST | `/admin/cache/clear-tickets` | 清除票种缓存 | 管理员 |

### 3.8 监控面板

| 方法 | 路径 | 说明 | 角色 |
|---|---|---|---|
| GET | `/admin/dashboard` | 仪表盘汇总 | 管理员 |
| GET | `/admin/monitoring/today-stats` | 今日统计 | 管理员 |
| GET | `/admin/monitoring/recent-logs` | 最近操作日志 | 管理员 |
| GET | `/admin/monitoring/daily-trend` | 每日操作趋势 | 管理员 |
| GET | `/admin/monitoring/hourly-performance` | 每小时性能数据 | 管理员 |

## 四、开发环境专用接口

`InitController` 仅在 `spring.init.debug=true` 时注册，默认不加载：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/init/generate-password` | 生成 BCrypt 密码串 |
| GET | `/init/list-admins` | 列出管理员 |
| GET | `/init/debug-admin` | 查看管理员详情 |
| GET | `/init/debug-password` | 查看密码哈希 |
| POST | `/init/reset-password` | 重置密码 |
| POST | `/init/force-reset-password` | 强制重置密码 |
| POST | `/init/unlock-account` | 解除账号锁定 |

## 五、权限矩阵

| 能力 | 未登录 | 普通用户 | 普通管理员 | 超级管理员 |
|---|:--:|:--:|:--:|:--:|
| 浏览演出、票种、评测、统计 | 是 | 是 | 是 | 是 |
| 查看用户主页 | 是 | 是 | 是 | 是 |
| 下单、支付、退款、核销 | 否 | 是 | 否 | 否 |
| 评测、评论、点赞、关注 | 否 | 是 | 否 | 否 |
| 签到、积分、通知 | 否 | 是 | 否 | 否 |
| 演出增删改、上下架 | 否 | 否 | 部分 | 是 |
| 票种增删改、库存调整 | 否 | 否 | 部分 | 是 |
| 订单审批、导出、批量操作 | 否 | 否 | 是 | 是 |
| 用户与员工管理 | 否 | 否 | 是 | 是 |
| 日志、回滚、数据修复 | 否 | 否 | 是 | 是 |
| 初始化超级管理员密码 | 否 | 否 | 否 | 是 |

`部分` 表示新增与编辑开放给普通管理员，删除、上下架、库存调整、批量生成座位保留给超级管理员。

## 六、维护约定

- 新增接口时同步更新本文件的接口索引表与权限矩阵。
- 接口注释使用 `@Operation(summary = "...")`，`summary` 是 Swagger 与本文档共同的描述来源。
- 业务错误通过 `Result.fail(AppMessages.XXX)` 返回，文案常量集中在 `com.echo.constants`。
- 写操作在 `@RequireRole` 中声明最低角色等级，权限判定由 `PermissionCheckAspect` 统一执行。
