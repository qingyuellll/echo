package com.echo.aspect;

import com.echo.config.PrometheusMetricsConfig;
import com.echo.dto.Result;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/**
 * API指标采集切面
 *
 * @author tulip
 */
@Aspect
@Component
public class ApiMetricsAspect {

    private final PrometheusMetricsConfig metricsConfig;

    public ApiMetricsAspect(PrometheusMetricsConfig metricsConfig) {
        this.metricsConfig = metricsConfig;
    }

    @Around("execution(* com.echo.controller..*(..))")
    public Object recordApiMetrics(ProceedingJoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        if (attributes == null) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = attributes.getRequest();
        String method = request.getMethod();
        String endpoint = request.getRequestURI();

        Timer.Sample timer = metricsConfig.startApiTimer();
        int status = 200;
        Object result;

        try {
            result = joinPoint.proceed();

            if (result instanceof Result<?>) {
                Result<?> resultObj = (Result<?>) result;
                if (!resultObj.isSuccess()) {
                    status = 400;
                }
            }

            return result;
        } catch (Exception e) {
            status = 500;
            throw e;
        } finally {
            metricsConfig.stopApiTimer(timer, method, endpoint);
            metricsConfig.recordApiRequest(method, endpoint, status);
        }
    }
}
