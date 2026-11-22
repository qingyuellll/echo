package com.echo.controller;

import com.echo.annotation.RequireRole;
import com.echo.constants.AppMessages;
import com.echo.dto.AdminChangePasswordDTO;
import com.echo.dto.AdminLoginDTO;
import com.echo.dto.Result;
import com.echo.dto.SeatBatchCreateDTO;
import com.echo.enums.AdminRoleLevel;
import com.echo.entity.SeckillTicket;
import com.echo.entity.Show;
import com.echo.entity.Ticket;
import com.echo.service.IAdminService;
import com.echo.service.ITicketService;
import com.echo.utils.RedisConstants;
import com.echo.utils.UserHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理员接口
 * <p>
 * 核心接口：
 * - POST /admin/login           管理员登录（密码/验证码）
 * - POST /admin/logout          管理员登出
 * - POST /admin/code            发送管理员登录验证码
 * - POST /admin/password/code   发送修改密码验证码
 * - PUT  /admin/password        修改密码
 * <p>
 * 角色等级：
 * 0=超级管理员（全权限）, 1=普通管理员（部分权限）
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@Tag(name = "管理员管理")
public class AdminController {

    @Resource
    private IAdminService adminService;

    @Resource
    private ITicketService ticketService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private com.echo.utils.AdminBootstrapGuard bootstrapGuard;

    @Resource
    private org.springframework.context.ApplicationContext applicationContext;

    // ==================== 认证相关 ====================

    @Operation(summary = "发送登录验证码", description = "向指定手机号发送登录验证码")
    @PostMapping("/code")
    public Result<?> sendCode(@RequestParam("phone") String phone) {
        return adminService.sendCode(phone);
    }

    @Operation(summary = "管理员登录", description = "支持密码登录或验证码登录")
    @PostMapping("/login")
    public Result<?> login(@RequestBody AdminLoginDTO loginForm) {
        return adminService.login(loginForm);
    }

    @Operation(summary = "管理员登出", description = "清除登录状态")
    @PostMapping("/logout")
    public Result<?> logout() {
        return adminService.logout();
    }

    @Operation(summary = "发送修改密码验证码", description = "向当前管理员手机发送验证码")
    @PostMapping("/password/code")
    public Result<?> sendPasswordCode() {
        return adminService.sendPasswordCode();
    }

    @Operation(summary = "修改密码", description = "使用新密码+验证码修改密码")
    @PutMapping("/password")
    public Result<?> changePassword(@RequestBody AdminChangePasswordDTO dto) {
        return adminService.changePassword(dto);
    }

    @Operation(summary = "获取当前管理员信息", description = "获取当前登录管理员的详细信息")
    @GetMapping("/info")
    public Result<?> getAdminInfo() {
        return adminService.getAdminInfo();
    }

    @Operation(summary = "重置超级管理员密码",
            description = """
                    引导入口：用于全新部署或密码遗失时恢复访问，重置为随机生成的 12 位临时密码。
                    授权方式为请求头 X-Bootstrap-Token，令牌由部署方通过环境变量 ADMIN_BOOTSTRAP_TOKEN 注入。
                    未配置该环境变量时通道关闭，调用会被拒绝。
                    临时密码只在本次响应中返回一次，请登录后立即修改。""")
    @PostMapping("/init-password")
    public Result<?> initPassword(
            @Parameter(description = "引导令牌，与 ADMIN_BOOTSTRAP_TOKEN 配置值一致")
            @RequestHeader(value = "X-Bootstrap-Token", required = false) String bootstrapToken) {

        if (!bootstrapGuard.isEnabled()) {
            bootstrapGuard.logRejected("引导通道未开启，未配置 ADMIN_BOOTSTRAP_TOKEN");
            return Result.fail(AppMessages.ADMIN_BOOTSTRAP_DISABLED);
        }

        if (!bootstrapGuard.matches(bootstrapToken)) {
            bootstrapGuard.logRejected("引导令牌不匹配");
            return Result.fail(AppMessages.ADMIN_BOOTSTRAP_TOKEN_INVALID);
        }

        log.warn("[AdminController] 通过引导通道重置超级管理员密码 - 时间:{}", java.time.LocalDateTime.now());
        return adminService.initSuperAdminPassword(true);
    }

