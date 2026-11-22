-- ============================================================
-- 清理 JMeter 压测产生的测试账号
-- 号段：19900000001 - 19900001000（1000 个），昵称「压测用户xx」，密码统一 123456
-- 执行前建议先确认这些账号没有真实业务数据
-- ============================================================

-- 1. 查看将被清理的账号数量
SELECT COUNT(*) AS bench_users FROM tb_user WHERE phone LIKE '199%';

-- 2. 清理 Redis 侧的影子数据（在 redis-cli 里执行，票种 8）
--    DEL seckill:order:8
--    EVAL "local k=redis.call('keys','seckill:idem:8:*') for i=1,#k do redis.call('del',k[i]) end return #k" 0
--    EVAL "local k=redis.call('keys','seckill:queue:result:8:*') for i=1,#k do redis.call('del',k[i]) end return #k" 0

-- 3. 删除账号（保留 132 号段的真实账号）
DELETE FROM tb_user WHERE phone LIKE '199%';

-- 4. 校验
SELECT COUNT(*) AS remain_users FROM tb_user;
