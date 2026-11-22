# Echo 本地一键启动

## 用法

双击 `一键启动.bat`，脚本按固定顺序拉起全部服务，每个服务各占一个命令行窗口，运行期间保持窗口打开。

启动顺序：

1. Redis
2. RocketMQ NameServer
3. RocketMQ Broker（读取 `rocketmq/broker.conf`）
4. RocketMQ 主题与消费组初始化（`rocketmq/init-topics.cmd`）
5. 后端 Spring Boot（`target` 下没有 jar 时先执行 `mvn clean package -DskipTests`）
6. 管理端前端（`npm run dev`）
7. 用户端前端（`npm run dev`）

停止：双击 `停止所有服务.bat`，结束 Redis、RocketMQ、Java、Node 相关进程及启动窗口。

## 访问地址

| 服务 | 地址 |
|---|---|
| 后端 API | `http://localhost:8081` |
| 管理端 | `http://localhost:3001` |
| 用户端 | `http://localhost:3002` |
| 健康检查 | `http://localhost:8081/actuator/health` |

后端启动参数显式声明 `--server.port=8081`，端口固定。前端端口由各自的 `vite.config` 决定。

## 依赖路径

脚本顶部的 `set` 变量声明各组件位置，换机器时改这几行：

| 变量 | 默认值 |
|---|---|
| `ECHO_DIR` | `D:\echo` |
| `ROCKETMQ_HOME` | `D:\study\rocketmq-5.3.4` |
| `REDIS_DIR` | `D:\study\Redis-7.0.13\Redis-7.0.13-Windows-x64-msys2` |
| `BACKEND_DIR` | `D:\echo\echo` |
| `ADMIN_DIR` | `D:\echo\admin` |
| `USER_DIR` | `D:\echo\user` |

## RocketMQ 配置

| 文件 | 作用 |
|---|---|
| `rocketmq/broker.conf` | 存储目录固定为 `D:\study\rocketmq-5.3.4\store`，开启主题与消费组自动创建 |
| `rocketmq/init-topics.cmd` | 幂等创建 4 个主题与 4 个消费组，其中缓存失效组为广播模式 |

Broker 已在运行时单独执行初始化：

```
D:\echo\rocketmq\init-topics.cmd
```

| 主题 | 消费组 | 模式 |
|---|---|---|
| `cache-invalidate-topic` | `cache-invalidate-consumer-group` | BROADCASTING |
| `notification-topic` | `notification-consumer-group` | CLUSTERING |
| `order-timeout-topic` | `order-timeout-consumer-group` | CLUSTERING |
| `seckill-order-topic` | `seckill-order-consumer-group` | CLUSTERING |

## 说明

- `.bat` 文件使用 GBK 编码（中文 Windows 原生编码），用 UTF-8 重新保存后中文命令行的解析会出错。
- Broker 控制台输出写入 `rocketmq/broker-console.log`，业务日志位于 `%USERPROFILE%\logs\rocketmqlogs\`，单文件 100MB 滚动，保留 10 份。
- Broker 日志中的 `Failed to persist ... AccessDeniedException` 来自 `MixAll.fsyncDirectory`：Windows 上 JDK 无法以文件方式打开目录，该调用必然失败。`consumerOffset.json`、`topics.json`、`delayOffset.json` 均正常落盘，消息收发不受影响，属于平台行为。
