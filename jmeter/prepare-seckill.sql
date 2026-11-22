-- ============================================================
-- Echo 秒杀压测数据准备（MySQL 部分）
-- 作用：把指定秒杀票的时间窗调整到「当前可售」，并重置库存
-- 用法：mysql -uroot -p123456 echo < prepare-seckill.sql
-- 注意：Redis 侧的库存/已购集合/幂等键需配合 reset-seckill.cmd 一起清理
-- ============================================================

-- 压测目标票种：tb_seckill_ticket.ticket_id
SET @ticket_id = 8;
-- 压测库存（同时决定可成功下单的并发上限）
SET @stock = 200;

UPDATE tb_seckill_ticket
SET begin_time = NOW() - INTERVAL 1 HOUR,
    end_time   = NOW() + INTERVAL 2 HOUR,
    stock      = @stock,
    scheduled_start_time = NULL
WHERE ticket_id = @ticket_id;

-- 票种本身必须上架，否则秒杀入口会先被票种状态拦掉
-- 秒杀库存以 tb_seckill_ticket.stock 为准，这里不改 tb_ticket.stock（那是普通售卖库存）
UPDATE tb_ticket
SET status = 1,
    locked = 0
WHERE id = @ticket_id;

-- 校验：时间窗应覆盖当前时刻
SELECT st.ticket_id,
       st.stock,
       st.begin_time,
       st.end_time,
       t.status  AS ticket_status,
       t.stock   AS ticket_stock,
       NOW()     AS now_time
FROM tb_seckill_ticket st
JOIN tb_ticket t ON t.id = st.ticket_id
WHERE st.ticket_id = @ticket_id;