    @RequireRole(AdminRoleLevel.SUPER_ADMIN)
    @Operation(summary = "重置超级管理员密码（已登录超管）",
            description = "已登录的超级管理员把自己账号的密码重置为随机 12 位临时密码，用于凭据轮换")
    @PostMapping("/reset-super-password")
    public Result<?> resetSuperPassword() {
        log.warn("[AdminController] 超级管理员自助重置密码 - 时间:{}", java.time.LocalDateTime.now());
        return adminService.initSuperAdminPassword(false);
    }

    // ==================== 演出管理 ====================

    @Operation(summary = "演出列表（管理端）", description = "分页查询所有演出（含下架/待发布），支持多条件筛选和排序，仅管理员可用")
    @GetMapping("/show/list")
    public Result<?> listShows(
            @Parameter(description = "演出名称（模糊搜索）")
            @RequestParam(value = "name", required = false) String name,

            @Parameter(description = "演出ID（精确匹配）")
            @RequestParam(value = "id", required = false) Long id,

            @Parameter(description = "演出状态：0=下架, 1=上架")
            @RequestParam(value = "status", required = false) Integer status,

            @Parameter(description = "演出类型ID")
            @RequestParam(value = "typeId", required = false) Integer typeId,

            @Valid com.echo.dto.PageQuery pageQuery) {

        log.info("[管理端] 查询演出列表 - 名称:{}, ID:{}, 状态:{}, 类型ID:{}, 页码:{}",
                name, id, status, typeId, pageQuery.getCurrent());
        return adminService.listShows(name, id, status, typeId, pageQuery);
    }

    @RequireRole(AdminRoleLevel.SUPER_ADMIN)
    @Operation(summary = "清除演出缓存", description = "强制清除所有演出缓存，使前端立即获取最新数据，仅超级管理员")
    @PostMapping("/show/cache/clear")
    public Result<?> clearShowCache() {
        log.info("[管理端] [超级管理员] 执行清除演出缓存 - 操作者:{}", com.echo.utils.PermissionUtils.getCurrentAdminName());
        return adminService.clearShowCache();
    }

    @RequireRole(AdminRoleLevel.SUPER_ADMIN)
    @Operation(summary = "创建演出", description = "创建新的演出信息（仅超级管理员）")
    @PostMapping("/show")
    public Result<?> createShow(@RequestBody Show show) {
        log.info("[AdminController] [超级管理员] 创建演出，name:{}, typeId:{}, 操作者:{}",
                show.getName(), show.getTypeId(), com.echo.utils.PermissionUtils.getCurrentAdminName());
        return adminService.createShow(show);
    }

    @Operation(summary = "演出下架", description = "将演出状态设为下架")
    @PutMapping("/show/{id}/offline")
    public Result<?> offlineShow(@PathVariable("id") Long id) {
        return adminService.offlineShow(id);
    }

    @Operation(summary = "演出上架", description = "将演出状态设为上架，并通知关注者")
    @PutMapping("/show/{id}/online")
    public Result<?> onlineShow(@PathVariable("id") Long id) {
        return adminService.onlineShow(id);
    }

    @Operation(summary = "更新演出信息", description = "根据ID更新演出的基本信息（RESTful风格：ID在路径中）")
    @PutMapping("/show/{id}")
    public Result<?> updateShow(
            @NotNull(message = "演出ID不能为空")
            @Min(value = 1, message = "演出ID必须大于0")
            @Parameter(description = "演出ID", required = true)
            @PathVariable("id") Long id,
            @RequestBody Show show) {

        //  明确校验：路径变量ID必须与请求体ID一致，防止越权操作
        if (show.getId() != null && !id.equals(show.getId())) {
            log.warn("[编辑演出] 路径ID与请求体ID不一致，拒绝请求 - pathId:{}, bodyId:{}", id, show.getId());
            return Result.fail("请求参数错误：路径ID与演出信息中的ID不一致");
        }

        // 统一使用路径变量ID（RESTful规范）
        show.setId(id);

        return adminService.updateShow(show);
    }

