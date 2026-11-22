# Echo 票务平台 开发指南

## 一、环境要求

| 组件 | 版本 | 用途 |
|---|---|---|
| JDK | 17 | 后端编译与运行 |
| Maven | 3.9 | 后端构建 |
| Node.js | 18 以上 | 两个前端 |
| MySQL | 8.0 | 主数据库 |
| Redis | 7.x | 缓存、库存、锁、限流 |
| RocketMQ | 5.3.4 | 消息队列 |

验证安装：

```bash
java -version        # openjdk version "17.x"
mvn -v
node -v
npm -v
```

## 二、配置文件

后端配置位于 `echo/src/main/resources`，按 profile 分层：

| 文件 | 用途 | 与原配置的差异 |
|---|---|---|
| `application.yaml` | 公共配置 | 端口 8081、HikariCP 20 连接、Redis 连接池、线程池、异常分类、路径白名单、CSRF 排除、秒杀与缓存参数 |
| `application-dev.yaml` | 开发 | 连接池缩小、Jackson 输出 null 与缩进，便于排查 |
| `application-docker.yaml` | 容器 | 图片目录指向 `/data/imgs`，开启 CSRF，devtools 开关设为关闭 |
| `application-prod.yaml` | 生产 | 隐藏健康详情、限制监控端点、MQ 重试策略 |

激活方式：

```bash
java -jar target/echo-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

数据库、Redis、RocketMQ 的地址在 `application.yaml` 中已写成环境变量占位符，改动环境时注入变量即可：

```bash
export MYSQL_HOST=127.0.0.1
export MYSQL_PORT=3306
export MYSQL_USER=root
export MYSQL_PASSWORD=123456
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export ROCKETMQ_NAMESRV=127.0.0.1:9876
```

## 三、常用配置项

| 节点 | 键 | 默认 | 说明 |
|---|---|---|---|
| `echo.mvc` | `fully-public` | 见配置 | 不解析 Token 的路径 |
| `echo.mvc` | `auth-optional` | 见配置 | 解析 Token 但不强制登录 |
| `echo.mvc` | `special-login-paths` | 见配置 | 必须登录的路径 |
| `echo.csrf` | `exclude-paths` | 登录类接口 | 免 CSRF 校验路径 |
| `echo.upload` | `image-root` | 本地 Nginx 目录 | 上传文件落盘位置 |
| `echo.seckill` | `queue-max-size` | 100000 | 秒杀排队上限 |
| `echo.cache` | `double-delete-delay-level` | 3 | 延迟双删的 MQ 延时级别，3 对应 10 秒 |
| `threadpool.*` | `core-size` / `max-size` | 见配置 | 关注、缓存删除、通用异步三组线程池 |
| `exception.*-keywords` | 关键字列表 | 见配置 | 运行时异常按关键字映射 HTTP 状态码 |

## 四、启动流程

### 4.1 依赖服务

```bash
# MySQL 与 Redis 已在本地运行时跳过此步
# RocketMQ
mqnamesrv
mqbroker -c D:\echo\rocketmq\broker.conf
D:\echo\rocketmq\init-topics.cmd
```

主题缺失时消费端会持续报 `the topic xxx not exist`，执行 `init-topics.cmd` 即可补齐。

### 4.2 后端

```bash
cd echo
mvn clean package -DskipTests
java --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
     --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     --add-opens java.base/java.util=ALL-UNNAMED \
     -jar target/echo-0.0.1-SNAPSHOT.jar --server.port=8081
```

开发期可用 `mvn spring-boot:run` 启动；项目未引入 devtools，修改代码后重启进程生效。

### 4.3 前端

```bash
cd admin && npm install && npm run dev   # http://127.0.0.1:3001
cd user  && npm install && npm run dev   # http://127.0.0.1:3002
```

Vite 代理把 `/api` 转发到 `http://localhost:8081` 并剥离前缀；`/ssn` 代理到同一后端，用于读取上传的图片。

## 五、构建与测试

