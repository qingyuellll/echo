-- 序号生成原子脚本：INCRBY + 首增时EXPIRE，消除两步操作间的崩溃窗口
-- KEYS[1] = 序号key
-- ARGV[1] = 增量（nextId传1，nextIds传批量数）
-- ARGV[2] = TTL秒数
-- 返回值 = 自增后的计数
local count = redis.call('INCRBY', KEYS[1], ARGV[1])
if count == tonumber(ARGV[1]) then
    redis.call('EXPIRE', KEYS[1], ARGV[2])
end
return count
