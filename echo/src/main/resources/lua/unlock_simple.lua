-- 简单分布式锁释放（字符串锁专用，与 CacheClient 的 setIfAbsent 加锁配对）
-- KEYS[1]: 锁的Key (如 "lock:show:1")
-- ARGV[1]: 加锁时写入的值（CacheClient 固定为 "1"）
-- 返回值：1 = 释放成功；0 = 锁不存在或非当前持有者（不误删）
--
-- 注意：lua/unlock.lua 是哈希结构的可重入锁脚本（HEXISTS/HINCRBY），
-- 供 SimpleRedisLock 的 reentrant_lock.lua 配对使用；
-- 字符串锁不能复用它（HEXISTS 作用于 string 键会抛 WRONGTYPE）。

if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("del", KEYS[1])
else
    return 0
end
