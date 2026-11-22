#!/bin/bash
# 读路径稳态压测：rampup 2s 快速到满并发，loops 50 保证采样窗口足够长
set -u
BIN="/d/study/apache-jmeter-5.6.3/apache-jmeter-5.6.3/bin"
OUT="/d/echo/jmeter/bench"
export JAVA_HOME='D:\study\jdk17'

for T in 50 100 200 400; do
  echo "===== 读路径稳态 threads=$T ====="
  rm -f "$OUT/steady_$T.jtl"
  ( cd "$BIN" && ./jmeter.bat -n -t 'D:\echo\jmeter\echo-read-bench.jmx' \
      -l "D:\\echo\\jmeter\\bench\\steady_$T.jtl" \
      -j "D:\\echo\\jmeter\\bench\\steady_$T.log" \
      -Jthreads="$T" -Jloops=50 -Jrampup=2 > "D:/echo/jmeter/bench/steady_$T.out" 2>&1 )
  grep "summary = " "$OUT/steady_$T.out"
  sleep 5
done
echo done
