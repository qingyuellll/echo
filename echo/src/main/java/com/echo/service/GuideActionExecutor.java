package com.echo.service;

import com.echo.dto.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;

/** A confirmed browser action is executed at most once per user/request within 24 hours. */
@Service
public class GuideActionExecutor {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private static final Duration RETENTION = Duration.ofHours(24);

    public GuideActionExecutor(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis; this.mapper = mapper;
    }

    public Object execute(Long userId, String action, String requestId, Object body, Supplier<Result<?>> operation) {
        String key = key(userId, action, requestId);
        try {
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical(mapper.valueToTree(body)))));
            String pending = mapper.writeValueAsString(Map.of("fingerprint",fingerprint,"status","PENDING"));
            Boolean claimed = redis.opsForValue().setIfAbsent(key, pending, RETENTION);
            if (!Boolean.TRUE.equals(claimed)) {
                String existing = redis.opsForValue().get(key);
                if (existing == null) return Result.fail(409,"GUIDE_RETRY_STATUS","请先查询提交结果，再决定是否重试");
                JsonNode saved = mapper.readTree(existing);
                if (!fingerprint.equals(saved.path("fingerprint").asText())) return Result.fail(409,"GUIDE_REQUEST_CHANGED","本次提交内容已变化，请刷新确认信息后重新提交");
                return saved.has("response") ? saved.get("response") : pendingResult();
            }
            // Retain PENDING when the outcome is uncertain. Never blindly repeat a write.
            Result<?> result = operation.get();
            redis.opsForValue().set(key, mapper.writeValueAsString(Map.of("fingerprint",fingerprint,"response",result)), RETENTION);
            return result;
        } catch (Exception failure) {
            return Result.fail(409,"GUIDE_RESULT_UNCERTAIN","暂未确认提交结果，请查看我的订单或我的评测，勿重复提交");
        }
    }

    public Object status(Long userId,String action,String requestId) {
        try {
            String saved = redis.opsForValue().get(key(userId,action,requestId));
            if (saved == null) return Result.ok(Map.of("status","NEW"));
            JsonNode node = mapper.readTree(saved);
            return node.has("response") ? node.get("response") : pendingResult();
        } catch(Exception failure) { return Result.fail("暂时无法查询提交状态，请稍后查看我的订单或评测"); }
    }

    private Result<?> pendingResult() { return Result.ok(Map.of("status","PENDING","message","正在确认提交结果，请查看我的订单或评测")); }
    private String key(Long userId,String action,String requestId) { return "guide:action:"+userId+":"+action+":"+requestId; }
    private JsonNode canonical(JsonNode node) {
        if(node.isObject()) {var sorted=mapper.createObjectNode();var names=new java.util.ArrayList<String>();node.fieldNames().forEachRemaining(names::add);java.util.Collections.sort(names);for(String name:names)sorted.set(name,canonical(node.get(name)));return sorted;}
        if(node.isArray()) {var array=mapper.createArrayNode();node.forEach(value->array.add(canonical(value)));return array;}
        return node;
    }
}
