package com.echo.exception;

/**
 * 权限不足异常 - 表示当前用户无权执行请求的操作
 * <p>
 * 核心设计理念：
 * - 独立实现的专用异常类（非继承 BusinessException），专注于"权限不足"这一单一职责
 * - 固定返回 HTTP 403 FORBIDDEN 状态码，无需调用者手动指定
 * - 通过 errorCode 字段支持细粒度的权限错误分类（如角色不足、资源所有权不符、IP限制等）
 * <p>
 * 业务场景：
 * - 角色级别不足：超级管理员专属操作被普通管理员/用户调用
 * - 权限标识缺失：需要特定权限标签（如 DATA_EXPORT, USER_MANAGE）但当前用户未拥有
 * - 资源所有权验证失败：尝试访问或修改他人的私有资源（如他人的订单、个人信息）
 * - 访问控制策略违规：IP地址不在白名单、功能未开放、账号状态异常等
 * <p>
 * HTTP 状态码：403 FORBIDDEN
 * <p>
 * 前端处理建议：
 * - 如果是未登录导致的 403：跳转到登录页并记录原始访问路径（登录后自动跳回）
 * - 如果是已登录但权限不足：展示友好的提示信息（如"您没有权限执行此操作"）
 * - 可选：提供"联系管理员"按钮或展示申请权限的入口
 * <p>
 * 与其他异常类的区别：
 * <pre>
 * 异常类型                  HTTP状态码    适用场景                    前端处理方式
 * ──────────────────────────────────────────────────────────────────────
 * PermissionDeniedException  403          权限不足                    跳转登录或提示无权限
 * BadRequestException        400          参数/数据错误              提示修正请求参数
 * BusinessException(OK)     200          用户可理解的业务提示       直接展示消息给用户
 * ServiceUnavailableException 503         系统繁忙/限流              降级或稍后重试
 * RuntimeException(未分类)  500          未预期的服务器内部错误      显示通用错误页面
 * </pre>
 * <p>
 * 为什么独立实现而非继承 BusinessException？
 * - 固定 HTTP 403：权限异常永远返回 403，不需要像 BusinessException 支持多种状态码
 * - API 更简洁：只需传入 message 即可，无需记忆 HttpStatus.FORBIDDEN 枚举
 * - 职责更单一：只做一件事（表示权限不足），符合 YAGNI 原则（You Aren't Gonna Need It）
 * <p>
 * 使用示例：
 * <pre>
 * // 场景1：角色检查（使用默认错误码 PERMISSION_DENIED）
 * if (!currentUser.isSuperAdmin()) {
 *     throw new PermissionDeniedException("仅超级管理员可执行此操作");
 * }
 *
 * // 场景2：自定义错误码（细粒度分类）
 * if (!order.getOwnerId().equals(currentUserId)) {
 *     throw new PermissionDeniedException("只能查看自己的订单", "NOT_RESOURCE_OWNER");
 * }
 *
 * // 场景3：使用工厂方法（推荐，与 BusinessException 风格统一）
 * throw PermissionDeniedException.of("权限不足");
 * throw PermissionDeniedException.of("需要VIP会员身份", "VIP_REQUIRED");
 * </pre>
 *
 * @see BusinessException 业务异常基类（支持多种 HTTP 状态码）
 * @see com.echo.config.WebExceptionAdvice 全局异常处理器（捕获此异常并返回 HTTP 403）
 * @author echo
 * @version 1.0
 */
public class PermissionDeniedException extends RuntimeException {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 默认错误码: 权限不足 */
    public static final String DEFAULT_ERROR_CODE = "PERMISSION_DENIED";

    /** 需要超级管理员角色 */
    public static final String ERROR_SUPER_ADMIN_REQUIRED = "SUPER_ADMIN_REQUIRED";

    /** 需要特定权限标识 */
    public static final String ERROR_PERMISSION_REQUIRED = "PERMISSION_REQUIRED";

