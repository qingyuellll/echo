package com.echo.config;

import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.exception.BusinessException;
import com.echo.exception.PermissionDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @Value("${exception.user-friendly-keywords:库存不足,积分,座位锁定,座位已,座位不,座位列表,座位状态,乐观锁冲突,票种,订单,支付,退款,上传,保存失败,创建失败,发布失败}")
    private List<String> userFriendlyKeywords;

    @Value("${exception.rate-limit-keywords:频繁,系统繁忙,服务繁忙,连接池,超时,mq,redis,缓存}")
    private List<String> rateLimitKeywords;

    @Value("${exception.data-error-keywords:不存在,不能为空,参数,格式,非法,无效}")
    private List<String> dataErrorKeywords;

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e,
                                                       HttpServletRequest request,
                                                       HttpServletResponse response) {
        logRequestContext(request);
        log.warn("[参数类型不匹配] 参数:{}, 值:{}, 目标类型:{}",
                e.getName(), e.getValue(), e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "未知");
        return buildErrorResponse(response, HttpStatus.BAD_REQUEST,
                "请求参数格式错误：" + e.getName() + " 应为" +
                        (e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "有效值"));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<?> handleNoHandlerFound(NoHandlerFoundException e,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        logRequestContext(request);
        log.warn("[路径不存在] {} {}", e.getHttpMethod(), e.getRequestURL());
        return buildErrorResponse(response, HttpStatus.NOT_FOUND,
                "接口不存在: " + e.getHttpMethod() + " " + e.getRequestURL());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<?> handleDataIntegrityViolation(DataIntegrityViolationException e,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) {
        logRequestContext(request);
        log.error("[数据完整性冲突] {}", e.getMessage());
        if (e.getCause() instanceof DuplicateKeyException) {
            return buildErrorResponse(response, HttpStatus.CONFLICT, "数据已存在，请勿重复提交");
        }
        return buildErrorResponse(response, HttpStatus.CONFLICT, "数据完整性约束冲突，请联系管理员");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<?> handleDuplicateKey(DuplicateKeyException e,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        logRequestContext(request);
        log.warn("[重复键异常] {}", e.getMessage());
        return buildErrorResponse(response, HttpStatus.CONFLICT, "数据已存在，请勿重复提交");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleIllegalArgument(IllegalArgumentException e,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        logRequestContext(request);
        log.warn("[参数非法] {}", e.getMessage());
        return buildErrorResponse(response, HttpStatus.BAD_REQUEST, "请求参数不合法，请检查输入内容");
    }

    @ExceptionHandler(PermissionDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<?> handlePermissionDenied(PermissionDeniedException e,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        logRequestContext(request);
        String message = e.getMessage();
        String errorCode = e.getErrorCode();

        log.warn("[权限拒绝] code:{}, message:{}, 原因:用户角色级别不足或未持有有效admin token",
                errorCode, message);

        return buildErrorResponse(response, HttpStatus.FORBIDDEN, message);
    }

    @ExceptionHandler(NullPointerException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleNullPointerException(NullPointerException e,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        logRequestContext(request);
        log.error("[空指针异常] {}", e.getMessage(), e);
        return buildErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR, AppMessages.EXCEPTION_SERVER_ERROR);
    }

    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e,
                                             HttpServletRequest request,
                                             HttpServletResponse response) {
        logRequestContext(request);
        String message = e.getMessage();
        HttpStatus httpStatus = e.getHttpStatus();
        String errorCode = e.getErrorCode();

        log.warn("[业务异常] code:{}, httpStatus:{}, message:{}",
                errorCode, httpStatus.value(), message);

        return buildErrorResponse(response, httpStatus, message);
    }

    @ExceptionHandler(RuntimeException.class)
    public Result<?> handleRuntimeException(RuntimeException e,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        logRequestContext(request);
        String message = e.getMessage();

        if (message == null || message.isEmpty()) {
            log.error("[运行时异常] 异常消息为空", e);
            return buildErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR, AppMessages.EXCEPTION_SERVER_ERROR);
        }

        String lowerMessage = message.toLowerCase();

        if (containsAny(lowerMessage, userFriendlyKeywords)) {
            log.warn("[业务异常-兼容模式] {}", message);
            return buildErrorResponse(response, HttpStatus.OK, message);
        }

        if (containsAny(lowerMessage, rateLimitKeywords)) {
            log.warn("[系统繁忙-兼容模式] {}", message);
            return buildErrorResponse(response, HttpStatus.SERVICE_UNAVAILABLE, "系统繁忙，请稍后再试");
        }

        if (containsAny(lowerMessage, dataErrorKeywords)) {
            log.warn("[数据/参数异常-兼容模式] {}", message);
            return buildErrorResponse(response, HttpStatus.BAD_REQUEST, message);
        }

        log.error("[运行时异常] 未分类异常 - message:{}, class:{}（建议改为BusinessException）",
                message, e.getClass().getSimpleName(), e);
        return buildErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR, AppMessages.EXCEPTION_SERVER_ERROR);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleHttpMessageNotReadableException(HttpMessageNotReadableException e,
                                                           HttpServletRequest request,
                                                           HttpServletResponse response) {
        logRequestContext(request);
        log.warn("[请求体解析失败] {}", e.getMessage());
        return buildErrorResponse(response, HttpStatus.BAD_REQUEST, AppMessages.EXCEPTION_REQUEST_PARAM_FORMAT_ERROR);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException e,
                                                           HttpServletRequest request,
                                                           HttpServletResponse response) {
        logRequestContext(request);
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse(AppMessages.EXCEPTION_PARAM_VALIDATION_FAIL);
        log.warn("[参数校验失败] {}", msg);
        return buildErrorResponse(response, HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleMissingServletRequestParameterException(MissingServletRequestParameterException e,
                                                                   HttpServletRequest request,
                                                                   HttpServletResponse response) {
        logRequestContext(request);
        log.warn("[缺少请求参数] {}", e.getMessage());
        return buildErrorResponse(response, HttpStatus.BAD_REQUEST, AppMessages.EXCEPTION_MISSING_REQUIRED_PARAM_PREFIX + e.getParameterName());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleException(Exception e,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        logRequestContext(request);
        log.error("[系统异常] {}", e.getMessage(), e);
        return buildErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR, AppMessages.SYSTEM_BUSY);
    }

    private Result<?> buildErrorResponse(HttpServletResponse response, HttpStatus status, String message) {
        response.setStatus(status.value());
        return Result.fail(message);
    }

    private void logRequestContext(HttpServletRequest request) {
        if (request != null) {
            log.debug("[请求上下文] {} {} | User-Agent:{} | IP:{}",
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getHeader("User-Agent"),
                    getClientIp(request));
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty() || "unknown".equalsIgnoreCase(xfHeader)) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }

    private boolean containsAny(String message, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        return keywords.stream().anyMatch(keyword ->
                keyword != null && !keyword.isEmpty() && message.contains(keyword.toLowerCase())
        );
    }
}
