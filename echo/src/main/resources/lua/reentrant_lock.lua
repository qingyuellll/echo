-- 可重入分布式锁：获取锁（支持同一线程多次获取）
-- KEYS[1]: 锁的Key (如 "lock:order:1001")
-- ARGV[1]: 锁的标识符 (如 "uuid-threadId")
-- ARGV[2]: 锁的超时时间（秒）
-- 返回值：
--   1 = 首次获取锁成功
--   >1 = 重入获取成功（返回当前重入次数）
--   0 = 获取失败（被其他线程持有）

if redis.call("exists", KEYS[1]) == 0 then
    -- 锁不存在 → 首次获取，创建Hash并设置TTL
    redis.call("hset", KEYS[1], ARGV[1], "1")
    redis.call("expire", KEYS[1], tonumber(ARGV[2]))
    return 1
elseif redis.call("hexists", KEYS[1], ARGV[1]) == 1 then
    -- 锁存在且是当前线程 → 重入计数+1
    redis.call("hincrby", KEYS[1], ARGV[1], "1")
    return tonumber(redis.call("hget", KEYS[1], ARGV[1]))
else
    -- 锁存在但不是当前线程 → 获取失败
    return 0
end