    /** 资源所有权验证失败 */
    public static final String ERROR_NOT_RESOURCE_OWNER = "NOT_RESOURCE_OWNER";

    /** IP地址不在白名单 */
    public static final String ERROR_IP_NOT_WHITELISTED = "IP_NOT_IN_WHITELIST";

    /** 功能未开放 */
    public static final String ERROR_FEATURE_DISABLED = "FEATURE_DISABLED";

    /** 账号状态异常 */
    public static final String ERROR_ACCOUNT_STATUS_INVALID = "ACCOUNT_STATUS_INVALID";

    /** 内部错误码(用于日志和监控) */
    private final String errorCode;

    // ==================== 构造函数 ====================

    /**
     * 构造权限不足异常（仅指定错误消息，使用默认错误码）
     * <p>
     * 最常用的构造方式，适用于大多数权限检查场景。
     * 错误码将自动设置为 {@link #DEFAULT_ERROR_CODE}（"PERMISSION_DENIED"），无需手动指定。
     * <p>
     * 典型使用场景：
     * <pre>
     * // 角色检查
     * if (!currentUser.hasRole("ADMIN")) {
     *     throw new PermissionDeniedException("仅管理员可执行此操作");
     * }
     *
     * // 功能权限检查
     * if (!currentUser.hasPermission("DATA_EXPORT")) {
     *     throw new PermissionDeniedException("您没有数据导出权限");
     * }
     *
     * // 资源所有权检查
     * if (!order.getOwnerId().equals(currentUserId)) {
     *     throw new PermissionDeniedException("只能查看自己的订单");
     * }
     * </pre>
     <p>
     * 执行流程：
     * 1. 设置用户可见的错误消息 message（通过 super(message)）
     * 2. 初始化 errorCode 为 "PERMISSION_DENIED"
     * 3. 由 WebExceptionAdvice 全局处理器捕获并返回 HTTP 403 响应
     *
     * @param message 用户可见的错误消息（建议使用简洁明了的中文，明确说明缺少什么权限）
     */
    public PermissionDeniedException(String message) {
        super(message);                                    // 设置异常消息
        this.errorCode = DEFAULT_ERROR_CODE;               // 使用默认错误码
    }

