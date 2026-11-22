# Echo 票务平台 系统架构

## 一、技术栈

| 层次 | 选型 |
|---|---|
| 后端框架 | Spring Boot 2.7.18、Java 17 |
| 持久层 | MyBatis-Plus 3.5.3、HikariCP |
| 数据库 | MySQL 8.0 |
| 缓存 | Caffeine（L1 进程内）+ Redis（L2 分布式） |
| 消息队列 | RocketMQ 5.x |
| 分布式锁 | `SimpleRedisLock`（Lua 实现，可重入与看门狗续期）+ Redisson（定时发布任务） |
| 缓存客户端 | Spring Data Redis（Lettuce 连接池） |
| 监控 | Micrometer + Prometheus，Actuator 暴露端点 |
| 接口文档 | SpringDoc OpenAPI |
| 前端 | Vue 3 + TypeScript + Vite + Vue Router 4 |
| 压测 | JMeter 5.6.3 + Node 原生压测脚本 |

## 二、整体结构

```
                 ┌──────────────────┐      ┌──────────────────┐
                 │  用户端 Vue3      │      │  管理端 Vue3      │
                 │  3002            │      │  3001            │
                 └────────┬─────────┘      └────────┬─────────┘
                          │  /api 反向代理           │
                          └───────────┬─────────────┘
                                      │
                         ┌────────────▼─────────────┐
                         │  Spring Boot 应用 :8081   │
                         │                          │
                         │  Controller              │
                         │      ↓                   │
                         │  Service（业务编排）       │
                         │      ↓                   │
                         │  Mapper（MyBatis-Plus）   │
                         └──┬────────┬────────┬─────┘
                            │        │        │
                   ┌────────▼──┐ ┌───▼────┐ ┌─▼──────────┐
                   │  Redis    │ │ MySQL  │ │ RocketMQ   │
                   │ 缓存/库存  │ │ 持久化  │ │ 异步解耦    │
                   │ 锁/限流    │ │        │ │ 削峰/延迟   │
                   └───────────┘ └────────┘ └────────────┘
                            │
                   ┌────────▼─────────┐
                   │ Prometheus 抓取   │
                   │ /actuator/prometheus │
                   └──────────────────┘
```

## 三、后端分层

| 包 | 职责 |
|---|---|
| `controller` | 参数接收、鉴权注解、调用 Service，共 15 个控制器 |
| `service` / `service.impl` | 业务编排，21 个实现类；`service.strategy` 承载有座 / 无座下单策略 |
| `mapper` | MyBatis-Plus Mapper，SQL 位于 `resources/mapper` |
| `entity` | 与数据表一一对应的实体 |
| `dto` | 请求与响应对象，统一响应为 `Result<T>` |
| `statemachine` | 订单状态机，集中管理状态迁移与副作用 |
| `event` / `listener` | 领域事件与监听器（如销量更新） |
| `mq` | 4 个消费者：缓存失效、通知、订单超时、秒杀落单；另有死信告警监控 |
| `task` | 定时任务：预订单超时、座位清理、Redis 补偿、秒杀队列消费 |
| `config` | 缓存、MQ、线程池、拦截器、限流、指标、跨域、Swagger 配置 |
| `aspect` | `ApiMetricsAspect`、`BusinessMetricsAspect`、`PermissionCheckAspect`、`PaginationAspect` |
| `monitor` | `OrderMetricsService`、`SeckillMonitorService` 登记业务指标；通用指标在 `config/PrometheusMetricsConfig` |
| `utils` | 登录态、Token、CSRF、限流、锁、Redis 键常量 |

## 四、请求处理链

请求进入后按固定顺序通过拦截器，最后进入控制器：

