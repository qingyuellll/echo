-- 看门狗续期：重置锁的TTL
-- KEYS[1]: 锁的Key (如 "lock:order:1001")
-- ARGV[1]: 锁的标识符 (如 "uuid-threadId")
-- ARGV[2]: 新的TTL（秒）
-- 返回值：
--   >0 = 续期成功（返回当前重入次数）
--   0 = 续期失败（锁不存在或非当前线程持有）

if redis.call("exists", KEYS[1]) == 1 then
    if redis.call("hexists", KEYS[1], ARGV[1]) == 1 then
        -- 锁存在且是当前线程 → 重置TTL
        redis.call("expire", KEYS[1], tonumber(ARGV[2]))
        return tonumber(redis.call("hget", KEYS[1], ARGV[1]))
    else
        -- 锁存在但不是当前线程
        return 0
    end
else
    -- 锁不存在
    return 0
end