    /**
     * 构造权限不足异常（指定错误消息和自定义错误码）
     * <p>
     * 当需要细粒度的权限错误分类时使用此构造函数（如日志追踪、监控统计、前端差异化处理）。
     * 与基础构造函数的区别在于：errorCode 由调用者显式指定，可使用预定义常量或自定义值。
     * <p>
     * 推荐使用预定义常量（避免拼写错误，IDE 支持自动补全）：
     * <pre>
     * // 统一的 PERMISSION_DENIED 无法区分具体原因
     * throw new PermissionDeniedException("权限不足");
     *
     * // 使用预定义常量（推荐）
     * throw new PermissionDeniedException("仅超级管理员可操作", ERROR_SUPER_ADMIN_REQUIRED);
     * throw new PermissionDeniedException("只能查看自己的订单", ERROR_NOT_RESOURCE_OWNER);
     * throw new PermissionDeniedException("IP不在白名单", ERROR_IP_NOT_WHITELISTED);
     * throw new PermissionDeniedException("功能暂未开放", ERROR_FEATURE_DISABLED);
     *
     * // 使用自定义值（特殊场景）
     * throw new PermissionDeniedException("需要高级版套餐", "PREMIUM_PLAN_REQUIRED");
     * </pre>
     * <p>
     * 收益对比（日志输出示例）：
     * <pre>
     * 使用默认错误码：
     *   [权限异常] code:PERMISSION_DENIED, message:权限不足
     *   → 无法区分具体原因，需查看堆栈跟踪或上下文日志
     *
     * 使用自定义错误码：
     *   [权限异常] code:SUPER_ADMIN_REQUIRED, message:仅超级管理员可操作
     *   [权限异常] code:NOT_RESOURCE_OWNER, message:只能查看自己的订单
     *   [权限异常] code:IP_NOT_IN_WHITELISTED, message:IP不在白名单
     *   → 一目了然，可直接过滤分析
     * </pre>
     *
     * @param message   用户可见的错误消息（将直接返回给前端展示）
     * @param errorCode 内部错误码（推荐使用本类定义的常量，如 ERROR_SUPER_ADMIN_REQUIRED）
     */
    public PermissionDeniedException(String message, String errorCode) {
        super(message);                                    // 设置异常消息
        this.errorCode = errorCode;                        // 使用自定义错误码
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取内部错误码
     * <p>
     * 此方法主要被以下组件调用：
     * - WebExceptionAdvice 全局异常处理器：读取 errorCode 并写入 HTTP 响应体
     * - 日志框架：如果配置了 JSON 日志格式，可通过反射读取此字段
     * - 单元测试：断言验证抛出的异常是否携带预期的错误码
     *
     * @return 错误码字符串（永远不会为 null，至少为 DEFAULT_ERROR_CODE）
     */
    public String getErrorCode() {
        return errorCode;
    }

    // ==================== 静态工厂方法（便捷创建 API）====================

    /**
     * 快速创建权限不足异常（使用默认错误码 PERMISSION_DENIED）
     * <p>
     * 与 {@link BusinessException#ofUserFriendly(String)} 工厂方法保持一致的 API 风格，
     * 提供更流畅的创建体验。适用于大多数简单的权限检查场景。
     * <p>
     * 使用对比：
     * <pre>
     * // 传统方式（构造函数）
     * throw new PermissionDeniedException("权限不足");
     *
     * // 工厂方法方式（推荐，语义更清晰）
     * throw PermissionDeniedException.of("权限不足");
     * </pre>
     *
     * @param message 用户可见的错误消息（建议简洁明了，明确说明缺少什么权限）
     * @return PermissionDeniedException 实例（errorCode=PERMISSION_DENIED）
     */
    public static PermissionDeniedException of(String message) {
        return new PermissionDeniedException(message);
    }

    /**
     * 快速创建带自定义错误码的权限不足异常
     * <p>
     * 当需要细粒度的权限错误分类时使用此工厂方法（如监控统计、前端差异化处理）。
     * 强烈推荐使用本类定义的常量作为 errorCode 参数（如 ERROR_SUPER_ADMIN_REQUIRED），
     * 以获得 IDE 自动补全和重构支持。
     * <p>
     * 典型使用示例：
     * <pre>
     * // 场景1：角色级别检查
     * if (!currentUser.isSuperAdmin()) {
     *     throw PermissionDeniedException.of(
     *         "仅超级管理员可执行此操作",
     *         ERROR_SUPER_ADMIN_REQUIRED  // 使用预定义常量
     *     );
     * }
     *
     * // 场景2：资源所有权验证
     * if (!resource.isOwner(currentUserId)) {
     *     throw PermissionDeniedException.of(
     *         "您没有权限访问此资源",
     *         ERROR_NOT_RESOURCE_OWNER
     *     );
     * }
     *
     * // 场景3：IP 白名单校验
     * if (!ipWhitelist.contains(clientIp)) {
     *     throw PermissionDeniedException.of(
     *         "您的 IP 地址不在允许列表中",
     *         ERROR_IP_NOT_WHITELISTED
     *     );
     * }
     * </pre>
     *
     * @param message   用户可见的错误消息（将直接返回给前端展示）
     * @param errorCode 内部错误码（强烈推荐使用本类定义的常量，如 ERROR_SUPER_ADMIN_REQUIRED）
     * @return PermissionDeniedException 实例（携带指定的自定义错误码）
     */
    public static PermissionDeniedException of(String message, String errorCode) {
        return new PermissionDeniedException(message, errorCode);
    }
}
