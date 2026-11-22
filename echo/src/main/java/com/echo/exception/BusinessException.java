package com.echo.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 业务异常基类 - 整个异常体系的核心基石，用于表示可预见的业务错误
 * <p>
 * 核心设计理念：
 * - 异常类型本身携带 HTTP 状态码（编译时确定，运行时不变）
 * - 前端可根据 HttpStatus 精确处理（200=业务提示, 400=参数错误, 503=系统繁忙）
 * - 无需依赖异常消息文本进行分类（彻底解决关键字匹配的脆弱性问题）
 * - 支持国际化（消息可从资源文件加载，状态码保持不变）
 * <p>
 * 业务场景：
 * - 用户可理解的业务提示 → HTTP 200 OK
 *   例：库存不足、座位已被购买、订单状态不允许操作、并发冲突等
 *   前端行为：直接展示 message 给用户，无需修正请求
 * <p>
 * - 参数/数据问题 → HTTP 400 BAD_REQUEST
 *   例：必填参数为空、数据格式错误、资源不存在、业务规则校验失败等
 *   前端行为：提示用户修正请求参数后重新提交
 * <p>
 * - 系统繁忙/限流 → HTTP 503 SERVICE_UNAVAILABLE
 *   例：操作频率过高、连接池耗尽、Redis/MQ 不可用、系统负载过高等
 *   前端行为：降级处理或稍后自动重试
 * <p>
 * 与传统 RuntimeException 的区别：
 * <pre>
 * 传统方案（不推荐）：
 *   throw new RuntimeException("库存不足");
 *   → WebExceptionAdvice 需通过关键字匹配猜测意图（脆弱且难维护）
 *
 * 推荐方案（本类）：
 *   throw new BusinessException("库存不足", HttpStatus.OK);
 *   → HTTP 状态码由异常对象明确声明，无歧义且易维护
 * </pre>
 * <p>
 * 异常体系结构：
 * <pre>
 * RuntimeException (Java 标准异常)
 *   └─ BusinessException (本类 - 携带 httpStatus + errorCode)
 *        ├─ BadRequestException (子类 - 固定 HTTP 400)
 *        └─ ServiceUnavailableException (子类 - 固定 HTTP 503)
 *
 * PermissionDeniedException (独立实现 - HTTP 403)
 * </pre>
 * <p>
 * 使用示例：
 * <pre>
 * // 场景1：用户可理解的业务提示（HTTP 200）- 前端直接展示给用户
 * throw new BusinessException("座位已被他人购买", HttpStatus.OK);
 * // 或使用工厂方法（推荐）
 * throw BusinessException.ofUserFriendly("座位已被他人购买");
 *
 * // 场景2：参数/数据错误（HTTP 400）- 前端需修正请求
 * throw new BusinessException("座位列表不能为空", HttpStatus.BAD_REQUEST);
 * // 或使用工厂方法（推荐）
 * throw BusinessException.ofBadRequest("座位列表不能为空");
 *
 * // 场景3：系统繁忙/限流（HTTP 503）- 前端可降级或重试
 * throw new BusinessException("操作过于频繁", HttpStatus.SERVICE_UNAVAILABLE);
 * // 或使用工厂方法（推荐）
 * throw BusinessException.ofServiceUnavailable("操作过于频繁");
 *
 * // 场景4：带自定义错误码（细粒度分类）
 * throw new BusinessException("座位已锁定", HttpStatus.OK, "SEAT_LOCKED");
 *
 * // 场景5：保留底层异常堆栈（便于问题排查）
 * try {
 *     objectMapper.readValue(json, OrderDTO.class);
 * } catch (JsonProcessingException e) {
 *     throw new BusinessException("订单数据格式错误", HttpStatus.BAD_REQUEST, e);
 * }
 * </pre>
 *
 * @see BadRequestException 参数/数据错误异常子类（固定 HTTP 400）
 * @see ServiceUnavailableException 系统繁忙异常子类（固定 HTTP 503）
 * @see com.echo.config.WebExceptionAdvice 全局异常处理器（读取 httpStatus 并设置响应）
 * @author echo
 * @version 1.0
 * @since 2026-08-31
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** HTTP响应状态码 */
    private final HttpStatus httpStatus;

    /** 业务错误码(用于日志追踪) */
    private final String errorCode;

    // ==================== 构造函数 ====================

    /**
     * 构造业务异常（仅指定错误消息和 HTTP 状态码）
     * <p>
     * 最常用的构造方式，适用于大多数业务场景。
     * 错误码（errorCode）将根据 HTTP 状态码自动推导，无需手动指定。
     * <p>
     * 自动推导规则：
     * - HTTP 200 → errorCode = "BUSINESS_ERROR"
     * - HTTP 400 → errorCode = "PARAM_ERROR"
     * - HTTP 503 → errorCode = "SERVICE_BUSY"
     * - 其他 → errorCode = "UNKNOWN_ERROR"
     * <p>
     * 典型使用场景：
     * <pre>
     * // 库存不足 → 用户友好提示（HTTP 200）
     * throw new BusinessException("该票种已售罄", HttpStatus.OK);
     *
     * // 参数校验失败 → 参数错误（HTTP 400）
     * throw new BusinessException("座位列表不能为空", HttpStatus.BAD_REQUEST);
     *
     * // 限流触发 → 系统繁忙（HTTP 503）
     * throw new BusinessException("操作过于频繁", HttpStatus.SERVICE_UNAVAILABLE);
     * </pre>
     *
     * @param message    用户可见的错误消息（将直接返回给前端展示，建议使用简洁明了的中文）
     * @param httpStatus HTTP 响应状态码（决定前端如何处理此异常，参见字段说明）
     */
    public BusinessException(String message, HttpStatus httpStatus) {
        super(message);                       // 设置异常消息（可通过 getMessage() 获取）
        this.httpStatus = httpStatus;          // 固定 HTTP 状态码（运行时不可变）
        this.errorCode = deriveErrorCode(message, httpStatus);  // 自动推导错误码
    }

    /**
     * 构造业务异常（指定错误消息、HTTP 状态码和自定义错误码）
     * <p>
     * 当需要细粒度的错误分类时使用此构造函数（如日志追踪、监控统计、前端差异化处理）。
     * 与基础构造函数的区别在于：errorCode 由调用者显式指定，而非自动推导。
     * <p>
     * 典型使用场景：
     * <pre>
     * // 统一的 PARAM_ERROR 无法区分具体是哪个字段出错
     * throw new BusinessException("座位列表不能为空", HttpStatus.BAD_REQUEST);
     *
     * // 自定义错误码可以精确定位问题（推荐）
     * throw new BusinessException("座位列表不能为空", HttpStatus.BAD_REQUEST, "SEAT_LIST_EMPTY");
     * throw new BusinessException("演出ID不存在", HttpStatus.BAD_REQUEST, "SHOW_NOT_FOUND");
     * throw new BusinessException("手机号格式错误", HttpStatus.BAD_REQUEST, "PHONE_FORMAT_INVALID");
     * </pre>
     * <p>
     * 收益对比：
     * <pre>
     * 使用通用错误码（PARAM_ERROR）：
     *   日志：[业务异常] code:PARAM_ERROR, httpStatus:400, message:座位列表不能为空
     *   统计：PARAM_ERROR 出现 1000 次/天（无法区分具体原因）
     *
     * 使用自定义错误码（SEAT_LIST_EMPTY）：
     *   日志：[业务异常] code:SEAT_LIST_EMPTY, httpStatus:400, message:座位列表不能为空
     *   统计：SEAT_LIST_EMPTY: 500次, SHOW_NOT_FOUND: 300次, PHONE_FORMAT: 200次（精准定位）
     * </pre>
     *
     * @param message    用户可见的错误消息（将直接返回给前端展示）
     * @param httpStatus HTTP 响应状态码（决定前端如何处理此异常）
     * @param errorCode  内部错误码（用于日志追踪和前端逻辑判断，格式：{模块}_{具体错误}）
     */
    public BusinessException(String message, HttpStatus httpStatus, String errorCode) {
        super(message);                       // 设置异常消息
        this.httpStatus = httpStatus;          // 固定 HTTP 状态码
        this.errorCode = errorCode;            // 使用自定义错误码（覆盖自动推导）
    }

    /**
     * 构造业务异常（带原始异常链，保留完整堆栈信息）
     * <p>
     * 适用于需要保留底层技术异常堆栈信息的场景，便于问题排查和根因分析（Root Cause Analysis）。
     * 当底层技术异常（如 JSON 解析失败、数据库异常、网络超时等）触发业务校验失败时使用。
     * <p>
     * 典型使用场景：
     * <pre>
     * // 场景1：JSON 反序列化失败 → 转换为友好的业务异常
     * try {
     *     OrderDTO order = objectMapper.readValue(jsonString, OrderDTO.class);
     * } catch (JsonProcessingException e) {
     *     throw new BusinessException("订单数据格式错误", HttpStatus.BAD_REQUEST, e);
     * }
     *
     * // 场景2：数据类型转换失败
     * try {
     *     Long showId = Long.valueOf(request.getParam("showId"));
     * } catch (NumberFormatException e) {
     *     throw new BusinessException("演出ID必须为整数", HttpStatus.BAD_REQUEST, e);
     * }
     *
     * // 场景3：数据库查询异常包装
     * try {
     *     Show show = showMapper.selectById(showId);
     * } catch (DataAccessException e) {
     *     throw new BusinessException("演出信息查询失败", HttpStatus.BAD_REQUEST, e);
     * }
     * </pre>
     * <p>
     * 异常链示例（生产环境日志输出）：
     * <pre>
     * BusinessException: 订单数据格式错误
     *   at com.echo.service.OrderServiceImpl.createOrder(OrderServiceImpl.java:85)
     *   at com.echo.controller.OrderController.createOrder(OrderController.java:42)
     *   Caused by: JsonParseException: Unexpected character ('e' (code 101))
     *       at [Source: (String)"{error: true}"; line: 1, column: 3]
     *       at com.fasterxml.jackson.core.JsonParser._constructError(JsonParser.java:1859)
     *       ...
     * </pre>
     * <p>
     * 为什么保留 cause（原始异常）？
     * 1. 问题排查：生产环境可通过 getCause() 找到根本原因（而非仅看到包装后的业务异常）
     * 2. 日志完整性：完整的异常链有助于定位代码行号和执行上下文
     * 3. 调试效率：开发环境可直接点击 IDE 中的堆栈跟踪跳转到出错位置
     *
     * @param message    用户可见的错误消息（将展示给前端，应隐藏技术细节）
     * @param httpStatus HTTP 响应状态码（决定前端如何处理此异常）
     * @param cause      原始异常对象（保留完整堆栈信息，用于日志记录和问题排查）
     */
    public BusinessException(String message, HttpStatus httpStatus, Throwable cause) {
        super(message, cause);                // 设置异常消息并保留原始异常链
        this.httpStatus = httpStatus;          // 固定 HTTP 状态码
        this.errorCode = deriveErrorCode(message, httpStatus);  // 自动推导错误码
    }

    // ==================== 私有工具方法 ====================

    /**
     * 根据消息和 HTTP 状态码自动推导错误码
     * <p>
     * 当调用者未显式指定 errorCode 时（即使用构造函数1或构造函数3），
     * 此方法根据 HTTP 状态码生成合理的默认值，简化调用方的使用成本。
     * <p>
     * 映射规则：
     * <table border="1">
     *   <tr><th>HTTP 状态码</th><th>自动推导的 errorCode</th><th>含义</th></tr>
     *   <tr><td>200 OK</td><td>BUSINESS_ERROR</td><td>通用业务错误</td></tr>
     *   <tr><td>400 BAD_REQUEST</td><td>PARAM_ERROR</td><td>参数/数据错误</td></tr>
     *   <tr><td>503 SERVICE_UNAVAILABLE</td><td>SERVICE_BUSY</td><td>系统繁忙</td></tr>
     *   <tr><td>其他任何值</td><td>UNKNOWN_ERROR</td><td>未知错误（防御性编程）</td></tr>
     * </table>
     * <p>
     * 设计决策说明：
     * - 为什么使用 if-else 而非 Map？当前只有4种映射，if-else 更直观易懂；如果未来映射增多，可重构为 Map
     * - 为什么需要默认值？避免 errorCode 为 null 导致 NPE，保证字段始终有值
     * - 为什么不使用 message 内容推导？因为消息可能变化（国际化、用户输入），而状态码是稳定的
     *
     * @param message    错误消息（当前未使用，保留参数以支持未来基于内容的智能推导）
     * @param httpStatus HTTP 响应状态码（用于确定错误码类型）
     * @return 推导出的错误码字符串（永远不会返回 null）
     */
    private String deriveErrorCode(String message, HttpStatus httpStatus) {
        if (httpStatus == HttpStatus.OK) {
            return "BUSINESS_ERROR";
        } else if (httpStatus == HttpStatus.BAD_REQUEST) {
            return "PARAM_ERROR";
        } else if (httpStatus == HttpStatus.SERVICE_UNAVAILABLE) {
            return "SERVICE_BUSY";
        } else {
            return "UNKNOWN_ERROR";
        }
    }

    // ==================== 静态工厂方法（便捷创建 API）====================

    /**
     * 快速创建"用户可理解的业务提示"异常（HTTP 200 OK）
     * <p>
     * 适用场景：需要向用户展示友好提示信息的情况，
     * 前端收到后会直接将 message 展示给用户（通常以 Toast 或对话框形式）。
     * <p>
     * 典型业务场景：
     * - 资源不足：库存不足、票种已售罄、座位已被他人锁定
     * - 状态冲突：订单状态不允许取消、订单已过期、已核销无法退款
     * - 并发问题：乐观锁冲突、操作过于频繁（非限流场景）、数据已被修改
     * - 业务规则：超过购票数量限制、不在售票时间范围内、不符合优惠条件
     * <p>
     * 前端处理建议：
     * <pre>
     * // 收到 HTTP 200 但 Result.code != 0 时
     * if (response.status === 200 && response.data.code !== 0) {
     *     toast(response.data.message);  // 直接展示："该票种已售罄"
     * }
     * </pre>
     * <p>
     * 使用示例：
     * <pre>
     * // 场景1：库存校验
     * if (ticket.getStock() <= 0) {
     *     throw BusinessException.ofUserFriendly("该票种已售罄");
     * }
     *
     * // 场景2：状态校验
     * if (!order.canCancel()) {
     *     throw BusinessException.ofUserFriendly("当前订单状态不允许取消");
     * }
     *
     * // 场景3：并发控制
     * if (!casUpdateOrderStatus(orderId, fromStatus, toStatus)) {
     *     throw BusinessException.ofUserFriendly("操作过于频繁，请刷新页面重试");
     * }
     * </pre>
     *
     * @param message 用户可见的错误消息（建议使用简洁明了的中文，长度控制在50字以内）
     * @return BusinessException 实例（httpStatus=200, errorCode="BUSINESS_ERROR"）
     */
    public static BusinessException ofUserFriendly(String message) {
        return new BusinessException(message, HttpStatus.OK);
    }

    /**
     * 快速创建"参数/数据错误"异常（HTTP 400 BAD_REQUEST）
     * <p>
     * 适用场景：请求参数缺失、格式错误、数据不存在或业务规则校验失败等情况，
     * 前端收到后需要提示用户修正请求参数后重新提交。
     * <p>
     * 注意：此方法返回的是 {@link BadRequestException} 子类实例（而非父类 BusinessException），
     * 可利用多态特性进行精确的类型匹配（如 instanceof 判断）。
     * <p>
     * 典型业务场景：
     * - 参数校验：必填字段为空、参数类型错误、参数值超出范围
     * - 数据存在性：订单ID不存在、演出已下架、票种已停售
     * - 格式验证：手机号格式不正确、邮箱格式无效、日期格式错误
     * - 业务规则：单次购票数量超限、选座数量超出限制、不符合年龄限制
     * <p>
     * 前端处理建议：
     * <pre>
     * // 收到 HTTP 400 时
     * if (response.status === 400) {
     *     showErrorDialog(response.data.message);  // 提示用户修正
     *     highlightInvalidFields(response.data.errorCode);  // 高亮错误字段（可选）
     * }
     * </pre>
     * <p>
     * 使用示例：
     * <pre>
     * // 场景1：非空校验
     * if (dto.getSeatIds() == null || dto.getSeatIds().isEmpty()) {
     *     throw BusinessException.ofBadRequest("座位列表不能为空");
     * }
     *
     * // 场景2：数据存在性检查
     * Show show = showService.getById(showId);
     * if (show == null) {
     *     throw BusinessException.ofBadRequest("演出不存在，ID=" + showId);
     * }
     *
     * // 场景3：格式验证
     * if (!PhoneUtils.isValid(phone)) {
     *     throw BusinessException.ofBadRequest("手机号格式不正确");
     * }
     * </pre>
     *
     * @param message 用户可见的错误消息（建议明确指出哪个字段或数据有问题）
     * @return BadRequestException 实例（httpStatus=400, errorCode="PARAM_ERROR"）
     * @see BadRequestException 参数/数据错误异常子类
     */
    public static BadRequestException ofBadRequest(String message) {
        return new BadRequestException(message);
    }

    /**
     * 快速创建"系统繁忙/服务不可用"异常（HTTP 503 SERVICE_UNAVAILABLE）
     * <p>
     * 适用场景：系统临时不可用或需要限制访问频率的情况，
     * 前端收到后可选择降级处理、显示友好提示或稍后自动重试。
     * <p>
     * 典型业务场景：
     * - 限流控制：用户操作频率过高（如每秒超过3次请求）
     * - 资源耗尽：数据库连接池耗尽、线程池满、Redis/MQ 连接数达上限
     * - 外部依赖不可用：Redis 宕机、RocketMQ 故障、第三方 API 超时
     * - 系统负载过高：CPU/内存使用率超过阈值、GC 频繁
     * - 维护模式：系统正在部署或升级，暂时停止服务
     * <p>
     * 前端处理建议：
     * <pre>
     * // 收到 HTTP 503 时
     * if (response.status === 503) {
     *     showRetryDialog(response.data.message, () => {
     *         retryRequest();  // 自动重试（指数退避策略）
     *     });
     *     // 或降级处理：展示缓存数据或跳转到维护页面
     * }
     * </pre>
     * <p>
     * 使用示例：
     * <pre>
     * // 场景1：限流检查
     * if (rateLimiter.tryAcquire(userId).isEmpty()) {
     *     throw BusinessException.ofServiceUnavailable("操作过于频繁，请5秒后再试");
     * }
     *
     * // 场景2：外部依赖健康检查
     * if (!redisTemplate.hasKey("health:check")) {
     *     throw BusinessException.ofServiceUnavailable("系统繁忙，请稍后重试");
     * }
     *
     * // 场景3：连接池监控
     * if (dataSource.getActiveConnections() >= dataSource.getMaxPoolSize() * 0.9) {
     *     throw BusinessException.ofServiceUnavailable("系统负载过高，请稍后再试");
     * }
     * </pre>
     *
     * @param message 用户可见的错误消息（建议包含重试建议或预计恢复时间）
     * @return ServiceUnavailableException 实例（httpStatus=503, errorCode="SERVICE_BUSY"）
     * @see ServiceUnavailableException 系统繁忙异常子类
     */
    public static ServiceUnavailableException ofServiceUnavailable(String message) {
        return new ServiceUnavailableException(message);
    }
}
