-- ============================================================
-- 秒杀退款 Lua 脚本 - 原子性事务操作（V1.2）
--
-- 使用场景：
--   1. 管理员审批退款成功后：释放库存 + 清除用户购买记录
--   2. 秒杀订单取消/超时：释放库存 + 清除用户购买记录（支持批量数量）
--
-- 核心设计：
--   ✅ Redis 单线程执行，天然保证原子性
--   ✅ 要么全部成功，要么全部失败（无中间状态）
--   ✅ 确保释放后用户可以重新购买该秒杀票
--
-- 执行流程：
--   1. 校验库存Key存在且为有效数字
--   2. INCRBY seckill:stock:{ticketId} {quantity}  → 释放库存
--   3. SREM seckill:order:{ticketId} userId        → 删除用户购买记录（允许重新购买）
--
-- 参数说明：
--   KEYS[1] = 库存Key (seckill:stock:{ticketId})
--   KEYS[2] = 订单Set Key (seckill:order:{ticketId})
--   ARGV[1] = 用户ID (userId)
--   ARGV[2] = 释放数量 (quantity，可选，缺省 1，兼容退款单张场景)
--
-- 返回值：
--   0 = 释放成功（库存已归还 + 用户记录已清除）
--  -1 = 库存Key不存在（如Redis重启后丢失，需重新预热）
--  -2 = 库存Key存在但库存值不是数字（数据异常）
-- ============================================================

local stockKey = KEYS[1]
local orderKey = KEYS[2]
local userId = ARGV[1]
local quantity = tonumber(ARGV[2]) or 1  -- 可选参数，缺省 1

-- 步骤1: 校验库存Key是否存在（防止INCRBY对不存在的Key自动创建，导致与MySQL库存不一致）
if (redis.call("exists", stockKey) == 0) then
    return -1  -- 库存Key不存在
end

-- 步骤2: 校验库存值是否为有效数字（防御性编程）
local stockVal = redis.call("get", stockKey)
if (tonumber(stockVal) == nil) then
    return -2  -- 库存数据异常（非数字）
end

-- 步骤3: 释放秒杀票库存（INCRBY +quantity）
redis.call("incrby", stockKey, quantity)

-- 步骤4: 从订单Set中移除用户ID（SREM）- 允许用户重新购买
redis.call("srem", orderKey, userId)

return 0  -- 所有操作成功完成
