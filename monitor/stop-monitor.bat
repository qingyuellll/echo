@echo off
REM ============================================================
REM  Stop Echo monitoring stack (Prometheus + Grafana)
REM  Data is preserved: prometheus-data\ and grafana-data\
REM ============================================================

echo Stopping Prometheus ...
taskkill /IM prometheus.exe /F >nul 2>&1
if %errorlevel%==0 (echo   [ok] Prometheus stopped) else (echo   [--] Prometheus was not running)

echo Stopping Grafana ...
taskkill /IM grafana.exe /F >nul 2>&1
if %errorlevel%==0 (echo   [ok] Grafana stopped) else (echo   [--] Grafana was not running)
taskkill /IM grafana-server.exe /F >nul 2>&1

echo.
echo Done. Metrics history and Grafana dashboards are kept on disk.
