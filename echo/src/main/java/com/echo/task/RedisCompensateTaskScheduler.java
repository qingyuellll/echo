package com.echo.task;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.echo.monitor.OrderMetricsService;
import com.echo.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis数据补偿任务调度器(异步补偿机制)
 * <p>
 * 自动修复MySQL成功但Redis失败的数据不一致，保证最终一致性
 *
 * @author tulip
 */
@Slf4j
@Component
public class RedisCompensateTaskScheduler {

    /** 补偿任务Key前缀 */
    private static final String TASK_KEY_PREFIX = "compensate:redis:task:";

    /** 分布式锁Key(防止多实例并发) */
    private static final String SCHEDULER_LOCK_KEY = "compensate:scheduler:lock";

    /** 任务处理中标记后缀(防重复处理) */
    private static final String PROCESSING_MARK_SUFFIX = ":processing";

    /** 最大重试次数 */
    private static final int MAX_RETRY_COUNT = 3;

    /** 单次处理最大任务数(防止阻塞) */
    private static final int MAX_TASKS_PER_RUN = 50;

    /** 定时任务执行间隔(毫秒) */
    private static final long SCHEDULE_INTERVAL_MS = 30000;

    /** 分布式锁超时时间(秒) */
    private static final long LOCK_TIMEOUT_SECONDS = 25;

    /** 任务处理中标记TTL(分钟) */
    private static final long PROCESSING_MARK_TTL_MINUTES = 5;

    /** 任务默认TTL(小时)，防止永久残留 */
    private static final long TASK_DEFAULT_TTL_HOURS = 24;

    /** 默认退款数量 */
    private static final int DEFAULT_QUANTITY = 1;

    /** 任务状态：待处理 */
    private static final int TASK_STATUS_PENDING = 0;

    /** 秒杀退款任务类型 */
    private static final String TASK_TYPE_REFUND_SECKILL = "REFUND_SECKILL_RELEASE";

    /** Lua脚本返回码：成功 */
    private static final long LUA_RESULT_SUCCESS = 0;

    /** 告警通道：系统告警Pub/Sub频道 */
    private static final String ALERT_PUBSUB_CHANNEL = "channel:system:alert";

    /** 告警Redis Key前缀 */
    private static final String ALERT_KEY_PREFIX = "compensate:alerts:";

    /** 告警保留天数 */
    private static final long ALERT_RETENTION_DAYS = 7;

    /** SCAN每次扫描数量(建议值为MAX_TASKS_PER_RUN的2倍) */
    private static final int SCAN_COUNT = MAX_TASKS_PER_RUN * 2;

    /** Redis模板(任务CRUD/Lua执行/SCAN/Pub/Sub) */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 秒杀退款Lua脚本(原子操作：INCRBY+SREM) */
    @Resource
    private DefaultRedisScript<Long> seckillRefundScript;

    /** 订单指标监控服务(可选) */
    @Resource
    private OrderMetricsService orderMetricsService;

    /** 总处理数 */
    private long totalProcessedCount = 0L;

    /** 总成功数 */
    private long totalSuccessCount = 0L;

    /** 总失败数 */
    private long totalFailedCount = 0L;

    /** 上次执行时间 */
    private LocalDateTime lastExecuteTime = null;

    /**
     * 定时扫描并执行待处理的补偿任务(每30秒)
     * <p>
     * 执行流程：
     * 1. 获取分布式锁(防止多实例并发)
     * 2. SCAN扫描所有补偿任务Key
     * 3. 过滤出到达执行时间的待处理任务
     * 4. 逐个执行补偿逻辑(标记处理中→执行→清理标记)
     * 5. 根据结果删除任务或更新重试信息
     * 6. 释放分布式锁并记录统计
     */
    @Scheduled(fixedRate = SCHEDULE_INTERVAL_MS)
    public void processPendingCompensateTasks() {
        long startTime = System.currentTimeMillis();
        TaskStatistics stats = new TaskStatistics();

        try {
            if (!acquireSchedulerLock()) {
                return;
            }

            Set<String> taskKeys = scanTaskKeys();
            if (taskKeys.isEmpty()) {
                log.debug("[补偿任务] 当前无待处理任务");
                return;
            }

            log.info("[补偿任务] 发现{}个候选任务", taskKeys.size());
            processTasks(taskKeys, stats);

        } catch (Exception e) {
            log.error("[补偿任务] 执行异常:{}", e.getMessage(), e);
        } finally {
            releaseSchedulerLock();
            updateAndLogStatistics(stats, startTime);
        }
    }

