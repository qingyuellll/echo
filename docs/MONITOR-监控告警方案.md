# Echo 票务平台 监控告警方案

## 一、监控构成

| 组件 | 作用 | 本仓库对应文件 |
|---|---|---|
| Spring Boot Actuator | 暴露健康检查与 Prometheus 指标 | `application.yaml` 的 `management` 节点 |
| Micrometer | 采集 JVM、HTTP、业务指标 | `config/PrometheusMetricsConfig.java`、`monitor/OrderMetricsService.java` |
| Prometheus | 拉取指标、计算告警 | `src/main/resources/prometheus/rules.yml` |
| Grafana | 可视化看板 | `src/main/resources/grafana/dashboard.json` |
| 日志 | 记录决策过程与异常堆栈 | Spring Boot 默认输出到控制台与文件 |

## 二、指标端点

| 端点 | 用途 | 访问控制 |
|---|---|---|
| `/actuator/health` | 存活与依赖健康 | 公开 |
| `/actuator/info` | 应用信息 | 公开 |
| `/actuator/prometheus` | 指标文本 | 公开 |

三个端点默认与业务共用 `8081` 端口。需要独立管理端口时设置 `MANAGEMENT_PORT`，生产 profile 的配置为：

```yaml
management:
  server:
    port: ${MANAGEMENT_PORT:${server.port:8081}}
```

设置独立端口后，Prometheus 抓取地址、容器健康检查、负载均衡探活三处都要同步改为新端口。

验证：

```bash
curl -s http://127.0.0.1:8081/actuator/health
curl -s http://127.0.0.1:8081/actuator/prometheus | head -30
```

## 三、指标清单

### 3.1 通用指标

| 指标 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `api_requests_total` | Counter | `method`、`endpoint`、`status` | 接口请求数与状态码分布 |
| `api_request_duration_seconds` | Timer | `method`、`endpoint` | 接口耗时直方图 |
| `login_attempts_total` | Counter | `result`、`role` | 登录成功与失败次数 |
| `orders_total` | Counter | `type`、`status` | 订单创建次数，`type` 取 `normal` / `seckill` |
| `verification_operations_total` | Counter | `result` | 核销成功与失败次数 |
| `seckill_stock_remaining` | Gauge | `ticket_id` | 秒杀票剩余库存 |
| `order_points_discount_rejected_total` | Counter | - | 积分抵扣被拒次数 |

Spring Boot 默认还会输出 `http_server_requests_seconds`、`jvm_memory_used_bytes`、`jvm_gc_pause_seconds`、`system_cpu_usage`、`hikaricp_connections_*` 等标准指标。

### 3.2 业务链路指标

| 指标 | 含义 |
|---|---|
| `order_create_duration` | 下单耗时 |
| `order_payment_success_total` / `order_payment_fail_total` / `payment_process_duration` | 支付结果与耗时 |
| `order_seckill_success_total` / `order_seckill_fail_total` | 秒杀下单结果 |
| `seckill_order_success_total` / `seckill_order_fail_total` / `seckill_order_duration` | 秒杀落单结果与耗时 |
| `seckill_process_duration` | 秒杀入口处理耗时 |
| `order_stock_reduce_failed_total` | 库存扣减失败次数 |
| `state_transition_success_total` / `state_transition_fail_total` / `state_transition_duration` | 状态机迁移 |
| `cache_invalidation_duration` / `cache_invalidation_fail_total` | 缓存失效广播 |
| `notification_success_total` / `notification_fail_total` / `notification_duration` | 站内通知投递 |
| `order_timeout_success_total` / `order_timeout_fail_total` / `order_timeout_duration` | 预订单超时关闭 |
| `pre_order_compensation_scanned_total` / `_success_total` / `_fail_total` / `_duration` | 预订单补偿任务 |
| `redis_compensation_success_total` / `_fail_total` / `_skipped_total` / `_duration` | Redis 数据补偿任务 |
| `seat_cleanup_success_total` / `_fail_total` / `_skipped_total` / `_duration` | 座位清理任务 |
| `seckill_reconcile_duration` / `seckill_reconcile_orphans_found_total` / `seckill_reconcile_rolled_back_total` | 秒杀库存核对 |

## 四、Prometheus 抓取

仓库提供告警规则，抓取配置由部署环境维护。单机部署的最小配置：

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - /etc/prometheus/rules.yml

