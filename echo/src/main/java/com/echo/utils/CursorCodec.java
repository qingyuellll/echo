package com.echo.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 游标编解码器(用于游标分页场景)
 *
 * @author tulip
 */
public final class CursorCodec {

    /** JSON序列化/反序列化器（线程安全，全局共享） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 私有构造函数（防止实例化） */
    private CursorCodec() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 编码游标信息为不透明字符串
     *
     * @param cursorInfo 游标信息对象
     * @return Base64编码的游标字符串
     */
    public static String encode(CursorInfo cursorInfo) {
        if (cursorInfo == null) {
            throw new IllegalArgumentException("游标信息不能为null");
        }

        try {
            Map<String, Object> data = buildDataMap(cursorInfo);
            String json = serializeToJson(data);
            return encodeToBase64(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("游标编码失败", e);
        }
    }

    /**
     * 构建数据Map（从CursorInfo提取字段）
     *
     * @param cursorInfo 游标信息对象
     * @return 包含lastId和lastTime的Map
     */
    private static Map<String, Object> buildDataMap(CursorInfo cursorInfo) {
        Map<String, Object> data = new HashMap<>();
        data.put("lastId", cursorInfo.getLastId());
        data.put("lastTime", cursorInfo.getLastTime());
        return data;
    }

    /**
     * 将Map序列化为JSON字符串
     *
     * @param data 数据Map
     * @return JSON字符串
     * @throws JsonProcessingException 序列化失败
     */
    private static String serializeToJson(Map<String, Object> data) throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(data);
    }

    /**
     * 将JSON字符串编码为URL-safe Base64
     *
     * @param json JSON字符串
     * @return Base64编码字符串（无填充）
     */
    private static String encodeToBase64(String json) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码游标字符串为游标信息对象
     * <p>
     * 解码流程：Base64字符串 → 字节 → UTF-8字符串(JSON) → Map → CursorInfo
     * <p>
     * 使用示例：
     * <pre>
     * CursorInfo decoded = CursorCodec.decode("eyJsYXN0...");
     * System.out.println(decoded.getLastId());  // 输出: 100
     * </pre>
     *
     * @param cursor Base64编码的游标字符串（由encode方法生成）
     * @return 游标信息对象（包含lastId和lastTime）
     * @throws IllegalArgumentException 如果cursor为空、格式无效或JSON解析失败
     */
    public static CursorInfo decode(String cursor) {
        validateCursorInput(cursor);

        try {
            byte[] decodedBytes = decodeBase64(cursor);
            String json = bytesToString(decodedBytes);
            Map<String, Object> data = deserializeFromJson(json);
            validateParsedData(data);
            return buildCursorInfo(data);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("游标解码失败: " + e.getMessage(), e);
        }
    }

    /**
     * 校验游标输入参数
     *
     * @param cursor 游标字符串
     * @throws IllegalArgumentException 如果cursor为null或空白字符串
     */
    private static void validateCursorInput(String cursor) {
        if (cursor == null || cursor.trim().isEmpty()) {
            throw new IllegalArgumentException("游标不能为空");
        }
    }

    /**
     * Base64解码
     *
     * @param cursor Base64字符串
     * @return 解码后的字节数组
     */
    private static byte[] decodeBase64(String cursor) {
        return Base64.getUrlDecoder().decode(cursor);
    }

    /**
     * 字节数组转为UTF-8字符串
     *
     * bytes 字节数组
     * @return UTF-8字符串
     */
    private static String bytesToString(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * JSON反序列化为Map
     *
     * @param json JSON字符串
     * @return 数据Map
     * @throws JsonProcessingException 反序列化失败
     */
    private static Map<String, Object> deserializeFromJson(String json) throws JsonProcessingException {
        return OBJECT_MAPPER.readValue(json,
                new TypeReference<Map<String, Object>>() {});
    }

    /**
     * 校验解析后的数据
     *
     * @param data 数据Map
     * @throws IllegalArgumentException 如果data为null
     */
    private static void validateParsedData(Map<String, Object> data) {
        if (data == null) {
            throw new IllegalArgumentException("游标JSON解析失败");
        }
    }

    /**
     * 从Map构建CursorInfo对象
     *
     * @param data 数据Map
     * @return CursorInfo对象
     */
    private static CursorInfo buildCursorInfo(Map<String, Object> data) {
        CursorInfo cursorInfo = new CursorInfo();
        cursorInfo.setLastId(extractLongValue(data, "lastId"));
        cursorInfo.setLastTime(extractStringValue(data, "lastTime"));
        return cursorInfo;
    }

    /**
     * 安全提取Long值（兼容Integer/Long/Double等数字类型）
     *
     * @param data 数据Map
     * @param key  键名
     * @return Long值
     */
    private static Long extractLongValue(Map<String, Object> data, String key) {
        return ((Number) data.get(key)).longValue();
    }

    /**
     * 安全提取String值
     *
     * @param data 数据Map
     * @param key  键名
     * @return String值
     */
    private static String extractStringValue(Map<String, Object> data, String key) {
        return (String) data.get(key);
    }

    /**
     * 检查游标字符串是否有效（不抛异常的校验方式）
     * <p>
     * 内部调用decode()方法进行完整校验，捕获所有异常并返回false。
     * 适用于需要快速判断游标合法性但不关心具体错误信息的场景。
     * <p>
     * 使用示例：
     * <pre>
     * if (StringUtils.hasText(cursor) && !CursorCodec.isValid(cursor)) {
     *     return Result.fail("无效的游标参数");
     * }
     * </pre>
     *
     * @param cursor 待校验的游标字符串
     * @return true=游标格式有效且可正常解码, false=游标为null/空或格式无效
     */
    public static boolean isValid(String cursor) {
        try {
            decode(cursor);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 游标信息数据类（DTO）
     * <p>
     * 存储分页位置信息，包含两个核心字段：
     * <ul>
     *     <li>lastId: 上一页最后一条记录的主键ID（用于WHERE条件）</li>
     *     <li>lastTime: 上一页最后一条记录的时间戳（辅助排序，处理ID不连续场景）</li>
     * </ul>
     */
    public static class CursorInfo {

        /** 上一页最后一条记录的主键ID */
        private Long lastId;

        /** 上一页最后一条记录的时间戳（格式：yyyy-MM-dd HH:mm:ss） */
        private String lastTime;

        /**
         * 获取最后记录ID
         *
         * @return 主键ID
         */
        public Long getLastId() {
            return lastId;
        }

        /**
         * 设置最后记录ID
         *
         * @param lastId 主键ID
         */
        public void setLastId(Long lastId) {
            this.lastId = lastId;
        }

        /**
         * 获取最后记录时间
         *
         * @return 时间戳字符串
         */
        public String getLastTime() {
            return lastTime;
        }

        /**
         * 设置最后记录时间
         *
         * @param lastTime 时间戳字符串
         */
        public void setLastTime(String lastTime) {
            this.lastTime = lastTime;
        }

        /**
         * 转换为字符串表示（用于日志输出）
         *
         * @return 格式化的字符串
         */
        @Override
        public String toString() {
            return "CursorInfo{" +
                    "lastId=" + lastId +
                    ", lastTime='" + lastTime + '\'' +
                    '}';
        }
    }
}
