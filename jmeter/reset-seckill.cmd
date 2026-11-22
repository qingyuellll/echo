@echo off
setlocal enabledelayedexpansion

rem ============================================================
rem Echo 秒杀压测数据准备（Redis 部分）
rem 用法：reset-seckill.cmd [票种ID] [库存]     缺省 8 / 200
rem 作用：重灌库存，清空已购集合 / 排队 / 幂等键 / 排队结果 / 预订单
rem 需与 prepare-seckill.sql 一起使用
rem 模式清理用 Lua 循环删除，保证删干净
rem ============================================================

set REDIS_CLI=D:\study\Redis-7.0.13\Redis-7.0.13-Windows-x64-msys2\redis-cli.exe
set REDIS_HOST=127.0.0.1
set REDIS_PORT=6379

set TICKET_ID=%1
if "%TICKET_ID%"=="" set TICKET_ID=8
set STOCK=%2
if "%STOCK%"=="" set STOCK=200

set DEL_PATTERN=local k=redis.call('keys',ARGV[1]) local n=0 for i=1,#k do redis.call('del',k[i]) n=n+1 end return n
set COUNT_PATTERN=return #redis.call('keys',ARGV[1])

echo [1/5] 写入秒杀库存 seckill:stock:%TICKET_ID% = %STOCK%
"%REDIS_CLI%" -h %REDIS_HOST% -p %REDIS_PORT% set seckill:stock:%TICKET_ID% %STOCK% EX 7200 >nul

echo [2/5] 清空已购集合与售罄标记
"%REDIS_CLI%" -h %REDIS_HOST% -p %REDIS_PORT% del seckill:order:%TICKET_ID% >nul
"%REDIS_CLI%" -h %REDIS_HOST% -p %REDIS_PORT% del seckill:soldout:%TICKET_ID% >nul

echo [3/5] 清空排队队列
"%REDIS_CLI%" -h %REDIS_HOST% -p %REDIS_PORT% del seckill:queue:%TICKET_ID% >nul
"%REDIS_CLI%" -h %REDIS_HOST% -p %REDIS_PORT% del seckill:queue:detail:%TICKET_ID% >nul
"%REDIS_CLI%" -h %REDIS_HOST% -p %REDIS_PORT% srem seckill:queue:active %TICKET_ID% >nul

echo [4/5] 清空幂等键 / 排队结果 / 预订单
"%REDIS_CLI%" -h %REDIS_HOST% -p %REDIS_PORT% eval "%DEL_PATTERN%" 0 "seckill:idem:%TICKET_ID%:*"
"%REDIS_CLI%" -h %REDIS_HOST% -p %REDIS_PORT% eval "%DEL_PATTERN%" 0 "seckill:queue:result:%TICKET_ID%:*"
"%REDIS_CLI%" -h %REDIS_HOST% -p %REDIS_PORT% eval "%DEL_PATTERN%" 0 "pending:seckill:order:%TICKET_ID%:*"

echo [5/5] 校验
echo         库存 =
"%REDIS_CLI%" -h %REDIS_HOST% -p %REDIS_PORT% get seckill:stock:%TICKET_ID%
echo         已购数量 =
"%REDIS_CLI%" -h %REDIS_HOST% -p %REDIS_PORT% scard seckill:order:%TICKET_ID%
echo         幂等键残留 =
"%REDIS_CLI%" -h %REDIS_HOST% -p %REDIS_PORT% eval "%COUNT_PATTERN%" 0 "seckill:idem:%TICKET_ID%:*"
endlocal