scrape_configs:
  - job_name: echo-backend
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['host.docker.internal:8081']
```

把 `src/main/resources/prometheus/rules.yml` 挂载到 `rule_files` 指向的路径。容器网络内抓取时，targets 写服务名与容器端口，例如 `echo-backend:8081`。

## 五、告警规则

`rules.yml` 定义四组共 10 条规则：

| 分组 | 告警 | 条件 | 级别 |
|---|---|---|---|
| `seckill_alerts` | `StockAnomaly` | `seckill_stock_remaining < 0` 持续 1 分钟 | critical |
| `seckill_alerts` | `StockExhausted` | 库存为 0 持续 5 分钟 | warning |
| `seckill_alerts` | `SeckillOrderSurge` | 秒杀订单速率超过 100 单/分钟 | warning |
| `api_alerts` | `HighErrorRate` | 5xx 占比超过 10% 持续 2 分钟 | critical |
| `api_alerts` | `HighLatency` | P95 超过 2 秒持续 3 分钟 | warning |
| `api_alerts` | `SuddenTrafficDrop` | 当前流量低于 5 分钟前的二分之一 | warning |
| `security_alerts` | `LoginFailureSurge` | 登录失败速率超过成功速率 5 倍 | warning |
| `security_alerts` | `VerificationAnomaly` | 核销失败超过 10 次/分钟 | warning |
| `business_health` | `NoOrders` | 1 小时内无新订单 | info |
| `business_health` | `OrderCancelHigh` | 超时关闭订单占比超过 50% | warning |

`StockAnomaly` 为最高优先级：库存出现负数意味着 Lua 扣减与回滚出现不一致，需要立即检查 `seckill:stock:{ticketId}` 与 `tb_seckill_ticket` 的差异。

## 六、Grafana 看板

`dashboard.json` 导入 Grafana 后包含八个面板：

| 面板 | 使用的指标 |
|---|---|
| 实时订单量 | `rate(orders_total[1m])` |
| 秒杀库存状态 | `seckill_stock_remaining` |
| API 请求速率 | `rate(api_requests_total[1m])` |
| 登录成功率 | `login_attempts_total` |
| 核销操作统计 | `verification_operations_total` |
| 错误率趋势 | `api_requests_total{status=~"5.."}` |
| 热门端点 TOP10 | `topk(10, sum by (endpoint) (rate(api_requests_total[5m])))` |
| JVM 与连接池 | `jvm_*`、`hikaricp_connections_*` |

导入方式：Grafana 控制台 `Dashboards` → `Import` → 上传 `dashboard.json`，数据源选择指向后端的 Prometheus 实例。

## 七、日志

日志使用 Spring Boot 默认实现，级别由 `application.yaml` 控制：

| 配置 | 值 | 说明 |
|---|---|---|
| `logging.level.root` | `info` | 全局级别 |
| `logging.level.com.echo.mapper` | `error` | Mapper 层只在出错时输出 |

主要日志前缀：

| 前缀 | 来源 |
|---|---|
| `[权限校验]` | `PermissionCheckAspect` |
| `[CSRF]` | `CsrfInterceptor` |
| `[秒杀入口]`、`[秒杀限流]` | 秒杀链路 |
| `[缓存失效]` | `CacheInvalidateListener` |
| `[业务异常]`、`[运行时异常]` | `WebExceptionAdvice` |
| `[指标服务]` | `OrderMetricsService` |

日志中的业务判断（限流命中、权限拒绝、缓存失效）用于回溯单个请求的决策路径，统计口径以 Prometheus 指标为准。

## 八、故障排查顺序

1. **健康检查**：`/actuator/health` 返回 `UP` 说明应用与数据源正常，`DOWN` 时看返回的组件明细。
2. **依赖连通**：`redis-cli ping`、`mysql -e "select 1"`、`mqadmin clusterList -n 127.0.0.1:9876`。
3. **指标定位**：先看 `api_requests_total{status=~"5.."}` 的端点分布，再看 `api_request_duration_seconds` 的分位值，最后看业务指标（`order_*`、`seckill_*`）。
4. **日志确认**：按端点和时间窗口过滤，查看异常堆栈与业务判断日志。
5. **数据核对**：Redis 侧 `seckill:stock:{id}` 与 `scard seckill:order:{id}`，数据库侧 `tb_ticket_order` 增量，二者应满足「库存减少量 = 已购人数 = 新增订单数」。

常见现象与对应指标：

| 现象 | 先看 |
|---|---|
| 下单变慢 | `order_create_duration`、`hikaricp_connections_pending` |
| 秒杀大面积被拒 | `orders_total{type="seckill",status="failed"}`、令牌桶拒绝日志 |
| 库存不扣减 | `order_stock_reduce_failed_total`、`seckill_reconcile_orphans_found_total` |
| 通知未送达 | `notification_fail_total` |
| 缓存不一致 | `cache_invalidation_fail_total` |
| 预订单未关闭 | `order_timeout_fail_total`、`pre_order_compensation_fail_total` |

## 九、常用命令

```bash
# 健康与指标
curl -s http://127.0.0.1:8081/actuator/health
curl -s http://127.0.0.1:8081/actuator/prometheus | grep -E "^orders_total|^seckill_stock_remaining"

# 规则语法检查
promtool check rules src/main/resources/prometheus/rules.yml

# 重载 Prometheus 配置
curl -X POST http://127.0.0.1:9090/-/reload
```

## 十、维护约定

- 新增业务指标时在 `OrderMetricsService` 或 `PrometheusMetricsConfig` 登记，命名遵循 `<域>_<对象>_<结果>`。
- 告警规则里的指标名必须与代码中的注册名完全一致，修改指标名时同步修改 `rules.yml`。
- 告警阈值以实测基线为参照，读路径与秒杀链路的基线数据见性能压测报告。
