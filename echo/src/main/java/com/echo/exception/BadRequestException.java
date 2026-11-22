package com.echo.exception;

import org.springframework.http.HttpStatus;

/**
 * 参数/数据错误异常 - 表示请求参数不合法或数据存在问题的业务异常
 * <p>
 * 业务场景：
 * - 请求参数缺失或格式错误（如必填字段为空、手机号格式不正确）
 * - 数据不存在或状态不合法（如订单ID不存在、订单状态不允许操作）
 * - 业务规则校验失败（如购票数量超过限制、演出已下架）
 * <p>
 * HTTP 状态码：400 BAD_REQUEST
 * <p>
 * 前端处理建议：收到此异常后应提示用户修正请求参数后重试，
 * 或检查输入数据的合法性和有效性。
 * <p>
 * 技术特性：
 * - 继承 BusinessException，自动携带 HTTP 状态码和错误码（errorCode=PARAM_ERROR）
 * - 提供两种构造方式：仅消息、消息+原始异常链
 * - 配合 WebExceptionAdvice 全局异常处理器自动返回标准化的 400 错误响应
 * <p>
 * 异常体系位置：
 * RuntimeException → BusinessException → BadRequestException (本类)
 *
 * @see BusinessException 业务异常基类（提供 HTTP 状态码和错误码支持）
 * @see org.springframework.web.bind.annotation.ExceptionHandler 全局异常处理
 * @author echo
 * @version 1.0
 */
public class BadRequestException extends BusinessException {

    /**
     * 构造参数错误异常
     *
     * @param message 错误消息
     */
    public BadRequestException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 构造参数/数据错误异常（指定错误消息和原始异常）
     * <p>
     * 适用于需要保留底层异常堆栈信息的场景，便于问题排查和根因分析。
     * 当底层技术异常（如 JSON 解析失败、类型转换错误）触发业务校验失败时使用。
     * <p>
     * 典型使用场景：
     * - JSON 反序列化失败时转换：catch (JsonProcessingException e) → throw new BadRequestException("格式错误", e)
     * - 数据类型转换失败时转换：catch (NumberFormatException e) → throw new BadRequestException("ID格式错误", e)
     * - 数据库查询异常包装：catch (DataAccessException e) → throw new BadRequestException("数据不存在", e)
     * <p>
     * 异常链示例：
     * <pre>
     * BadRequestException: 订单数据格式错误
     *   └─ BusinessException (httpStatus=400, errorCode=PARAM_ERROR)
     *       └─ JsonParseException: Unexpected character ('e' at [line:1, column:3])
     * </pre>
     *
     * @param message 用户可见的错误消息（将直接返回给前端展示）
     * @param cause   原始异常对象（保留完整堆栈信息用于日志记录和问题排查）
     */
    public BadRequestException(String message, Throwable cause) {
        super(message, HttpStatus.BAD_REQUEST, cause);
    }
}
