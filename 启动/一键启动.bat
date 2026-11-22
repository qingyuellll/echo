@echo off
setlocal enabledelayedexpansion

rem 项目根目录：默认取本脚本所在目录的上一级，也可用环境变量 ECHO_DIR 覆盖
if not defined ECHO_DIR for %%i in ("%~dp0..") do set ECHO_DIR=%%~fi
rem 本地中间件目录：改成你自己机器上的实际安装位置，或用同名环境变量覆盖
if not defined ROCKETMQ_HOME set ROCKETMQ_HOME=D:\rocketmq
if not defined REDIS_DIR set REDIS_DIR=D:\redis
set ADMIN_DIR=%ECHO_DIR%\admin
set USER_DIR=%ECHO_DIR%\user
set BACKEND_DIR=%ECHO_DIR%\echo

echo ============================================
echo   Echo 项目一键启动
echo ============================================
echo.

echo [1/6] 正在启动 Redis ...
if exist "%REDIS_DIR%\redis-server.exe" (
    start "Redis" /min "%REDIS_DIR%\redis-server.exe"
    echo      Redis 启动命令已发出
) else (
    echo      [警告] 未找到 redis-server.exe: %REDIS_DIR%
)
timeout /t 2 /nobreak >nul

echo [2/6] 正在启动 RocketMQ NameServer ...
start "RocketMQ-NameServer" cmd /k "chcp 65001 >nul & set ROCKETMQ_HOME=%ROCKETMQ_HOME%&& call %ROCKETMQ_HOME%\bin\mqnamesrv.cmd"
timeout /t 10 /nobreak >nul

echo [3/6] 正在启动 RocketMQ Broker ...
set BROKER_CONF=%ECHO_DIR%\rocketmq\broker.conf
if not exist "%BROKER_CONF%" (
    echo      [警告] 未找到 %BROKER_CONF%，Broker 将以默认配置启动
)
start "RocketMQ-Broker" cmd /k "chcp 65001 >nul & set ROCKETMQ_HOME=%ROCKETMQ_HOME%&& call %ROCKETMQ_HOME%\bin\mqbroker.cmd -c %BROKER_CONF%"
call "%ECHO_DIR%\rocketmq\init-topics.cmd"
timeout /t 5 /nobreak >nul

echo [4/6] 正在准备后端服务 ...
set JAR=
for /f "delims=" %%f in ('dir /b /a-d "%BACKEND_DIR%\target\*.jar" 2^>nul ^| findstr /v /i "sources"') do set JAR=%%f
if not defined JAR (
    echo      未找到已编译 jar，开始 Maven 构建（可能需要几分钟）...
    pushd "%BACKEND_DIR%"
    call mvn clean package -DskipTests
    popd
    for /f "delims=" %%f in ('dir /b /a-d "%BACKEND_DIR%\target\*.jar" 2^>nul ^| findstr /v /i "sources"') do set JAR=%%f
)
if defined JAR (
    echo      找到后端 jar: %JAR%
    start "Echo-Backend" cmd /k "chcp 65001 >nul & cd /d %BACKEND_DIR%\target & java -jar %JAR% --server.port=8081"
    echo      后端启动命令已发出
) else (
    echo      [警告] 后端 jar 未生成，跳过后端启动
)
timeout /t 5 /nobreak >nul

timeout /t 5 /nobreak >nul

echo [5/8] 正在启动 Prometheus 监控抓取 ...
set PROM_DIR=%ECHO_DIR%\monitor\prometheus-2.53.1.windows-amd64
if exist "%PROM_DIR%\prometheus.exe" (
    if not exist "%ECHO_DIR%\monitor\prometheus-data" mkdir "%ECHO_DIR%\monitor\prometheus-data"
    start "Prometheus" cmd /k "chcp 65001 >nul & %PROM_DIR%\prometheus.exe --config.file=%ECHO_DIR%\monitor\prometheus.yml --storage.tsdb.path=%ECHO_DIR%\monitor\prometheus-data --web.listen-address=:9090"
    echo      Prometheus 启动命令已发出
) else (
    echo      [警告] 未找到 prometheus.exe: %PROM_DIR%
)
timeout /t 3 /nobreak >nul

echo [6/8] 正在启动 Grafana 监控面板 ...
set GRAFANA_HOME=%ECHO_DIR%\monitor\grafana-dist\grafana-v11.1.0
if exist "%GRAFANA_HOME%\bin\grafana-server.exe" (
    if not exist "%ECHO_DIR%\monitor\grafana-data" mkdir "%ECHO_DIR%\monitor\grafana-data"
    if not exist "%ECHO_DIR%\monitor\grafana-logs" mkdir "%ECHO_DIR%\monitor\grafana-logs"
    start "Grafana" cmd /k "chcp 65001 >nul & set GF_PATHS_PROVISIONING=%ECHO_DIR%\monitor\grafana-provisioning&& set GF_PATHS_DATA=%ECHO_DIR%\monitor\grafana-data&& set GF_PATHS_LOGS=%ECHO_DIR%\monitor\grafana-logs&& set GF_AUTH_ANONYMOUS_ENABLED=true&& set GF_AUTH_ANONYMOUS_ORG_ROLE=Editor&& %GRAFANA_HOME%\bin\grafana-server.exe --homepath=%GRAFANA_HOME%"
    echo      Grafana 启动命令已发出
) else (
    echo      [警告] 未找到 grafana-server.exe: %GRAFANA_HOME%
)
timeout /t 5 /nobreak >nul

echo [7/8] 正在启动 Admin 前端 ...
if exist "%ADMIN_DIR%\package.json" (
    start "Echo-Admin" cmd /k "chcp 65001 >nul & cd /d %ADMIN_DIR% & npm run dev"
    echo      Admin 前端启动命令已发出
) else (
    echo      [警告] 未找到 %ADMIN_DIR%\package.json
)

echo [8/8] 正在启动 User 前端 ...
if exist "%USER_DIR%\package.json" (
    start "Echo-User" cmd /k "chcp 65001 >nul & cd /d %USER_DIR% & npm run dev"
    echo      User 前端启动命令已发出
) else (
    echo      [警告] 未找到 %USER_DIR%\package.json
)

echo.
echo ============================================
echo   启动完成！各服务窗口请保持打开
echo ============================================
echo.
echo   Redis      : localhost:6379
echo   NameServer : localhost:9876
echo   Broker     : localhost:10911
echo   后端       : http://localhost:8081
echo   Admin      : http://localhost:3001
echo   User       : http://localhost:3002
echo   Prometheus : http://localhost:9090
echo   Grafana    : http://localhost:3000/d/echo-dashboard （免登录，自动打开业务面板）
echo.
pause
