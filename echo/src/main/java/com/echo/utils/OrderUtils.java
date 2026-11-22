package com.echo.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Set;

/**
 * 订单工具类(核销码生成与验证)
 *
 * @author tulip
 */
@Slf4j
@Component
public class OrderUtils {

    private static final String VERIFY_CODE_DIGITS = "0123456789";
    private static final String VERIFY_CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";

    public static final int DEFAULT_VERIFY_CODE_LENGTH = 8;
    public static final int MAX_RETRY_COUNT = 10;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private OrderUtils() {}

    /**
     * 生成默认长度（8位）的核销码
     * <p>
     * 格式：字母数字交替，如 A1B2C3D4
     *
     * @return 8位核销码字符串
     */
    public static String generateVerificationCode() {
        return generateVerificationCode(DEFAULT_VERIFY_CODE_LENGTH);
    }

    /**
     * 生成指定长度的核销码
     * <p>
     * 格式规则：
     * - 长度必须为正偶数
     * - 偶数位使用字母（排除I和O）
     * - 奇数位使用数字
     * <p>
     * 示例（长度=6）：A1B2C3
     * 示例（长度=10）：A1B2C3D4E5
     *
     * @param length 核销码长度（必须为正偶数）
     * @return 指定长度的核销码字符串
     * @throws IllegalArgumentException 如果长度不是正偶数
     */
    public static String generateVerificationCode(int length) {
        validateLength(length);

        StringBuilder code = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            if (i % 2 == 0) {
                code.append(VERIFY_CODE_LETTERS.charAt(SECURE_RANDOM.nextInt(VERIFY_CODE_LETTERS.length())));
            } else {
                code.append(VERIFY_CODE_DIGITS.charAt(SECURE_RANDOM.nextInt(VERIFY_CODE_DIGITS.length())));
            }
        }

        String result = code.toString();
        log.debug("[核销码生成] 生成新核销码(长度={}):{}", length, result);
        return result;
    }

    /**
     * 生成唯一核销码（带重试机制）
     * <p>
     * 在批量生成场景下，确保生成的核销码不与已存在的码重复。
     * 如果达到最大重试次数仍未生成唯一码，则抛出运行时异常。
     * <p>
     * 使用示例：
     * <pre>
     * Set&lt;String&gt; existingCodes = new HashSet&lt;&gt;();
     * List&lt;String&gt; codes = new ArrayList&lt;&gt;();
     * for (int i = 0; i &lt; 100; i++) {
     *     String code = OrderUtils.generateUniqueVerificationCode(existingCodes);
     *     existingCodes.add(code);
     *     codes.add(code);
     * }
     * </pre>
     *
     * @param existingCodes 已存在的核销码集合（用于去重校验）
     * @return 唯一的核销码字符串
     * @throws RuntimeException 如果超过最大重试次数（{@value #MAX_RETRY_COUNT}次）仍无法生成唯一码
     * @throws IllegalArgumentException 如果 existingCodes 为 null
     */
    public static String generateUniqueVerificationCode(Set<String> existingCodes) {
        if (existingCodes == null) {
            throw new IllegalArgumentException("已存在的核销码集合不能为null");
        }

        for (int i = 0; i < MAX_RETRY_COUNT; i++) {
            String code = generateVerificationCode();
            if (!existingCodes.contains(code)) {
                log.debug("[核销码生成] 成功生成唯一核销码:{}, 重试次数:{}", code, i);
                return code;
            }
            log.warn("[核销码生成] 检测到重复核销码:{}, 重试第{}次", code, i + 1);
        }

        String errorMsg = String.format("无法生成唯一核销码，已达到最大重试次数:%d", MAX_RETRY_COUNT);
        log.error(errorMsg);
        throw new RuntimeException(errorMsg);
    }

    /**
     * 验证核销码格式是否合法
     * <p>
     * 校验规则：
     * - 不能为 null
     * - 长度必须为 {@value #DEFAULT_VERIFY_CODE_LENGTH} 位
     * - 偶数位必须是字母（A-Z，排除I和O）
     * - 奇数位必须是数字（0-9）
     *
     * @param code 待验证的核销码
     * @return true-格式合法；false-格式非法
     */
    public static boolean isValidVerificationCode(String code) {
        if (code == null || code.length() != DEFAULT_VERIFY_CODE_LENGTH) {
            return false;
        }

        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (i % 2 == 0) {
                if (VERIFY_CODE_LETTERS.indexOf(c) == -1) {
                    return false;
                }
            } else {
                if (VERIFY_CODE_DIGITS.indexOf(c) == -1) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * 快速校验核销码格式（正则版本）
     * <p>
     * 使用正则表达式进行快速匹配，适用于简单场景。
     * 对于性能敏感的高频调用场景，建议使用 {@link #isValidVerificationCode(String)}
     *
     * @param code 待验证的核销码
     * @return true-格式合法；false-格式非法
     */
    public static boolean quickValidateVerificationCode(String code) {
        if (code == null) {
            return false;
        }
        String pattern = "^" + VERIFY_CODE_LETTERS + VERIFY_CODE_DIGITS + 
                         VERIFY_CODE_LETTERS + VERIFY_CODE_DIGITS +
                         VERIFY_CODE_LETTERS + VERIFY_CODE_DIGITS +
                         VERIFY_CODE_LETTERS + VERIFY_CODE_DIGITS + "$";
        return code.matches(pattern);
    }

    private static void validateLength(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("核销码长度必须为正数:" + length);
        }
        if (length % 2 != 0) {
            throw new IllegalArgumentException("核销码长度必须为偶数:" + length);
        }
    }

}
