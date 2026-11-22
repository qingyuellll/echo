package com.echo.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码编码工具类(BCrypt加密)
 *
 * @author tulip
 */
@Slf4j
public class PasswordEncoder {

    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int MAX_PASSWORD_LENGTH = 128;
    public static final int DEFAULT_BCRYPT_STRENGTH = 10;
    public static final int RECOMMENDED_BCRYPT_STRENGTH = 12;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(DEFAULT_BCRYPT_STRENGTH);

    private PasswordEncoder() {}

    /**
     * 将明文密码加密为 BCrypt 哈希值
     * <p>
     * 每次调用都会生成不同的哈希值（因为随机盐值），但都能通过 matches() 验证。
     * <p>
     * 性能说明：cost=10 时单次约 50ms，对于注册/修改密码场景完全可接受。
     *
     * @param password 明文密码（不能为 null，建议长度 8-128 字符）
     * @return 60 字符的 BCrypt 哈希字符串（格式：$2a$10$...）
     * @throws IllegalArgumentException 如果 password 为 null 或超出长度限制
     */
    public static String encode(String password) {
        validatePassword(password);

        String encoded = ENCODER.encode(password);
        log.debug("[密码加密] 成功生成哈希值, hashPrefix={}", maskHash(encoded));
        return encoded;
    }

    /**
     * 加密密码并记录审计日志
     * <p>
     * 在 encode() 基础上增加审计日志记录，适用于需要追踪密码变更的场景。
     * <b>安全注意：日志中只记录哈希前缀，绝不记录明文密码！</b>
     *
     * @param password 明文密码
     * @param operation 操作类型（REGISTER/MODIFY/RESET/INIT）
     * @param userId    用户ID（用于审计追踪）
     * @return 编码后的密码哈希
     */
    public static String encodeWithAudit(String password, String operation, Long userId) {
        validatePassword(password);

        String encoded = ENCODER.encode(password);
        log.info("[密码审计] operation={}, userId={}, hashPrefix={}, strength={}",
                operation, userId, maskHash(encoded), DEFAULT_BCRYPT_STRENGTH);

        return encoded;
    }

    /**
     * 验证明文密码是否与存储的哈希值匹配
     * <p>
     * 内部实现：
     * <ol>
     *   <li>从 encodedPassword 中提取盐值</li>
     *   <li>用相同盐值 + rawPassword 重新计算哈希</li>
     *   <li>使用恒定时间比较（防止时序攻击）</li>
     * </ol>
     *
     * @param rawPassword      用户输入的明文密码
     * @param encodedPassword  数据库中存储的 BCrypt 哈希值
     * @return true-匹配成功；false-匹配失败、参数为null、格式非法
     */
    public static Boolean matches(String rawPassword, String encodedPassword) {
        if (encodedPassword == null || rawPassword == null) {
            log.warn("[密码验证] 参数为null, rawPassword={}, encodedPassword={}",
                    rawPassword == null, encodedPassword == null);
            return false;
        }

        try {
            boolean result = ENCODER.matches(rawPassword, encodedPassword);
            if (!result) {
                log.warn("[密码验证] 密码不匹配, hashPrefix={}", maskHash(encodedPassword));
            }
            return result;
        } catch (IllegalArgumentException e) {
            log.error("[密码验证] 哈希格式非法, hashPrefix={}, error={}",
                    maskHash(encodedPassword), e.getMessage());
            return false;
        }
    }

    /**
     * 校验密码强度是否符合要求
     * <p>
     * 强度规则：
     * <ul>
     *   <li>长度：8-128 字符</li>
     *   <li>必须包含小写字母（a-z）</li>
     *   <li>必须包含大写字母（A-Z）</li>
     *   <li>必须包含数字（0-9）</li>
     *   <li>建议包含特殊字符（!@#$%^&* 等）</li>
     * </ul>
     *
     * @param password 明文密码
     * @return true-强度符合要求；false-强度不足
     */
    public static boolean isValidStrength(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return false;
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            return false;
        }
        if (!password.matches(".*[a-z].*")) {
            return false;
        }
        if (!password.matches(".*[A-Z].*")) {
            return false;
        }
        if (!password.matches(".*\\d.*")) {
            return false;
        }
        return true;
    }

    /**
     * 获取密码强度不足的具体原因
     * <p>
     * 与 isValidStrength() 配合使用，返回详细的错误信息用于提示用户。
     *
     * @param password 明文密码
     * @return 强度不足的原因描述；如果强度足够则返回 null
     */
    public static String getStrengthValidationError(String password) {
        if (password == null) {
            return "密码不能为空";
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return "密码长度至少" + MIN_PASSWORD_LENGTH + "位";
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            return "密码长度不能超过" + MAX_PASSWORD_LENGTH + "位";
        }
        if (!password.matches(".*[a-z].*")) {
            return "必须包含小写字母";
        }
        if (!password.matches(".*[A-Z].*")) {
            return "必须包含大写字母";
        }
        if (!password.matches(".*\\d.*")) {
            return "必须包含数字";
        }
        return null;
    }

    /**
     * 检查密码哈希是否需要升级
     * <p>
     * 升级场景：
     * <ul>
     *   <li>当前 cost 参数低于推荐值（12）</li>
     *   <li>未来迁移到新算法（如 Argon2）时</li>
     * </ul>
     * <p>
     * 推荐在用户登录成功后调用此方法检查并自动升级。
     *
     * @param rawPassword   用户输入的明文密码（已通过验证）
     * @param currentHash   当前存储的哈希值
     * @return 新的哈希值（如果需要升级）；否则返回原哈希值
     */
    public static String upgradeIfNeeded(String rawPassword, String currentHash) {
        if (currentHash == null || !currentHash.startsWith("$2a$")) {
            log.info("[密码升级] 检测到非BCrypt哈希，重新编码");
            return encode(rawPassword);
        }

        int currentCost = extractCost(currentHash);
        if (currentCost < RECOMMENDED_BCRYPT_STRENGTH) {
            log.info("[密码升级] 当前cost={}低于推荐值{}，重新编码",
                    currentCost, RECOMMENDED_BCRYPT_STRENGTH);
            return encode(rawPassword);
        }

        return currentHash;
    }

    /**
     * 从 BCrypt 哈希值中提取 cost 参数
     *
     * @param hash BCrypt 哈希字符串（格式：$2a$10$...）
     * @return cost 参数值（如 10、12 等）；如果格式非法则返回 -1
     */
    public static int extractCost(String hash) {
        if (hash == null || hash.length() < 7) {
            return -1;
        }
        try {
            return Integer.parseInt(hash.substring(4, 6));
        } catch (NumberFormatException e) {
            log.error("[密码解析] 无法提取cost参数, hash={}", maskHash(hash));
            return -1;
        }
    }

    /**
     * 脱敏处理哈希值（只显示前10个字符）
     * <p>
     * 用于日志输出，避免泄露完整哈希值。
     *
     * @param hash 完整哈希值
     * @return 脱敏后的哈希值（如 "$2a$10$N9qo8uLO..."）
     */
    public static String maskHash(String hash) {
        if (hash == null) {
            return "null";
        }
        if (hash.length() <= 10) {
            return hash + "...";
        }
        return hash.substring(0, 10) + "...";
    }

    private static void validatePassword(String password) {
        if (password == null) {
            throw new IllegalArgumentException("密码不能为null");
        }
        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "密码长度必须在" + MIN_PASSWORD_LENGTH + "-" + MAX_PASSWORD_LENGTH + "位之间");
        }
    }

}
