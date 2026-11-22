-- ============================================================
-- 限流Lua脚本 - 令牌桶(Token Bucket)实现
--
-- 解决：
--   1. 固定窗口的窗口边界突发(最坏2倍突发)
--   2. Java端 INCR + EXPIRE 非原子导致键永久残留(S8 bug)
--
-- 参数：
--   KEYS[1] = 限流key
--   ARGV[1] = capacity       桶容量(突发上限, 即原 limit)
--   ARGV[2] = windowSeconds  补充周期(秒, 即原 window)
--   补充速率 = capacity / windowSeconds (tokens/秒)
--   当前时间取自 Redis TIME, 避免客户端时钟偏移
--
-- 返回：
--   >= 0 : 放行(返回剩余令牌数, 整数)
--   -1   : 超过容量(拒绝)
--   -2   : 参数错误
--
-- 调用方：
--   rate:seckill:user:{userId}  capacity=5   window=1
--   rate:seckill:global         capacity=1000 window=1
--   rate:order:user:{userId}    capacity=5   window=60
-- ============================================================

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local windowSeconds = tonumber(ARGV[2])

if (not key or not capacity or not windowSeconds or capacity <= 0 or windowSeconds <= 0) then
    return -2
end

local rate = capacity / windowSeconds

local redisTime = redis.call("TIME")
local now = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)

local data = redis.call("HMGET", key, "tokens", "ts")
local tokens = tonumber(data[1])
local ts = tonumber(data[2])

if tokens == nil then
    tokens = capacity
    ts = now
else
    local elapsed = (now - ts) / 1000
    if elapsed > 0 then
        tokens = math.min(capacity, tokens + elapsed * rate)
        ts = now
    end
end

if tokens < 1 then
    redis.call("HSET", key, "tokens", tokens, "ts", ts)
    redis.call("PEXPIRE", key, windowSeconds * 1000)
    return -1
end

tokens = tokens - 1
redis.call("HSET", key, "tokens", tokens, "ts", ts)
redis.call("PEXPIRE", key, windowSeconds * 1000)
return math.floor(tokens)