```bash
mvn -o compile                 # 离线增量编译
mvn -o package -DskipTests     # 打包
mvn test                       # 全量测试
mvn test -Dtest=ShowServiceTest # 单个测试类
```

测试分层：

| 目录 | 数量 | 内容 |
|---|---|---|
| `src/test/java/com/echo/unit` | 76 | 按业务域组织：controller、entity、enums、service（含 notification、points、review、scheduled、seat、show、statistics 等子域） |
| `src/test/java/com/echo/integration` | 1 | 组件集成 |
| `src/test/java/com/echo/e2e` | 1 | 完整下单流程 |
| `src/test/java/com/echo/performance` | 1 | 批量操作基准 |

前端构建与类型检查：

```bash
cd admin && npx vue-tsc --noEmit && npx vite build
cd user  && npx vue-tsc --noEmit && npx vite build
```

## 六、调试

**远程调试**：追加 JVM 参数后，在 IDEA 中新建 Remote JVM Debug 连接 `localhost:5005`。

```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar target/echo-0.0.1-SNAPSHOT.jar --server.port=8081
```

**日志级别**：`application.yaml` 的 `logging.level` 控制输出，Mapper 日志默认关闭（`com.echo.mapper: error`），排查 SQL 时临时改为 `debug`。

**接口联调**：`http://127.0.0.1:8081/swagger-ui.html` 提供全部接口的在线调试；写操作需要先在页面上登录拿到 Token 与 CSRF Token。

## 七、扩展功能

新增一个业务能力的落地顺序：

1. 在 `entity` 增加实体，`mapper` 增加 Mapper 接口与 XML。
2. 在 `service` 定义接口、`service.impl` 实现；复杂分支拆到 `service.strategy`。
3. 在 `controller` 暴露接口，标注 `@Operation`、`@RequireRole`（需要角色控制时）。
4. 需要异步的动作发 MQ 消息，消费端放在 `mq`，消息体放在 `message`。
5. 需要定时执行的任务放在 `task`，用 `@Scheduled` 声明周期。
6. 关键业务路径增加 Micrometer 指标，登记到 `OrderMetricsService` 或 `PrometheusMetricsConfig`。
7. 查询接口若对外公开，把路径加入 `echo.mvc` 对应白名单。
8. 同步更新 `docs/API-接口文档.md` 与本文档的配置章节。

命名与注释规范见 `docs/CODING-编码规范.md`。

## 八、常见问题

**Maven 在 Git Bash 中执行报 `plexus-classworlds` 加载失败**
在 PowerShell 中调用 `mvn.cmd`：

```powershell
& "D:\study\apache-maven-3.9.9\bin\mvn.cmd" -B -o package -DskipTests
```

**后端启动后监听随机端口**
执行环境存在 `SERVER__PORT=0` 时，Spring 会用 0 覆盖 `server.port` 并分配随机端口。启动命令显式声明 `--server.port=8081` 即可固定。

**日志持续出现 `Failed to persist ... AccessDeniedException`**
RocketMQ 的 `MixAll.fsyncDirectory` 在 Windows 上无法以文件方式打开目录，属平台行为。`consumerOffset.json`、`topics.json`、`delayOffset.json` 均正常写入磁盘，消息收发不受影响，日志按 100MB 滚动。

**消费端报 `the topic xxx not exist` 或 `group info not exist`**
主题与消费组未创建，执行 `D:\echo\rocketmq\init-topics.cmd`。

**上传图片返回 404**
确认 `echo.upload.image-root` 指向真实存在的目录，且该目录就是 Nginx 提供 `/ssn/imgs/` 的根目录；容器环境检查 `./deploy/imgs` 卷是否挂载成功。

**写接口返回 403 且提示 CSRF 校验失败**
先发起任意已登录的 GET 请求，从响应头 `X-CSRF-Token` 取 Token，再在写请求头中带上同名头。

**端口被占用**
`netstat -ano | findstr :8081` 找到 PID，结束进程后重启。

**前端类型检查报错但页面可用**
`vue-tsc` 比运行时更严格，先修类型再提交；CI 与镜像构建以 `vite build` 为准。
