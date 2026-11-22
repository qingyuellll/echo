#!/bin/bash
# 秒杀纯链路压测（预登录 token）：100 / 300 / 500 并发
set -u
BIN="/d/study/apache-jmeter-5.6.3/apache-jmeter-5.6.3/bin"
OUT="/d/echo/jmeter/bench"
RC="/d/echo/jmeter/reset-seckill.cmd"
REDIS="/d/study/Redis-7.0.13/Redis-7.0.13-Windows-x64-msys2/redis-cli.exe"
export JAVA_HOME='D:\study\jdk17'

for T in 100 300 500; do
  "$RC" 8 "$T" > /dev/null 2>&1
  echo "===== 纯秒杀 threads=$T 起始库存=$("$REDIS" -h 127.0.0.1 -p 6379 get seckill:stock:8) ====="
  rm -f "$OUT/pure_$T.jtl"
  ( cd "$BIN" && ./jmeter.bat -n -t 'D:\echo\jmeter\echo-seckill-pure.jmx' \
      -l "D:\\echo\\jmeter\\bench\\pure_$T.jtl" \
      -j "D:\\echo\\jmeter\\bench\\pure_$T.log" \
      -Jthreads="$T" -Jrampup=3 > "D:/echo/jmeter/bench/pure_$T.out" 2>&1 )
  grep "summary = " "$OUT/pure_$T.out"
  sleep 10
  echo "结束库存=$("$REDIS" -h 127.0.0.1 -p 6379 get seckill:stock:8) 已购=$("$REDIS" -h 127.0.0.1 -p 6379 scard seckill:order:8)"
done
echo done
