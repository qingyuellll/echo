@echo off
setlocal
cd /d "%~dp0"
if not exist "dist\index.html" exit /b 1
call npm run preview:direct
