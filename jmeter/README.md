# Echo 压测脚本

## 一、文件清单

| 文件 | 作用 |
|---|---|
| `echo-load-test.jmx` | 功能联调计划：前置检查 + 秒杀（含排队查询）+ 浏览查询，默认 30 并发 |
| `echo-read-bench.jmx` | 读路径梯度压测，4 个读接口混合，并发与循环参数化 |
| `echo-seckill-bench.jmx` | 秒杀高并发压测，每线程登录一次再秒杀，用于测完整链路 |
| `echo-seckill-pure.jmx` | 秒杀纯链路压测，使用预生成 token，剥离登录开销 |
| `burst-seckill.js` | 秒杀入口分层压测器（Node 原生 http），按响应文案统计各闸门的拒绝分布 |
| `gen-tokens.js` | 批量登录 500 个账号，产出 `tokens.csv` |
| `users.csv` | 30 个常规测试账号（密码 123456） |
| `users-bench.csv` | 500 个压测账号（199 号段） |
| `tokens1000.csv` | 1000 个账号的 token 与 csrfToken，供分层压测使用 |
| `prepare-seckill.sql` | 打开秒杀时间窗并重置库存（MySQL 侧） |
| `reset-seckill.cmd` | 重灌库存并清空已购集合、排队、幂等键（Redis 侧），支持传参 |
| `bench-read-steady.sh` | 读路径 50 / 100 / 200 / 400 并发梯度 |
| `bench-seckill-pure.sh` | 秒杀纯链路梯度 |
| `bench-seckill3.sh` | 秒杀含登录梯度 |
| `cleanup-bench-users.sql` | 清理 199 号段的压测账号 |

压测数据与瓶颈分析见 `docs/PERFORMANCE-性能压测报告.md`。

## 二、启动 JMeter 图形界面

双击：

```
D:\study\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin\jmeter.bat
```

打开后 `File` → `Open` → 选择 `D:\echo\jmeter\echo-load-test.jmx`。

根目录的 `ApacheJMeter.jar` 是程序本体，图形界面入口在 `bin\jmeter.bat`。首次启动需要扫描插件，命令行窗口在运行期间保持打开。

## 三、压测前准备

秒杀票的时间窗过期、库存被扣完、用户已购买过，都会让请求落到业务拒绝分支，压测结果失去意义。每次压测前执行：

```bat
:: 1. 打开时间窗并重置库存（MySQL）
mysql -uroot -p123456 echo < D:\echo\jmeter\prepare-seckill.sql

:: 2. 重置 Redis 库存与已购集合，参数为票种 ID 与库存
D:\echo\jmeter\reset-seckill.cmd 8 500
```

脚本默认票种为 `ticket_id = 8`。换票种时同步修改 `prepare-seckill.sql` 与 `reset-seckill.cmd` 顶部的变量。

## 四、图形界面运行

1. 打开 `echo-load-test.jmx`
2. 左侧选中 `场景一：秒杀下单`，按需调整线程数（默认 30）
3. 点绿色启动按钮，查看 `汇总报告（秒杀）` 与 `聚合报告（全部）`

## 五、命令行运行

```bat
cd /d D:\study\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin
jmeter.bat -n -t D:\echo\jmeter\echo-load-test.jmx -l D:\echo\jmeter\result.jtl ^
  -Jthreads=30 -Jrampup=5 -JbrowseThreads=10 -JbrowseLoops=3
```

生成 HTML 报告：

```bat
jmeter.bat -n -t D:\echo\jmeter\echo-load-test.jmx -l D:\echo\jmeter\result.jtl ^
  -e -o D:\echo\jmeter\report -Jthreads=30 -Jrampup=5
```

## 六、可覆盖参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `-Jhost` | localhost | 后端地址 |
| `-Jport` | 8081 | 后端端口 |
| `-JticketId` | 8 | 秒杀票种 ID |
| `-JshowId` | 28 | 浏览场景的演出 ID |
| `-Jthreads` | 30 | 秒杀并发线程数，取值与 `users.csv` 行数一致 |
| `-Jrampup` | 10 | 并发爬坡秒数 |
| `-Jloops` | 1 | 每线程秒杀次数，秒杀一人一单，保持 1 |
| `-JbrowseThreads` | 20 | 浏览场景并发 |
| `-JbrowseLoops` | 5 | 浏览场景循环次数 |
| `-JusersCsv` | D:/echo/jmeter/users.csv | 账号文件路径 |

## 七、场景结构

**前置检查（setUp，1 线程）**

- `GET /actuator/health` 断言 `"status":"UP"`
- `GET /ticket/detail/{ticketId}` 断言票种存在
- 任一失败则整轮不启动，避免用错误数据压测

**场景一：秒杀下单**

1. CSV 每个线程取一个账号
2. 每线程登录一次：`POST /user/login`，提取 `token`、`csrfToken`、`userId`
3. `POST /ticket-order/seckill/{ticketId}`，携带 `authorization` 与 `X-CSRF-Token`
4. 等待 3 秒后 `GET /ticket-order/seckill/queue/status/{ticketId}` 查询排队结果

秒杀走排队模式，受理成功返回 `{"data":{"status":"QUEUED"}}`，脚本按该结构判定。

**场景二：浏览查询**

- `GET /show/search?current=1&size=10`
- `GET /show/{showId}`
- `GET /ticket/detail/{ticketId}`