    @Operation(summary = "删除演出", description = "根据ID删除演出")
    @DeleteMapping("/show/{id}")
    public Result<?> deleteShow(
            @NotNull(message = "演出ID不能为空")
            @Min(value = 1, message = "演出ID必须大于0")
            @PathVariable("id") Long id) {
        return adminService.deleteShow(id);
    }

    // ==================== 票种管理 ====================
    // 注意：票种的CRUD操作已移至 AdminTicketController（含完整权限控制）
    // AdminController 不再提供票种接口，避免路径冲突

    /* 以下接口已迁移至 AdminTicketController：
     - GET    /admin/ticket/list          → /admin/tickets/list
     - POST   /admin/ticket               → /admin/tickets
     - PUT    /admin/ticket               → /admin/tickets
     - DELETE /admin/ticket/{id}           → /admin/tickets/{id}
     - PUT    /admin/ticket/{id}/stock     → /admin/tickets/{id}/stock
     - POST   /admin/ticket/{id}/seats/batch → /admin/tickets/{id}/seats/batch
     - POST   /admin/ticket/with-seats     → /admin/tickets/with-seats
     - POST   /admin/ticket/seckill        → /admin/tickets/seckill (需添加)
     - PUT    /admin/ticket/seckill        → /admin/tickets/seckill (需添加)
    */

    // ==================== 订单管理 ====================

    @Operation(summary = "订单列表", description = "分页查询订单列表，支持按状态、关键词、用户名筛选和排序，返回订单详情（含票种信息、用户信息）")
    @GetMapping("/order/list")
    public Result<?> listOrders(
            @Parameter(description = "订单状态：0=待支付, 1=秒杀预占, 2=已支付, 3=已核销, 4=已取消, 5=已退款")
            @RequestParam(value = "status", required = false) Integer status,

            @Parameter(description = "关键词搜索（订单号/演出名称/票种名称）")
            @RequestParam(value = "keyword", required = false) String keyword,

            @Parameter(description = "用户名/手机号筛选")
            @RequestParam(value = "userName", required = false) String userName,

            @Valid com.echo.dto.PageQuery pageQuery) {
        return adminService.listOrders(status, keyword, userName, pageQuery);
    }

    @Operation(summary = "订单详情", description = "按订单ID查询单条订单详情，返回票种、演出、用户与核销码信息")
    @GetMapping("/order/{id}")
    public Result<?> getOrderDetail(
            @Parameter(description = "订单ID", required = true)
            @PathVariable("id") Long orderId) {
        return adminService.getOrderDetail(orderId);
    }

    @Operation(summary = "审批退款", description = "管理员审批用户退款申请")
    @PostMapping("/order/{id}/approve-refund")
    public Result<?> approveRefund(@PathVariable("id") Long orderId) {
        return adminService.approveRefund(orderId);
    }

    @Operation(summary = "仪表盘数据", description = "获取系统统计数据")
    @GetMapping("/dashboard")
    public Result<?> dashboard() {
        return adminService.dashboard();
    }

    @Operation(summary = "导出订单", description = "导出订单数据为CSV格式")
    @GetMapping("/order/export")
    public Result<?> exportOrders(
            @RequestParam(value = "status", required = false) Integer status) {
        return adminService.exportOrders(status);
    }

    // ==================== 员工管理 ====================

    @Operation(summary = "员工列表", description = "获取所有管理员/员工列表")
    @GetMapping("/staff/list")
    public Result<?> listStaff() {
        return adminService.listStaff();
    }

    @Operation(summary = "新增员工", description = "将普通用户提升为管理员")
    @PostMapping("/staff/add")
    public Result<?> addStaff(@Valid @RequestBody com.echo.entity.Admin admin) {
        return adminService.addStaff(admin);
    }

    @Operation(summary = "移除员工", description = "移除管理员身份")
    @PostMapping("/staff/remove")
    public Result<?> removeStaff(@RequestParam("userId") Long userId) {
        return adminService.removeStaff(userId);
    }

    @Operation(summary = "禁用员工", description = "禁用管理员账号")
    @PutMapping("/staff/{id}/disable")
    public Result<?> disableStaff(@PathVariable("id") Long adminId) {
        return adminService.disableStaff(adminId);
    }

