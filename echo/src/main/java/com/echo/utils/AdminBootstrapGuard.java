package com.echo.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 管理员引导通道令牌校验
 * <p>
 * 场景：全新部署或超级管理员密码遗失时，系统里没有任何可以登录的凭证，
 * 需要一条不依赖登录态的入口来重置超级管理员密码。这条入口用一次性配置的
 * 引导令牌授权，令牌由部署方通过环境变量注入，默认关闭。
 *
 * <h3>安全约束</h3>
 * <ul>
 *   <li>未配置令牌时通道整体关闭，端点直接拒绝，不存在默认弱口令</li>
 *   <li>令牌只从配置读取，不落库、不回显、不写日志</li>
 *   <li>比较采用定长时间算法，避免按响应耗时逐字节猜测</li>
 * </ul>
 *
 * @author tulip
 */
@Slf4j
@Component
public class AdminBootstrapGuard {

    /** 引导令牌，为空表示通道关闭 */
    @Value("${echo.admin.bootstrap-token:}")
    private String bootstrapToken;

    /**
     * 引导通道是否开放
     *
     * @return true=已配置令牌，通道可用
     */
    public boolean isEnabled() {
        return bootstrapToken != null && !bootstrapToken.trim().isEmpty();
    }

    /**
     * 校验请求携带的引导令牌
     *
     * @param providedToken 请求头中的令牌
     * @return true=令牌匹配
     */
    public boolean matches(String providedToken) {
        if (!isEnabled()) {
            return false;
        }
        if (providedToken == null || providedToken.trim().isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                bootstrapToken.trim().getBytes(StandardCharsets.UTF_8),
                providedToken.trim().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 记录一次引导通道被拒绝的访问，便于在日志中定位探测行为
     *
     * @param reason 拒绝原因
     */
    public void logRejected(String reason) {
        log.warn("[引导通道] 拒绝访问 - 原因:{}", reason);
    }
}
