-- 原子关注/取关演出
-- KEYS[1]: show:follow:{userId}   用户关注的演出set
-- KEYS[2]: show:followers:{showId} 演出的关注者set
-- ARGV[1]: showId                演出ID
-- ARGV[2]: -1=取关, 1=关注
-- ARGV[3]: userId                当前用户ID（用于关注者set）
-- 返回: 1=关注成功, -1=取关成功, 0=已关注(重复), -2=未关注(无法取关)
local followKey = KEYS[1]
local followersKey = KEYS[2]
local showId = ARGV[1]
local action = tonumber(ARGV[2])
local userId = ARGV[3]

if action == 1 then
    if redis.call("sismember", followKey, showId) == 1 then
        return 0
    end
    redis.call("sadd", followKey, showId)
    redis.call("sadd", followersKey, userId)
    return 1
else
    if redis.call("sismember", followKey, showId) == 0 then
        return -2
    end
    redis.call("srem", followKey, showId)
    redis.call("srem", followersKey, userId)
    return -1
end