    @Operation(summary = "启用员工", description = "启用已禁用的管理员账号")
    @PutMapping("/staff/{id}/enable")
    public Result<?> enableStaff(@PathVariable("id") Long adminId) {
        return adminService.enableStaff(adminId);
    }

    // ==================== 用户管理 ====================

    @Operation(summary = "用户列表", description = "分页查询用户列表，支持关键词、手机号、ID搜索和排序，返回用户基本信息（脱敏手机号）")
    @GetMapping("/user/list")
    public Result<?> listUsers(
            @Parameter(description = "关键词搜索（昵称/姓名）")
            @RequestParam(value = "keyword", required = false) String keyword,

            @Parameter(description = "手机号筛选（支持模糊匹配）")
            @RequestParam(value = "phone", required = false) String phone,

            @Parameter(description = "精确用户ID")
            @RequestParam(value = "id", required = false) Long id,

            @Parameter(description = "当前页码（从1开始）", example = "1")
            @RequestParam(value = "current", defaultValue = "1") Integer current,

            @Parameter(description = "每页记录数（1-100）", example = "10")
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortOrder", required = false) String sortOrder) {
        return adminService.listUsers(keyword, phone, id, current, pageSize, sortBy, sortOrder);
    }

    @Operation(summary = "用户详情", description = "获取用户详细信息")
    @GetMapping("/user/{id}")
    public Result<?> getUserDetail(@PathVariable("id") Long userId) {
        return adminService.getUserDetail(userId);
    }

    @Operation(summary = "禁用用户", description = "禁用普通用户账号")
    @PutMapping("/user/{id}/disable")
    public Result<?> disableUser(@PathVariable("id") Long userId) {
        return adminService.disableUser(userId);
    }

    @Operation(summary = "启用用户", description = "启用已禁用的用户账号")
    @PutMapping("/user/{id}/enable")
    public Result<?> enableUser(@PathVariable("id") Long userId) {
        return adminService.enableUser(userId);
    }

    // ==================== 批量操作接口 ====================

    @Operation(summary = "批量操作演出", description = "支持批量上架/下架/删除演出。operation: online/offline/delete。一次最多操作50个演出，需要管理员权限。")
    @PostMapping("/show/batch")
    public Result<?> batchOperationShows(
            @Valid
            @Parameter(description = "批量操作参数", required = true)
            @RequestBody com.echo.dto.BatchOperationDTO batchDTO) {

        log.info("[Admin] 批量操作演出 - operation:{}, count:{}",
                batchDTO.getOperation(), batchDTO.getIds().size());

        // 限制单次操作数量
        if (batchDTO.getIds().size() > 50) {
            return Result.fail(AppMessages.BATCH_OPERATION_LIMIT_50);
        }

        return adminService.batchOperationShows(batchDTO);
    }

    @Operation(summary = "批量操作票种", description = "支持批量更新/删除票种。operation: update/delete。update时需在extraData中提供更新的字段。")
    @PostMapping("/ticket/batch")
    public Result<?> batchOperationTickets(
            @Valid
            @Parameter(description = "批量操作参数", required = true)
            @RequestBody com.echo.dto.BatchOperationDTO batchDTO) {

        log.info("[Admin] 批量操作票种 - operation:{}, count:{}",
                batchDTO.getOperation(), batchDTO.getIds().size());

        if (batchDTO.getIds().size() > 100) {
            return Result.fail(AppMessages.BATCH_OPERATION_LIMIT_100);
        }

        return adminService.batchOperationTickets(batchDTO);
    }

    @Operation(summary = "批量操作订单", description = "支持批量审批退款。operation: approve-refund/reject-refund。用于快速处理多个退款申请。")
    @PostMapping("/order/batch")
    public Result<?> batchOperationOrders(
            @Valid
            @Parameter(description = "批量操作参数", required = true)
            @RequestBody com.echo.dto.BatchOperationDTO batchDTO) {

        log.info("[Admin] 批量操作订单 - operation:{}, count:{}",
                batchDTO.getOperation(), batchDTO.getIds().size());

        if (batchDTO.getIds().size() > 30) {
            return Result.fail(AppMessages.BATCH_OPERATION_LIMIT_30);
        }

        return adminService.batchOperationOrders(batchDTO);
    }

