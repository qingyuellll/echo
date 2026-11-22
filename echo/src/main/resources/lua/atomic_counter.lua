-- ============================================================
-- 原子计数器Lua脚本 - 原子性INCR + EXPIRE
--
-- 解决：Java端 INCR + EXPIRE 非原子操作导致键永久残留(S8 bug)
--       首次自增后程序崩溃, EXPIRE 未执行, key 永久存在, 用户永远锁定
--
-- 语义：固定窗口计数器(非限流), 适用于登录失败累计等场景
--       计数器在窗口过期后自动清除, 窗口内累计递增
--
-- 参数：
--   KEYS[1] = 计数器key
--   ARGV[1] = windowSeconds  窗口时长(秒)
--
-- 返回：
--   >= 1 : 当前计数值(允许通过, 调用方可与阈值比较)
--   -2   : 参数错误
--
-- 调用方：
--   login:code:fail:{phone}    window=900(15分钟)
--   login:pwd:fail:{phone}     window=900(15分钟)
--   password:code:fail:{phone} window=900(15分钟)
--   admin:login:fail:{phone}   window=900(15分钟)
-- ============================================================

local key = KEYS[1]
local windowSeconds = tonumber(ARGV[1])

if (not key or not windowSeconds or windowSeconds <= 0) then
    return -2
end

local currentCount = redis.call("INCR", key)

if (currentCount == 1) then
    redis.call("PEXPIRE", key, windowSeconds * 1000)
end

return currentCount
