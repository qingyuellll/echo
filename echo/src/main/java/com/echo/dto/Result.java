package com.echo.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.echo.constants.DTOMessages;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import java.io.Serializable;
import java.util.List;

/**
 * 统一响应结果封装类
 *
 * @param <T> 响应数据类型
 * @author tulip
 * @see CursorPageResult
 */
@Getter
@ToString
@EqualsAndHashCode
public final class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否成功
     */
    private final Boolean success;

    /**
     * 错误码（业务错误码，如 "SHOW_NOT_FOUND"）
     */
    private final String errorCode;

    /**
     * HTTP状态码（如 200, 400, 404, 500）
     */
    private final Integer httpStatus;

    /**
     * 错误/成功消息
     */
    private final String message;

    /**
     * 响应数据
     */
    private final T data;

    /**
     * 总记录数（仅传统分页使用）
     */
    private final Long total;

    // ==================== 私有构造函数 ====================

    private Result(Boolean success, String errorCode, Integer httpStatus,
                   String message, T data, Long total) {
        this.success = success;
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.message = message;
        this.data = data;
        this.total = total;
    }

    // ==================== 成功响应工厂方法 ====================

    /**
     * 成功响应（无数据）
     *
     * @return 空的成功响应
     */
    public static <T> Result<T> ok() {
        return new Result<>(true, null, 200, "操作成功", null, null);
    }

    /**
     * 成功响应（带数据）
     *
     * @param data 响应数据
     * @return 包含数据的成功响应
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(true, null, 200, "操作成功", data, null);
    }

    /**
     * 成功响应（带数据和消息）
     *
     * @param data    响应数据
     * @param message 自定义消息
     * @return 包含数据和消息的成功响应
     */
    public static <T> Result<T> ok(T data, String message) {
        return new Result<>(true, null, 200, message, data, null);
    }

    /**
     * 传统分页成功响应（列表+总数）
     *
     * @param data  数据列表
     * @param total 总记录数
     * @return 分页响应
     */
    public static <T> Result<List<T>> ok(List<T> data, Long total) {
        return new Result<>(true, null, 200, "查询成功", data, total);
    }

    /**
     * 从MyBatis-Plus的Page对象创建分页响应
     * <p>
     * 自动提取records、total、pages等信息
     *
     * @param page MyBatis-Plus分页对象
     * @return 标准分页响应
     */
    public static <T> Result<PageResult<T>> fromPage(Page<T> page) {
        if (page == null) {
            return ok(PageResult.empty(1, 10));
        }
        return ok(PageResult.from(page));
    }

    /**
     * 从游标分页结果创建响应
     *
     * @param cursorPageResult 游标分页结果
     * @return 游标分页响应
     */
    public static <T> Result<CursorPageResult<T>> fromCursor(CursorPageResult<T> cursorPageResult) {
        if (cursorPageResult == null) {
            return ok(CursorPageResult.empty());
        }
        return ok(cursorPageResult);
    }

    // ==================== 失败响应工厂方法 ====================

    /**
     * 失败响应（简单消息）
     *
     * @param message 错误消息
     * @return 失败响应
     */
    public static <T> Result<T> fail(String message) {
        return new Result<>(false, null, 500, message, null, null);
    }

    /**
     * 失败响应（带错误码和HTTP状态码）
     *
     * @param httpStatus HTTP状态码（400, 404, 500等）
     * @param errorCode  业务错误码（"SHOW_NOT_FOUND"等）
     * @param message    错误消息
     * @return 结构化失败响应
     */
    public static <T> Result<T> fail(int httpStatus, String errorCode, String message) {
        return new Result<>(false, errorCode, httpStatus, message, null, null);
    }

    /**
     * 失败响应（带错误码）
     *
     * @param errorCode 业务错误码
     * @param message   错误消息
     * @return 失败响应（HTTP状态码默认为400）
     */
    public static <T> Result<T> fail(String errorCode, String message) {
        return fail(400, errorCode, message);
    }

    // ==================== Builder模式 ====================

    /**
     * 创建Builder实例
     *
     * @param <T> 数据类型
     * @return Builder构建器
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Result构建器
     * <p>
     * 支持链式调用，灵活构建复杂响应对象
     *
     * <h3>使用示例：</h3>
     * <pre>
     * Result&lt;Show&gt; result = Result.&lt;Show&gt;builder()
     *      .success(true)
     *      .data(show)
     *      .message("查询成功")
     *      .total(100L)
     *      .build();
     * </pre>
     *
     * @param <T> 数据类型
     */
    public static class Builder<T> {
        private Boolean success = true;
        private String errorCode;
        private Integer httpStatus = 200;
        private String message = DTOMessages.DEFAULT_SUCCESS_MESSAGE;
        private T data;
        private Long total;

        public Builder<T> success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder<T> errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder<T> httpStatus(int httpStatus) {
            this.httpStatus = httpStatus;
            return this;
        }

        public Builder<T> message(String message) {
            this.message = message;
            return this;
        }

        public Builder<T> data(T data) {
            this.data = data;
            return this;
        }

        public Builder<T> total(Long total) {
            this.total = total;
            return this;
        }

        public Result<T> build() {
            return new Result<>(success, errorCode, httpStatus, message, data, total);
        }
    }

    // ==================== 兼容性方法（向后兼容旧代码） ====================

    /**
     * 获取错误消息（兼容旧的getMsg()方法）
     *
     * @return 消息内容
     */
    public String getErrorMsg() {
        return getMessage();
    }

    /**
     * 获取消息（兼容旧代码）
     *
     * @return 消息内容
     * @deprecated 使用 {@link #getMessage()} 替代
     */
    @Deprecated
    public String getMsg() {
        return getMessage();
    }

    /**
     * 判断是否成功
     *
     * @return true=成功, false=失败
     */
    public boolean isSuccess() {
        return Boolean.TRUE.equals(getSuccess());
    }

    // ==================== 工具方法 ====================

    /**
     * 判断是否包含数据
     *
     * @return true=有数据, false=无数据
     */
    public boolean hasData() {
        return getData() != null;
    }

    /**
     * 判断是否为分页响应
     *
     * @return true=是分页响应（total不为null）
     */
    public boolean isPaginated() {
        return getTotal() != null;
    }

    /**
     * 判断是否为错误响应
     *
     * @return true=是错误响应
     */
    public boolean isError() {
        return !isSuccess();
    }

    /**
     * 获取数据（如果存在），否则返回null
     *
     * @return 数据或null
     */
    @JsonIgnore
    public T getDataOrNull() {
        return getData();
    }

    /**
     * 获取数据（如果存在），否则抛出异常
     * <p>
     * 必须加 @JsonIgnore：方法名符合 getXxx 命名，Jackson 会将其误判为
     * "dataOrThrow" 属性并在序列化时调用；data 为 null 时本方法抛出
     * IllegalStateException，导致整个 Result 序列化失败（含异常处理器自身的
     * 错误响应），请求 fallthrough 到 /error 被登录拦截器拦截成误导性 401。
     *
     * @return 数据（保证非null）
     * @throws IllegalStateException 如果数据为null
     */
    @JsonIgnore
    public T getDataOrThrow() {
        if (getData() == null) {
            throw new IllegalStateException("响应数据为空，但尝试获取非空数据");
        }
        return getData();
    }

    /**
     * 转换为字符串表示（用于日志记录）
     *
     * @return 格式化的字符串
     */
    public String toLogString() {
        StringBuilder sb = new StringBuilder("Result{");
        sb.append("success=").append(success);
        if (errorCode != null) sb.append(", errorCode='").append(errorCode).append('\'');
        if (httpStatus != null && httpStatus != 200) {
            sb.append(", httpStatus=").append(httpStatus);
        }
        if (message != null) sb.append(", message='").append(message).append('\'');
        if (data != null) sb.append(", hasData=true");
        if (total != null) sb.append(", total=").append(total);
        sb.append('}');
        return sb.toString();
    }
}
