local stockKey = KEYS[1]
local orderKey = KEYS[2]
local pendingOrderKey = KEYS[3]
local userId = ARGV[1]
local quantity = tonumber(ARGV[2]) or 1

local currentStock = redis.call("incrby", stockKey, quantity)
if (not currentStock) then
    return -1
end

redis.call("srem", orderKey, userId)
redis.call("del", pendingOrderKey)

return 0
