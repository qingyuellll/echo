package com.echo.aspect;

import com.echo.dto.PageQuery;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 分页参数处理切面
 * <p>
 * 拦截所有Controller方法中的PageQuery参数，自动设置默认值和参数校验
 *
 * @author tulip
 * @see PageQuery
 */
@Aspect
@Component
@Slf4j
@Order(1)
public class PaginationAspect {

    /**
     * 环绕通知：拦截所有Controller方法，自动处理PageQuery参数
     * 注意：AspectJ的@annotation只接受具体注解类型，不能使用*Mapping通配（运行期解析切点会报
     * "Pointcut is not well-formed"），必须逐一列举各HTTP方法注解
     */
    @Around("execution(* com.echo.controller..*(..)) && (" +
            "@annotation(org.springframework.web.bind.annotation.GetMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.PostMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.PutMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.DeleteMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.PatchMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.RequestMapping))")
    public Object handlePagination(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        boolean modified = false;

        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof PageQuery) {
                PageQuery original = (PageQuery) args[i];
                PageQuery processed = processPageQuery(original, joinPoint);

                if (processed != original) {
                    args[i] = processed;
                    modified = true;
                }

                log.debug("[分页AOP] 处理完成 - 方法:{}, current:{}, size:{}, cursorMode:{}", 
                        joinPoint.getSignature().getName(),
                        processed.getCurrent(),
                        processed.getSize(),
                        processed.isCursorMode());
                
                break;
            }
        }

        long startTime = System.currentTimeMillis();
        Object result;

        try {
            if (modified) {
                result = joinPoint.proceed(args);
            } else {
                result = joinPoint.proceed();
            }
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            
            if (duration > 1000) {
                log.warn("[分页AOP] 慢查询警告 - 方法:{}, 耗时:{}ms", 
                        joinPoint.getSignature().getName(), duration);
            } else {
                log.debug("[分页AOP] 执行完成 - 方法:{}, 耗时:{}ms", 
                        joinPoint.getSignature().getName(), duration);
            }
        }

        return result;
    }

    /**
     * 处理PageQuery对象：设置默认值、校验参数、记录日志
     *
     * @param pageQuery 原始PageQuery对象
     * @param joinPoint 连接点（用于获取方法信息）
     * @return 处理后的PageQuery对象（可能返回原对象或新对象）
     */
    private PageQuery processPageQuery(PageQuery pageQuery, ProceedingJoinPoint joinPoint) {
        String methodName = joinPoint.getSignature().getName();
        
        boolean needsProcessing = false;

        if (pageQuery.getCurrent() == null) {
            pageQuery.setCurrent(1);
            needsProcessing = true;
        }

        if (pageQuery.getSize() == null) {
            pageQuery.setSize(10);
            needsProcessing = true;
        } else {
            int size = pageQuery.getSize();
            if (size < 1 || size > 100) {
                log.warn("[分页AOP] 参数越界 - 方法:{}, size:{} (限制1-100), 已自动修正", 
                        methodName, size);
                pageQuery.setSize(Math.min(Math.max(size, 1), 100));
                needsProcessing = true;
            }
        }

        if (pageQuery.getSortOrder() == null) {
            pageQuery.setSortOrder("desc");
            needsProcessing = true;
        }

        if (needsProcessing) {
            log.info("[分页AOP] 应用默认值 - 方法:{}, current:{}, size:{}, sortBy:{}, sortOrder:{}, cursorMode:{}", 
                    methodName,
                    pageQuery.getCurrent(),
                    pageQuery.getSize(),
                    pageQuery.getSortBy(),
                    pageQuery.getSortOrder(),
                    pageQuery.isCursorMode());
        }

        return pageQuery;
    }
}