    @Operation(summary = "批量操作用户", description = "支持批量启用/禁用用户。operation: enable/disable。需提供reason作为操作原因。")
    @PostMapping("/user/batch")
    public Result<?> batchOperationUsers(
            @Valid
            @Parameter(description = "批量操作参数", required = true)
            @RequestBody com.echo.dto.BatchOperationDTO batchDTO) {

        log.info("[Admin] 批量操作用户 - operation:{}, count:{}, reason:{}",
                batchDTO.getOperation(),
                batchDTO.getIds().size(),
                batchDTO.getReason());

        if (batchDTO.getIds().size() > 50) {
            return Result.fail(AppMessages.BATCH_OPERATION_LIMIT_50);
        }

        // 禁用操作必须提供原因
        if (batchDTO.isDisable() &&
            (batchDTO.getReason() == null || batchDTO.getReason().trim().isEmpty())) {
            return Result.fail(AppMessages.ADMIN_DISABLE_USER_REASON_REQUIRED);
        }

        return adminService.batchOperationUsers(batchDTO);
    }

    // ==================== 操作日志与监控 ====================

    @Resource
    private com.echo.service.IOperationLogService operationLogService;

    @Operation(summary = "查询操作日志", description = "支持按操作者、操作类型、结果状态、时间范围筛选。返回分页结果，包含操作详情和性能数据。")
    @GetMapping("/operation-logs")
    public Result<?> queryOperationLogs(
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) Integer resultStatus,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "20") Integer pageSize) {

        // 解析时间参数
        java.time.LocalDateTime start = null;
        java.time.LocalDateTime end = null;

        if (startTime != null && !startTime.isEmpty()) {
            start = java.time.LocalDateTime.parse(startTime);
        }
        if (endTime != null && !endTime.isEmpty()) {
            end = java.time.LocalDateTime.parse(endTime);
        }

        return operationLogService.queryLogs(
                operatorId,
                operationType,
                resultStatus,
                start,
                end,
                current,
                pageSize
        );
    }

    @Operation(summary = "获取今日统计数据", description = "返回今日操作总数、成功数、失败数、平均耗时、成功率等指标。")
    @GetMapping("/monitoring/today-stats")
    public Result<?> getTodayStatistics() {
        return Result.ok(operationLogService.getTodayStatistics());
    }

    @Operation(summary = "获取最近操作日志", description = "返回最近N条操作记录，用于监控仪表盘实时展示。")
    @GetMapping("/monitoring/recent-logs")
    public Result<?> getRecentLogs(
            @RequestParam(defaultValue = "10") Integer limit) {
        return Result.ok(operationLogService.getRecentLogs(limit));
    }

    @Operation(summary = "获取每日操作趋势", description = "返回最近30天的操作数量趋势数据，用于图表展示。")
    @GetMapping("/monitoring/daily-trend")
    public Result<?> getDailyTrend() {
        return Result.ok(operationLogService.getDailyTrend());
    }

    @Operation(summary = "获取每小时性能数据", description = "返回最近24小时每小时的平均响应时间。")
    @GetMapping("/monitoring/hourly-performance")
    public Result<?> getHourlyPerformance() {
        return Result.ok(operationLogService.getHourlyPerformance());
    }

    // ==================== 票务核销统计 ====================

    @Resource
    private com.echo.service.ITicketOrderService ticketOrderService;

    @Operation(summary = "获取核销统计数据", description = "返回今日核销数量、总已核销票数、待处理订单数")
    @GetMapping("/verification/statistics")
    public Result<?> getVerificationStatistics() {
        Map<String, Object> stats = new HashMap<>();

        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDateTime todayStart = today.atStartOfDay();
        java.time.LocalDateTime todayEnd = today.plusDays(1).atStartOfDay();

        long todayVerifiedCount = ticketOrderService.count(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.TicketOrder>()
                .eq("status", 3)
                .between("use_time", todayStart, todayEnd)
        );

        long totalVerifiedCount = ticketOrderService.count(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.TicketOrder>()
                .eq("status", 3)
        );
        long pendingOrderCount = ticketOrderService.count(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.TicketOrder>()
                .eq("status", 2)
        );

        stats.put("todayCount", todayVerifiedCount);
        stats.put("totalCount", totalVerifiedCount);
        stats.put("pendingCount", pendingOrderCount);

        log.info("[核销统计] 今日:{}, 总计:{}, 待处理:{}",
                todayVerifiedCount, totalVerifiedCount, pendingOrderCount);

        return Result.ok(stats);
    }

    @Operation(summary = "获取最近核销记录", description = "返回最近已核销的订单列表，只包含实际执行过核销操作的订单")
    @GetMapping("/verification/recent")
    public Result<?> getRecentVerifications(
            @RequestParam(defaultValue = "20") Integer limit) {

        List<com.echo.entity.TicketOrder> orders = ticketOrderService.list(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.TicketOrder>()
                .eq("status", 3)
                .isNotNull("use_time")
                .orderByDesc("use_time")
                .last("LIMIT " + Math.min(limit, 100))
        );

        if (orders.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        Set<Long> ticketIds = orders.stream()
            .map(com.echo.entity.TicketOrder::getTicketId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        final Map<Long, com.echo.entity.Ticket> ticketMap;
        if (!ticketIds.isEmpty()) {
            ticketMap = applicationContext.getBean(com.echo.service.ITicketService.class).listByIds(ticketIds)
                .stream()
                .collect(Collectors.toMap(com.echo.entity.Ticket::getId, t -> t, (a, b) -> a));
        } else {
            ticketMap = Collections.emptyMap();
        }

        Set<Long> showIds = ticketMap.values().stream()
            .map(com.echo.entity.Ticket::getShowId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        final Map<Long, com.echo.entity.Show> showMap;
        if (!showIds.isEmpty()) {
            showMap = applicationContext.getBean(com.echo.service.IShowService.class).listByIds(showIds)
                .stream()
                .collect(Collectors.toMap(com.echo.entity.Show::getId, s -> s, (a, b) -> a));
        } else {
            showMap = Collections.emptyMap();
        }

        List<Map<String, Object>> result = new ArrayList<>();

        for (com.echo.entity.TicketOrder order : orders) {
            Map<String, Object> item = new HashMap<>();

            final com.echo.entity.Ticket ticket = ticketMap.get(order.getTicketId());
            final com.echo.entity.Show show = (ticket != null) ? showMap.get(ticket.getShowId()) : null;

            item.put("id", order.getId());
            item.put("ticketTitle", ticket != null ? ticket.getTitle() : "-");
            item.put("showName", show != null ? show.getName() : "-");

            final int orderQuantity = (order.getQuantity() != null) ? order.getQuantity() : 1;
            item.put("quantity", orderQuantity);
            item.put("subOrderIndex", order.getSubOrderIndex());
            item.put("status", order.getStatus());
            item.put("verificationCode", order.getVerificationCode());

            long verifiedCount = 0;
            for (int idx = 0; idx < orderQuantity; idx++) {
                String key = "verify:used:" + order.getId() + ":" + idx;
                if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                    verifiedCount++;
                }
            }

            boolean isFullyVerified = (verifiedCount == orderQuantity);
            boolean isPartiallyVerified = (verifiedCount > 0 && verifiedCount < orderQuantity);

            item.put("isVerified", isFullyVerified);
            item.put("verificationStatus",
                    isFullyVerified ? "已核销" :
                            isPartiallyVerified ? "部分核销(" + verifiedCount + "/" + orderQuantity + ")" :
                                    "未核销");
            item.put("verifiedAt", order.getUseTime());
            item.put("verifiedCount", verifiedCount);
            item.put("totalTickets", orderQuantity);

            result.add(item);
        }

        log.info("[核销记录] 查询最近{}条已核销记录，实际返回{}条", limit, result.size());

        return Result.ok(result);
    }

    @Operation(summary = "导出操作日志", description = "根据查询条件导出Excel格式的操作日志文件。")
    @GetMapping("/operation-logs/export")
    public Result<?> exportOperationLogs(
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) Integer resultStatus,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {

        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("operatorId", operatorId);
        params.put("operationType", operationType);
        params.put("resultStatus", resultStatus);
        params.put("startTime", startTime);
        params.put("endTime", endTime);

        return operationLogService.exportLogs(params);
    }

    // ==================== 操作回滚 ====================

    @Resource
    private com.echo.service.impl.OperationRollbackService rollbackService;

    @Operation(summary = "获取可回滚的操作列表", description = "返回最近24小时内成功或部分成功的批量操作记录。只有这些操作可以被回滚。")
    @GetMapping("/rollback/rollable-operations")
    public Result<?> getRollableOperations(
            @RequestParam(required = false) String targetType) {
        return Result.ok(rollbackService.getRollableOperations(targetType));
    }

    @Operation(summary = "预览回滚影响范围", description = "查看回滚操作会影响的实体数量和具体信息，帮助管理员确认是否执行回滚。")
    @GetMapping("/rollback/preview/{logId}")
    public Result<?> previewRollback(@PathVariable Long logId) {
        return rollbackService.previewRollback(logId);
    }

    @RequireRole(AdminRoleLevel.NORMAL_ADMIN)
    @Operation(summary = "执行操作回滚", description = "对指定ID的批量操作进行回滚。仅支持24小时内的操作。回滚会自动执行反向操作（如：上架→下架）。删除操作无法自动恢复。")
    @PostMapping("/rollback/{logId}")
    public Result<?> executeRollback(@PathVariable Long logId) {
        return rollbackService.rollbackOperation(logId);
    }

    @RequireRole(AdminRoleLevel.NORMAL_ADMIN)
    @Operation(summary = "清除所有演出缓存", description = "强制清除Redis中的所有演出缓存，使前端获取最新数据。仅管理员可操作。")
    @PostMapping("/cache/clear-shows")
    public Result<?> clearAllShowCaches() {
        Set<String> keys = stringRedisTemplate.keys(RedisConstants.CACHE_SHOW_KEY + "*");
        if (!keys.isEmpty()) {
            long deleted = stringRedisTemplate.delete(keys);
            log.info("[清除缓存] 已删除 {} 个演出缓存", deleted);
            return Result.ok(AppMessages.format(AppMessages.SHOW_CACHE_CLEAR_SUCCESS, deleted));
        } else {
            return Result.ok(AppMessages.SHOW_CACHE_NOT_FOUND);
        }
    }

    /**
     *  清除所有票种缓存（SQL变更后使用）
     * <p>
     * 使用场景：
     * - SQL查询条件修改后（如添加status=1过滤未上架票种）
     * - 强制用户端获取最新的票种列表
     *
     * @return 清除的缓存数量
     */
    @RequireRole(AdminRoleLevel.NORMAL_ADMIN)
    @Operation(summary = "清除所有票种缓存", description = "强制清除Redis中所有票种缓存，使SQL变更立即生效")
    @PostMapping("/cache/clear-tickets")
    public Result<?> clearAllTicketCaches() {
        int deletedCount = ticketService.clearAllTicketCache();

        if (deletedCount > 0) {
            log.info("[管理] 管理员已手动清除 {} 个票种缓存", deletedCount);
            return Result.ok("成功清除 " + deletedCount + " 个票种缓存，用户端将获取最新数据（只显示已上架票种）");
        } else if (deletedCount == 0) {
            return Result.ok("当前无票种缓存需要清除");
        } else {
            return Result.fail("清除票种缓存失败，请检查Redis连接");
        }
    }

    @RequireRole(AdminRoleLevel.NORMAL_ADMIN)
    @Operation(summary = "手动触发数据修复", description = "修复演出已售数量和票种已售数量（用于历史数据修正）")
    @PostMapping("/repair-data")
    public Result<?> repairData() {
        log.info("[数据修复] 手动触发数据修复...");

        com.echo.utils.DataRepairScript dataRepairScript = applicationContext.getBean(com.echo.utils.DataRepairScript.class);
        dataRepairScript.repairShowSoldCount();
        dataRepairScript.repairTicketSoldCount();

        log.info("[数据修复] 手动修复完成");
        return Result.ok(AppMessages.ADMIN_DATA_REPAIR_SUCCESS);
    }
}
