@echo off
setlocal enabledelayedexpansion

echo ============================================
echo   Echo 项目停止所有服务
echo ============================================
echo.

echo [1/4] 正在停止 Redis ...
taskkill /f /im redis-server.exe >nul 2>&1
if errorlevel 1 (echo      Redis 未运行或已停止) else (echo      Redis 已停止)

echo [2/4] 正在停止 RocketMQ ...
taskkill /f /im mqnamesrv.exe >nul 2>&1
taskkill /f /im mqbroker.exe >nul 2>&1
taskkill /f /im java.exe >nul 2>&1
echo      RocketMQ / Java 进程已尝试停止

echo [3/5] 正在停止监控服务 ...
taskkill /f /im prometheus.exe >nul 2>&1
if errorlevel 1 (echo      Prometheus 未运行或已停止) else (echo      Prometheus 已停止)
taskkill /f /im grafana-server.exe >nul 2>&1
if errorlevel 1 (echo      Grafana 未运行或已停止) else (echo      Grafana 已停止)

echo [4/5] 正在停止前端 Node 服务 ...
taskkill /f /im node.exe >nul 2>&1
if errorlevel 1 (echo      前端未运行或已停止) else (echo      前端已停止)

echo [5/5] 正在清理启动窗口 ...
taskkill /f /fi "WINDOWTITLE eq RocketMQ-NameServer" >nul 2>&1
taskkill /f /fi "WINDOWTITLE eq RocketMQ-Broker" >nul 2>&1
taskkill /f /fi "WINDOWTITLE eq Echo-Backend" >nul 2>&1
taskkill /f /fi "WINDOWTITLE eq Echo-Admin" >nul 2>&1
taskkill /f /fi "WINDOWTITLE eq Echo-User" >nul 2>&1
taskkill /f /fi "WINDOWTITLE eq Prometheus" >nul 2>&1
taskkill /f /fi "WINDOWTITLE eq Grafana" >nul 2>&1

echo.
echo ============================================
echo   停止完成
echo ============================================
echo.
pause
