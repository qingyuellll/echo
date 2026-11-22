package com.echo.exception;

import org.springframework.http.HttpStatus;

/**
 * 服务不可用异常(HTTP 503)
 *
 * @author tulip
 */
public class ServiceUnavailableException extends BusinessException {

    public ServiceUnavailableException(String message) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE, cause);
    }

    /**
     * 快速创建方法（仅消息）
     */
    public static ServiceUnavailableException ofServiceUnavailable(String message) {
        return new ServiceUnavailableException(message);
    }

    /**
     * 快速创建方法（消息 + 原因）
     */
    public static ServiceUnavailableException ofServiceUnavailable(String message, Throwable cause) {
        return new ServiceUnavailableException(message, cause);
    }
}
