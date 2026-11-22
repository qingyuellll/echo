local stockKey = KEYS[1]
local orderKey = KEYS[2]
local graceKey = KEYS[3]
local ticketId = ARGV[1]
local userId = ARGV[2]
local quantity = tonumber(ARGV[3])
local graceTtl = tonumber(ARGV[4])

if (not quantity or quantity <= 0 or quantity > 99) then
    return 3
end

local stock = redis.call("get", stockKey)
if (not stock or tonumber(stock) < quantity) then
    return 1
end

if (redis.call("sismember", orderKey, userId) == 1) then
    return 2
end

local newStock = redis.call("decrby", stockKey, quantity)

if (newStock < 0) then
    redis.call("incrby", stockKey, quantity)
    return 1
end

redis.call("sadd", orderKey, userId)
redis.call("set", graceKey, "1", "EX", graceTtl)

return 0
