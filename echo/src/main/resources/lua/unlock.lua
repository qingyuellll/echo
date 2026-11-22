-- 可重入分布式锁：释放锁（支持重入计数递减）
-- KEYS[1]: 锁的Key (如 "lock:order:1001")
-- ARGV[1]: 锁的标识符 (如 "uuid-threadId")
-- 返回值：
--   >0 = 释放成功（返回剩余重入次数，可能还有其他重入未释放）
--   0 = 释放失败（锁不存在或非当前线程持有）

if redis.call("hexists", KEYS[1], ARGV[1]) == 1 then
    -- 当前线程持有锁 → 重入计数-1
    local count = redis.call("hincrby", KEYS[1], ARGV[1], -1)

    if tonumber(count) > 0 then
        -- 还有其他重入，不删除锁，只减少计数
        return count
    else
        -- 重入计数归零 → 删除整个锁
        redis.call("del", KEYS[1])
        return 1
    end
else
    -- 锁不存在或不是当前线程持有
    return 0
end
