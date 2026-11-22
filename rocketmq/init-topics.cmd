@echo off
setlocal

rem ============================================================
rem Echo 项目 RocketMQ 主题与消费组初始化
rem 由「一键启动.bat」在 Broker 启动后调用，也可以单独双击执行
rem 幂等：已存在的 Topic / 消费组会被更新而不是报错
rem ============================================================

set ROCKETMQ_HOME=D:\study\rocketmq-5.3.4
set NAMESRV=127.0.0.1:9876
set CLUSTER=DefaultCluster
set ADMIN=%ROCKETMQ_HOME%\bin\mqadmin.cmd

echo [RocketMQ] 等待 NameServer / Broker 就绪 ...
set /a TRY=0

:probe
call "%ADMIN%" clusterList -n %NAMESRV% >nul 2>&1
if not errorlevel 1 goto ready
set /a TRY+=1
if %TRY% GEQ 15 (
    echo [RocketMQ] NameServer / Broker 未就绪，跳过初始化
    exit /b 1
)
timeout /t 2 /nobreak >nul
goto probe

:ready
echo [RocketMQ] 创建 / 更新 Topic ...
for %%t in (cache-invalidate-topic notification-topic order-timeout-topic seckill-order-topic) do (
    call "%ADMIN%" updateTopic -n %NAMESRV% -c %CLUSTER% -t %%t -r 4 -w 4 >nul 2>&1
    if not errorlevel 1 echo          %%t 已就绪
)

echo [RocketMQ] 创建 / 更新消费组 ...
for %%g in (notification-consumer-group order-timeout-consumer-group seckill-order-consumer-group) do (
    call "%ADMIN%" updateSubGroup -n %NAMESRV% -c %CLUSTER% -g %%g >nul 2>&1
    if not errorlevel 1 echo          %%g 已就绪
)
rem 缓存失效使用 BROADCASTING 模式，消费组需要开启广播开关
call "%ADMIN%" updateSubGroup -n %NAMESRV% -c %CLUSTER% -g cache-invalidate-consumer-group -d true >nul 2>&1
if not errorlevel 1 echo          cache-invalidate-consumer-group 已就绪（广播模式）

echo [RocketMQ] 初始化完成
exit /b 0
