package com.echo.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 秒杀活动实时健康监控服务
 * <p>
 * 每10秒检查Redis库存消耗和MQ积压情况，连续异常触发严重告警
 *
 * @author tulip
 */
@Slf4j
@Component
public class SeckillMonitorService {

    /** Redis字符串操作模板 */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 时间格式化器 */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 连续告警计数器(防误报：连续3次才触发严重告警) */
    private int consecutiveAlertCount = 0;

    /** MQ积压告警阈值(条) */
    private static final long MQ_PENDING_THRESHOLD = 1000L;

    /** 库存消耗率告警阈值(%) */
    private static final double CONSUMPTION_RATE_THRESHOLD = 80.0;

    /** 严重告警触发次数阈值 */
    private static final int CRITICAL_ALERT_COUNT = 3;

    /**
     * 定时监控任务(每10秒执行一次)
     */
    @Scheduled(fixedRate = 10000)
    public void monitorSeckillHealth() {
        try {
            Map<String, Object> metrics = collectMetrics();
            analyzeMetrics(metrics);
            logMetrics(metrics);
        } catch (Exception e) {
            log.error("[秒杀监控] 监控执行异常:{}", e.getMessage());
        }
    }

    /**
     * 采集秒杀相关指标
     *
     * @return 指标数据Map
     */
    private Map<String, Object> collectMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        collectStockMetrics(metrics);
        collectMQMetrics(metrics);
        metrics.put("checkTime", LocalDateTime.now().format(FORMATTER));