    /**
     * 获取调度器分布式锁
     *
     * @return true=获取成功, false=获取失败
     */
    private boolean acquireSchedulerLock() {
        try {
            Boolean lockAcquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(SCHEDULER_LOCK_KEY,
                            String.valueOf(System.currentTimeMillis()),
                            LOCK_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS);

            if (Boolean.TRUE.equals(lockAcquired)) {
                log.debug("[补偿任务] 已获取分布式锁");
                return true;
            }

            log.debug("[补偿任务] 未获取到分布式锁，跳过");
            return false;
        } catch (Exception e) {
            log.warn("[补偿任务] 获取锁异常，继续执行 - error:{}", e.getMessage());
            return true;  // 锁异常时不阻塞主流程
        }
    }

    /**
     * 释放调度器分布式锁
     */
    private void releaseSchedulerLock() {
        try {
            stringRedisTemplate.delete(SCHEDULER_LOCK_KEY);
            log.debug("[补偿任务] 已释放分布式锁");
        } catch (Exception e) {
            log.warn("[补偿任务] 释放锁异常 - error:{}", e.getMessage());
        }
    }

    /**
     * 使用SCAN命令扫描所有补偿任务Key(生产环境最佳实践)
     *
     * @return 任务Key集合(可能为空)
     */
    private Set<String> scanTaskKeys() {
        Set<String> taskKeys = new HashSet<>();

        try {
            stringRedisTemplate.execute((org.springframework.data.redis.connection.RedisConnection connection) -> {
                org.springframework.data.redis.core.Cursor<byte[]> cursor = connection.scan(
                        org.springframework.data.redis.core.ScanOptions.scanOptions()
                                .match(TASK_KEY_PREFIX + "*")
                                .count(SCAN_COUNT)
                                .build()
                );
                while (cursor.hasNext()) {
                    taskKeys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
                return null;
            });
        } catch (Exception scanEx) {
            log.warn("[补偿任务] SCAN失败，降级为keys() - error:{}", scanEx.getMessage());
            fallbackToKeysCommand(taskKeys);
        }

        return taskKeys;
    }

    /**
     * 降级方案：使用keys()命令(SCAN失败时使用)
     *
     * @param taskKeys 任务Key集合(用于填充结果)
     */
    private void fallbackToKeysCommand(Set<String> taskKeys) {
        try {
            Set<String> keys = stringRedisTemplate.keys(TASK_KEY_PREFIX + "*");
            if (keys != null) {
                taskKeys.addAll(keys);
            }
        } catch (Exception keysEx) {
            log.error("[补偿任务] keys()也失败，终止本轮 - error:{}", keysEx.getMessage());
        }
    }

    /**
     * 处理所有候选任务
     *
     * @param taskKeys 候选任务Key集合
     * @param stats   统计信息
     */
    private void processTasks(Set<String> taskKeys, TaskStatistics stats) {
        for (String taskKey : taskKeys) {
            if (stats.processedCount >= MAX_TASKS_PER_RUN) {
                log.warn("[补偿任务] 达到单次处理上限({})，剩余下轮处理", MAX_TASKS_PER_RUN);
                break;
            }

            processSingleTask(taskKey, stats);
        }
    }

    /**
     * 处理单个补偿任务
     *
     * @param taskKey 任务Key
     * @param stats  统计信息
     */
    private void processSingleTask(String taskKey, TaskStatistics stats) {
        try {
            Map<String, Object> task = fetchTask(taskKey);

            if (task == null) {
                cleanInvalidTask(taskKey);
                stats.skippedCount++;
                return;
            }

            if (!isTaskReadyForExecution(task)) {
                stats.skippedCount++;
                return;
            }

            if (!markTaskAsProcessing(taskKey)) {
                log.info("[补偿任务] 任务正在处理中，跳过 - taskKey:{}", taskKey);
                stats.skippedCount++;
                return;
            }

            stats.processedCount++;
            boolean success = executeCompensateTask(taskKey, task);
            clearProcessingMark(taskKey);

            if (success) {
                stats.successCount++;
            } else {
                stats.failedCount++;
            }

        } catch (Exception e) {
            stats.failedCount++;
            clearProcessingMark(extractTaskIdFromKey(taskKey));
            log.error("[补偿任务] 处理异常 - taskKey:{}, error:{}", taskKey, e.getMessage(), e);
        }
    }

    /**
     * 从Redis获取并解析任务详情
     *
     * @param taskKey 任务Key
     * @return 任务Map，解析失败返回null
     */
    private Map<String, Object> fetchTask(String taskKey) {
        try {
            String taskJson = stringRedisTemplate.opsForValue().get(taskKey);
            if (taskJson == null || taskJson.trim().isEmpty()) {
                return null;
            }

            return JSON.parseObject(taskJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("[补偿任务] 解析JSON失败 - taskKey:{}, error:{}", taskKey, e.getMessage());
            return null;
        }
    }

    /**
     * 清理无效任务(解析失败的脏数据)
     *
     * @param taskKey 任务Key
     */
    private void cleanInvalidTask(String taskKey) {
        try {
            stringRedisTemplate.delete(taskKey);
            log.debug("[补偿任务] 已删除无效任务 - taskKey:{}", taskKey);
        } catch (Exception e) {
            log.warn("[补偿任务] 删除无效任务失败 - taskKey:{}, error:{}", taskKey, e.getMessage());
        }
    }

    /**
     * 检查任务是否满足执行条件(状态+时间)
     *
     * @param task 任务详情
     * @return true=可以执行, false=跳过
     */
    private boolean isTaskReadyForExecution(Map<String, Object> task) {
        Integer status = getIntValue(task, "status", -1);
        if (status == null || status != TASK_STATUS_PENDING) {
            return false;
        }

        LocalDateTime nextExecuteTime = parseNextExecuteTime(task);
        if (nextExecuteTime == null || LocalDateTime.now().isBefore(nextExecuteTime)) {
            return false;
        }

        return true;
    }

    /**
     * 解析下次执行时间
     *
     * @param task 任务详情
     * @return 下次执行时间，解析失败返回null
     */
    private LocalDateTime parseNextExecuteTime(Map<String, Object> task) {
        try {
            Object nextTimeObj = task.get("nextExecuteTime");
            if (nextTimeObj == null) {
                return null;
            }
            return LocalDateTime.parse(nextTimeObj.toString());
        } catch (Exception e) {
            log.warn("[补偿任务] 解析nextExecuteTime失败 - error:{}", e.getMessage());
            return null;
        }
    }

    /**
     * 标记任务为处理中(防止重复处理)
     *
     * @param taskKey 任务Key
     * @return true=标记成功, false=已被其他线程标记
     */
    private boolean markTaskAsProcessing(String taskKey) {
        try {
            String processingKey = taskKey + PROCESSING_MARK_SUFFIX;
            Boolean marked = stringRedisTemplate.opsForValue()
                    .setIfAbsent(processingKey, "1", PROCESSING_MARK_TTL_MINUTES, TimeUnit.MINUTES);
            return Boolean.TRUE.equals(marked);
        } catch (Exception e) {
            log.warn("[补偿任务] 设置处理标记异常 - taskKey:{}, error:{}", taskKey, e.getMessage());
            return false;  // 标记失败不阻塞主流程
        }
    }

    /**
     * 清理任务处理中标记
     *
     * @param taskKey 任务Key(可为null)
     */
    private void clearProcessingMark(String taskKey) {
        if (taskKey == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(taskKey + PROCESSING_MARK_SUFFIX);
        } catch (Exception e) {
            log.warn("[补偿任务] 清理处理标记异常 - taskKey:{}, error:{}", taskKey, e.getMessage());
        }
    }

    /**
     * 执行单个补偿任务
     * <p>
     * 执行流程：
     * 1. 根据任务类型调用对应的补偿逻辑
     * 2. 成功 → 删除任务
     * 3. 失败 → 更新重试次数和下次执行时间(指数退避)
     * 4. 超过最大重试次数 → 发送告警并删除任务
     *
     * @param taskKey Redis中的任务Key
     * @param task   任务详情
     * @return true=执行成功, false=执行失败或需要重试
     */
    private boolean executeCompensateTask(String taskKey, Map<String, Object> task) {
        Long taskId = getLongValue(task, "id");
        Long ticketId = getLongValue(task, "ticketId");
        Long userId = getLongValue(task, "userId");
        String taskType = getStringValue(task, "taskType");
        int retryCount = getIntValue(task, "retryCount", 0);

        logInfoTaskStart(taskId, taskType, ticketId, userId, retryCount);

        boolean executeResult = false;

        try {
            executeResult = dispatchByTaskType(taskType, ticketId, userId, task, taskId);
        } catch (Exception e) {
            log.error("[补偿任务-执行异常] taskId:{}, error:{}", taskId, e.getMessage(), e);
            executeResult = false;
        }

        return handleExecuteResult(executeResult, taskKey, task, taskId, retryCount);
    }

    /**
     * 记录任务开始执行日志
     */
    private void logInfoTaskStart(Long taskId, String taskType, Long ticketId, Long userId, int retryCount) {
        log.info("[补偿任务-开始执行] taskId:{}, type:{}, ticketId:{}, userId:{}, 第{}次重试",
                taskId, taskType, ticketId, userId, retryCount + 1);
    }

    /**
     * 根据任务类型分发到具体的补偿逻辑
     *
     * @param taskType 任务类型
     * @param ticketId 票种ID
     * @param userId   用户ID
     * @param task     原始任务信息
     * @param taskId   任务ID
     * @return 执行结果
     */
    private boolean dispatchByTaskType(String taskType, Long ticketId, Long userId,
                                       Map<String, Object> task, Long taskId) {
        switch (taskType) {
            case TASK_TYPE_REFUND_SECKILL:
                return executeSeckillRefundCompensate(ticketId, userId, task);

            default:
                log.error("[补偿任务] 未知任务类型:{}", taskType);
                sendAlert(buildUnknownTypeAlertMessage(taskId, taskType));
                return false;
        }
    }

    /**
     * 处理执行结果(成功/失败重试/达到上限告警)
     *
     * @param executeResult 执行结果
     * @param taskKey       任务Key
     * @param task          任务详情
     * @param taskId        任务ID
     * @param retryCount    当前重试次数
     * @return 最终结果(true=成功, false=失败)
     */
    private boolean handleExecuteResult(boolean executeResult, String taskKey,
                                        Map<String, Object> task, Long taskId, int retryCount) {
        if (executeResult) {
            log.info("[补偿任务-执行成功] taskId:{}，已自动修复Redis数据", taskId);
            deleteTask(taskKey);
            return true;
        }

        return handleFailure(taskKey, task, taskId, retryCount);
    }

    /**
     * 处理执行失败(重试或告警)
     *
     * @param taskKey    任务Key
     * @param task       任务详情
     * @param taskId     任务ID
     * @param retryCount 当前重试次数
     * @return false(始终返回false，因为这是失败处理)
     */
    private boolean handleFailure(String taskKey, Map<String, Object> task, Long taskId, int retryCount) {
        int newRetryCount = retryCount + 1;

        if (newRetryCount >= MAX_RETRY_COUNT) {
            log.error("[补偿任务-达到最大重试次数] taskId:{}，已重试{}次，转为人工处理",
                    taskId, newRetryCount);
            sendAlert(buildFailureAlertMessage(task));
            deleteTask(taskKey);
            return false;
        }

        scheduleRetry(taskKey, task, newRetryCount);
        return false;
    }

    /**
     * 安排下一次重试(指数退避)
     *
     * @param taskKey      任务Key
     * @param task         任务详情
     * @param newRetryCount 新的重试次数
     */
    private void scheduleRetry(String taskKey, Map<String, Object> task, int newRetryCount) {
        long delayMinutes = (long) Math.pow(2, newRetryCount);
        LocalDateTime nextTime = LocalDateTime.now().plusMinutes(delayMinutes);

        task.put("retryCount", newRetryCount);
        task.put("status", TASK_STATUS_PENDING);
        task.put("nextExecuteTime", nextTime);
        task.put("lastErrorTime", LocalDateTime.now());
        task.put("lastRetryTime", LocalDateTime.now());

        writeTaskBack(taskKey, task);

        log.warn("[补偿任务-等待重试] taskId:{}, 第{}次重试，将在{}分钟后执行",
                getLongValue(task, "id"), newRetryCount, delayMinutes);
    }

    /**
     * 将任务写回Redis(更新重试信息)
     *
     * @param taskKey 任务Key
     * @param task    任务详情
     */
    private void writeTaskBack(String taskKey, Map<String, Object> task) {
        try {
            stringRedisTemplate.opsForValue().set(
                    taskKey,
                    JSON.toJSONString(task),
                    TASK_DEFAULT_TTL_HOURS,
                    TimeUnit.HOURS
            );
        } catch (Exception e) {
            log.error("[补偿任务] 写回任务失败 - taskKey:{}, error:{}", taskKey, e.getMessage());
        }
    }

    /**
     * 删除已完成的任务
     *
     * @param taskKey 任务Key
     */
    private void deleteTask(String taskKey) {
        try {
            stringRedisTemplate.delete(taskKey);
        } catch (Exception e) {
            log.warn("[补偿任务] 删除任务失败 - taskKey:{}, error:{}", taskKey, e.getMessage());
        }
    }

    /**
     * 执行秒杀退款补偿(释放库存+清除用户记录)
     *
     * @param ticketId 票种ID
     * @param userId   用户ID
     * @param task     原始任务信息
     * @return true=补偿成功, false=补偿失败
     */
    private boolean executeSeckillRefundCompensate(Long ticketId, Long userId, Map<String, Object> task) {
        try {
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + ticketId;
            String orderKey = RedisConstants.SECKILL_ORDER_KEY + ticketId;
            int quantity = extractQuantity(task);

            Long result = executeSeckillRefundLua(stockKey, orderKey, userId, quantity);

            if (result != null && result == LUA_RESULT_SUCCESS) {
                log.info("[补偿任务-秒杀退款成功] 库存已释放{}张，用户记录已清除 - ticketId:{}, userId:{}",
                        quantity, ticketId, userId);
                return true;
            } else {
                log.warn("[补偿任务-秒杀退款失败] Lua返回错误码:{} - ticketId:{}, userId:{}, quantity:{}",
                        result, ticketId, userId, quantity);
                return false;
            }
        } catch (Exception e) {
            log.error("[补偿任务-秒杀退款异常] - ticketId:{}, userId:{}, error:{}",
                    ticketId, userId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 从任务中提取退款数量
     *
     * @param task 任务详情
     * @return 退款数量
     */
    private int extractQuantity(Map<String, Object> task) {
        if (task.get("quantity") == null) {
            return DEFAULT_QUANTITY;
        }
        return ((Number) task.get("quantity")).intValue();
    }

    /**
     * 执行秒杀退款Lua脚本
     *
     * @param stockKey 库存Key
     * @param orderKey 用户集合Key
     * @param userId   用户ID
     * @param quantity 数量
     * @return Lua脚本返回值
     */
    private Long executeSeckillRefundLua(String stockKey, String orderKey, Long userId, int quantity) {
        return stringRedisTemplate.execute(
                seckillRefundScript,
                Arrays.asList(stockKey, orderKey),
                String.valueOf(userId),
                String.valueOf(quantity)
        );
    }

    /**
     * 发送多通道告警通知
     * <p>
     * 三通道冗余设计：
     * 1. 日志记录(始终生效)
     * 2. Redis Pub/Sub(实时通知)
     * 3. Redis List持久化(确保不丢失)
     *
     * @param message 告警内容
     */
    private void sendAlert(String message) {
        logAlert(message);
        sendAlertViaPubSub(message);
        persistAlert(message);
        logInfoAlertCompleted();
    }

    /**
     * 通道1：记录告警日志
     */
    private void logAlert(String message) {
        log.error("[补偿任务-紧急告警] {}", message);
    }

    /**
     * 通道2：通过Redis Pub/Sub发送实时告警
     *
     * @param message 告警内容
     */
    private void sendAlertViaPubSub(String message) {
        try {
            stringRedisTemplate.convertAndSend(
                    ALERT_PUBSUB_CHANNEL,
                    buildAlertJson(message)
            );
            log.debug("[补偿任务-告警-PubSub已发送]");
        } catch (Exception e) {
            log.warn("[补偿任务-告警-PubSub发送失败 - error:{}", e.getMessage());
        }
    }

    /**
     * 通道3：持久化告警到Redis List(保留7天)
     *
     * @param message 告警内容
     */
    private void persistAlert(String message) {
        try {
            String alertKey = ALERT_KEY_PREFIX + LocalDate.now().toString();
            stringRedisTemplate.opsForList().rightPush(alertKey, buildAlertJson(message));
            stringRedisTemplate.expire(alertKey, ALERT_RETENTION_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("[补偿任务-告警-持久化失败 - error:{}", e.getMessage());
        }
    }

    /**
     * 记录告警完成日志
     */
    private void logInfoAlertCompleted() {
        log.info("[补偿任务-告警已完成多通道发送] channels=[LOG, PUBSUB, PERSIST]");
    }

    /**
     * 构建告警JSON
     *
     * @param message 告警消息
     * @return JSON字符串
     */
    private String buildAlertJson(String message) {
        return JSON.toJSONString(Map.of(
                "level", "URGENT",
                "source", "REDIS_COMPENSATE_TASK",
                "type", "COMPENSATE_MAX_RETRY_EXCEEDED",
                "message", message,
                "timestamp", System.currentTimeMillis(),
                "needManualIntervention", true
        ));
    }

    /**
     * 构建未知任务类型的告警消息
     *
     * @param taskId   任务ID
     * @param taskType 任务类型
     * @return 格式化的告警消息
     */
    private String buildUnknownTypeAlertMessage(Long taskId, String taskType) {
        return String.format("发现未知类型的补偿任务：taskId=%d, type=%s", taskId, taskType);
    }

    /**
     * 构建达到最大重试次数的详细告警消息(包含修复步骤)
     *
     * @param task 失败的任务
     * @return 格式化的告警消息
     */
    private String buildFailureAlertMessage(Map<String, Object> task) {
        Long taskId = getLongValue(task, "id");
        Long ticketId = getLongValue(task, "ticketId");
        Long userId = getLongValue(task, "userId");
        String taskType = getStringValue(task, "taskType");
        String errorMessage = getStringValue(task, "errorMessage");
        int retryCount = getIntValue(task, "retryCount", 0);

        return String.format(
                "【紧急】Redis补偿任务达到最大重试次数！\n\n" +
                "任务ID: %d\n" +
                "任务类型: %s\n" +
                "票种ID: %d\n" +
                "用户ID: %d\n" +
                "原始错误: %s\n" +
                "已重试次数: %d/%d\n\n" +
                "请立即手动检查以下Redis Key的一致性：\n" +
                "  - %s%d (库存)\n" +
                "  - %s%d (用户集合)\n\n" +
                "建议操作：\n" +
                "  1. 查看当前值：GET %s%d\n" +
                "  2. 查看用户集合：SMEMBERS %s%d\n" +
                "  3. 如需手动修复：INCRBY %s%d 1 && SREM %s%d %d",
                taskId, taskType, ticketId, userId, errorMessage, retryCount, MAX_RETRY_COUNT,
                RedisConstants.SECKILL_STOCK_KEY, ticketId,
                RedisConstants.SECKILL_ORDER_KEY, ticketId,
                RedisConstants.SECKILL_STOCK_KEY, ticketId,
                RedisConstants.SECKILL_ORDER_KEY, ticketId,
                RedisConstants.SECKILL_STOCK_KEY, ticketId,
                RedisConstants.SECKILL_ORDER_KEY, ticketId, userId
        );
    }

    /**
     * 从任务Key中提取任务ID
     *
     * @param taskKey 完整的任务Key
     * @return 任务ID字符串，解析失败返回null
     */
    private String extractTaskIdFromKey(String taskKey) {
        if (taskKey == null || !taskKey.startsWith(TASK_KEY_PREFIX)) {
            return null;
        }
        return taskKey.substring(TASK_KEY_PREFIX.length());
    }

    /**
     * 安全获取Long值
     *
     * @param map 数据Map
     * @param key 键名
     * @return Long值，转换失败返回null
     */
    private Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 安全获取String值
     *
     * @param map 数据Map
     * @param key 键名
     * @return String值，不存在返回null
     */
    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 安全获取int值(带默认值)
     *
     * @param map          数据Map
     * @param key          键名
     * @param defaultValue 默认值
     * @return int值
     */
    private int getIntValue(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 更新累计统计信息并记录日志
     *
     * @param stats     本轮统计
     * @param startTime 开始时间
     */
    private void updateAndLogStatistics(TaskStatistics stats, long startTime) {
        lastExecuteTime = LocalDateTime.now();
        totalProcessedCount += stats.processedCount;
        totalSuccessCount += stats.successCount;
        totalFailedCount += stats.failedCount;

        recordMetrics(stats, startTime);
        logTaskSummary(stats, startTime);
    }

    /**
     * 记录监控指标
     *
     * @param stats     统计信息
     * @param startTime 开始时间
     */
    private void recordMetrics(TaskStatistics stats, long startTime) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            orderMetricsService.recordRedisCompensationStats(
                    stats.processedCount,
                    stats.successCount,
                    stats.failedCount,
                    stats.skippedCount,
                    duration
            );
        } catch (Exception ignored) {
        }
    }

    /**
     * 记录任务执行摘要日志
     *
     * @param stats     统计信息
     * @param startTime 开始时间
     */
    private void logTaskSummary(TaskStatistics stats, long startTime) {
        if (stats.processedCount > 0) {
            long costTime = System.currentTimeMillis() - startTime;
            log.info("[补偿任务-本轮完成] 处理:{}, 成功:{}, 失败:{}, 跳过:{}, 耗时:{}ms",
                    stats.processedCount, stats.successCount, stats.failedCount, stats.skippedCount, costTime);

            double successRate = stats.processedCount > 0 ?
                    (double) stats.successCount / stats.processedCount * 100 : 0;
            log.info("[补偿任务-累计统计] 总处理:{}, 总成功:{}, 总失败:{}, 成功率:{:.1f}%",
                    totalProcessedCount, totalSuccessCount, totalFailedCount, successRate);
        }
    }

    /**
     * 获取调度器运行状态(用于监控接口)
     *
     * @return 状态统计Map
     */
    public Map<String, Object> getSchedulerStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("totalProcessedCount", totalProcessedCount);
        status.put("totalSuccessCount", totalSuccessCount);
        status.put("totalFailedCount", totalFailedCount);
        status.put("lastExecuteTime", lastExecuteTime != null ? lastExecuteTime.toString() : "从未执行");
        status.put("maxRetryCount", MAX_RETRY_COUNT);
        status.put("maxTasksPerRun", MAX_TASKS_PER_RUN);

        double successRate = totalProcessedCount > 0 ?
                Math.round((double) totalSuccessCount / totalProcessedCount * 10000) / 100.0 : 0;
        status.put("successRate", successRate);

        return status;
    }

    /**
     * 手动触发补偿任务执行(用于管理接口)
     *
     * @return 执行结果Map
     */
    public Map<String, Object> manualTrigger() {
        Map<String, Object> result = new HashMap<>();
        result.put("triggerTime", LocalDateTime.now().toString());

        long startTime = System.currentTimeMillis();

        try {
            processPendingCompensateTasks();

            result.put("status", "SUCCESS");
            result.put("message", "手动触发成功");

        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("message", "执行异常：" + e.getMessage());
            log.error("[补偿任务-手动触发失败] error:{}", e.getMessage(), e);
        }

        result.put("costTime", System.currentTimeMillis() - startTime);
        result.put("statistics", getSchedulerStatus());

        return result;
    }

    /**
     * 任务统计信息(内部使用)
     */
    private static class TaskStatistics {
        int processedCount = 0;
        int successCount = 0;
        int failedCount = 0;
        int skippedCount = 0;
    }
}
