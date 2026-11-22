# Echo 票务平台 部署文档

## 一、部署形态

| 形态 | 适用场景 | 入口 |
|---|---|---|
| 容器编排 | 一台机器拉起全套服务，含 MySQL、Redis、RocketMQ | `docker compose up -d --build` |
| Windows 一键启动 | 本地已有 MySQL、Redis、RocketMQ，快速起应用 | `启动\一键启动.bat` |
| 手动启动 | 调试单个模块 | `java -jar` 与 `npm run dev` |

## 二、端口分配

| 服务 | 本地 | 容器映射 | 说明 |
|---|---|---|---|
| 后端 API | 8081 | `${BACKEND_PORT:-8089}` → 8081 | 容器内监听 8081 |
| 管理端 | 3001 | `${ADMIN_PORT:-3001}` → 80 | Nginx 托管静态资源 |
| 用户端 | 3002 | `${USER_PORT:-3002}` → 80 | Nginx 托管静态资源 |
| MySQL | 3306 | 仅容器网络 | 不对外暴露 |
| Redis | 6379 | 仅容器网络 | 不对外暴露 |
| RocketMQ NameServer | 9876 | 仅容器网络 | 不对外暴露 |
| RocketMQ Broker | 10911 | 仅容器网络 | 固定容器 IP `172.28.0.5` |

宿主机 8081 留给本地开发的后端实例，容器化后端使用 8089，两套可以同时运行。

## 三、容器化部署

### 3.1 服务组成

| 服务 | 镜像来源 | 依赖 |
|---|---|---|
| `mysql` | `mysql:8.0` | 无 |
| `redis` | `redis:7-alpine` | 无 |
| `rocketmq-namesrv` | `deploy/rocketmq/Dockerfile` 构建（`echo/rocketmq:5.3.1`） | 无 |
| `rocketmq-broker` | 同上，复用 `echo/rocketmq:5.3.1` | namesrv 健康后 |
| `backend` | `echo/Dockerfile` 构建 | mysql、redis 健康后 |
| `admin-web` | `deploy/admin.Dockerfile` 构建 | backend |
| `user-web` | `deploy/user.Dockerfile` 构建 | backend |

网络 `echo-net` 为 bridge，子网 `172.28.0.0/16`。卷 `mysql-data`、`redis-data`、`rocketmq-store` 持久化数据，`./deploy/imgs` 挂载到容器 `/data/imgs` 存放上传图片。

### 3.2 关键实现说明

**Broker 地址**：`deploy/rocketmq/broker.conf` 把 `brokerIP1` 固定为 `172.28.0.5`。Broker 会把该地址告知客户端用于回连，使用容器静态 IP 保证后端容器能连回 Broker。

**Broker 自动创建**：配置开启 `autoCreateTopicEnable` 与 `autoCreateSubscriptionGroup`，首次启动即可收发未知主题。

**RocketMQ 镜像自建**：`deploy/rocketmq/Dockerfile` 基于 `eclipse-temurin:11-jre`，从国内 Apache 镜像站下载 5.3.1 发行包自行安装，产物为 `echo/rocketmq:5.3.1`。自建解决三个问题：官方镜像的大层在国内加速器上缓存缺失、下载容易卡住；官方启动脚本通过 `readlink $(which javac)` 推导 `JAVA_HOME`，JRE 镜像里推导失败会清空该变量；官方脚本默认堆为 4g/8g，容器内存不足时会被 OOM Killer 终止。镜像内把堆压到 256m/512m，`mapedFileSizeCommitLog` 调为 256M。

**后端镜像**：`echo/Dockerfile` 两阶段构建。第一阶段用 `maven:3.9-eclipse-temurin-17` 编译，依赖 `echo/docker/maven-settings.xml` 的国内镜像；第二阶段只保留 `eclipse-temurin:17-jre` 与 jar，内置 `HEALTHCHECK` 探测 `127.0.0.1:8081`。

**前端镜像**：两个 Dockerfile 的构建上下文都是项目根目录。`user` 的 Vite 配置把 `shared` 别名指向 `../shared`，`admin` 的 `Login.vue` 通过相对路径引用 `../../../shared/utils/security.js`，两者都要求容器内保持 `/build/{admin,user,shared}` 的同级布局。镜像内执行 `npx vite build`，静态产物交给 `nginx:1.27-alpine`，站点配置来自 `deploy/nginx/admin.conf` 与 `deploy/nginx/user.conf`。

**Nginx 转发**：`location /api/` 转发到 `http://echo-backend:8081/`，因 `proxy_pass` 带结尾斜杠，`/api/admin/login` 会被改写为 `/admin/login`；`location /ssn/` 直连后端读取图片；`location /` 使用 `try_files ... /index.html` 支持 History 路由回退。

### 3.3 环境变量

`docker-compose.yml` 通过环境变量注入配置，全部带默认值，可另建 `.env` 覆盖：

