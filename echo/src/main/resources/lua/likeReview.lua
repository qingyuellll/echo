-- 原子点赞/取消：使用ZSet存储，score为时间戳，支持排序
-- 返回: 1=点赞成功，-1=取消成功
local key = KEYS[1]
local userId = ARGV[1]
local now = ARGV[2]

if redis.call("zscore", key, userId) then
    redis.call("zrem", key, userId)
    return -1
else
    redis.call("zadd", key, now, userId)
    return 1
end
