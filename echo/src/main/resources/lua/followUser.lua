-- 原子关注/取关用户
-- KEYS[1]: follow:{userId}    关注者set
-- KEYS[2]: fans:{followUserId} 粉丝set
-- ARGV[1]: followUserId       目标用户ID
-- ARGV[2]: -1=取关, 1=关注
-- ARGV[3]: userId             当前用户ID（用于粉丝set）
-- 返回: 1=关注成功, -1=取关成功, 0=已关注(重复), -2=未关注(无法取关)
local followKey = KEYS[1]
local fansKey = KEYS[2]
local targetId = ARGV[1]
local action = tonumber(ARGV[2])
local userId = ARGV[3]

if action == 1 then
    -- 关注
    if redis.call("sismember", followKey, targetId) == 1 then
        return 0
    end
    redis.call("sadd", followKey, targetId)
    redis.call("sadd", fansKey, userId)
    return 1
else
    -- 取关
    if redis.call("sismember", followKey, targetId) == 0 then
        return -2
    end
    redis.call("srem", followKey, targetId)
    redis.call("srem", fansKey, userId)
    return -1
end