| 顺序 | 组件 | 作用 |
|---|---|---|
| 0 | `RefreshTokenInterceptor` | 解析 `authorization`，把用户或管理员放入 `UserHolder`，不做强制校验 |
| 1 | `AdminRoleInterceptor` | `/admin/**` 强制管理员身份，放行登录、验证码与登出 |
| 2 | `LoginInterceptor` | 对 `special-login-paths` 内的路径强制登录 |
| 3 | `LoginInterceptor` | 全局登录校验，按 `echo.mvc.fully-public` 与 `auth-optional` 放行 |
| 4 | `CsrfInterceptor` | 写操作校验 `X-CSRF-Token` |
| 5 | `PermissionCheckAspect` | 读取方法上的 `@RequireRole`，比较角色等级 |

路径白名单集中在 `application.yaml` 的 `echo.mvc` 节点，三类语义：

- `fully-public`：不解析 Token，直接放行，包含登录、验证码、静态资源、Swagger、Actuator 与 `/error`。
- `auth-optional`：解析 Token 但不强制登录，用于「登录后看到更多信息」的查询接口。
- `special-login-paths`：必须登录，用于个人数据（我的收藏、我的关注、上传）。

## 五、核心链路

### 5.1 缓存体系

两级缓存，读路径优先命中进程内缓存：

| 缓存 | 位置 | 规格 |
|---|---|---|
| `showLocalCache` | Caffeine | 上限 1000，写入后 5 分钟过期 |
| `genericLocalCache` | Caffeine | 上限 5000，写入后 10 分钟过期 |
| `seckillTicketLocalCache` | Caffeine | 上限 200，写入后 3 秒过期，用于秒杀入口削读 |
| `userCreditLocalCache` | Caffeine | 上限 10000，写入后 10 秒过期 |
| `ticketLocalCache` | Caffeine | 上限 5000，写入后 10 秒过期 |
| `cache:*` | Redis | 分布式共享，键定义见数据库文档第四节 |

写操作采用延迟双删：更新数据库后删除 Redis，再通过 RocketMQ 延时消息（`echo.cache.double-delete-delay-level=3`，即 10 秒）触发第二次删除，覆盖主从同步窗口。

缓存失效通过广播消息下发：`CacheInvalidateListener` 以 `BROADCASTING` 模式消费 `cache-invalidate-topic`，让每个实例清理自己的 Caffeine，避免各节点本地缓存长期不一致。

### 5.2 秒杀链路

```
客户端
  │ POST /ticket-order/seckill/{ticketId}
  ▼
本地令牌桶（容量 2000，1000 token/s）        ← 单机第一道闸
  ▼
用户级限流（rate:seckill:user，5 次/秒）     ← rate_limit.lua
  ▼
全局限流（rate:seckill:global，5000 次/秒）  ← rate_limit.lua
  ▼
票种与时间窗（seckillTicketLocalCache，TTL 3 秒）
  ▼
下单频控（rate:order:user）
  ▼
信誉分校验（userCreditLocalCache，TTL 10 秒）
  ▼
一人一单预检（seckill:order:{ticketId} Set）
  ▼
售罄快速判断（seckill:soldout:{ticketId}）
  ▼
入队（ZADD seckill:queue:{ticketId}，member=userId）→ 返回 {"status":"QUEUED"}
  ▼
SeckillQueueConsumer（fixedDelay 100ms）逐批出队
  ▼
幂等检查（seckill:idem）→ seckill.lua：判断库存 → 扣减 → 写已购集合
  ▼
创建预订单 → 发送 seckill-order-topic
  ▼
SeckillOrderConsumer 落库生成订单
  ▼
PreOrderTimeoutScheduler 核对未支付预订单并回滚库存
```

关键取舍：

- 库存判断与扣减在 `seckill.lua` 内完成，已购集合由同一脚本写入，Redis 单线程保证原子性，数据库不参与抢购判定。
- 入队再异步落库，把数据库写在链路外侧，入口只承受 Redis 的开销。
- 队列按用户 ZAdd 去重，配合 `echo.seckill.queue-max-size` 限制异常灌入。
- 售罄后写 `seckill:soldout:{ticketId}`，后续请求直接快速失败，不回查库存。

