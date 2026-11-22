# Echo 回响 · 在线票务平台

演出售票系统的完整实现，覆盖演出与票种管理、选座、秒杀抢票、订单流转、票务核销、用户社区与运营后台。

## 技术栈

| 层次 | 选型 |
|---|---|
| 后端 | Spring Boot 2.7.18、Java 17、MyBatis-Plus 3.5.3 |
| 存储 | MySQL 8.0、Redis（Caffeine 本地缓存 + Redis 分布式缓存） |
| 中间件 | RocketMQ 5.x、Redisson |
| 可观测 | Micrometer、Prometheus、Actuator |
| 前端 | Vue 3、TypeScript、Vite、Vue Router 4 |
| 部署 | Docker Compose、多阶段镜像构建、Nginx |

## 服务与端口

| 服务 | 本地地址 | 容器地址 |
|---|---|---|
| 后端 API | `http://127.0.0.1:8081` | `http://127.0.0.1:8089` |
| 管理端 | `http://127.0.0.1:3001` | `http://127.0.0.1:3001` |
| 用户端 | `http://127.0.0.1:3002` | `http://127.0.0.1:3002` |
| MySQL | `127.0.0.1:3306` | 容器内 `mysql:3306` |
| Redis | `127.0.0.1:6379` | 容器内 `redis:6379` |
| RocketMQ NameServer | `127.0.0.1:9876` | 容器内 `rocketmq-namesrv:9876` |
| RocketMQ Broker | `127.0.0.1:10911` | 容器内 `172.28.0.5:10911` |

## 快速开始

### 方式一：Windows 一键启动

双击 `启动\一键启动.bat`，脚本按顺序拉起 Redis、RocketMQ NameServer 与 Broker、主题与消费组初始化、后端、两个前端。停止时双击 `启动\停止所有服务.bat`。详细说明见 `启动\README.md`。

### 方式二：容器部署

```bash
docker compose up -d --build
docker compose ps
```

首次启动时 MySQL 会自动执行 `deploy/mysql-init/01-schema.sql` 建立业务表，管理员账号随后用 `echo/src/main/resources/init-super-admin.sql` 初始化。明细见 `docs/DEPLOY-部署文档.md`。

### 方式三：分别启动

```bash
# 后端
cd echo
mvn clean package -DskipTests
java -jar target/echo-0.0.1-SNAPSHOT.jar --server.port=8081

# 管理端
cd admin && npm install && npm run dev

# 用户端
cd user && npm install && npm run dev
```

管理员账号通过 `echo/src/main/resources/init-super-admin.sql` 初始化，手机号与密码在脚本里自行填写。

## 健康检查

```bash
curl http://127.0.0.1:8081/actuator/health
```

接口文档：`http://127.0.0.1:8081/swagger-ui.html`，原始规范 `http://127.0.0.1:8081/v3/api-docs`。

## 文档索引

| 文档 | 内容 |
|---|---|
| `docs/ARCHITECTURE-系统架构.md` | 分层结构、请求处理链、缓存与秒杀链路、事务边界 |
| `docs/API-接口文档.md` | 通用规范、完整接口索引、权限矩阵 |
| `docs/DATABASE-数据库设计文档.md` | 19 张表的字段与索引、Redis 键设计、枚举字典 |
| `docs/DEPLOY-部署文档.md` | 本地与容器部署、环境变量、运维命令 |
| `docs/DEV-开发指南.md` | 环境搭建、配置项、调试与排错 |
| `docs/FRONTEND-路由与页面文档.md` | 两端路由表、守卫、页面与接口映射 |
| `docs/MONITOR-监控告警方案.md` | 指标清单、Prometheus 抓取、告警规则、看板 |
| `docs/PERFORMANCE-性能压测报告.md` | 读路径与秒杀链路的压测数据与瓶颈定位 |
| `docs/CODING-编码规范.md` | 命名规则与注释规范 |
| `docs/adr/` | 架构决策记录 |
| `jmeter/README.md` | 压测计划与压测脚本用法 |

## 目录结构

```
echo/
├── echo/           Spring Boot 后端
├── admin/          管理端前端
├── user/           用户端前端
├── shared/         前端共享工具
├── docs/           项目文档
├── deploy/         部署资源
├── jmeter/         压测计划与脚本
├── monitor/        Prometheus 抓取配置与 Grafana 面板
├── rocketmq/       本地 Broker 配置与主题初始化
├── 启动/           Windows 启停脚本
└── docker-compose.yml
```

## 仓库内容说明

仓库只保留源码、脚本、文档与部署配置。以下运行期数据不入库，需在本地自行生成：

| 未入库内容 | 说明 |
|---|---|
| `nginx-1.18.0/` | 本机 nginx 发行包，以及运行期上传的图片（头像、演出/博客配图） |
| `deploy/imgs/`、`deploy/logs/` | 容器挂载的上传目录与构建日志 |
| `deploy/mysql-init/01-echo-init.sql` | 含业务数据的初始化 dump；建表结构见同目录 `01-schema.sql` |
| `jmeter/users*.csv`、`tokens*.csv`、`bench/` | 压测账号、登录令牌与压测结果文件 |
| `*.rdb`、`*.log`、`probe-stack.txt` | Redis 快照、运行日志、线程栈 |

压测账号用 `jmeter/gen-tokens.js` 重新生成，建表与初始化按 `docs/DEPLOY-部署文档.md` 操作。

## 许可

本项目开源用于学习与作品展示，可自由阅读、克隆与参考；商用前请先联系作者。
