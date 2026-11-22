@echo off
setlocal enabledelayedexpansion
REM ============================================================
REM  Echo monitoring stack  :  Prometheus(:9090) + Grafana(:3000)
REM  Scrape target          :  backend http://localhost:8081/actuator/prometheus  every 30s
REM
REM  Idempotent: safe to run twice, already-running services are skipped.
REM ============================================================

set "MON=%~dp0"
if "%MON:~-1%"=="\" set "MON=%MON:~0,-1%"

echo ============================================================
echo   Echo monitor  -  Prometheus + Grafana
echo   base dir: %MON%
echo ============================================================

REM ---------------- 1) Prometheus :9090 ----------------
tasklist /FI "IMAGENAME eq prometheus.exe" 2>nul | findstr /I "prometheus.exe" >nul
if !errorlevel!==0 (
    echo [skip ] Prometheus already running on :9090
) else (
    echo [start] Prometheus  -^>  http://localhost:9090
    if not exist "%MON%\prometheus-data" mkdir "%MON%\prometheus-data"
    start "Echo-Prometheus" /min "%MON%\prometheus-2.53.1.windows-amd64\prometheus.exe" --config.file="%MON%\prometheus.yml" --storage.tsdb.path="%MON%\prometheus-data" --web.listen-address=:9090 --web.enable-lifecycle
)

REM ---------------- 2) Grafana :3000 ----------------
tasklist /FI "IMAGENAME eq grafana.exe" 2>nul | findstr /I "grafana.exe" >nul
if !errorlevel!==0 (
    echo [skip ] Grafana already running on :3000
) else (
    echo [start] Grafana     -^>  http://localhost:3000
    set "GF_PATHS_DATA=%MON%\grafana-data"
    set "GF_PATHS_LOGS=%MON%\grafana-logs"
    set "GF_PATHS_PROVISIONING=%MON%\grafana-provisioning"
    set "GF_SERVER_HTTP_PORT=3000"
    set "GF_AUTH_ANONYMOUS_ENABLED=true"
    set "GF_AUTH_ANONYMOUS_ORG_ROLE=Editor"
    set "GF_SECURITY_ADMIN_USER=admin"
    set "GF_SECURITY_ADMIN_PASSWORD=admin"
    start "Echo-Grafana" /min "%MON%\grafana-dist\grafana-v11.1.0\bin\grafana.exe" --homepath "%MON%\grafana-dist\grafana-v11.1.0"
)

echo.
echo Waiting for services to come up ...
timeout /t 8 /nobreak >nul

echo.
echo   Prometheus target status : http://localhost:9090/targets
echo   Prometheus query         : http://localhost:9090/graph
echo   Grafana dashboard        : http://localhost:3000/d/echo-dashboard
echo   Grafana login            : admin / admin   (anonymous Editor also enabled)
echo.
echo Reload Prometheus config after editing prometheus.yml:
echo   curl -X POST http://localhost:9090/-/reload
echo.
endlocal
