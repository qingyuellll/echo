@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 exit /b 1
if not exist "target\echo-0.0.1-SNAPSHOT.jar" exit /b 1
set "ECHO_GUIDE_SOCKET_DIR=%~dp0target\runtime\local-network-compatibility-for-java-on-windows-without-domain-sockets-temporary-files"
if not exist "%ECHO_GUIDE_SOCKET_DIR%" mkdir "%ECHO_GUIDE_SOCKET_DIR%"
if not exist "logs\guide-rocketmq" mkdir "logs\guide-rocketmq"
java "-Djdk.net.unixdomain.tmpdir=%ECHO_GUIDE_SOCKET_DIR%" "-Drocketmq.client.logRoot=%~dp0logs\guide-rocketmq" --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED -jar "target\echo-0.0.1-SNAPSHOT.jar" --server.port=8081
