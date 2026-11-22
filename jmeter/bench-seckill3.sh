#!/bin/bash
# 秒杀梯度压测（最终版）：100 / 300 / 500 并发，每轮重置库存=线程数并校验一致性
set -u
BIN="/d/study/apache-jmeter-5.6.3/apache-jmeter-5.6.3/bin"
OUT="/d/echo/jmeter/bench"
RC="/d/echo/jmeter/reset-seckill.cmd"
REDIS="/d/study/Redis-7.0.13/Redis-7.0.13-Windows-x64-msys2/redis-cli.exe"
export JAVA_HOME='D:\study\jdk17'

for T in 100 300 500; do
  "$RC" 8 "$T" > /dev/null 2>&1
  ST0=$("$REDIS" -h 127.0.0.1 -p 6379 get seckill:stock:8)
  IDEM=$("$REDIS" -h 127.0.0.1 -p 6379 --scan --pattern "seckill:idem:8:*" | wc -l)
  echo "===== 秒杀 threads=$T  起始库存=$ST0  残留幂等键=$IDEM ====="
  rm -f "$OUT/final_$T.jtl"
  ( cd "$BIN" && ./jmeter.bat -n -t 'D:\echo\jmeter\echo-seckill-bench.jmx' \
      -l "D:\\echo\\jmeter\\bench\\final_$T.jtl" \
      -j "D:\\echo\\jmeter\\bench\\final_$T.log" \
      -Jthreads="$T" -Jrampup=5 > "D:/echo/jmeter/bench/final_$T.out" 2>&1 )
  grep "summary = " "$OUT/final_$T.out"
  sleep 10
  echo "结束库存=$("$REDIS" -h 127.0.0.1 -p 6379 get seckill:stock:8)  已购=$("$REDIS" -h 127.0.0.1 -p 6379 scard seckill:order:8)  队列残留=$("$REDIS" -h 127.0.0.1 -p 6379 zcard seckill:queue:8)"
done
echo "done"
