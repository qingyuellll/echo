package com.echo.aspect;

import com.echo.config.PrometheusMetricsConfig;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

/**
 * 业务指标采集切面
 *
 * @author tulip
 */
@Aspect
@Component
public class BusinessMetricsAspect {

    private final PrometheusMetricsConfig metricsConfig;

    public BusinessMetricsAspect(PrometheusMetricsConfig metricsConfig) {
        this.metricsConfig = metricsConfig;
    }

    @Pointcut("execution(* com.echo.controller.AdminController.login(..))")
    public void adminLoginPointcut() {}

    @Pointcut("execution(* com.echo.controller.TicketOrderController.createTicketOrder(..))")
    public void createOrderPointcut() {}

    @Pointcut("execution(* com.echo.controller.TicketOrderController.seckillTicket(..))")
    public void seckillOrderPointcut() {}

    @Pointcut("execution(* com.echo.controller.TicketOrderController.verifyOrder(..))")
    public void verifyOrderPointcut() {}

    @Around("adminLoginPointcut()")
    public Object recordLoginMetric(ProceedingJoinPoint joinPoint) throws Throwable {
        String role = "admin";
        String result = "success";

        try {
            Object obj = joinPoint.proceed();
            if (obj instanceof com.echo.dto.Result<?> && !((com.echo.dto.Result<?>) obj).isSuccess()) {
                result = "failed";
            }
            return obj;
        } catch (Exception e) {
            result = "failed";
            throw e;
        } finally {
            metricsConfig.recordLoginAttempt(result, role);
        }
    }

    @Around("createOrderPointcut()")
    public Object recordCreateOrderMetric(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            Object obj = joinPoint.proceed();
            metricsConfig.recordOrder("normal", "success");
            return obj;
        } catch (Exception e) {
            metricsConfig.recordOrder("normal", "failed");
            throw e;
        }
    }

    @Around("seckillOrderPointcut()")
    public Object recordSeckillOrderMetric(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            Object obj = joinPoint.proceed();
            metricsConfig.recordOrder("seckill", "success");
            return obj;
        } catch (Exception e) {
            metricsConfig.recordOrder("seckill", "failed");
            throw e;
        }
    }

    @Around("verifyOrderPointcut()")
    public Object recordVerificationMetric(ProceedingJoinPoint joinPoint) throws Throwable {
        boolean success = false;

        try {
            Object obj = joinPoint.proceed();
            success = true;
            return obj;
        } finally {
            metricsConfig.recordVerification(success);
        }
    }
}