### 5.3 订单与状态机

状态定义见 `OrderStatus`，迁移集中在 `statemachine` 包，副作用（库存、座位、积分、通知）由状态机触发而不是散落在 Service 中。

- 普通下单：锁座位 → 生成预订单（`tb_pre_order`，30 分钟有效）→ 支付 → 订单转为已支付 → 座位转为已售。
- 预订单超时：`PreOrderTimeoutScheduler` 扫描过期预订单，回滚座位与库存，并通过 `order-timeout-topic` 发送延时消息做二次兜底。
- 退款：用户申请后订单进入申请退款中，管理端审批通过转为已退款并恢复库存。
- 补偿：`RedisCompensateTaskScheduler` 定期核对 Redis 与数据库的库存差异，修复不一致。

### 5.4 座位与并发控制

有座票的下单流程用分布式锁串行化同一座位的竞争：`lock:seat:{seatId}` 由 `SimpleRedisLock` 加锁，加锁与释放各由一段 Lua 脚本完成，客户端带实例标识，锁内维护可重入计数并由看门狗按 TTL 的三分之二续期。座位状态 `0 可售 / 1 已锁定 / 2 已售 / 3 维护中`，锁定的座位由 `SeatCleanupScheduler` 回收。定时发布演出由 Redisson 的 `RLock` 保证同一时刻只有一个实例执行。

### 5.5 可观测性

- `ApiMetricsAspect` 记录 `api_requests_total` 与 `api_request_duration_seconds`。
- `BusinessMetricsAspect` 记录登录、下单、核销、库存等业务指标。
- `OrderMetricsService` 覆盖订单、秒杀、缓存失效、通知、状态迁移、定时任务的成功 / 失败 / 耗时三类指标。
- 指标通过 `/actuator/prometheus` 暴露，抓取与告警配置见监控文档。

## 六、事务边界

订单创建、库存扣减、座位状态变更在同一个 MySQL 事务内完成，因此业务库保持单库单事务，不引入分布式事务框架。跨库一致性由「本地事务 + 消息补偿」承担：

- 需要异步执行的动作（通知、缓存失效、落单）以 MQ 消息发出，消费端保证幂等。
- 失败的消息进入死信队列，由 `DlqAlertMonitor` 告警。

`application-seata.yml.disabled` 保留了切换到 Seata 的配置位置，取舍理由与升级条件见 `docs/adr/ADR-001-本地事务与分布式事务取舍.md`。

## 七、目录结构

```
echo/
├── echo/                      后端模块
│   ├── src/main/java/com/echo
│   ├── src/main/resources     application*.yaml、lua 脚本、mapper XML、监控配置
│   ├── src/test/java/com/echo 单元 / 集成 / e2e / 性能测试，79 个测试类
│   └── Dockerfile
├── admin/                     管理端（Vue 3，端口 3001，Hash 路由）
├── user/                      用户端（Vue 3，端口 3002，History 路由）
├── shared/                    两端共享的前端工具
├── docs/                      项目文档
├── deploy/                    容器部署资源（Dockerfile、Nginx、MySQL 初始化、Broker 配置）
├── jmeter/                    压测计划与压测脚本
├── rocketmq/                  本地 Broker 配置与主题初始化脚本
├── 启动/                       Windows 一键启动 / 停止脚本
└── docker-compose.yml         容器编排
```

## 八、设计原则

- 热点数据走缓存，读多写少的查询优先命中 L1，减少 Redis 网络往返。
- 强一致场景把判断下沉到 Redis Lua 或数据库唯一索引，业务代码只做前置提示。
- 长耗时动作（通知、落单、缓存失效）走 MQ，接口只返回受理结果。
- 权限同时在拦截器与切面表达：拦截器管「是否登录」，切面管「角色是否够」。
- 所有可观测数字来自 Micrometer，日志只记录决策过程，避免日志承担统计职责。