        return metrics;
    }

    /**
     * 采集库存指标(使用SCAN命令避免阻塞Redis)
     *
     * @param metrics 指标容器
     */
    private void collectStockMetrics(Map<String, Object> metrics) {
        try {
            Set<String> stockKeys = scanSeckillStockKeys();
            Long stockKeyCount = (stockKeys != null) ? (long) stockKeys.size() : 0L;

            long totalCurrentStock = 0;

            if (stockKeyCount != null && stockKeyCount > 0) {
                for (String stockKey : stockKeys) {
                    try {
                        String stockStr = stringRedisTemplate.opsForValue().get(stockKey);
                        if (stockStr != null && !stockStr.isEmpty()) {
                            totalCurrentStock += Long.parseLong(stockStr);
                        }
                    } catch (Exception ex) {
                        log.debug("[秒杀监控] 读取库存Key异常 - key:{}, error:{}", stockKey, ex.getMessage());
                    }
                }

                metrics.put("activeSeckillItems", stockKeyCount);
                metrics.put("totalCurrentStock", totalCurrentStock);

                double consumptionRate = calculateConsumptionRate(totalCurrentStock, stockKeyCount);
                metrics.put("consumptionRate", consumptionRate);
            }
        } catch (Exception e) {
            log.warn("[秒杀监控] 收集库存指标异常:{}", e.getMessage());
        }
    }

    /**
     * 使用SCAN命令渐进式扫描秒杀库存Key(避免keys()导致的Redis阻塞)
     *
     * @return 匹配的Key集合
     */
    private Set<String> scanSeckillStockKeys() {
        final Set<String> keys = new HashSet<>();
        try {
            stringRedisTemplate.execute((RedisConnection connection) -> {
                var cursor = connection.scan(
                        ScanOptions.scanOptions()
                                .match("seckill:stock:*")
                                .count(100)
                                .build()
                );
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next()));
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("[秒杀监控] SCAN操作异常:{}", e.getMessage());
        }
        return keys;
    }

    /**
     * 计算库存消耗率(基于当前绝对值判断)
     *
     * @param currentStock 当前总库存
     * @param itemCount 秒杀商品数量
     * @return 消耗率百分比(0-100)
     */
    private double calculateConsumptionRate(long currentStock, long itemCount) {
        if (itemCount == 0 || currentStock <= 0) {
            return 0.0;
        }

        long estimatedInitialStock = currentStock + Math.max(currentStock, itemCount * 100);
        double rate = (double)(estimatedInitialStock - currentStock) / estimatedInitialStock * 100;
        return Math.round(rate * 100.0) / 100.0;
    }

    /**
     * 采集MQ积压指标
     *
     * @param metrics 指标容器
     */
    private void collectMQMetrics(Map<String, Object> metrics) {
        try {
            String pendingOrderKey = "mq:seckill:pending:count";
            String pendingCountStr = stringRedisTemplate.opsForValue().get(pendingOrderKey);
            if (pendingCountStr != null) {
                metrics.put("mqPendingMessages", Long.parseLong(pendingCountStr));
            }
        } catch (Exception e) {
            log.warn("[秒杀监控] 收集MQ指标异常:{}", e.getMessage());
        }
    }

    /**
     * 分析指标并判断是否触发告警
     *
     * @param metrics 采集到的指标数据
     */
    private void analyzeMetrics(Map<String, Object> metrics) {
        boolean alertTriggered = false;
        StringBuilder alertMessage = new StringBuilder("[秒杀告警] ");

        if (isMQOverloaded(metrics)) {
            Object mqPending = metrics.get("mqPendingMessages");
            alertMessage.append(String.format("MQ积压过多(%d条); ", mqPending));
            alertTriggered = true;
        }

        if (isStockConsumingTooFast(metrics)) {
            Object consumptionRate = metrics.get("consumptionRate");
            alertMessage.append(String.format("库存消耗过快(%.1f%%); ", consumptionRate));
            alertTriggered = true;
        }

        handleAlertResult(alertTriggered, alertMessage, metrics);
    }

    /**
     * 判断MQ是否积压过多
     *
     * @param metrics 指标数据
     * @return true=积压过多
     */
    private boolean isMQOverloaded(Map<String, Object> metrics) {
        Object mqPending = metrics.get("mqPendingMessages");
        return mqPending != null && (Long) mqPending > MQ_PENDING_THRESHOLD;
    }

    /**
     * 判断库存消耗是否过快
     *
     * @param metrics 指标数据
     * @return true=消耗过快
     */
    private boolean isStockConsumingTooFast(Map<String, Object> metrics) {
        Object consumptionRate = metrics.get("consumptionRate");
        return consumptionRate != null && (Double) consumptionRate > CONSUMPTION_RATE_THRESHOLD;
    }

    /**
     * 处理告警结果(连续3次触发严重告警)
     *
     * @param alertTriggered 是否触发告警
     * @param alertMessage 告警消息
     * @param metrics 当前指标
     */
    private void handleAlertResult(boolean alertTriggered, StringBuilder alertMessage, Map<String, Object> metrics) {
        if (alertTriggered) {
            consecutiveAlertCount++;
            log.error(alertMessage.toString());

            if (consecutiveAlertCount >= CRITICAL_ALERT_COUNT) {
                sendCriticalAlert(alertMessage.toString(), metrics);
                consecutiveAlertCount = 0;
            }
        } else {
            if (consecutiveAlertCount > 0) {
                log.info("[秒杀恢复] 告警已解除，系统恢复正常");
                consecutiveAlertCount = 0;
            }
        }
    }

    /**
     * 发送严重告警(连续3次异常后触发)
     *
     * @param message 告警消息
     * @param metrics 当前指标快照
     */
    private void sendCriticalAlert(String message, Map<String, Object> metrics) {
        log.error("[严重告警] 连续3次触发告警，需要人工介入！");
        log.error("当前指标: {}", metrics);
        log.error("建议: 1.检查MQ消费者是否正常 2.考虑限流或暂停秒杀 3.准备降级方案");
    }

    /**
     * 记录指标日志(DEBUG级别)
     *
     * @param metrics 指标数据
     */
    private void logMetrics(Map<String, Object> metrics) {
        if (log.isDebugEnabled()) {
            log.debug("[秒杀监控] {}", metrics);
        }
    }
}
