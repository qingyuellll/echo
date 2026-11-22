-- 秒杀排队原子入队脚本
-- 将"队列上限校验 + 入队 + 明细写入 + 历史结果清理 + TTL续期"合并为单次RTT原子操作：
-- 1. ZADD按member幂等，判重即入队本身，不存在"判重与入队之间两次都通过"的并发窗口
-- 2. 原ZCARD-then-ZADD两步在并发下可超出容量上限，脚本内校验严格成立
-- KEYS[1] = 排队ZSET     seckill:queue:{ticketId}
-- KEYS[2] = 排队明细HASH seckill:queue:detail:{ticketId}
-- KEYS[3] = 活跃队列集合 seckill:queue:active
-- ARGV[1] = userId（ZSET member）
-- ARGV[2] = score（入队时间戳毫秒）
-- ARGV[3] = 队列容量上限
-- ARGV[4] = 排队明细JSON（quantity/seatIds）
-- ARGV[5] = 该用户历史结果key seckill:queue:result:{ticketId}:{userId}
-- ARGV[6] = 队列/明细TTL（秒）
-- ARGV[7] = ticketId（活跃集合的成员）
-- 返回：1=新入队  0=已在队列中（明细已覆盖）  -1=队列已满

if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then
    return -1
end

local added = redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
redis.call('HSET', KEYS[2], ARGV[1], ARGV[4])
redis.call('SADD', KEYS[3], ARGV[7])
redis.call('DEL', ARGV[5])
redis.call('EXPIRE', KEYS[1], ARGV[6])
redis.call('EXPIRE', KEYS[2], ARGV[6])

return added