## 八、断言策略

秒杀接口存在大量合理的业务拒绝：售罄、已购买、请求频繁、活动已结束。这些是服务正常工作的表现，应计入通过。`业务结果断言` 用 Groovy 分三类：

| 情况 | 判定 |
|---|---|
| HTTP 非 200 | 失败（网关、鉴权或服务异常） |
| 响应含 `"status":"QUEUED"` 或 `"success":true` | 通过（受理成功） |
| 响应含 售罄 / 已购买 / 频繁 / 已结束 / 尚未开始 / 库存不足 | 通过（业务拒绝） |
| 其他 | 失败，并打印响应前 200 字 |

## 九、结果核对

除 JMeter 报告外，对照三处数据：

```bat
:: Redis 库存减少量应等于成功受理单数
redis-cli get seckill:stock:8
:: 已购集合人数应等于成交用户数
redis-cli scard seckill:order:8
```

- 后端日志：`[秒杀入口]`、`[秒杀限流]`、`[秒杀-风控通过]` 等标记
- 数据库：`tb_ticket_order` 中对应 `ticket_id` 的订单增量

一致性校验的时机是 `seckill:queue:8` 归零之后。秒杀是「入队 → 异步扣减」，刚打完立即读库存会看到少量未扣完的差额。

## 十、实测基线

环境：i5-13500HX（20 逻辑核）/ 16GB，应用、MySQL、Redis、MQ、压测工具同机混布。

读路径（4 接口混合，稳态 50 轮）：

| 并发 | 吞吐 | 平均 | P90 |
|---|---|---|---|
| 50 | 1257 req/s | 32ms | 72ms |
| 100 | 1523 req/s | 57ms | 135ms |
| 200 | 1662 req/s | 111ms | 268ms |
| 400 | 1602 req/s | 240ms | 416ms |

秒杀（库存与并发数相等，已购集合、库存、受理数三者一致）：

| 场景 | 结果 |
|---|---|
| 纯链路 500 并发瞬时涌入 | 500/500 受理，P90 324ms，零超卖 |
| 纯链路 500 并发 × 5 轮持续 | 434 req/s，P90 904ms，失败率 0% |
| 完整链路（含登录）500 并发 | 500/500 受理，整轮 19.5s，登录 P50 12.5s |
| 入口分层：1000 账号 × 30 轮 = 3 万请求 | 25.6s 打完，峰值 1919 req/s，成交 1000 单；下单频控拒 59.7%、令牌桶拒 23.7%、一人一单拒 2.5%、入队 14.2% |
| 单用户 1 秒 20 次 | 5 次通过、15 次「1秒内最多5次」 |

## 十一、秒杀入口分层压测

```bat
:: 3 万请求：1000 账号 × 30 轮，并发 1000，库存 1000
D:\echo\jmeter\reset-seckill.cmd 8 1000
node D:\echo\jmeter\burst-seckill.js --total 30000 --concurrency 1000 --repeat 30

:: 单用户限流验证：1 秒内 20 次
node D:\echo\jmeter\burst-seckill.js --single-user 20 --concurrency 20

:: 极限瞬时：3000 并发一次性涌入，库存 3000
D:\echo\jmeter\reset-seckill.cmd 8 3000
node D:\echo\jmeter\burst-seckill.js --total 3000 --concurrency 3000 --repeat 3
```

输出按「排队成功 / 本地令牌桶 / 用户级限流 / 全局限流 / 下单频控 / 一人一单 / 售罄」分类计数，并写入 `bench/burst_result.json`，可用 `BURST_OUT` 指定路径。

## 十二、阶梯压测

```bat
:: 读路径：50 / 100 / 200 / 400 并发
bash D:\echo\jmeter\bench-read-steady.sh

:: 秒杀纯链路（先批量登录拿 token）
node D:\echo\jmeter\gen-tokens.js
bash D:\echo\jmeter\bench-seckill-pure.sh

:: 秒杀完整链路（含登录）
bash D:\echo\jmeter\bench-seckill3.sh
```

每轮脚本自动重置库存，并打印起始库存、结束库存、已购人数，用于一致性校验。

## 十三、注意事项

1. **并发上限由账号数决定**：秒杀一人一单，账号文件有多少行，单轮最多成功多少单。高并发场景使用 `users-bench.csv`（500 个）与 `tokens1000.csv`（1000 个，本地用 `gen-tokens.js` 生成）。
2. **账号需先在库中存在**：两个 CSV 只提供手机号与密码，对应的 `tb_user` 记录要先建好。`users-bench.csv` 使用 `19900000001` 起的 500 个合成号码，可用 `cleanup-bench-users.sql` 反查与清理。
3. **一致性校验等队列排空**：秒杀是「入队 → 异步扣减」，比对库存与已购集合前先确认 `seckill:queue:{ticketId}` 归零。
4. **CSRF Token 在会话内有效**：脚本用登录返回的 `csrfToken` 发写请求，同一值可重复使用。
5. **秒杀有用户级限流**：5 次/秒加下单频控，`-Jloops` 调大会大量返回「操作过于频繁」，属预期行为。
6. **压测前执行两个准备脚本**：时间窗过期会导致请求全部返回「秒杀已结束」。
7. **结果文件输出到临时目录**：`.jtl`、`report/`、`bench/` 已在 `.gitignore` 中忽略。