| 变量 | 默认 | 作用 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | `123456` | MySQL root 密码，同时传给后端 |
| `BACKEND_PORT` | `8089` | 后端对外端口 |
| `ADMIN_PORT` | `3001` | 管理端对外端口 |
| `USER_PORT` | `3002` | 用户端对外端口 |
| `JAVA_OPTS` | `-Xms512m -Xmx1024m -XX:+UseG1GC` | 后端 JVM 参数 |

后端自身识别的环境变量：

| 变量 | 默认 | 作用 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | compose 内为 `docker` | 激活的配置文件 |
| `MYSQL_HOST` / `MYSQL_PORT` | `127.0.0.1` / `3306` | 数据库地址 |
| `MYSQL_USER` / `MYSQL_PASSWORD` | `root` / `123456` | 数据库账号 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `127.0.0.1` / `6379` / 空 | 缓存地址 |
| `ROCKETMQ_NAMESRV` | `127.0.0.1:9876` | NameServer 地址 |
| `ECHO_IMAGE_ROOT` | `D:/echo/nginx-1.18.0/nginx-1.18.0/html/ssn/imgs` | 图片根目录，容器内为 `/data/imgs` |
| `SWAGGER_ENABLED` | `true` | 接口文档开关 |
| `MANAGEMENT_PORT` | 跟随 `server.port` | Actuator 独立端口 |

CSRF 开关按 profile 区分：`docker` 与 `prod` 为开启，`dev` 为关闭，默认 profile 走 `csrf.enabled` 的默认值 true。开启时两个前端自动携带 `X-CSRF-Token`，登录与验证码接口在免校验清单内。

### 3.4 部署步骤

```bash
# 1. 构建并启动
docker compose up -d --build

# 2. 查看状态，等待 mysql、redis、backend 变为 healthy
docker compose ps

# 3. 跟随后端日志
docker compose logs -f backend
```

### 3.5 验证

```bash
# 后端健康
curl http://127.0.0.1:8089/actuator/health

# 管理端与用户端页面
curl -o /dev/null -w "%{http_code}\n" http://127.0.0.1:3001
curl -o /dev/null -w "%{http_code}\n" http://127.0.0.1:3002

# 经前端代理登录（手机号与密码替换为 init-super-admin.sql 中填写的值）
curl -s -X POST http://127.0.0.1:3001/api/admin/login \
  -H "Content-Type: application/json" \
  -d '{"phone":"<管理员手机号>","password":"<管理员密码>"}'
```

登录返回 `success:true` 说明网关、代理、后端、数据库链路全部连通。

### 3.6 数据库初始化

`deploy/mysql-init/01-schema.sql` 在 MySQL 数据卷为空时自动执行，建立 19 张业务表（仅结构，不含业务数据）。

管理员账号在服务启动后单独初始化：

```bash
docker exec -i echo-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" echo < echo/src/main/resources/init-super-admin.sql
```

该脚本是模板：先填 `@admin_phone` 与 `@admin_hash`（密码的 BCrypt 哈希，生成方式写在文件头注释里），再执行。脚本按 `id = 1` 判断是否已存在，可重复执行；首次登录后立即在个人中心修改密码。

演示业务数据（演出、票种、评测、订单）按需准备：

```bash
# 从已有环境导出数据并导入新库
mysqldump -uroot -p --default-character-set=utf8mb4 echo > echo_demo.sql
docker exec -i echo-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" echo < echo_demo.sql
```

也可以在管理端录入演出与票种，再用用户端下单生成订单。

数据卷已存在时初始化脚本不再执行。需要重建数据库时：

```bash
docker compose down -v   # 同时删除 mysql-data、redis-data、rocketmq-store
docker compose up -d
```

## 四、本地部署

### 4.1 依赖

| 组件 | 版本 | 默认端口 |
|---|---|---|
| JDK | 17 | - |
| Maven | 3.9 | - |
| MySQL | 8.0 | 3306 |
| Redis | 7.x | 6379 |
| RocketMQ | 5.3.4 | 9876 / 10911 |
| Node.js | 18 以上 | - |

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

`--add-opens` 放开 JDK 17 的模块反射限制，供 Redisson 与序列化框架访问内部字段。容器镜像通过 `JAVA_OPTS` 调整 JVM 参数，`ENTRYPOINT` 直接启动 jar。

### 4.3 RocketMQ

本地 Broker 使用项目内的配置启动，保证存储目录与自动创建策略固定：

```bash
# NameServer
mqnamesrv

# Broker（配置见 rocketmq/broker.conf）
mqbroker -c D:\echo\rocketmq\broker.conf

# 主题与消费组（Broker 启动后执行，幂等）
D:\echo\rocketmq\init-topics.cmd
```

主题与消费组：

| 主题 | 消费组 | 模式 | 用途 |
|---|---|---|---|
| `cache-invalidate-topic` | `cache-invalidate-consumer-group` | BROADCASTING | 广播通知各实例清理本地缓存 |
| `notification-topic` | `notification-consumer-group` | CLUSTERING | 站内通知 |
| `order-timeout-topic` | `order-timeout-consumer-group` | CLUSTERING | 预订单超时关闭 |
| `seckill-order-topic` | `seckill-order-consumer-group` | CLUSTERING | 秒杀订单落库 |

