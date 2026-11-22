-- 秒杀幂等门闸：请求级幂等三态（PROCESSING / SUCCESS / FAILED）
-- 与业务状态 seckill:order:{ticketId}（买过没有）正交——本键回答的是"这次请求处理过没有"。
-- 退款 SREM 只释放购买资格，绝不删除本键；本键到期只能靠 TTL 自然过期。
--
-- KEYS[1] = seckill:idem:{ticketId}:{userId}
-- ARGV[1] = TTL（秒）
--
-- 返回码：
--   0 = 无记录，已原子占位 PROCESSING，放行处理
--   1 = PROCESSING，其他执行方正在处理，跳过且不写结果（结果由持有方写入）
--   2 = SUCCESS，本次请求已成功终结，跳过
--   3 = FAILED（上次处理失败后显式落下的标记），已重置为 PROCESSING，允许重做

local v = redis.call("get", KEYS[1])
if (not v) then
    redis.call("set", KEYS[1], "PROCESSING", "EX", tonumber(ARGV[1]))
    return 0
end
if (v == "PROCESSING") then
    return 1
end
if (v == "SUCCESS") then
    return 2
end
-- FAILED：允许重做，重置为 PROCESSING 后放行
redis.call("set", KEYS[1], "PROCESSING", "EX", tonumber(ARGV[1]))
return 3
