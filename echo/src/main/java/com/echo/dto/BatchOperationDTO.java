package com.echo.dto;

import com.echo.constants.DTOMessages;
import com.echo.constants.OrderMessages;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 通用批量操作请求DTO
 *
 * @author tulip
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchOperationDTO {

    /**
     * 批量操作类型枚举
     * <p>
     * 定义系统支持的所有批量操作类型，确保类型安全。
     * 每种操作类型可配置是否需要原因、是否支持额外数据。
     */
    public enum Operation {
        /** 上架（演出/票种等资源上线） */
        ONLINE("online", false),

        /** 下架（资源下线） */
        OFFLINE("offline", false),

        /** 删除（物理删除或逻辑删除） */
        DELETE("delete", true),

        /** 启用（用户/管理员账号启用） */
        ENABLE("enable", false),

        /** 禁用（用户/管理员账号禁用） */
        DISABLE("disable", true),

        /** 批量更新（需配合extraData使用） */
        UPDATE("update", false),

        /** 审批通过退款（订单专用） */
        APPROVE_REFUND("approve-refund", false),

        /** 拒绝退款申请（订单专用） */
        REJECT_REFUND("reject-refund", false);

        private final String code;
        private final boolean requireReason;

        Operation(String code, boolean requireReason) {
            this.code = code;
            this.requireReason = requireReason;
        }

        /**
         * 对外的操作码，同时是 JSON 报文中的取值（如 online、delete、approve-refund）
         */
        @JsonValue
        public String getCode() {
            return code;
        }

        public boolean isRequireReason() {
            return requireReason;
        }

        /**
         * 解析操作码
         * <p>
         * 同时接受操作码（{@code online}）与枚举名（{@code ONLINE}），大小写不敏感：
         * 前端与接口文档使用操作码，历史调用方可能使用枚举名，两者落到同一个枚举值。
         *
         * @param code 操作码或枚举名
         * @return 对应枚举值；入参为空时返回 null
         * @throws IllegalArgumentException 入参无法识别
         */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Operation fromCode(String code) {
            if (code == null || code.trim().isEmpty()) {
                return null;
            }
            String normalized = code.trim();
            for (Operation op : values()) {
                if (op.code.equalsIgnoreCase(normalized) || op.name().equalsIgnoreCase(normalized)) {
                    return op;
                }
            }
            throw new IllegalArgumentException("未知的操作类型: " + code);
        }
    }

    /** 操作目标ID列表(必填,最多100个) */
    @NotEmpty(message = DTOMessages.BATCH_IDS_NOT_EMPTY)
    @Size(max = 100, message = DTOMessages.BATCH_IDS_MAX_SIZE)
    private List<Long> ids;

    /** 操作类型(必填) */
    @NotNull(message = OrderMessages.OPERATION_NOT_EMPTY)
    private Operation operation;

    /**
     * 额外数据（可选，用于UPDATE等需要额外参数的操作）
     * <p>
     * 使用场景示例：
     * <pre>
     * // 批量更新票种价格和库存
     * {
     *   "operation": "UPDATE",
     *   "ids": [10, 11, 12],
     *   "extraData": {
     *     "price": 299.00,
     *     "stock": 1000,
     *     "remark": "双11调价"
     *   }
     * }
     * </pre>
     */
    @Valid
    private Map<String, Object> extraData;

    /**
     * 操作原因/备注（条件必填）
     * <p>
     * 以下操作类型<strong>必须</strong>填写原因：
     * <ul>
     *     <li>{@link Operation#DELETE} - 删除操作</li>
     *     <li>{@link Operation#DISABLE} - 禁用操作</li>
     * </ul>
     * 用于审计日志和操作追溯。
     */
    private String reason;

    public List<Long> getIds() {
        return ids == null ? Collections.emptyList() : Collections.unmodifiableList(ids);
    }

    public Map<String, Object> getExtraData() {
        return extraData == null ? Collections.emptyMap() : Collections.unmodifiableMap(extraData);
    }

    public boolean isDelete() {
        return operation == Operation.DELETE;
    }

    public boolean isOnline() {
        return operation == Operation.ONLINE;
    }

    public boolean isOffline() {
        return operation == Operation.OFFLINE;
    }

    public boolean isEnable() {
        return operation == Operation.ENABLE;
    }

    public boolean isDisable() {
        return operation == Operation.DISABLE;
    }

    public boolean isUpdate() {
        return operation == Operation.UPDATE;
    }

    public boolean isRequireReason() {
        return operation != null && operation.isRequireReason();
    }

    public int size() {
        return ids == null ? 0 : ids.size();
    }

    public boolean isEmpty() {
        return ids == null || ids.isEmpty();
    }

    public static BatchOperationDTO of(List<Long> ids, Operation operation) {
        return builder().ids(ids).operation(operation).build();
    }

    public static BatchOperationDTO of(List<Long> ids, Operation operation, String reason) {
        return builder().ids(ids).operation(operation).reason(reason).build();
    }

    public static BatchOperationDTO delete(List<Long> ids, String reason) {
        return of(ids, Operation.DELETE, reason);
    }

    public static BatchOperationDTO online(List<Long> ids) {
        return of(ids, Operation.ONLINE);
    }

    public static BatchOperationDTO online(List<Long> ids, String reason) {
        return of(ids, Operation.ONLINE, reason);
    }

    public static BatchOperationDTO offline(List<Long> ids) {
        return of(ids, Operation.OFFLINE);
    }

    public static BatchOperationDTO offline(List<Long> ids, String reason) {
        return of(ids, Operation.OFFLINE, reason);
    }

    public static BatchOperationDTO enable(List<Long> ids) {
        return of(ids, Operation.ENABLE);
    }

    public static BatchOperationDTO enable(List<Long> ids, String reason) {
        return of(ids, Operation.ENABLE, reason);
    }

    public static BatchOperationDTO disable(List<Long> ids, String reason) {
        return of(ids, Operation.DISABLE, reason);
    }

    public static BatchOperationDTO update(List<Long> ids, Map<String, Object> extraData) {
        return BatchOperationDTO.builder()
                .ids(ids)
                .operation(Operation.UPDATE)
                .extraData(extraData)
                .build();
    }
}