### 4.4 前端

```bash
cd admin && npm install && npm run dev   # 3001
cd user  && npm install && npm run dev   # 3002
```

两端通过 Vite 代理把 `/api` 转发到 `http://localhost:8081`。管理端的地图定位使用 `admin/.env` 中的 `VITE_AMAP_KEY`，用于地址转经纬度。

### 4.5 图片目录

后端把上传文件写入 `echo.upload.image-root`，默认指向本地 Nginx 的静态目录：

```
D:/echo/nginx-1.18.0/nginx-1.18.0/html/ssn/imgs
```

改目录时设置环境变量 `ECHO_IMAGE_ROOT`，容器内对应 `/data/imgs` 卷。

## 五、生产环境注意事项

| 项 | 设置 |
|---|---|
| 接口文档 | `SWAGGER_ENABLED=false`，关闭 `/swagger-ui.html` 与 `/v3/api-docs` |
| Actuator | 生产 profile 暴露 `health,info,prometheus`，健康详情为 `when-authorized` |
| 监控端口 | 默认与业务端口一致；独立端口时设置 `MANAGEMENT_PORT`，并同步修改抓取与探活地址 |
| JVM | `-Xms2048m -Xmx4096m -XX:+UseG1GC -XX:MaxGCPauseMillis=200`，配合 `-XX:+HeapDumpOnOutOfMemoryError` |
| 数据库密码 | 覆盖 `MYSQL_PASSWORD`，`.env` 文件权限收紧 |
| 上传大小 | Nginx `client_max_body_size 20m`，需要更大文件时同步调整 |
| 密钥 | `admin/.env` 中的地图 Key 换成自申请的 Key |

## 六、运维命令

```bash
docker compose ps                       # 服务状态
docker compose logs -f backend          # 后端日志
docker compose restart backend          # 重启后端
docker compose up -d --build backend    # 重新构建并替换后端
docker compose down                     # 停止，保留数据卷
```

数据库备份：

```bash
docker exec echo-mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
  --default-character-set=utf8mb4 --single-transaction echo > echo_$(date +%F).sql
```

## 七、容器部署常见问题

**镜像拉取卡在拉层阶段**
基础镜像走公共加速器时，冷门镜像常常没有缓存。`deploy/rocketmq/Dockerfile` 已改为从国内 Apache 镜像站取发行包；其余基础镜像（`mysql:8.0`、`redis:7-alpine`、`eclipse-temurin:17-jre`、`node:22-alpine`、`nginx:1.27-alpine`、`maven:3.9-eclipse-temurin-17`）都是热门镜像，加速器命中率高。加速器失效时更换 Docker Desktop 的 registry mirror 后重试。

**Broker 容器反复重启**
官方脚本默认堆为 8g，容器内存不足时会触发 OOM Killer。自建镜像已把堆压到 512m，并把 `mapedFileSizeCommitLog` 降到 256M。宿主机内存低于 4GB 时，同时运行 MySQL 与 Broker 容易出现资源竞争，建议给 Docker 至少分配 4GB。

**前端构建报 `Could not resolve "../../../shared/..."`**
`admin/src/views/Login.vue` 通过相对路径引用 `shared/utils/security.js`，因此两个前端镜像的构建上下文都是项目根目录，容器内需保持 `/build/admin`、`/build/user`、`/build/shared` 的相对布局。修改 Dockerfile 的 `COPY` 路径时保持这三个目录同级。

**镜像构建阶段的类型检查**
两个前端镜像只执行 `npx vite build`，`vue-tsc` 留给本地与 CI。类型错误在本地用 `npx vue-tsc --noEmit` 暴露，避免阻断发布流程。

**登录验证码从哪里取**
项目未接短信通道，验证码写入 Redis。取码命令：

```bash
docker exec echo-redis redis-cli --scan --pattern '*login*code*'
docker exec echo-redis redis-cli get "<上一步查到的 key>"
```

**离线发布到无外网服务器**

```bash
# 构建机
docker save echo/backend:latest echo/admin-web:latest echo/user-web:latest echo/rocketmq:5.3.1 -o echo-images.tar
docker save mysql:8.0 redis:7-alpine eclipse-temurin:17-jre nginx:1.27-alpine -o echo-base.tar

# 目标机
docker load -i echo-base.tar && docker load -i echo-images.tar
docker compose up -d --no-build
```

## 八、上线检查清单

1. `docker compose ps` 中 mysql、redis、backend 均为 healthy。
2. `curl /actuator/health` 返回 `{"status":"UP"}`。
3. `/actuator/prometheus` 可访问，指标含 `api_requests_total`。
4. 管理端登录成功，演出、票种、订单列表可加载。
5. 用户端首页、演出详情、下单流程可用。
6. RocketMQ 四个主题与四个消费组齐备，`cache-invalidate-topic` 为广播模式。
7. 压测账号已清理，`tb_user` 只保留真实账号。
8. 接口文档开关、数据库密码、地图 Key 已替换为生产值。
