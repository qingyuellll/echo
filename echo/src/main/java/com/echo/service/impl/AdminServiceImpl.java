package com.echo.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.constants.AppMessages;
import com.echo.dto.*;
import com.echo.entity.*;
import com.echo.enums.AdminRoleLevel;
import com.echo.enums.AdminStatus;
import com.echo.enums.NotificationType;
import com.echo.enums.OrderStatus;
import com.echo.enums.ShowStatus;
import com.echo.enums.TicketLock;
import com.echo.enums.TicketStatus;
import com.echo.mapper.AdminMapper;
import com.echo.message.NotificationMessage;
import com.echo.service.*;
import com.echo.utils.MqConstants;
import com.echo.utils.PasswordEncoder;
import com.echo.utils.PermissionUtils;
import com.echo.utils.RedisConstants;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.echo.utils.BusinessConstants.*;
import static com.echo.utils.RedisConstants.*;

/**
 * 管理员服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class AdminServiceImpl extends ServiceImpl<AdminMapper, Admin> implements IAdminService {

    @Resource
    private IShowService showService;
    @Resource
    private com.echo.mapper.ShowMapper showMapper;
    @Resource
    private ITicketService ticketService;
    @Resource
    private ITicketOrderService ticketOrderService;
    @Resource
    private ISeckillTicketService seckillTicketService;
    @Resource
    private IUserService userService;
    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private ISeatService seatService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private DefaultRedisScript<Long> atomicCounterScript;
    @Resource
    private IOperationLogService operationLogService;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private com.echo.utils.CsrfTokenUtil csrfTokenUtil;
    @Resource
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;
    @Resource
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Resource
    private com.echo.statemachine.OrderStateMachine orderStateMachine;

    /** 验证码重发最小间隔（秒）：距上次下发不足该值时拒绝重发 */
    private static final long CODE_RESEND_INTERVAL_SECONDS = 60L;

    private boolean isAdmin() {
        return UserHolder.getAdmin() != null;
    }

    /**
     * 判断当前用户是否是超级管理员
     *
     * 超级管理员定义：roleLevel = 0
     * 普通管理员定义：roleLevel = 1
     *
     * @return true=超级管理员, false=普通管理员或未登录
     */
    private boolean isSuperAdmin() {
        try {
            AdminDTO admin = UserHolder.getAdmin();

            // 未登录 → 不是超级管理员
            if (admin == null) {
                log.debug("[权限检查] 用户未登录，判定为非超级管理员");
                return false;
            }

            // roleLevel为空 → 默认为普通管理员（安全原则）
            if (admin.getRoleLevel() == null) {
                log.warn("[权限检查] 管理员角色等级为空，默认判定为普通管理员，adminId:{}", admin.getId());
                return false;
            }


            boolean isSuper = admin.getRoleLevel() == AdminRoleLevel.SUPER_ADMIN;

            if (isSuper) {
                log.info("[权限检查] 检测到超级管理员，adminId:{}, roleLevel:{}", admin.getId(), admin.getRoleLevel());
            } else {
                log.debug("[权限检查] 当前用户为普通管理员，adminId:{}, roleLevel:{}", admin.getId(), admin.getRoleLevel());
            }

            return isSuper;

        } catch (Exception e) {
            log.error("[权限检查] 判断超级管理员失败，默认返回false", e);
            return false;  // 异常时默认为普通管理员（安全原则）
        }
    }

    @Override
    public Result<?> sendCode(String phone) {
        if (StrUtil.isBlank(phone)) {
            log.warn("[管理员验证码] 手机号为空");
            return Result.fail(AppMessages.AUTH_PHONE_REQUIRED);
        }

        if (com.echo.utils.RegexUtils.isPhoneInvalid(phone)) {
            log.warn("[管理员验证码] 手机号格式不合法 - phone:{}", phone);
            return Result.fail(AppMessages.PHONE_FORMAT_ERROR);
        }

        // 验证码写在管理员自己的键空间里，与用户端验证码互不影响
        String codeKey = RedisConstants.AdminAuth.CODE_KEY + phone;
        String existingCode = stringRedisTemplate.opsForValue().get(codeKey);
        if (StrUtil.isNotBlank(existingCode)) {
            Long ttl = stringRedisTemplate.getExpire(codeKey, TimeUnit.SECONDS);
            if (ttl != null && ttl > CODE_RESEND_INTERVAL_SECONDS) {
                log.warn("[管理员验证码] 距上次发送不足{}秒，剩余:{}秒", CODE_RESEND_INTERVAL_SECONDS, ttl);
                return Result.fail(AppMessages.format(AppMessages.AUTH_CODE_SEND_FREQUENT, Math.ceil(ttl / 60.0)));
            }
        }

        // 只有已绑定管理员的手机号才真正下发，未绑定也返回同一句提示，避免探测号码归属
        Admin admin = getOne(new QueryWrapper<Admin>().eq("phone", phone));
        if (admin == null) {
            log.warn("[管理员验证码] 手机号未绑定管理员账号，返回统一提示 - phone:{}", phone);
            return Result.ok(AppMessages.AUTH_CODE_SEND_SUCCESS);
        }

        if (admin.getStatus() != null && admin.getStatus() == AdminStatus.DISABLED) {
            log.warn("[管理员验证码] 账号已禁用，返回统一提示 - adminId:{}", admin.getId());
            return Result.ok(AppMessages.AUTH_CODE_SEND_SUCCESS);
        }

        try {
            String code = RandomStringUtils.randomNumeric(6);
            stringRedisTemplate.opsForValue().set(
                    codeKey, code, RedisConstants.AdminAuth.CODE_TTL, TimeUnit.SECONDS);

            log.info("[管理员验证码] 下发成功 - adminId:{}, phone:{}, code:{}, TTL:{}秒",
                    admin.getId(), phone, code, RedisConstants.AdminAuth.CODE_TTL);
            log.info("[管理员验证码] 模拟短信：您的验证码为{}，{}秒内有效",
                    code, RedisConstants.AdminAuth.CODE_TTL);

            return Result.ok(AppMessages.AUTH_CODE_SEND_SUCCESS);
        } catch (Exception e) {
            log.error("[管理员验证码] 下发失败 - phone:{}, error:{}", phone, e.getMessage(), e);
            return Result.fail(AppMessages.AUTH_CODE_SEND_FAIL);
        }
    }

    @Override
    public Result<?> login(AdminLoginDTO loginForm) {
        if (loginForm == null) {
            return Result.fail(AppMessages.AUTH_CREDENTIAL_REQUIRED);
        }

        String phone = loginForm.getPhone();
        String password = loginForm.getPassword();
        String code = loginForm.getCode();

        if (StrUtil.isBlank(phone)) {
            return Result.fail(AppMessages.AUTH_PHONE_REQUIRED);
        }

        boolean byPassword = StrUtil.isNotBlank(password);
        if (!byPassword && StrUtil.isBlank(code)) {
            return Result.fail(AppMessages.AUTH_CREDENTIAL_REQUIRED);
        }

        // 凭证按传输原值参与校验，编码与传输层由 HTTPS 负责

        // 防爆破：密码与验证码共用同一个失败计数，任一方式失败都计入
        String failKey = ADMIN_LOGIN_FAIL_KEY + phone;
        if (isAdminLoginLocked(failKey)) {
            log.warn("[管理员登录失败] 尝试次数过多已锁定: {}", phone);
            return Result.fail(AppMessages.AUTH_ACCOUNT_OR_PASSWORD_ERROR);
        }

        Admin admin = getOne(new QueryWrapper<Admin>().eq("phone", phone));
        if (admin == null) {
            recordAdminLoginFail(failKey);
            log.warn("[管理员登录失败] 手机号不存在: {}", phone);
            return Result.fail(AppMessages.AUTH_ACCOUNT_OR_PASSWORD_ERROR);
        }

        if (admin.getStatus() != null && admin.getStatus() == AdminStatus.DISABLED) {
            recordAdminLoginFail(failKey);
            log.warn("[管理员登录失败] 账号已禁用: {}", phone);
            return Result.fail(AppMessages.USER_ACCOUNT_DISABLED);
        }

        boolean credentialValid = byPassword
                ? PasswordEncoder.matches(password, admin.getPassword())
                : matchesLoginCode(phone, code);

        if (!credentialValid) {
            recordAdminLoginFail(failKey);
            log.warn("[管理员登录失败] {} - adminId:{}, phone:{}",
                    byPassword ? "密码错误" : "验证码错误或已过期", admin.getId(), phone);
            return Result.fail(AppMessages.AUTH_ACCOUNT_OR_PASSWORD_ERROR);
        }

        // 登录成功：清失败计数，验证码一次性消费
        stringRedisTemplate.delete(failKey);
        stringRedisTemplate.delete(RedisConstants.AdminAuth.CODE_KEY + phone);

        log.info("[管理员登录成功] adminId:{}, 登录方式:{}", admin.getId(), byPassword ? "密码" : "验证码");
        return issueAdminSession(admin);
    }

    /**
     * 判断管理员登录是否已被锁定
     *
     * @param failKey 失败计数键
     * @return true=锁定中
     */
    private boolean isAdminLoginLocked(String failKey) {
        String failCount = stringRedisTemplate.opsForValue().get(failKey);
        if (failCount == null) {
            return false;
        }
        try {
            return Integer.parseInt(failCount) >= LOGIN_FAIL_MAX_ATTEMPTS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 校验管理员登录验证码
     * <p>
     * 只读校验，验证码在登录成功后统一删除，保证同一验证码只能完成一次登录。
     *
     * @param phone 手机号
     * @param code  用户提交的验证码
     * @return true=匹配
     */
    private boolean matchesLoginCode(String phone, String code) {
        if (StrUtil.isBlank(code)) {
            return false;
        }
        String cachedCode = stringRedisTemplate.opsForValue().get(RedisConstants.AdminAuth.CODE_KEY + phone);
        if (StrUtil.isBlank(cachedCode)) {
            log.warn("[管理员验证码] 验证码不存在或已过期 - phone:{}", phone);
            return false;
        }
        return cachedCode.equals(code.trim());
    }

    /**
     * 签发管理员会话
     * <p>
     * 写入登录令牌哈希、签发 CSRF Token，并组装前端判定角色所需的字段。
     *
     * @param admin 已通过凭证校验的管理员
     * @return 登录响应
     */
    private Result<?> issueAdminSession(Admin admin) {
        String token = UUID.randomUUID().toString().replace("-", "");

        AdminDTO adminDTO = new AdminDTO();
        BeanUtil.copyProperties(admin, adminDTO);

        String tokenKey = LOGIN_ADMIN_KEY + token;
        Map<String, Object> adminMap = BeanUtil.beanToMap(adminDTO, false, true);
        // 将所有值转换为String类型，避免Redis序列化错误
        Map<String, String> stringAdminMap = new HashMap<>();
        adminMap.forEach((key, value) -> {
            if (value != null) {
                stringAdminMap.put(key, value.toString());
            }
        });
        stringRedisTemplate.opsForHash().putAll(tokenKey, stringAdminMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_ADMIN_TTL, TimeUnit.SECONDS);

        log.info("[管理员登录成功] adminId:{}, phone:{}, token:{}..., roleLevel:{}",
                admin.getId(), admin.getPhone(), maskLoginToken(token), admin.getRoleLevel());

        //返回token + 管理员基本信息（供前端判断角色权限）
        Map<String, Object> loginResult = new HashMap<>();
        loginResult.put("token", token);
        loginResult.put("adminId", admin.getId());
        loginResult.put("name", admin.getName());
        loginResult.put("phone", admin.getPhone());
        loginResult.put("roleLevel", admin.getRoleLevel());
        loginResult.put("isSuperAdmin", admin.getRoleLevel() != null && admin.getRoleLevel() == AdminRoleLevel.SUPER_ADMIN);

        String csrfToken = csrfTokenUtil.generateToken(
                com.echo.utils.CsrfTokenUtil.SubjectRole.ADMIN, admin.getId());
        loginResult.put("csrfToken", csrfToken);
        log.info("[管理员登录] CSRF Token已生成 - adminId:{}, token:{}...", admin.getId(), csrfToken.substring(0, 8));

        return Result.ok(loginResult);
    }

    /**
     * 令牌日志脱敏：保留前 8 位
     */
    private String maskLoginToken(String token) {
        return token != null && token.length() > 8 ? token.substring(0, 8) : token;
    }

    @Override
    public Result<?> logout() {
        AdminDTO admin = UserHolder.getAdmin();
        if (admin == null) {
            return Result.fail(AppMessages.AUTH_NOT_LOGGED_IN);
        }

        String token = UserHolder.getToken();
        if (StrUtil.isNotBlank(token)) {
            String tokenKey = LOGIN_ADMIN_KEY + token;
            stringRedisTemplate.delete(tokenKey);
            log.info("[管理员登出成功] adminId:{}, token:{}", admin.getId(), token);
        }

        csrfTokenUtil.clearToken(com.echo.utils.CsrfTokenUtil.SubjectRole.ADMIN, admin.getId());
        log.info("[管理员登出] CSRF Token已清理 - adminId:{}", admin.getId());

        return Result.ok(AppMessages.AUTH_LOGOUT_SUCCESS);
    }

    @Override
    public Result<?> sendPasswordCode() {
        AdminDTO admin = UserHolder.getAdmin();
        if (admin == null) {
            return Result.fail(AppMessages.AUTH_NOT_LOGGED_IN);
        }

        String phone = admin.getPhone();
        if (StrUtil.isBlank(phone)) {
            log.warn("[管理员修改密码] 手机号为空");
            return Result.fail(AppMessages.AUTH_CODE_SEND_FAIL);
        }

        String codeKey = LOGIN_CODE_KEY + phone;
        try {
            String code = RandomStringUtils.randomNumeric(6);
            stringRedisTemplate.opsForValue().set(codeKey, code, LOGIN_CODE_TTL, TimeUnit.SECONDS);

            log.info("[管理员密码重置验证码] 发送成功，phone:{}, code:{}", phone, code);
            log.info("[管理员密码重置验证码] 模拟短信：您的密码重置验证码为{}，{}秒内有效", code, LOGIN_CODE_TTL);

            return Result.ok(AppMessages.PASSWORD_CODE_SEND_SUCCESS);
        } catch (Exception e) {
            log.error("[管理员密码重置验证码] 发送失败，phone:{}, error:{}", phone, e.getMessage(), e);
            return Result.fail(AppMessages.AUTH_CODE_SEND_FAIL);
        }
    }

    @Override
    public Result<?> changePassword(AdminChangePasswordDTO dto) {
        AdminDTO admin = UserHolder.getAdmin();
        if (admin == null) {
            return Result.fail(AppMessages.AUTH_NOT_LOGGED_IN);
        }

        if (StrUtil.isBlank(dto.getOldPassword()) || StrUtil.isBlank(dto.getNewPassword())) {
            return Result.fail(AppMessages.PASSWORD_EMPTY);
        }

        String oldPassword = dto.getOldPassword();
        String newPassword = dto.getNewPassword();

        Admin currentAdmin = getById(admin.getId());
        if (currentAdmin == null) {
            log.error("[管理员修改密码] 管理员不存在，adminId:{}", admin.getId());
            return Result.fail(AppMessages.SYSTEM_BUSY);
        }

        if (!PasswordEncoder.matches(oldPassword, currentAdmin.getPassword())) {
            log.warn("[管理员修改密码] 旧密码错误，adminId:{}", admin.getId());
            return Result.fail(AppMessages.PASSWORD_OLD_ERROR);
        }

        if (java.util.Objects.equals(oldPassword, newPassword)) {
            log.warn("[管理员修改密码] 新旧密码相同，adminId:{}", admin.getId());
            return Result.fail(AppMessages.PASSWORD_SAME_AS_OLD);
        }

        if (StrUtil.isNotBlank(dto.getCode())) {
            String codeKey = LOGIN_CODE_KEY + admin.getPhone();
            String cachedCode = stringRedisTemplate.opsForValue().get(codeKey);
            if (StrUtil.isBlank(cachedCode) || !cachedCode.equals(dto.getCode())) {
                log.warn("[管理员修改密码] 验证码错误或已过期，adminId:{}", admin.getId());
                return Result.fail(AppMessages.AUTH_CODE_INCORRECT);
            }
            stringRedisTemplate.delete(codeKey);
        }

        try {
            String encodedPassword = PasswordEncoder.encode(newPassword);
            currentAdmin.setPassword(encodedPassword);
            currentAdmin.setUpdateTime(LocalDateTime.now());

            updateById(currentAdmin);

            String token = UserHolder.getToken();
            if (StrUtil.isNotBlank(token)) {
                String tokenKey = LOGIN_ADMIN_KEY + token;
                stringRedisTemplate.delete(tokenKey);
                log.info("[管理员修改密码] 已清除登录token，需要重新登录");
            }

            log.info("[管理员修改密码] 成功，adminId:{}, phone:{}", admin.getId(), admin.getPhone());
            return Result.ok(AppMessages.PASSWORD_CHANGE_SUCCESS);
        } catch (Exception e) {
            log.error("[管理员修改密码] 失败，adminId:{}, error:{}", admin.getId(), e.getMessage(), e);
            return Result.fail(AppMessages.PASSWORD_CHANGE_FAIL);
        }
    }

    @Override
    public Result<?> getAdminInfo() {
        AdminDTO admin = UserHolder.getAdmin();
        if (admin == null) {
            return Result.fail(AppMessages.AUTH_NOT_LOGGED_IN);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("id", admin.getId());
        result.put("userId", admin.getUserId());
        result.put("name", admin.getName());
        result.put("phone", admin.getPhone());
        result.put("roleLevel", admin.getRoleLevel());
        result.put("status", admin.getStatus());

        log.debug("[管理] 获取当前管理员信息，adminId:{}", admin.getId());
        return Result.ok(result);
    }

    @Override
    public Result<?> initSuperAdminPassword(boolean authorizedByBootstrap) {
        try {
            if (!authorizedByBootstrap && !isSuperAdmin()) {
                log.warn("[初始化] 非超级管理员尝试重置超管密码，操作被拒绝");
                return Result.fail(AppMessages.PERMISSION_SUPER_ADMIN_ONLY);
            }

            Admin superAdmin = getOne(new QueryWrapper<Admin>()
                    .eq("role_level", AdminRoleLevel.SUPER_ADMIN.getCode())
                    .orderByAsc("id")
                    .last("LIMIT 1"));
            if (superAdmin == null) {
                log.warn("[初始化] 库中不存在超级管理员账号，无法重置");
                return Result.fail(AppMessages.ADMIN_SUPER_ADMIN_NOT_EXIST);
            }

            String rawPassword = generateTemporaryPassword();
            superAdmin.setPassword(PasswordEncoder.encode(rawPassword));
            superAdmin.setStatus(AdminStatus.NORMAL);
            superAdmin.setUpdateTime(LocalDateTime.now());
            updateById(superAdmin);

            // 密码已变更：清掉该管理员的登录令牌与失败计数，强制重新认证
            stringRedisTemplate.delete(ADMIN_LOGIN_FAIL_KEY + superAdmin.getPhone());
            csrfTokenUtil.clearToken(
                    com.echo.utils.CsrfTokenUtil.SubjectRole.ADMIN, superAdmin.getId());

            log.warn("[初始化] 超级管理员密码已重置 - adminId:{}, phone:{}, 授权来源:{}",
                    superAdmin.getId(), superAdmin.getPhone(),
                    authorizedByBootstrap ? "引导令牌" : "已登录超管");

            // 临时密码只在本次响应中出现一次，服务端不留明文
            Map<String, Object> data = new HashMap<>();
            data.put("adminId", superAdmin.getId());
            data.put("phone", superAdmin.getPhone());
            data.put("temporaryPassword", rawPassword);
            data.put("notice", "临时密码仅在本次响应中返回，请登录后立即修改");

            return Result.ok(data);
        } catch (Exception e) {
            log.error("[初始化] 更新超级管理员密码失败，error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.PASSWORD_UPDATE_FAIL_PREFIX + e.getMessage());
        }
    }

    /**
     * 生成临时密码
     * <p>
     * 12 位，同时包含大写字母、小写字母、数字与符号，满足
     * {@link PasswordEncoder} 的长度与强度规则，避免重置出来的密码本身不合规。
     *
     * @return 临时密码明文
     */
    private String generateTemporaryPassword() {
        String upper = RandomStringUtils.random(3, 65, 90, false, false);
        String lower = RandomStringUtils.random(5, 97, 122, false, false);
        String digits = RandomStringUtils.random(3, 48, 57, false, false);
        String symbols = "@#%&*";
        String seed = upper + lower + digits + symbols.charAt(java.util.concurrent.ThreadLocalRandom.current().nextInt(symbols.length()));

        char[] chars = seed.toCharArray();
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }

    // ==================== 演出管理 ====================

    @Override
    public Result<?> listShows(String name, Long id, Integer status, Integer typeId,
                               com.echo.dto.PageQuery pageQuery) {
        log.info("[管理-查询演出列表] 参数 - name:{}, id:{}, status:{}, typeId:{}, 页码:{}",
                name, id, status, typeId, pageQuery.getCurrent());

        // 使用 PageQuery 创建分页对象（自动处理默认值和边界检查）
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Show> page =
                pageQuery.toTypedPage();

        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Show> queryWrapper =
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();

        if (name != null && !name.trim().isEmpty()) {
            queryWrapper.like("name", name.trim());
        }
        if (id != null) {
            queryWrapper.eq("id", id);
        }
        if (status != null) {
            queryWrapper.eq("status", status);
        }
        if (typeId != null) {
            queryWrapper.eq("type_id", typeId);
        }

        // 使用 PageQuery 的排序参数（自动校验和规范化）
        String sortBy = pageQuery.getSortBy();
        String sortOrder = pageQuery.getSortOrder();

        if (sortBy != null && !sortBy.isEmpty()) {
            String column = sortBy.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
            queryWrapper.orderBy(true, "asc".equalsIgnoreCase(sortOrder), column);
        } else {
            queryWrapper.orderByDesc("create_time");
        }

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Show> result = showMapper.selectPage(page, queryWrapper);

        // 使用 PageResult 统一返回格式
        log.info("[管理-查询演出列表] 结果 - 总数:{}, 当前页记录数:{}", result.getTotal(), result.getRecords().size());
        return Result.ok(com.echo.dto.PageResult.from(result));
    }

    @Override
    public Result<?> clearShowCache() {
        try {
            Set<String> keys = stringRedisTemplate.keys(CACHE_SHOW_KEY + "*");
            if (keys != null && !keys.isEmpty()) {
                long deleted = stringRedisTemplate.delete(keys);
                log.info("[管理-清除缓存] 成功删除 {} 个演出缓存", deleted);
                return Result.ok(AppMessages.format(AppMessages.SHOW_CACHE_CLEAR_SUCCESS, deleted));
            } else {
                log.info("[管理-清除缓存] 无缓存需要清除");
                return Result.ok(AppMessages.SHOW_CACHE_NOT_FOUND);
            }
        } catch (Exception e) {
            log.error("[管理-清除缓存] 异常: {}", e.getMessage(), e);
            return Result.fail(AppMessages.CACHE_CLEAR_FAIL_PREFIX + e.getMessage());
        }
    }

    @Override
    @Transactional
    public Result<?> createShow(Show show) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        if (show.getName() == null || show.getName().trim().isEmpty()) {
            return Result.fail("演出名称不能为空");
        }

        if (show.getTypeId() == null) {
            return Result.fail("请选择演出类型");
        }

        LocalDateTime now = LocalDateTime.now();

        log.info("[管理-创建演出] 接收数据:\n" +
                "  name={}\n" +
                "  typeId={}\n" +
                "  scheduledPublishTime={}\n" +
                "  status(原始)={}",
                show.getName(),
                show.getTypeId(),
                show.getScheduledPublishTime(),
                show.getStatus());

        if (show.getScheduledPublishTime() != null) {
            show.setStatus(ShowStatus.OFFLINE.getCode());

            long minutesUntilPublish = java.time.Duration.between(now, show.getScheduledPublishTime()).toMinutes();

            log.info("[管理-创建演出] 定时上架模式:\n" +
                    "  name={}\n" +
                    "  scheduledPublishTime={}\n" +
                    "  status=0(下架/待发布)\n" +
                    "  当前时间={}\n" +
                    "  距离发布还有: {}分钟",
                    show.getName(),
                    show.getScheduledPublishTime(),
                    now,
                    minutesUntilPublish);
        } else {
            show.setStatus(ShowStatus.ONLINE.getCode());

            log.info("[管理-创建演出] 立即上架模式:\n" +
                    "  name={}\n" +
                    "  scheduledPublishTime=null\n" +
                    "  status=1(立即上架)",
                    show.getName());
        }

        show.setComments(0);
        show.setScore(0);
        show.setCreateTime(now);
        show.setUpdateTime(now);

        log.info("[管理-创建演出] 最终保存数据:\n" +
                "  name={}\n" +
                "  typeId={}\n" +
                "  status={}\n" +
                "  scheduledPublishTime={}\n" +
                "  x={}, y={}",
                show.getName(),
                show.getTypeId(),
                show.getStatus(),
                show.getScheduledPublishTime(),
                show.getX(),
                show.getY());

        boolean success = showService.save(show);

        if (success) {
            log.info("[创建演出成功] id:{}, name:{}, finalStatus={}, 操作者:{}",
                    show.getId(), show.getName(), show.getStatus(), PermissionUtils.getCurrentAdminName());

            try {
                String cacheKey = CACHE_SHOW_KEY + show.getId();
                stringRedisTemplate.delete(cacheKey);
                log.info("[创建演出] 已清除缓存 - showId:{}", show.getId());
            } catch (Exception e) {
                log.warn("[创建演出] 清除缓存失败 - error:{}", e.getMessage());
            }

            return Result.ok(show.getId());
        } else {
            log.error("[创建演出失败] name:{}", show.getName());
            return Result.fail(AppMessages.SHOW_CREATE_FAIL);
        }
    }

    @Override
    public Result<?> offlineShow(Long id) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }
        Show show = showService.getById(id);
        if (show == null) {
            return Result.fail(AppMessages.SHOW_NOT_EXIST);
        }
        show.setStatus(ShowStatus.OFFLINE.getCode());
        show.setUpdateTime(LocalDateTime.now());
        showService.updateById(show);

        // [OK] 修复：下架时清除缓存和GEO数据，确保用户端立即不可见
        try {
            // 清除演出详情缓存
            String cacheKey = CACHE_SHOW_KEY + id;
            stringRedisTemplate.delete(cacheKey);
            log.info("[管理-下架] 已清除演出Redis缓存 - key:{}", cacheKey);

            // 从GEO索引中删除（附近搜索不再显示）
            if (show.getTypeId() != null) {
                String geoKey = SHOW_GEO_KEY + show.getTypeId();
                Long removed = stringRedisTemplate.opsForGeo().remove(geoKey, id.toString());
                if (removed != null && removed > 0) {
                    log.info("[管理-下架] 已从GEO索引删除演出 - geoKey:{}, showId:{}", geoKey, id);
                }
            }
        } catch (Exception e) {
            log.warn("[管理-下架] 清除缓存/GEO失败（不影响主流程）- showId:{}, error:{}", id, e.getMessage());
        }

        log.info("[管理] 演出下架完成，showId:{}, name:{}", id, show.getName());
        return Result.ok();
    }

    @Override
    public Result<?> onlineShow(Long id) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }
        Show show = showService.getById(id);
        if (show == null) {
            return Result.fail(AppMessages.SHOW_NOT_EXIST);
        }
        show.setStatus(ShowStatus.ONLINE.getCode());
        show.setUpdateTime(LocalDateTime.now());
        showService.updateById(show);

        // [OK] 修复：上架时清除缓存，并重新添加到GEO索引
        try {
            // 清除演出详情缓存（确保用户端获取最新状态）
            String cacheKey = CACHE_SHOW_KEY + id;
            stringRedisTemplate.delete(cacheKey);
            log.info("[管理-上架] 已清除演出Redis缓存 - key:{}", cacheKey);

            // 如果演出有坐标信息，重新添加到GEO索引
            if (show.getTypeId() != null && show.getX() != null && show.getY() != null) {
                String geoKey = SHOW_GEO_KEY + show.getTypeId();
                stringRedisTemplate.opsForGeo().add(geoKey,
                        new Point(show.getX(), show.getY()), id.toString());
                log.info("[管理-上架] 已重新添加到GEO索引 - geoKey:{}, showId:{}, 坐标:({}, {})",
                        geoKey, id, show.getX(), show.getY());
            }
        } catch (Exception e) {
            log.warn("[管理-上架] 清除缓存/添加GEO失败（不影响主流程）- showId:{}, error:{}", id, e.getMessage());
        }

        log.info("[管理] 演出上架完成，showId:{}, name:{}", id, show.getName());

        Set<String> followerIds = stringRedisTemplate.opsForSet()
                .members(SHOW_FOLLOWERS_KEY + id);
        if (followerIds == null || followerIds.isEmpty()) {
            log.info("[管理] 演出上架，无关注者，showId:{}", id);
            return Result.ok();
        }

        int notifyCount = 0;
        for (String followerIdStr : followerIds) {
            Long followerId = Long.valueOf(followerIdStr);
            String rateLimitKey = NOTIFICATION_RATE_LIMIT_KEY + "6:" + followerId;
            String exists = stringRedisTemplate.opsForValue().get(rateLimitKey);
            if (exists != null) {
                continue;
            }
            stringRedisTemplate.opsForValue().set(rateLimitKey, "1",
                    NOTIFICATION_RATE_LIMIT_TTL, java.util.concurrent.TimeUnit.SECONDS);

            NotificationMessage msg = NotificationMessage.of(followerId, NotificationType.SHOW_ON_SALE, NOTIFICATION_SHOW_ONLINE_TITLE,
                    NOTIFICATION_SHOW_ONLINE_CONTENT_PREFIX + show.getName() + NOTIFICATION_SHOW_ONLINE_CONTENT_SUFFIX, show.getId());
            rocketMQTemplate.asyncSend(MqConstants.NOTIFICATION_TOPIC,
                    MessageBuilder.withPayload(msg).build(),
                    new SendCallback() {
                        @Override
                        public void onSuccess(SendResult sendResult) {}
                        @Override
                        public void onException(Throwable e) {
                            log.error("[通知] 演出开售通知发送失败，userId:{}", followerId, e);
                        }
                    });
            notifyCount++;
        }
        log.info("[管理] 演出上架通知已发送，showId:{}, 通知人数:{}", id, notifyCount);
        return Result.ok();
    }

    @Override
    public Result<?> updateShow(Show show) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }
        show.setUpdateTime(LocalDateTime.now());

        // 如果设置了定时发布时间且在未来，状态改为0（下架）
        if (show.getScheduledPublishTime() != null && show.getScheduledPublishTime().isAfter(LocalDateTime.now())) {
            show.setStatus(ShowStatus.OFFLINE.getCode());
            log.info("[管理] 更新演出（定时发布），showId:{}, name:{}, scheduledTime:{}",
                show.getId(), show.getName(), show.getScheduledPublishTime());
        }

        showService.updateById(show);
        log.info("[管理] 更新演出成功，showId:{}, status:{}", show.getId(), show.getStatus());
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> deleteShow(Long id) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }
        Show show = showService.getById(id);
        if (show == null) {
            return Result.fail(AppMessages.SHOW_NOT_EXIST);
        }

        // 查找所有关联的票种并级联删除
        QueryWrapper<Ticket> ticketQuery = new QueryWrapper<>();
        ticketQuery.eq("show_id", id);
        List<Ticket> relatedTickets = ticketService.list(ticketQuery);

        if (!relatedTickets.isEmpty()) {
            log.warn("[管理] 删除演出(id:{})，发现{}个关联票种，将级联删除", id, relatedTickets.size());

            // 1. 收集所有票种ID
            List<Long> ticketIds = relatedTickets.stream()
                    .map(Ticket::getId)
                    .toList();

            // 2. 删除关联的秒杀票信息
            seckillTicketService.remove(
                    new QueryWrapper<SeckillTicket>().in("ticket_id", ticketIds)
            );

            // 3. 删除所有关联的票种
            ticketService.remove(ticketQuery);

            log.info("[管理] 已级联删除演出({})的{}个票种及秒杀信息", id, relatedTickets.size());
        }

        // 删除演出本身
        showService.removeById(id);

        // 清除管理端缓存
        clearTicketCache(null);

        // 关键：刷新所有关联票种的用户端缓存
        try {
            for (Ticket ticket : relatedTickets) {
                if (ticket.getShowId() != null) {
                    ticketService.refreshTicketCache(ticket.getShowId());
                }
            }
            log.info("[管理] 删除演出成功并已刷新{}个关联票种的用户端缓存，showId:{}", relatedTickets.size(), id);
        } catch (Exception cacheEx) {
            log.warn("[管理] 删除演出成功但刷新用户端缓存失败（不影响主流程），showId:{}, error:{}", id, cacheEx.getMessage());
        }

        log.info("[管理] 删除演出并清除缓存，showId:{}, 锁定票种数:{}", id, relatedTickets.size());
        return Result.ok();
    }

    // ==================== 票种管理 ====================

    @Override
    public Result<?> listTickets(Long showId, String keyword, Integer current, Integer pageSize, String sortBy, String sortOrder) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        // 缓存键覆盖所有影响结果的查询维度（含每页条数）
        String cacheKey = String.format("admin:tickets:%d:%s:%d:%d:%s:%s",
                showId != null ? showId : 0,
                keyword != null ? keyword : "",
                current,
                pageSize != null ? pageSize : 0,
                sortBy != null ? sortBy : "",
                sortOrder != null ? sortOrder : "");

        try {
            String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cachedJson != null && !cachedJson.isEmpty()) {
                log.debug("[管理] 从缓存获取票种列表，key:{}", cacheKey);
                return Result.ok(com.alibaba.fastjson.JSON.parseObject(cachedJson, Map.class));
            }
        } catch (Exception e) {
            log.warn("[管理] 缓存读取失败，将查询数据库，error:{}", e.getMessage());
        }

        QueryWrapper<Ticket> wrapper = new QueryWrapper<>();
        if (showId != null) {
            wrapper.eq("show_id", showId);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like("title", keyword);
        }
        if (sortBy != null && !sortBy.isBlank()) {
            boolean asc = "asc".equalsIgnoreCase(sortOrder);
            switch (sortBy) {
                case "id":
                    wrapper.orderBy(true, asc, "id");
                    break;
                case "showId":
                    wrapper.orderBy(true, asc, "show_id");
                    break;
                case "title":
                    wrapper.orderBy(true, asc, "title");
                    break;
                case "payValue":
                    wrapper.orderBy(true, asc, "pay_value");
                    break;
                case "actualValue":
                    wrapper.orderBy(true, asc, "actual_value");
                    break;
                case "stock":
                    wrapper.orderBy(true, asc, "stock");
                    break;
                case "createTime":
                    wrapper.orderBy(true, asc, "create_time");
                    break;
                default:
                    wrapper.orderByDesc("create_time");
                    break;
            }
        } else {
            wrapper.orderByDesc("create_time");
        }
        int actualPageSize = (pageSize != null && pageSize > 0) ? pageSize : 20;
        Page<Ticket> page = ticketService.page(new Page<>(current, actualPageSize), wrapper);

        List<Map<String, Object>> enrichedRecords = page.getRecords().stream().map(ticket -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", ticket.getId());
            map.put("showId", ticket.getShowId());
            map.put("title", ticket.getTitle());
            map.put("type", ticket.getType());
            map.put("payValue", ticket.getPayValue());
            map.put("actualValue", ticket.getActualValue());
            map.put("stock", ticket.getStock());
            map.put("status", ticket.getStatus());
            map.put("locked", ticket.getLocked() != null ? ticket.getLocked() : 0);
            map.put("hasSeats", ticket.getHasSeats() != null ? ticket.getHasSeats() : false);
            map.put("createTime", ticket.getCreateTime());
            map.put("updateTime", ticket.getUpdateTime());

            int totalStock = ticket.getStock() != null ? ticket.getStock() : 0;
            int soldCount = 0;
            int remainingStock = totalStock;

            try {
                // 统计已售数量：订单状态 IN (2, 3) - 已支付 + 已核销
                // 业务规则：已核销的票仍算"已售"，不能重新售卖
                long countResult = ticketOrderService.count(
                    new QueryWrapper<TicketOrder>()
                        .eq("ticket_id", ticket.getId())
                        .in("status", 2, 3)  // 2=已支付, 3=已核销
                );
                soldCount = (int) countResult;
                remainingStock = Math.max(0, totalStock - soldCount);

                log.debug("[管理] 票种库存统计 - ticketId:{}, 总:{}, 已售:{}, 剩余:{}",
                    ticket.getId(), totalStock, soldCount, remainingStock);
            } catch (Exception e) {
                log.warn("[管理] 统计票种已售数量失败 - ticketId:{}, error:{}", ticket.getId(), e.getMessage());
            }

            map.put("totalStock", totalStock);
            map.put("soldCount", soldCount);
            map.put("remainingStock", remainingStock);

            try {
                Show show = showService.getById(ticket.getShowId());
                map.put("showName", show != null ? show.getName() : "未知演出");
            } catch (Exception e) {
                map.put("showName", "未知演出");
            }

            try {
                SeckillTicket seckill = seckillTicketService.getById(ticket.getId());
                if (seckill != null) {
                    int seckillTotalStock = seckill.getStock() != null ? seckill.getStock() : 0;
                    int seckillRemainingStock = seckillTotalStock;
                    int seckillSoldCount = 0;

                    if (ticket.getType() != null && ticket.getType() == 1) {
                        try {
                            String redisStockKey = SECKILL_STOCK_KEY + ticket.getId();
                            String redisStockStr = stringRedisTemplate.opsForValue().get(redisStockKey);
                            if (redisStockStr != null && !redisStockStr.isEmpty()) {
                                seckillRemainingStock = Integer.parseInt(redisStockStr);
                                seckillSoldCount = Math.max(0, seckillTotalStock - seckillRemainingStock);

                                log.debug("[管理] 秒杀票库存（Redis）, ticketId:{}, 剩余:{}, 总:{}, 已售:{}",
                                    ticket.getId(), seckillRemainingStock, seckillTotalStock, seckillSoldCount);
                            } else {
                                log.warn("[管理] 秒杀票Redis库存为空，使用数据库总库存, ticketId:{}, 总库存:{}",
                                    ticket.getId(), seckillTotalStock);
                            }
                        } catch (NumberFormatException nfe) {
                            log.warn("[管理] 秒杀票Redis库存格式错误，使用数据库总库存, ticketId:{}, error:{}",
                                ticket.getId(), nfe.getMessage());
                        } catch (Exception redisEx) {
                            log.warn("[管理] 获取秒杀票Redis库存失败，使用数据库总库存, ticketId:{}, error:{}",
                                ticket.getId(), redisEx.getMessage());
                        }
                    }

                    map.put("seckillStock", seckillRemainingStock);
                    map.put("seckillTotalStock", seckillTotalStock);
                    map.put("seckillSoldCount", seckillSoldCount);
                    map.put("beginTime", seckill.getBeginTime());
                    map.put("endTime", seckill.getEndTime());
                    if (seckill.getScheduledStartTime() != null) {
                        map.put("scheduledStartTime", seckill.getScheduledStartTime());
                    }
                    map.put("isSeckill", true);
                } else {
                    map.put("seckillStock", 0);
                    map.put("seckillTotalStock", 0);
                    map.put("seckillSoldCount", 0);
                    map.put("isSeckill", false);
                }
            } catch (Exception e) {
                map.put("seckillStock", 0);
                map.put("seckillTotalStock", 0);
                map.put("isSeckill", false);
            }

            //  关键：查询座位数量（用于显示"生成座位"按钮和已生成座位数）
            if (ticket.getHasSeats() != null && ticket.getHasSeats()) {
                try {
                    long seatCount = seatService.count(
                        new QueryWrapper<Seat>().eq("ticket_id", ticket.getId())
                    );
                    map.put("seatCount", (int) seatCount);

                    log.debug("[管理] 票种座位数量 - ticketId:{}, seatCount:{}",
                        ticket.getId(), seatCount);
                } catch (Exception seatEx) {
                    log.warn("[管理] 查询票种座位数量失败 - ticketId:{}, error:{}",
                        ticket.getId(), seatEx.getMessage());
                    map.put("seatCount", 0);
                }
            } else {
                map.put("seatCount", null); // 无需选座的票种不显示座位数
            }

            return map;
        }).toList();

        Map<String, Object> result = new HashMap<>();
        result.put("records", enrichedRecords);
        result.put("total", page.getTotal());
        result.put("current", page.getCurrent());
        result.put("pages", page.getPages());

        try {
            stringRedisTemplate.opsForValue().set(cacheKey,
                    com.alibaba.fastjson.JSON.toJSONString(result),
                    5, java.util.concurrent.TimeUnit.MINUTES);
            log.debug("[管理] 票种列表已缓存，key:{}, TTL:5分钟", cacheKey);
        } catch (Exception e) {
            log.warn("[管理] 缓存写入失败，不影响业务，error:{}", e.getMessage());
        }

        return Result.ok(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> createTicket(Ticket ticket) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        if (ticket.getShowId() == null) {
            return Result.fail(AppMessages.SHOW_ID_NOT_EMPTY);
        }
        if (ticket.getTitle() == null || ticket.getTitle().trim().isEmpty()) {
            return Result.fail(AppMessages.TICKET_TITLE_EMPTY);
        }

        try {
            ticket.setCreateTime(LocalDateTime.now());
            ticket.setUpdateTime(LocalDateTime.now());

            // 默认类型为普通票（0）
            if (ticket.getType() == null) {
                ticket.setType(0);
            }

            // 根据票种类型进行不同的处理
            if (ticket.getType() == 1) {
                // 秒杀票：验证必填字段
                if (ticket.getBeginTime() == null || ticket.getEndTime() == null) {
                    return Result.fail(AppMessages.TICKET_SECKILL_TIME_REQUIRED);
                }
                if (ticket.getBeginTime().isAfter(ticket.getEndTime()) || ticket.getBeginTime().isEqual(ticket.getEndTime())) {
                    return Result.fail(AppMessages.TICKET_SECKILL_TIME_INVALID);
                }
                // 秒杀票的普通库存设为0
                ticket.setStock(0);
            } else {
                // 普通票：验证库存
                if (ticket.getStock() == null || ticket.getStock() < 0) {
                    return Result.fail(AppMessages.TICKET_STOCK_INVALID);
                }
            }

            // 如果设置了定时发布时间，初始状态为0（下架），否则立即上架
            if (ticket.getScheduledPublishTime() != null && ticket.getScheduledPublishTime().isAfter(LocalDateTime.now())) {
                ticket.setStatus(0);
                log.info("[管理] 创建票种（定时上架），showId:{}, title:{}, type:{}, scheduledTime:{}",
                    ticket.getShowId(), ticket.getTitle(), ticket.getType(), ticket.getScheduledPublishTime());
            } else {
                ticket.setStatus(1);
            }

            ticketService.save(ticket);

            // 如果是秒杀票，同时创建秒杀配置
            if (ticket.getType() == 1) {
                SeckillTicket seckillTicket = new SeckillTicket();
                seckillTicket.setTicketId(ticket.getId());
                seckillTicket.setStock(ticket.getSeckillStock() != null ? ticket.getSeckillStock() : 0);
                seckillTicket.setBeginTime(ticket.getBeginTime());
                seckillTicket.setEndTime(ticket.getEndTime());
                seckillTicket.setScheduledStartTime(ticket.getScheduledStartTime());
                seckillTicket.setCreateTime(LocalDateTime.now());
                seckillTicketService.save(seckillTicket);

                log.info("[管理] 创建秒杀票成功，ticketId:{}, stock:{}, beginTime:{}, endTime:{}",
                    ticket.getId(), seckillTicket.getStock(), ticket.getBeginTime(), ticket.getEndTime());
            }

            // 清除管理端缓存
            clearTicketCache(ticket.getShowId());

            // 关键：立即刷新用户端缓存（Write-Through模式）
            try {
                ticketService.refreshTicketCache(ticket.getShowId());
                log.info("[管理] 创建票种成功并已刷新用户端缓存，ticketId:{}, showId:{}",
                    ticket.getId(), ticket.getShowId());
            } catch (Exception cacheEx) {
                log.warn("[管理] 创建票种成功但刷新用户端缓存失败（不影响主流程），ticketId:{}, showId:{}, error:{}",
                    ticket.getId(), ticket.getShowId(), cacheEx.getMessage());
            }

            //  刷新该演出的平均票价
            refreshShowAvgPrice(ticket.getShowId());

            return Result.ok(ticket.getId());
        } catch (Exception e) {
            log.error("[管理] 创建票种失败，error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.TICKET_CREATE_FAIL_PREFIX + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> createTicketWithSeats(TicketWithSeatsDTO dto) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        log.info("[管理] 接收到创建请求 - {}", dto.toString());

        // 0. 幂等性检查（防止重复提交）
        String idempotencyKey = dto.getIdempotencyKey();
        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            String cacheKey = "idempotent:ticket_with_seats:" + idempotencyKey;
            String cachedResult = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cachedResult != null) {
                log.info("[管理] 幂等命中，直接返回缓存结果，idempotencyKey:{}", idempotencyKey);
                try {
                    return objectMapper.readValue(cachedResult, com.echo.dto.Result.class);
                } catch (Exception e) {
                    log.warn("[管理] 幂等缓存解析失败，继续正常处理，error:{}", e.getMessage());
                }
            }
        }

        // 1. 使用DTO内置校验方法进行统一验证（使用DTOMessages常量，无硬编码）
        if (!dto.isValid()) {
            String error = dto.getValidationError();
            log.warn("[管理] DTO校验失败 - {}", error);
            return Result.fail(error);
        }

        Ticket ticket = dto.getTicket();
        SeatBatchCreateDTO seatConfig = dto.getSeatConfig();

        // 2. 输出详细的创建日志（包含审计信息）
        log.info("[管理] 操作来源渠道: {}, 备注: {}", dto.getSourceChannel(), dto.getRemark());
        if (dto.requiresSeats()) {
            log.info("[管理] 开始事务性创建票种+座位，title:{}, 区域数:{}, 预计总座位:{}",
                dto.getTicketTitle(),
                dto.getAreaCount(),
                dto.calculateTotalSeats());
        } else {
            log.info("[管理] 开始创建不需要选座的票种，title:{}", dto.getTicketTitle());
        }

        try {
            // 3. 创建票种（复用原有逻辑）
            LocalDateTime now = LocalDateTime.now();
            ticket.setCreateTime(now);
            ticket.setUpdateTime(now);

            if (ticket.getType() == null) {
                ticket.setType(0);
            }
            if (dto.isSeckillTicket()) {
                log.info("[管理] 检测到秒杀票，执行秒杀特殊校验");
                if (ticket.getBeginTime() == null || ticket.getEndTime() == null) {
                    return Result.fail(AppMessages.TICKET_SECKILL_TIME_REQUIRED);
                }
                if (ticket.getBeginTime().isAfter(ticket.getEndTime()) || ticket.getBeginTime().isEqual(ticket.getEndTime())) {
                    return Result.fail(AppMessages.TICKET_SECKILL_TIME_INVALID);
                }
                ticket.setStock(0);
            } else {
                if (ticket.getStock() == null || ticket.getStock() < 0) {
                    return Result.fail(AppMessages.TICKET_STOCK_INVALID);
                }
            }

            if (ticket.getScheduledPublishTime() != null && ticket.getScheduledPublishTime().isAfter(now)) {
                ticket.setStatus(0);
            } else {
                ticket.setStatus(1);
            }

            ticketService.save(ticket);
            Long newTicketId = ticket.getId();
            log.info("[管理] 票种创建成功，id:{}, title:{}", newTicketId, ticket.getTitle());

            // 4. 如果是秒杀票，同时创建秒杀配置
            if (dto.isSeckillTicket()) {
                SeckillTicket seckillTicket = new SeckillTicket();
                seckillTicket.setTicketId(newTicketId);
                seckillTicket.setStock(ticket.getSeckillStock() != null ? ticket.getSeckillStock() : 0);
                seckillTicket.setBeginTime(ticket.getBeginTime());
                seckillTicket.setEndTime(ticket.getEndTime());
                seckillTicket.setScheduledStartTime(ticket.getScheduledStartTime());
                seckillTicket.setCreateTime(now);
                seckillTicketService.save(seckillTicket);
                log.info("[管理] 秒杀票配置创建成功，ticketId:{}, stock:{}",
                    newTicketId, seckillTicket.getStock());
            }

            // 5. 如果需要选座，立即生成座位（在同一事务中）
            int totalSeatsGenerated = 0;
            if (dto.requiresSeats()) {
                try {
                    Result<?> seatResult = seatService.batchCreateSeats(newTicketId, seatConfig);

                    if (!Boolean.TRUE.equals(seatResult.getSuccess())) {
                        throw new com.echo.exception.BusinessException(
                                AppMessages.RUNTIME_SEAT_GENERATE_FAIL_PREFIX + (seatResult.getErrorMsg() != null ? seatResult.getErrorMsg() : "未知错误"),
                                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
                    }

                    Object data = seatResult.getData();
                    if (data instanceof Map<?, ?> seatData) {
                        Object totalSeatsObj = seatData.get("totalSeats");
                        if (totalSeatsObj instanceof Number) {
                            totalSeatsGenerated = ((Number) totalSeatsObj).intValue();
                        }
                        Object createdCountObj = seatData.get("createdCount");
                        if (createdCountObj instanceof Number && totalSeatsGenerated == 0) {
                            totalSeatsGenerated = ((Number) createdCountObj).intValue();
                        }
                    }

                    log.info("[管理] 座位生成成功，ticketId:{}, 座位数:{}", newTicketId, totalSeatsGenerated);
                } catch (Exception seatEx) {
                    log.error("[管理] 座位生成失败，整个操作将回滚，ticketId:{}, error:{}",
                        newTicketId, seatEx.getMessage(), seatEx);
                    throw new com.echo.exception.BusinessException(
                            AppMessages.RUNTIME_SEAT_GENERATE_FAIL_ROLLBACK_PREFIX + seatEx.getMessage(),
                            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                            seatEx);
                }
            }

            // 6. 清除缓存
            clearTicketCache(dto.getShowId());
            try {
                ticketService.refreshTicketCache(dto.getShowId());
                log.info("[管理] 已刷新用户端缓存，showId:{}", dto.getShowId());
            } catch (Exception cacheEx) {
                log.warn("[管理] 刷新用户端缓存失败（不影响主流程），error:{}", cacheEx.getMessage());
            }

            refreshShowAvgPrice(dto.getShowId());

            // 7. 构建返回结果（包含前端所需的所有字段）
            java.util.Map<String, Object> resultData = new java.util.HashMap<>();
            resultData.put("id", newTicketId);
            resultData.put("ticketId", newTicketId);
            resultData.put("ticketTitle", dto.getTicketTitle());
            resultData.put("hasSeats", dto.requiresSeats());

            // 关键：同时返回 totalSeats 和 seatCount，确保前端能正确识别座位已生成
            // - totalSeats：本次生成的座位总数（用于提示信息）
            // - seatCount：该票种的当前座位总数（用于前端判断是否显示"生成座位"按钮）
            resultData.put("totalSeats", totalSeatsGenerated);

            if (dto.requiresSeats() && totalSeatsGenerated > 0) {
                resultData.put("seatCount", totalSeatsGenerated);
            } else {
                resultData.put("seatCount", 0);
            }

            log.info("[管理] 事务性创建完成 - ticketId:{}, title:{}, hasSeats:{}, seatCount:{}, totalSeats:{}",
                newTicketId,
                dto.getTicketTitle(),
                dto.requiresSeats(),
                resultData.get("seatCount"),
                totalSeatsGenerated);

            // 8. 缓存幂等结果（24小时过期，防止重复提交）
            Result<?> finalResult = Result.ok(resultData);
            if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
                try {
                    String cacheKey = "idempotent:ticket_with_seats:" + idempotencyKey;
                    stringRedisTemplate.opsForValue().set(
                        cacheKey,
                        objectMapper.writeValueAsString(finalResult),
                        24,
                        java.util.concurrent.TimeUnit.HOURS
                    );
                    log.info("[管理] 幂等结果已缓存，key:{}, 过期时间:24小时", idempotencyKey);
                } catch (Exception cacheEx) {
                    log.warn("[管理] 幂等结果缓存失败（不影响主流程），error:{}", cacheEx.getMessage());
                }
            }

            return finalResult;

        } catch (Exception e) {
            log.error("[管理] 事务性创建失败，已自动回滚，error:{}", e.getMessage(), e);
            if (e.getMessage().contains("座位生成失败")) {
                return Result.fail(e.getMessage());
            }
            return Result.fail(AppMessages.TICKET_CREATE_FAIL_PREFIX + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> createSeckillTicket(SeckillTicket seckillTicket) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }
        Ticket ticket = ticketService.getById(seckillTicket.getTicketId());
        if (ticket == null) {
            return Result.fail(AppMessages.TICKET_RELATED_NOT_EXIST);
        }
        seckillTicket.setCreateTime(LocalDateTime.now());

        // 如果设置了定时开始时间，记录日志
        if (seckillTicket.getScheduledStartTime() != null) {
            log.info("[管理] 创建秒杀票（定时开始），ticketId:{}, scheduledStartTime:{}",
                seckillTicket.getTicketId(), seckillTicket.getScheduledStartTime());
        }

        seckillTicketService.save(seckillTicket);
        log.info("[管理] 创建秒杀票成功，ticketId:{}, beginTime:{}, endTime:{}, scheduledStartTime:{}",
                seckillTicket.getTicketId(), seckillTicket.getBeginTime(),
                seckillTicket.getEndTime(), seckillTicket.getScheduledStartTime());

        // 刷新该演出的平均票价（秒杀票也可能影响均价）
        if (ticket.getShowId() != null && ticket.getShowId() > 0L) {
            refreshShowAvgPrice(ticket.getShowId());
        }

        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> updateSeckillTicket(SeckillTicket seckillTicket) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }
        SeckillTicket existing = seckillTicketService.getById(seckillTicket.getTicketId());
        if (existing == null) {
            return Result.fail(AppMessages.TICKET_SECKILL_NOT_EXIST);
        }
        int oldStock = existing.getStock() != null ? existing.getStock() : 0;
        existing.setStock(seckillTicket.getStock());
        existing.setBeginTime(seckillTicket.getBeginTime());
        existing.setEndTime(seckillTicket.getEndTime());
        existing.setScheduledStartTime(seckillTicket.getScheduledStartTime());
        existing.setUpdateTime(LocalDateTime.now());

        // 如果设置了定时开始时间，记录日志
        if (seckillTicket.getScheduledStartTime() != null) {
            log.info("[管理] 更新秒杀票（定时开始），ticketId:{}, scheduledStartTime:{}",
                seckillTicket.getTicketId(), seckillTicket.getScheduledStartTime());
        }

        seckillTicketService.updateById(existing);

        try {
            SeckillTicketServiceImpl impl = (SeckillTicketServiceImpl) seckillTicketService;
            // 同步Redis缓存（DB已由上方的updateById更新，此处仅同步Redis）
            impl.refreshSeckillStockToRedis(seckillTicket.getTicketId(), seckillTicket.getStock());
            log.info("[管理] Redis库存已同步，ticketId:{}, 旧库存:{} → 新库存:{}",
                seckillTicket.getTicketId(), oldStock, seckillTicket.getStock());
        } catch (Exception e) {
            log.error("[管理] Redis库存同步失败（不影响主流程），ticketId:{}, error:{}",
                seckillTicket.getTicketId(), e.getMessage());
        }

        log.info("[管理] 更新秒杀票成功，ticketId:{}", seckillTicket.getTicketId());

        // 刷新该演出的平均票价
        Ticket ticketForSeckill = ticketService.getById(seckillTicket.getTicketId());
        if (ticketForSeckill != null && ticketForSeckill.getShowId() != null) {
            refreshShowAvgPrice(ticketForSeckill.getShowId());
        }

        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> updateTicket(Ticket ticket) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        Ticket existingTicket = ticketService.getById(ticket.getId());
        if (existingTicket == null) {
            return Result.fail(AppMessages.ORDER_TICKET_NOT_EXIST);
        }

        // 验证票种状态（已上架限制）
        Result<?> statusValidationResult = validateTicketStatus(ticket, existingTicket);
        if (statusValidationResult != null && !statusValidationResult.getSuccess()) {
            return statusValidationResult;
        }

        // 验证演出ID变更（智能判断）
        boolean[] needUnlock = {false};
        boolean explicitShowIdChange;
        Long effectiveShowId;

        try {
            effectiveShowId = validateShowIdChange(ticket, existingTicket, needUnlock);
            explicitShowIdChange = !effectiveShowId.equals(existingTicket.getShowId());

            // 处理锁定/解锁逻辑
            Result<?> lockResult = processLockUnlock(
                ticket, existingTicket, effectiveShowId,
                needUnlock[0], explicitShowIdChange
            );
            if (lockResult != null && !lockResult.getSuccess()) {
                return lockResult;
            }

        } catch (RuntimeException e) {
            log.error("[管理] 验证失败, ticketId:{}, error:{}", ticket.getId(), e.getMessage());
            return Result.fail(e.getMessage());
        }

        // 关键修复：如果不是明确要修改showId，强制使用数据库值覆盖请求值
        if (!explicitShowIdChange) {
            ticket.setShowId(existingTicket.getShowId());
            log.debug("[管理] 使用数据库showId({})覆盖请求值, ticketId:{}", existingTicket.getShowId(), ticket.getId());
        }

        // 处理票种类型变更和秒杀配置更新
        int oldType = existingTicket.getType() != null ? existingTicket.getType() : 0;
        int newType = ticket.getType() != null ? ticket.getType() : 0;

        try {
            if (newType == 1) {
                // 更新为或保持为秒杀票：验证必填字段
                if (ticket.getBeginTime() == null || ticket.getEndTime() == null) {
                    return Result.fail(AppMessages.TICKET_SECKILL_TIME_REQUIRED);
                }
                if (ticket.getBeginTime().isAfter(ticket.getEndTime()) || ticket.getBeginTime().isEqual(ticket.getEndTime())) {
                    return Result.fail(AppMessages.TICKET_SECKILL_TIME_INVALID);
                }
                // 秒杀票的普通库存设为0
                ticket.setStock(0);

                // 查找是否已存在秒杀配置
                SeckillTicket existingSeckill = seckillTicketService.getById(ticket.getId());

                if (existingSeckill != null) {
                    // 更新现有秒杀配置
                    existingSeckill.setStock(ticket.getSeckillStock() != null ? ticket.getSeckillStock() : existingSeckill.getStock());
                    existingSeckill.setBeginTime(ticket.getBeginTime());
                    existingSeckill.setEndTime(ticket.getEndTime());
                    existingSeckill.setScheduledStartTime(ticket.getScheduledStartTime());
                    existingSeckill.setUpdateTime(LocalDateTime.now());
                    seckillTicketService.updateById(existingSeckill);
                    log.info("[管理] 更新秒杀票配置成功，ticketId:{}, stock:{}", ticket.getId(), existingSeckill.getStock());
                } else if (oldType != 1) {
                    // 从普通票改为秒杀票，创建新秒杀配置
                    SeckillTicket newSeckillTicket = new SeckillTicket();
                    newSeckillTicket.setTicketId(ticket.getId());
                    newSeckillTicket.setStock(ticket.getSeckillStock() != null ? ticket.getSeckillStock() : 0);
                    newSeckillTicket.setBeginTime(ticket.getBeginTime());
                    newSeckillTicket.setEndTime(ticket.getEndTime());
                    newSeckillTicket.setScheduledStartTime(ticket.getScheduledStartTime());
                    newSeckillTicket.setCreateTime(LocalDateTime.now());
                    seckillTicketService.save(newSeckillTicket);
                    log.info("[管理] 创建秒杀票配置（从普通票转换），ticketId:{}, stock:{}", ticket.getId(), newSeckillTicket.getStock());
                }
            } else if (oldType == 1 && newType == 0) {
                // 从秒杀票改为普通票：删除秒杀配置
                seckillTicketService.removeById(ticket.getId());
                log.info("[管理] 删除秒杀票配置（转换为普通票），ticketId:{}", ticket.getId());
                // 验证普通票库存
                if (ticket.getStock() == null || ticket.getStock() < 0) {
                    return Result.fail(AppMessages.TICKET_STOCK_INVALID);
                }
            } else {
                // 保持为普通票：验证库存
                if (ticket.getStock() == null || ticket.getStock() < 0) {
                    return Result.fail(AppMessages.TICKET_STOCK_INVALID);
                }
            }

            ticket.setUpdateTime(LocalDateTime.now());
            ticketService.updateById(ticket);

            // 清除管理端缓存
            clearTicketCache(ticket.getShowId());

            // 关键：立即刷新用户端缓存（Write-Through模式）
            try {
                ticketService.refreshTicketCache(ticket.getShowId());
                log.info("[管理] 更新票种成功并已刷新用户端缓存，ticketId:{}, showId:{}",
                    ticket.getId(), ticket.getShowId());
            } catch (Exception cacheEx) {
                log.warn("[管理] 更新票种成功但刷新用户端缓存失败（不影响主流程），ticketId:{}, showId:{}, error:{}",
                    ticket.getId(), ticket.getShowId(), cacheEx.getMessage());
            }

            // 刷新该演出的平均票价（票价可能被修改）
            refreshShowAvgPrice(ticket.getShowId());

            return Result.ok();
        } catch (Exception e) {
            log.error("[管理] 更新票种失败，error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.TICKET_UPDATE_FAIL_PREFIX + e.getMessage());
        }
    }

    /**
     * 验证票种状态（已上架票种的限制逻辑）
     *
     * 权限规则：
     * - 超级管理员（roleLevel=0）：可无视所有限制，强制操作
     * - 普通管理员（roleLevel=1）：受业务规则约束
     *
     * @param ticket 请求的票种对象
     * @param existingTicket 数据库中已有的票种
     * @return 验证结果（Result.success表示通过，Result.fail表示拒绝）
     */
    private Result<?> validateTicketStatus(Ticket ticket, Ticket existingTicket) {
        if (existingTicket.getStatus() == null || !TicketStatus.isOnline(existingTicket.getStatus())) {
            return Result.ok(); // 未上架，无需验证
        }

        // 超级管理员豁免：可强制操作已上架的票种
        if (isSuperAdmin()) {
            log.info("[权限检查] ⚡ 超级管理员强制操作 - 跳过票种状态验证, ticketId:{}, 操作:{}",
                    ticket.getId(),
                    TicketStatus.isOffline(ticket.getStatus()) ? "下架" : "编辑");
            return Result.ok();
        }

        // 以下是普通管理员的限制逻辑

        if (TicketStatus.isOffline(ticket.getStatus())) {
            // 下架操作：检查是否有已售订单
            try {
                long soldCount = ticketOrderService.count(
                    new QueryWrapper<TicketOrder>()
                        .eq("ticket_id", ticket.getId())
                        .in("status", 2, 3)
                );

                if (soldCount > 0) {
                    log.warn("[管理] [普通管理员] 拒绝下架 - 票种有{}个已售订单（需超级管理员操作）, ticketId:{}",
                            soldCount, ticket.getId());
                    return Result.fail(AppMessages.format(AppMessages.TICKET_OFFLINE_HAS_ORDERS, soldCount));
                }
                log.info("[管理] [普通管理员] 允许下架 - 票种无已售订单, ticketId:{}", ticket.getId());
            } catch (Exception e) {
                log.error("[管理] 检查已售订单失败, ticketId:{}, error:{}", ticket.getId(), e.getMessage());
                return Result.fail(AppMessages.ADMIN_TICKET_SYSTEM_ERROR_VERIFY_FAIL);
            }
            return Result.ok();
        } else {
            // 编辑操作：已上架的票种禁止修改（除非是超级管理员）
            log.warn("[管理] [普通管理员] 拒绝修改 - 票种已上架（需超级管理员操作）, ticketId:{}, 当前状态:{}",
                    ticket.getId(), existingTicket.getStatus());
            return Result.fail(AppMessages.ADMIN_TICKET_CANNOT_MODIFY_ONLINE);
        }
    }

    /**
     * 验证演出ID变更（智能判断是否真的要修改showId）
     *
     * @param ticket 请求的票种对象
     * @param existingTicket 数据库中已有的票种
     * @param needUnlock 输出参数：是否需要解锁票种
     * @return 最终生效的showId
     */
    private Long validateShowIdChange(Ticket ticket, Ticket existingTicket, boolean[] needUnlock) {
        Long requestShowId = ticket.getShowId();
        Long dbShowId = existingTicket.getShowId();

        if (requestShowId != null && !requestShowId.equals(dbShowId)) {
            if (requestShowId > 0) {
                log.info("[管理] [TARGET] 检测到明确的showId修改意图: {} → {}, ticketId:{}",
                        dbShowId, requestShowId, ticket.getId());

                Show targetShow = showService.getById(requestShowId);
                if (targetShow == null) {
                    log.error("[管理] [FAIL] 目标演出不存在，无法转移票种，targetShowId:{}", requestShowId);
                    throw new com.echo.exception.BusinessException(
                            AppMessages.format(AppMessages.RUNTIME_TARGET_SHOW_NOT_EXIST, requestShowId),
                            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
                }

                needUnlock[0] = true;
                log.info("[管理] [OK] 目标演出({})存在，将解锁票种({})", requestShowId, ticket.getId());
                return requestShowId;
            } else {
                log.warn("[管理] [WARN] 前端传了无效showId({})，使用数据库值({}), ticketId:{}",
                        requestShowId, dbShowId, ticket.getId());
                return dbShowId;
            }
        } else {
            return dbShowId;
        }
    }

    /**
     * 处理锁定/解锁逻辑（包括自动锁定孤儿票种、自动解锁等）
     *
     * @param ticket 请求的票种对象
     * @param existingTicket 数据库中已有的票种
     * @param effectiveShowId 最终生效的showId
     * @param needUnlock 是否需要解锁
     * @param explicitShowIdChange 是否明确修改了showId
     * @return 处理结果（Result.success表示通过，Result.fail表示拒绝）
     */
    private Result<?> processLockUnlock(Ticket ticket, Ticket existingTicket, Long effectiveShowId,
                                     boolean needUnlock, boolean explicitShowIdChange) {

        if (TicketStatus.isOnline(ticket.getStatus())) {
            log.info("[管理] 尝试上架票种({})，验证演出({})是否存在...", ticket.getId(), effectiveShowId);

            Show relatedShow = showService.getById(effectiveShowId);
            if (relatedShow == null) {
                log.error("[管理] [FAIL] 禁止上架！票种({})的关联演出({})不存在或已删除",
                        ticket.getId(), effectiveShowId);

                existingTicket.setLocked(TicketLock.LOCKED_ORPHAN.getCode());
                existingTicket.setUpdateTime(java.time.LocalDateTime.now());
                ticketService.updateById(existingTicket);

                log.warn("[管理] 已自动锁定孤儿票种，ticketId:{}, showId:{}",
                        ticket.getId(), effectiveShowId);

                return Result.fail(AppMessages.ADMIN_TICKET_ONLINE_SHOW_NOT_EXIST_PREFIX + effectiveShowId + AppMessages.ADMIN_TICKET_ONLINE_SHOW_NOT_EXIST_SUFFIX);
            }

            log.info("[管理] [OK] 演出({})存在，允许上架票种({})", effectiveShowId, ticket.getId());
        }

        if (TicketLock.isLocked(existingTicket.getLocked())) {
            if (needUnlock) {
                ticket.setLocked(TicketLock.UNLOCKED.getCode());
                log.info("[管理] 票种已自动解锁（转移到有效演出），ticketId:{}, newShowId:{}",
                        ticket.getId(), effectiveShowId);
            } else if (!explicitShowIdChange) {
                if (TicketStatus.isOnline(ticket.getStatus())) {
                    log.warn("[管理] 票种仍处于锁定状态，禁止上架，ticketId:{}, 原因:所属演出可能已删除",
                            ticket.getId());
                    return Result.fail(AppMessages.ADMIN_TICKET_LOCKED_PREFIX);
                }
                log.warn("[管理] [WARN] 票种仍处于锁定状态，但本次非上架操作，允许继续，ticketId:{}", ticket.getId());
            }
        }

        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> deleteTicket(Long id) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }
        Ticket ticket = ticketService.getById(id);
        if (ticket == null) {
            return Result.fail(AppMessages.ORDER_TICKET_NOT_EXIST);
        }

        // 超级管理员豁免：可强制删除（即使有已售订单）
        boolean isSuperAdminForceDelete = isSuperAdmin();

        // ============================================================
        // 检查是否有已售订单（普通管理员有则禁止删除）
        // 已售口径：订单状态 IN (2, 3) - 已支付 + 已核销
        // ============================================================
        try {
            long soldCount = ticketOrderService.count(
                new QueryWrapper<TicketOrder>()
                    .eq("ticket_id", id)
                    .in("status", 2, 3)  // 2=已支付, 3=已核销（完整已售口径）
            );

            if (soldCount > 0 && !isSuperAdminForceDelete) {
                log.warn("[管理] [普通管理员] 禁止删除票种（存在{}个已售订单，需超级管理员操作）, ticketId:{}",
                        soldCount, id);
                return Result.fail(AppMessages.ADMIN_TICKET_DELETE_HAS_SALES_PREFIX + soldCount + AppMessages.ADMIN_TICKET_DELETE_HAS_SALES_SUFFIX);
            }

            if (soldCount > 0 && isSuperAdminForceDelete) {
                log.warn("[权限检查] 超级管理员强制删除 - 忽略{}个已售订单, ticketId:{}", soldCount, id);
            } else {
                log.debug("[管理] [OK] 票种无已售订单，允许删除，ticketId:{}, soldCount:{}", id, soldCount);
            }
        } catch (Exception e) {
            log.error("[管理] [FAIL] 检查已售订单数失败，为安全起见禁止删除，ticketId:{}, error:{}", id, e.getMessage());
            return Result.fail(AppMessages.ADMIN_TICKET_DELETE_VERIFY_FAIL);
        }

        // 删除前记录信息用于清除缓存
        Long showId = ticket.getShowId();

        // 从数据库删除
        ticketService.removeById(id);

        // 清除管理端缓存
        clearTicketCache(showId);

        //  关键：立即刷新用户端缓存（Write-Through模式）
        try {
            ticketService.refreshTicketCache(showId);
            log.info("[管理] 删除票种成功并已刷新用户端缓存，ticketId:{}, showId:{}", id, showId);
        } catch (Exception cacheEx) {
            log.warn("[管理] 删除票种成功但刷新用户端缓存失败（不影响主流程），ticketId:{}, showId:{}, error:{}",
                id, showId, cacheEx.getMessage());
        }

        // 刷新该演出的平均票价（票种被删除，均价需要重新计算）
        refreshShowAvgPrice(showId);

        return Result.ok();
    }

    @Override
    public Result<?> updateStock(Long id, Integer stock) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }
        seckillTicketService.update().setSql("stock = stock + " + stock).eq("ticket_id", id).update();
        return Result.ok();
    }

    @Override
    public Result<?> batchCreateSeats(Long ticketId, SeatBatchCreateDTO dto) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        // 1. 执行座位生成（调用 SeatServiceImpl，避免同名方法自调用）
        Result<?> result = seatService.batchCreateSeats(ticketId, dto);

        // 2. 关键：清除管理端票种列表缓存（确保刷新后显示最新的seatCount）
        try {
            // 清除所有可能包含该票种的缓存（不同筛选条件可能有不同缓存）
            String[] cachePatterns = {
                "admin:tickets:0::*",           // 无showId筛选
                "admin:tickets:*:*:*:*:*"       // 所有可能的组合
            };

            // 简单方案：直接删除所有admin:tickets开头的缓存
            Set<String> keys = stringRedisTemplate.keys("admin:tickets:*");
            if (!keys.isEmpty()) {
                long deletedCount = stringRedisTemplate.delete(keys);
                log.info("[管理] 座位生成成功，已清除{}个票务列表缓存", deletedCount);
            }
        } catch (Exception cacheEx) {
            log.warn("[管理] 座位生成成功但清除列表缓存失败（不影响主流程），error:{}", cacheEx.getMessage());
        }

        // 3. 同时刷新用户端票种缓存（Write-Through模式）
        try {
            Ticket ticket = ticketService.getById(ticketId);
            if (ticket.getShowId() != null) {
                ticketService.refreshTicketCache(ticket.getShowId());
                log.info("[管理] 座位生成成功，已刷新用户端缓存，showId:{}", ticket.getShowId());
            }
        } catch (Exception refreshEx) {
            log.warn("[管理] 座位生成成功但刷新用户端缓存失败（不影响主流程），error:{}", refreshEx.getMessage());
        }

        // 关键：确保返回数据包含 seatCount 字段（前端用于判断是否显示"修复座位"按钮）
        if (Boolean.TRUE.equals(result.getSuccess())) {
            Object data = result.getData();
            if (data instanceof java.util.Map<?, ?> resultMap) {
                // 如果返回的是 Map，添加 seatCount 字段
                Object totalSeats = resultMap.get("totalSeats");
                if (totalSeats != null) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> mutableMap = (java.util.Map<String, Object>) resultMap;
                    mutableMap.put("seatCount", totalSeats);
                    log.info("[管理] 座位生成成功 - ticketId:{}, seatCount:{}, totalSeats:{}",
                            ticketId, totalSeats, totalSeats);
                }
            }
        }

        return result;
    }

    // ==================== 订单管理 ====================

    @Override
    public Result<?> listOrders(Integer status, String keyword, String userName, com.echo.dto.PageQuery pageQuery) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        // 缓存键必须覆盖所有影响结果的查询维度（含每页条数），
        // 否则先以 size=10 落缓存后，size=50 的请求会拿到同一份结果
        String cacheKey = String.format("admin:orders:%d:%s:%d:%d:%s:%s",
                status != null ? status : -1,
                keyword != null ? keyword : "",
                pageQuery.getCurrent(),
                pageQuery.getSize(),
                pageQuery.getSortBy() != null ? pageQuery.getSortBy() : "",
                pageQuery.getSortOrder() != null ? pageQuery.getSortOrder() : "");

        try {
            String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cachedJson != null && !cachedJson.isEmpty()) {
                log.debug("[管理] 从缓存获取订单列表，key:{}", cacheKey);
                return Result.ok(com.alibaba.fastjson.JSON.parseObject(cachedJson, Map.class));
            }
        } catch (Exception e) {
            log.warn("[管理] 缓存读取失败，将查询数据库，error:{}", e.getMessage());
        }

        QueryWrapper<TicketOrder> wrapper = new QueryWrapper<>();
        if (status != null) {
            wrapper.eq("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            try {
                wrapper.eq("id", Long.valueOf(keyword))
                        .or()
                        .eq("user_id", Long.valueOf(keyword));
            } catch (NumberFormatException ignored) {
            }
        }
        if (userName != null && !userName.isBlank()) {
            QueryWrapper<User> userQuery = new QueryWrapper<>();
            userQuery.like("nick_name", userName);
            List<User> users = userService.list(userQuery);
            if (!users.isEmpty()) {
                List<Long> userIds = users.stream().map(User::getId).toList();
                wrapper.in("user_id", userIds);
            } else {
                wrapper.apply("1=0");
            }
        }

        // 使用 PageQuery 的排序参数
        String sortBy = pageQuery.getSortBy();
        String sortOrder = pageQuery.getSortOrder();

        if (sortBy != null && !sortBy.isBlank()) {
            boolean asc = "asc".equalsIgnoreCase(sortOrder);
            switch (sortBy) {
                case "id":
                    wrapper.orderBy(true, asc, "id");
                    break;
                case "userId":
                    wrapper.orderBy(true, asc, "user_id");
                    break;
                case "status":
                    wrapper.orderBy(true, asc, "status");
                    break;
                case "createTime":
                    wrapper.orderBy(true, asc, "create_time");
                    break;
                case "payTime":
                    wrapper.orderBy(true, asc, "pay_time");
                    break;
                default:
                    wrapper.orderByDesc("create_time");
                    break;
            }
        } else {
            wrapper.orderByDesc("create_time");
        }

        // 使用 PageQuery 创建分页对象
        Page<TicketOrder> page = ticketOrderService.page(pageQuery.toTypedPage(), wrapper);

        List<TicketOrder> orders = page.getRecords();

        List<Map<String, Object>> enrichedRecords = enrichOrderRecords(orders);

        // 使用 PageResult 统一返回格式
        com.echo.dto.PageResult<Map<String, Object>> result =
                com.echo.dto.PageResult.of(enrichedRecords, page.getTotal(),
                        (int) page.getCurrent(), (int) page.getSize());

        try {
            stringRedisTemplate.opsForValue().set(cacheKey,
                    com.alibaba.fastjson.JSON.toJSONString(result),
                    3, java.util.concurrent.TimeUnit.MINUTES);
            log.debug("[管理] 订单列表已缓存，key:{}, TTL:3分钟", cacheKey);
        } catch (Exception e) {
            log.warn("[管理] 缓存写入失败，不影响业务，error:{}", e.getMessage());
        }

        return Result.ok(result);
    }

    @Override
    public Result<?> getOrderDetail(Long orderId) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        TicketOrder order = ticketOrderService.getById(orderId);
        if (order == null) {
            log.warn("[管理] 订单详情查询失败，订单不存在，orderId:{}", orderId);
            return Result.fail(AppMessages.ORDER_NOT_EXIST);
        }

        Map<String, Object> detail = enrichOrderRecords(List.of(order)).get(0);

        log.info("[管理] 订单详情查询成功，orderId:{}, userId:{}", orderId, order.getUserId());
        return Result.ok(detail);
    }

    /**
     * 将订单实体装配为管理端展示结构（合并用户、票种、演出信息，并按状态输出核销码）
     *
     * @param orders 订单实体列表
     * @return 装配后的展示数据，顺序与入参一致
     */
    private List<Map<String, Object>> enrichOrderRecords(List<TicketOrder> orders) {
        List<Long> userIds = orders.stream()
                .map(TicketOrder::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, User> userMap = userIds.isEmpty() ? Collections.emptyMap()
                : userService.listByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));

        List<Long> ticketIds = orders.stream()
                .map(TicketOrder::getTicketId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Ticket> ticketMap = ticketIds.isEmpty() ? Collections.emptyMap()
                : ticketService.listByIds(ticketIds).stream()
                        .collect(Collectors.toMap(Ticket::getId, t -> t));
        Map<Long, SeckillTicket> seckillTicketMap = ticketIds.isEmpty() ? Collections.emptyMap()
                : seckillTicketService.listByIds(ticketIds).stream()
                        .collect(Collectors.toMap(SeckillTicket::getTicketId, st -> st));

        Set<Long> showIdSet = new HashSet<>();
        ticketMap.values().forEach(t -> { if (t.getShowId() != null) { showIdSet.add(t.getShowId()); } });
        seckillTicketMap.values().forEach(st -> {
            Ticket linked = ticketMap.get(st.getTicketId());
            if (linked != null && linked.getShowId() != null) { showIdSet.add(linked.getShowId()); }
        });
        Map<Long, Show> showMap = showIdSet.isEmpty() ? Collections.emptyMap()
                : showService.listByIds(showIdSet).stream()
                        .collect(Collectors.toMap(Show::getId, s -> s));

        List<Map<String, Object>> enrichedRecords = orders.stream().map(order -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", order.getId());
            map.put("userId", order.getUserId());
            map.put("ticketId", order.getTicketId());
            map.put("payType", order.getPayType());
            map.put("status", order.getStatus());
            map.put("createTime", order.getCreateTime());
            map.put("payTime", order.getPayTime());
            map.put("useTime", order.getUseTime());
            map.put("refundTime", order.getRefundTime());

            // 返回核销码（仅已支付及以上的订单）
            if (OrderStatus.isPostPayment(order.getStatus()) && order.getVerificationCode() != null) {
                map.put("verificationCode", order.getVerificationCode());
                log.debug("[管理端] 订单核销码 - orderId:{}, code:{}", order.getId(), order.getVerificationCode());
            }

            User user = userMap.get(order.getUserId());
            map.put("userName", user != null ? user.getNickName() : "未知用户");

            try {
                Ticket ticket = ticketMap.get(order.getTicketId());
                if (ticket != null) {
                    map.put("ticketTitle", ticket.getTitle());
                    map.put("payValue", ticket.getPayValue());
                    map.put("actualValue", ticket.getActualValue());
                    Show show = showMap.get(ticket.getShowId());
                    map.put("showName", show != null ? show.getName() : "");
                } else {
                    SeckillTicket seckillTicket = seckillTicketMap.get(order.getTicketId());
                    if (seckillTicket != null) {
                        Ticket seckillLinkedTicket = ticketMap.get(seckillTicket.getTicketId());
                        if (seckillLinkedTicket != null) {
                            map.put("ticketTitle", seckillLinkedTicket.getTitle());
                            map.put("payValue", seckillLinkedTicket.getPayValue());
                            map.put("actualValue", seckillLinkedTicket.getActualValue());
                            Show show = showMap.get(seckillLinkedTicket.getShowId());
                            map.put("showName", show != null ? show.getName() : "");
                        } else {
                            map.put("ticketTitle", "秒杀票");
                            map.put("payValue", 0);
                            map.put("actualValue", 0);
                            map.put("showName", "");
                        }
                    } else {
                        map.put("ticketTitle", "未知票种");
                        map.put("payValue", 0);
                        map.put("actualValue", 0);
                        map.put("showName", "");
                    }
                }
            } catch (Exception e) {
                map.put("ticketTitle", "未知票种");
                map.put("payValue", 0);
                map.put("actualValue", 0);
                map.put("showName", "");
            }

            return map;
        }).toList();
        return enrichedRecords;
    }

    @Override
    public Result<?> approveRefund(Long orderId) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }
        return ticketOrderService.adminRefundOrder(orderId);
    }

    @Override
    public Result<?> dashboard() {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        Map<String, Object> baseStats = getBaseStatistics();
        long todayOrderCount = getTodayOrderCount();
        List<Map<String, Object>> topShows = getTopShowsBySales();

        Map<String, Object> result = new HashMap<>(baseStats);
        result.put("todayOrderCount", todayOrderCount);
        result.put("topShows", topShows);
        return Result.ok(result);
    }

    private Map<String, Object> getBaseStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("showCount", showService.count());
        stats.put("ticketCount", ticketService.count());
        stats.put("orderCount", ticketOrderService.count());
        stats.put("userCount", userService.count());
        return stats;
    }

    private long getTodayOrderCount() {
        QueryWrapper<TicketOrder> todayQuery = new QueryWrapper<>();
        todayQuery.ge("create_time", LocalDateTime.now().withHour(0).withMinute(0).withSecond(0));
        return ticketOrderService.count(todayQuery);
    }

    private List<Map<String, Object>> getTopShowsBySales() {
        QueryWrapper<TicketOrder> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("status", 2, 3)
                .select("ticket_id, COUNT(*) as sales_count")
                .groupBy("ticket_id")
                .orderByDesc("sales_count")
                .last("LIMIT 10");

        List<Map<String, Object>> dbResult = ticketOrderService.listMaps(queryWrapper);

        return dbResult.stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            Object ticketIdObj = row.get("ticket_id");
            Long ticketId = ticketIdObj instanceof Number ? ((Number) ticketIdObj).longValue() : null;

            map.put("ticketId", ticketId);
            map.put("count", row.get("sales_count"));

            if (ticketId != null) {
                try {
                    Ticket ticket = ticketService.getById(ticketId);
                    if (ticket != null) {
                        map.put("ticketTitle", ticket.getTitle());
                        Show show = showService.getById(ticket.getShowId());
                        map.put("showName", show != null ? show.getName() : "未知演出");
                    } else {
                        map.put("ticketTitle", "未知票种");
                        map.put("showName", "未知演出");
                    }
                } catch (Exception ex) {
                    map.put("ticketTitle", "未知票种");
                    map.put("showName", "未知演出");
                }
            } else {
                map.put("ticketTitle", "未知票种");
                map.put("showName", "未知演出");
            }

            return map;
        }).toList();
    }

    @Override
    public Result<?> exportOrders(Integer status) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        final int MAX_EXPORT_LIMIT = 10000;

        QueryWrapper<TicketOrder> query = new QueryWrapper<>();
        if (status != null) {
            query.eq("status", status);
        }
        query.orderByDesc("create_time");

        long totalCount = ticketOrderService.count(query);

        if (totalCount > MAX_EXPORT_LIMIT) {
            log.warn("[管理] [导出限制] 订单总数{}超过最大导出限制{}，已自动截断",
                    totalCount, MAX_EXPORT_LIMIT);

            Map<String, Object> limitWarning = new HashMap<>();
            limitWarning.put("success", true);
            limitWarning.put("message", String.format(
                    "订单总数 %d 条超过单次导出上限 %d 条，已自动截断为最近 %d 条数据。如需导出全部数据，请按状态分批导出。",
                    totalCount, MAX_EXPORT_LIMIT, MAX_EXPORT_LIMIT));
            limitWarning.put("total_count", totalCount);
            limitWarning.put("exported_count", MAX_EXPORT_LIMIT);
            limitWarning.put("limit_reached", true);

            Page<TicketOrder> page = new Page<>(1, MAX_EXPORT_LIMIT);
            Page<TicketOrder> orderPage = ticketOrderService.page(page, query);

            limitWarning.put("data", buildCsvContent(orderPage.getRecords()));
            return Result.ok(limitWarning);
        }

        List<TicketOrder> orders = ticketOrderService.list(query);
        log.info("[管理] 导出订单数据，共{}条（未超限）", orders.size());

        return Result.ok(buildCsvContent(orders));
    }

    private String buildCsvContent(List<TicketOrder> orders) {
        StringBuilder sb = new StringBuilder();
        sb.append("订单ID,用户ID,票种ID,支付方式,状态,创建时间,支付时间,核销时间,退款时间\n");

        for (TicketOrder o : orders) {
            sb.append(o.getId()).append(",")
                    .append(o.getUserId()).append(",")
                    .append(o.getTicketId()).append(",")
                    .append(o.getPayType() != null ? o.getPayType() : "").append(",")
                    .append(o.getStatus() != null ? o.getStatus() : "").append(",")
                    .append(o.getCreateTime() != null ? o.getCreateTime() : "").append(",")
                    .append(o.getPayTime() != null ? o.getPayTime() : "").append(",")
                    .append(o.getUseTime() != null ? o.getUseTime() : "").append(",")
                    .append(o.getRefundTime() != null ? o.getRefundTime() : "").append("\n");
        }

        return sb.toString();
    }

    // ==================== 员工管理 ====================

    @Override
    public Result<?> listStaff() {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        AdminDTO currentAdmin = UserHolder.getAdmin();
        AdminRoleLevel currentRoleLevel = (currentAdmin != null && currentAdmin.getRoleLevel() != null)
                ? currentAdmin.getRoleLevel() : AdminRoleLevel.NORMAL_ADMIN;

        List<Admin> staffList = lambdaQuery()
                .ge(Admin::getRoleLevel, currentRoleLevel)
                .orderByDesc(Admin::getCreateTime)
                .list();

        // 对外统一输出 AdminDTO：凭证字段（password）停留在实体内部，越过服务层边界前完成转换
        List<AdminDTO> staffDTOList = staffList.stream()
                .map(admin -> {
                    AdminDTO dto = new AdminDTO();
                    BeanUtil.copyProperties(admin, dto);
                    if (admin.getRoleLevel() != null
                            && admin.getRoleLevel().getCode() < currentRoleLevel.getCode()) {
                        dto.setPhone("****");
                    }
                    return dto;
                })
                .collect(Collectors.toList());

        log.info("[管理] 员工列表查询成功，当前管理员roleLevel:{}, 返回记录数:{}",
                currentRoleLevel, staffDTOList.size());
        return Result.ok(staffDTOList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> addStaff(Admin admin) {
        if (!isSuperAdmin()) {
            return Result.fail(AppMessages.PERMISSION_SUPER_ADMIN_ONLY);
        }
        User user = userService.getById(admin.getUserId());
        if (user == null) {
            return Result.fail(AppMessages.USER_NOT_EXIST);
        }

        long count = lambdaQuery()
                .eq(Admin::getUserId, admin.getUserId())
                .count();
        if (count > 0) {
            return Result.fail(AppMessages.ADMIN_USER_ALREADY_ADMIN);
        }

        admin.setStatus(AdminStatus.NORMAL);
        admin.setCreateTime(LocalDateTime.now());
        admin.setUpdateTime(LocalDateTime.now());
        save(admin);
        log.info("[管理] 新增管理员，userId:{}, name:{}", admin.getUserId(), admin.getName());
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> removeStaff(Long userId) {
        if (!isSuperAdmin()) {
            return Result.fail(AppMessages.PERMISSION_SUPER_ADMIN_ONLY);
        }

        AdminDTO currentAdmin = UserHolder.getAdmin();
        if (currentAdmin != null && Objects.equals(currentAdmin.getUserId(), userId)) {
            log.warn("[安全警告] 超级管理员尝试移除自己的管理员身份，adminId:{}, userId:{}", currentAdmin.getId(), userId);
            return Result.fail(AppMessages.ADMIN_CANNOT_OPERATE_SELF);
        }

        Admin admin = lambdaQuery()
                .eq(Admin::getUserId, userId)
                .one();
        if (admin == null) {
            return Result.fail(AppMessages.ADMIN_USER_NOT_ADMIN);
        }

        if (currentAdmin.getId().equals(admin.getId())) {
            log.warn("[安全警告] 超级管理员尝试移除自己的管理员身份，adminId:{}", admin.getId());
            return Result.fail(AppMessages.ADMIN_CANNOT_OPERATE_SELF);
        }

        removeById(admin.getId());
        log.info("[管理] 移除管理员，userId:{}", userId);
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> disableStaff(Long adminId) {
        if (!isSuperAdmin()) {
            return Result.fail(AppMessages.PERMISSION_SUPER_ADMIN_ONLY);
        }

        AdminDTO currentAdmin = UserHolder.getAdmin();
        if (currentAdmin != null && Objects.equals(currentAdmin.getId(), adminId)) {
            log.warn("[安全警告] 管理员尝试禁用自己的账号，adminId:{}", adminId);
            return Result.fail(AppMessages.ADMIN_CANNOT_DISABLE_SELF);
        }

        Admin admin = getById(adminId);
        if (admin == null) {
            return Result.fail(AppMessages.ADMIN_NOT_EXIST);
        }
        admin.setStatus(AdminStatus.DISABLED);
        admin.setUpdateTime(LocalDateTime.now());
        updateById(admin);
        log.info("[管理] 禁用管理员，adminId:{}, userId:{}", adminId, admin.getUserId());
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> enableStaff(Long adminId) {
        if (!isSuperAdmin()) {
            return Result.fail(AppMessages.PERMISSION_SUPER_ADMIN_ONLY);
        }
        Admin admin = getById(adminId);
        if (admin == null) {
            return Result.fail(AppMessages.ADMIN_NOT_EXIST);
        }
        admin.setStatus(AdminStatus.NORMAL);
        admin.setUpdateTime(LocalDateTime.now());
        updateById(admin);
        log.info("[管理] 启用管理员，adminId:{}, userId:{}", adminId, admin.getUserId());
        return Result.ok();
    }

    // ==================== 用户管理 ====================

    @Override
    public Result<?> listUsers(String keyword, String phone, Long id, Integer current, Integer pageSize, String sortBy, String sortOrder) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }
        QueryWrapper<User> wrapper = new QueryWrapper<>();

        if (id != null && id > 0) {
            wrapper.eq("id", id);
        } else if (phone != null && !phone.isBlank()) {
            wrapper.like("phone", phone);
        } else if (keyword != null && !keyword.isBlank()) {
            wrapper.like("nick_name", keyword);
        }

        if (sortBy != null && !sortBy.isBlank()) {
            boolean asc = "asc".equalsIgnoreCase(sortOrder);
            switch (sortBy) {
                case "id":
                    wrapper.orderBy(true, asc, "id");
                    break;
                case "nickName":
                    wrapper.orderBy(true, asc, "nick_name");
                    break;
                case "phone":
                    wrapper.orderBy(true, asc, "phone");
                    break;
                case "status":
                    wrapper.orderBy(true, asc, "status");
                    break;
                case "createTime":
                    wrapper.orderBy(true, asc, "create_time");
                    break;
                default:
                    wrapper.orderByDesc("create_time");
                    break;
            }
        } else {
            wrapper.orderByDesc("create_time");
        }

        int actualPageSize = (pageSize != null && pageSize > 0) ? pageSize : 20;
        Page<User> page = userService.page(new Page<>(current, actualPageSize), wrapper);
        return Result.ok(page);
    }

    @Override
    public Result<?> getUserDetail(Long userId) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }
        User user = userService.getById(userId);
        if (user == null) {
            return Result.fail(AppMessages.USER_NOT_EXIST);
        }
        UserInfo userInfo = userInfoService.getById(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("user", user);
        result.put("userInfo", userInfo);
        return Result.ok(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> disableUser(Long userId) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }
        User user = userService.getById(userId);
        if (user == null) {
            return Result.fail(AppMessages.USER_NOT_EXIST);
        }

        long adminCount = lambdaQuery()
                .eq(Admin::getUserId, userId)
                .eq(Admin::getStatus, 0)
                .count();
        if (adminCount > 0) {
            return Result.fail(AppMessages.ADMIN_CANNOT_DISABLE_ADMIN);
        }

        user.setStatus(1);
        userService.updateById(user);
        // 【P3修复】禁用后立即踢下线该用户所有已签发Token，防止被禁用账号继续操作
        kickUserTokens(userId);
        log.info("[管理] 禁用用户，userId:{}", userId);
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> enableUser(Long userId) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }
        User user = userService.getById(userId);
        if (user == null) {
            return Result.fail(AppMessages.USER_NOT_EXIST);
        }

        user.setStatus(0);
        userService.updateById(user);
        log.info("[管理] 启用用户，userId:{}", userId);
        return Result.ok();
    }

    /**
     * 清除票种列表缓存
     * @param showId 演出ID（可选）
     */
    private void clearTicketCache(Long showId) {
        try {
            // 删除所有票种相关的缓存
            Set<String> keys = stringRedisTemplate.keys("admin:tickets:*");
            if (!keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
                log.debug("[缓存清除] 已清除 {} 个票种缓存", keys.size());
            }

            // 如果有showId，也尝试删除更精确的key模式
            if (showId != null) {
                String pattern = String.format("admin:tickets:%d:*", showId);
                keys = stringRedisTemplate.keys(pattern);
                if (!keys.isEmpty()) {
                    stringRedisTemplate.delete(keys);
                    log.debug("[缓存清除] 已清除演出{}的 {} 个票种缓存", showId, keys.size());
                }
            }
        } catch (Exception e) {
            log.warn("[缓存清除] 清除票种缓存失败，不影响业务，error:{}", e.getMessage());
        }
    }

    // ==================== 批量操作实现 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> batchOperationShows(com.echo.dto.BatchOperationDTO batchDTO) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        long startTime = System.currentTimeMillis();

        List<Long> ids = batchDTO.getIds();
        BatchOperationDTO.Operation operation = batchDTO.getOperation();

        // 记录操作开始日志
        OperationLog operationLog = operationLogService.logBatchStart(
                "BATCH_" + operation.name(),
                "show",
                ids,
                null  // HttpServletRequest将在Controller层处理
        );

        int successCount = 0;
        int failCount = 0;
        List<String> failedIds = new ArrayList<>();

        try {
            for (Long id : ids) {
                try {
                    Show show = showService.getById(id);
                    if (show == null) {
                        failCount++;
                        failedIds.add(String.valueOf(id));
                        continue;
                    }

                    switch (operation) {
                        case ONLINE:
                            show.setStatus(ShowStatus.ONLINE.getCode());
                            showService.updateById(show);
                            successCount++;
                            break;

                        case OFFLINE:
                            show.setStatus(ShowStatus.OFFLINE.getCode());
                            showService.updateById(show);
                            successCount++;
                            break;

                        case DELETE:
                            // 检查是否有关联票种
                            long ticketCount = ticketService.count(
                                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.Ticket>()
                                            .eq("show_id", id)
                            );

                            if (ticketCount > 0) {
                                // 级联删除关联的票种和秒杀票
                                log.warn("[批量删除演出] 演出(id:{})有关联票种({}个)，将级联删除", id, ticketCount);

                                // 1. 查询所有关联的票种ID
                                List<Long> ticketIds = ticketService.list(
                                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.Ticket>()
                                                .select("id")
                                                .eq("show_id", id)
                                ).stream().map(com.echo.entity.Ticket::getId).toList();

                                // 2. 删除关联的秒杀票信息
                                if (!ticketIds.isEmpty()) {
                                    seckillTicketService.remove(
                                            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.SeckillTicket>()
                                                    .in("ticket_id", ticketIds)
                                    );
                                }

                                // 3. 删除所有关联的票种
                                ticketService.remove(
                                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.echo.entity.Ticket>()
                                                .eq("show_id", id)
                                );

                                log.info("[批量删除演出] 已级联删除演出({})的{}个票种及秒杀信息", id, ticketCount);
                            }

                            // 4. 最后删除演出本身
                            showService.removeById(id);
                            successCount++;
                            break;

                        default:
                            failCount++;
                            failedIds.add(String.valueOf(id));
                            log.warn("[批量操作] 不支持的操作类型 - id:{}, operation:{}", id, operation.name());
                    }

                } catch (Exception e) {
                    failCount++;
                    failedIds.add(String.valueOf(id));
                    log.error("[批量操作] 演出操作失败 - id:{}, error:{}", id, e.getMessage(), e);
                }
            }

            long durationMs = System.currentTimeMillis() - startTime;

            // 更新日志为成功状态（如果日志记录成功的话）
            if (operationLog != null) {
                operationLogService.logBatchSuccess(
                        operationLog.getId(),
                        successCount,
                        failCount,
                        durationMs
                );
            } else {
                log.warn("[批量操作] 操作日志未记录，跳过更新状态");
            }

            log.info("[批量操作] 演出操作完成 - operation:{}, total:{}, success:{}, fail:{}, duration:{}ms",
                    operation.name(), ids.size(), successCount, failCount, durationMs);

            Map<String, Object> result = new HashMap<>();
            result.put("successCount", successCount);
            result.put("failCount", failCount);
            result.put("failedIds", failedIds);
            result.put("operationLogId", operationLog != null ? operationLog.getId() : null); // 返回日志ID用于回滚

            return Result.ok(result);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;

            // 更新日志为失败状态（如果日志记录成功的话）
            if (operationLog != null) {
                operationLogService.logBatchFailure(
                        operationLog.getId(),
                        e.getMessage(),
                        durationMs
                );
            }

            log.error("[批量操作] 演出操作异常 - error:{}", e.getMessage(), e);
            throw e; // 重新抛出异常，让事务回滚
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> batchOperationTickets(com.echo.dto.BatchOperationDTO batchDTO) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        List<Long> ids = batchDTO.getIds();
        BatchOperationDTO.Operation operation = batchDTO.getOperation();
        int successCount = 0;
        int failCount = 0;
        List<String> failedIds = new ArrayList<>();

        for (Long id : ids) {
            try {
                Ticket ticket = ticketService.getById(id);
                if (ticket == null) {
                    failCount++;
                    failedIds.add(String.valueOf(id));
                    continue;
                }

                switch (operation) {
                    case DELETE:
                        ticketService.removeById(id);
                        // 同时删除关联的秒杀票信息
                        seckillTicketService.remove(
                            new QueryWrapper<SeckillTicket>().eq("ticket_id", id)
                        );
                        successCount++;
                        break;

                    case UPDATE:
                        // 从extraData获取更新字段
                        if (batchDTO.getExtraData() instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> updateData = (Map<String, Object>) batchDTO.getExtraData();

                            // 检查是否尝试上架孤儿票种
                            if (TicketLock.isLocked(ticket.getLocked())) {
                                Object newStatus = updateData.get("status");
                                if (newStatus != null && (newStatus instanceof Number && ((Number) newStatus).intValue() == 1)) {
                                    log.warn("[批量操作] 票种已被锁定，禁止上架，ticketId:{}, 原因:所属演出可能已删除", id);

                                    // 验证关联演出是否存在
                                    Show relatedShow = showService.getById(ticket.getShowId());
                                    if (relatedShow == null) {
                                        log.error("[批量操作] 票种({})所属演出({})不存在，禁止上架", id, ticket.getShowId());
                                        failCount++;
                                        failedIds.add(id + "(所属演出已删除)");
                                        continue;
                                    }
                                }
                            }

                            // 如果修改了showId，验证新演出是否存在
                            if (updateData.containsKey("showId")) {
                                Long newShowId = Long.valueOf(updateData.get("showId").toString());
                                Show newShow = showService.getById(newShowId);
                                if (newShow == null) {
                                    log.error("[批量操作] 目标演出不存在，showId:{}", newShowId);
                                    failCount++;
                                    failedIds.add(id + "(目标演出不存在)");
                                    continue;
                                }
                            }

                            BeanUtil.copyProperties(updateData, ticket, "id", "createTime");
                            ticketService.updateById(ticket);
                            successCount++;
                        } else {
                            failCount++;
                            failedIds.add(String.valueOf(id));
                        }
                        break;

                    default:
                        failCount++;
                        failedIds.add(String.valueOf(id));
                }

            } catch (Exception e) {
                failCount++;
                failedIds.add(String.valueOf(id));
                log.error("[批量操作] 票种操作失败 - id:{}, error:{}", id, e.getMessage(), e);
            }
        }

        // 清除相关缓存
        clearTicketCache(null);

        log.info("[批量操作] 票种操作完成 - operation:{}, total:{}, success:{}, fail:{}",
                operation.name(), ids.size(), successCount, failCount);

        Map<String, Object> result = new HashMap<>();
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("failedIds", failedIds);

        return Result.ok(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> batchOperationOrders(com.echo.dto.BatchOperationDTO batchDTO) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        List<Long> ids = batchDTO.getIds();
        BatchOperationDTO.Operation operation = batchDTO.getOperation();
        int successCount = 0;
        int failCount = 0;
        List<String> failedIds = new ArrayList<>();

        for (Long orderId : ids) {
            try {
                TicketOrder order = ticketOrderService.getById(orderId);
                if (order == null) {
                    failCount++;
                    failedIds.add(String.valueOf(orderId));
                    continue;
                }

                switch (operation) {
                    case APPROVE_REFUND:
                        // 只有"申请退款中"的订单才能审批通过
                        if (OrderStatus.REFUND_PENDING.getCode() == order.getStatus()) {
                            try {
                                Result<?> refundResult = ticketOrderService.adminRefundOrder(orderId);

                                // 检查返回结果（可能是成功消息或失败）
                                Object successFlag = refundResult.getSuccess();
                                boolean isSuccess = (successFlag != null &&
                                    (successFlag.equals(true) || refundResult.getData() instanceof String));

                                if (isSuccess || (refundResult.getData() != null &&
                                    refundResult.getData().toString().contains("已退款"))) {
                                    successCount++;
                                    log.info("[批量操作] 审批退款成功 - orderId:{}, 结果:{}", orderId,
                                        refundResult.getData() != null ? refundResult.getData() : "成功");
                                } else {
                                    failCount++;
                                    String errorMsg = (refundResult.getErrorMsg() != null) ? refundResult.getErrorMsg() : "审批失败";
                                    failedIds.add(orderId + "(" + errorMsg + ")");
                                    log.warn("[批量操作] 审批退款返回失败 - orderId:{}, error:{}", orderId, errorMsg);
                                }
                            } catch (Exception ex) {
                                failCount++;
                                failedIds.add(orderId + "(异常:" + ex.getMessage() + ")");
                                log.error("[批量操作] 审批退款异常 - orderId:{}", orderId, ex);
                            }
                        } else if (OrderStatus.REFUNDED.getCode() == order.getStatus()) {
                            // 已退款的订单视为成功（幂等处理）
                            successCount++;
                            log.info("[批量操作] 订单已退款，跳过 - orderId:{}", orderId);
                        } else {
                            failCount++;
                            failedIds.add(orderId + "(状态:" + order.getStatus() + ")");
                            log.warn("[批量操作] 订单状态不是'申请退款中'，无法审批 - orderId:{}, 当前状态:{}",
                                orderId, order.getStatus());
                        }
                        break;

                    case REJECT_REFUND:
                        // 拒绝退款申请（将订单状态从"申请退款"改为"已拒绝"）
                        if (order.getStatus() == com.echo.enums.OrderStatus.REFUND_PENDING.getCode()) {
                            // 状态写入统一走状态机唯一入口：规则校验(仅退款中6可拒绝) + 条件更新(并发安全)
                            java.util.Map<String, Object> rejectExtra = new java.util.HashMap<>();
                            rejectExtra.put("update_time", LocalDateTime.now());
                            com.echo.statemachine.OrderStateMachine.TransitionContext rejectCtx =
                                    com.echo.statemachine.OrderStateMachine.TransitionContext.of(
                                            order.getUserId(), order.getTicketId(), order.getShowId(),
                                            order.getQuantity(), "管理员拒绝退款");
                            var rejectStateResult = orderStateMachine.applyTransition(
                                    String.valueOf(orderId),
                                    com.echo.enums.OrderStatus.REFUND_REJECTED.getCode(), rejectExtra, rejectCtx);
                            boolean rejectSuccess = rejectStateResult.isSuccess();
                            if (rejectSuccess) {
                                successCount++;
                                log.info("[批量操作] 拒绝退款成功 - orderId:{}, 状态: {} → {}",
                                        orderId,
                                        com.echo.enums.OrderStatus.REFUND_PENDING.getDescription(),
                                        com.echo.enums.OrderStatus.REFUND_REJECTED.getDescription());

                                try {
                                    // 事件来源统一：直接发布状态机构建的事件（与applyTransition的校验/落库同源，
                                    // 替代原先from硬编码为6的手工构建）
                                    applicationEventPublisher.publishEvent(rejectStateResult.getEvent());
                                    log.info("[批量操作] 已发布 OrderRefRejectEvent - orderId:{}, ticketId:{}", orderId, order.getTicketId());
                                } catch (Exception eventEx) {
                                    log.warn("[批量操作] 发布领域事件失败（不影响主流程） - orderId:{}, error:{}", orderId, eventEx.getMessage());
                                }

                                try {
                                    Long userId = order.getUserId();
                                    String notificationTitle = "退款申请已被拒绝";
                                    String notificationContent = "您对订单 #" + orderId + " 的退款申请已被管理员拒绝。如有疑问请联系客服。";

                                    com.echo.message.NotificationMessage msg = com.echo.message.NotificationMessage.of(
                                            userId,
                                            NotificationType.REFUND_SUCCESS,
                                            notificationTitle,
                                            notificationContent,
                                            orderId
                                    );
                                    rocketMQTemplate.asyncSend(
                                            com.echo.utils.MqConstants.NOTIFICATION_TOPIC,
                                            org.springframework.messaging.support.MessageBuilder.withPayload(msg).build(),
                                            new org.apache.rocketmq.client.producer.SendCallback() {
                                                @Override
                                                public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                                                    log.info("[批量操作] 拒绝退款通知已发送 - orderId:{}, userId:{}", orderId, userId);
                                                }
                                                @Override
                                                public void onException(Throwable e) {
                                                    log.warn("[批量操作] 拒绝退款通知发送失败 - orderId:{}, error:{}", orderId, e.getMessage());
                                                }
                                            }
                                    );
                                } catch (Exception notifyEx) {
                                    log.warn("[批量操作] 拒绝退款通知发送失败 - orderId:{}, error:{}", orderId, notifyEx.getMessage());
                                }
                            } else {
                                failCount++;
                                failedIds.add(orderId + "(" + rejectStateResult.getErrorMessage() + ")");
                                log.error("[批量操作] 拒绝退款失败 - orderId:{}, 原因:{}", orderId, rejectStateResult.getErrorMessage());
                            }
                        } else {
                            failCount++;
                            failedIds.add(String.valueOf(orderId));
                            log.warn("[批量操作] 订单状态不是'申请退款'，无法拒绝 - orderId:{}, 当前状态:{}", orderId, order.getStatus());
                        }
                        break;

                    default:
                        failCount++;
                        failedIds.add(String.valueOf(orderId));
                }

            } catch (Exception e) {
                failCount++;
                failedIds.add(String.valueOf(orderId));
                log.error("[batchOperation] 订单操作失败 - orderId:{}, error:{}", orderId, e.getMessage(), e);
            }
        }

        log.info("[批量操作] 订单操作完成 - operation:{}, total:{}, success:{}, fail:{}",
                operation.name(), ids.size(), successCount, failCount);

        Map<String, Object> result = new HashMap<>();
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("failedIds", failedIds);

        return Result.ok(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> batchOperationUsers(com.echo.dto.BatchOperationDTO batchDTO) {
        if (!isAdmin()) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        List<Long> ids = batchDTO.getIds();
        BatchOperationDTO.Operation operation = batchDTO.getOperation();
        String reason = batchDTO.getReason();
        int successCount = 0;
        int failCount = 0;
        List<String> failedIds = new ArrayList<>();

        for (Long userId : ids) {
            try {
                User user = userService.getById(userId);
                if (user == null) {
                    failCount++;
                    failedIds.add(String.valueOf(userId));
                    continue;
                }

                switch (operation) {
                    case ENABLE:
                        user.setStatus(0); // 启用
                        userService.updateById(user);
                        successCount++;
                        break;

                    case DISABLE:
                        user.setStatus(1); // 禁用
                        userService.updateById(user);
                        successCount++;

                        // 【P3修复】禁用后立即踢下线该用户所有已签发Token
                        kickUserTokens(userId);

                        // 发送通知给用户（如果有消息服务）
                        try {
                            NotificationMessage msg = new NotificationMessage();
                            msg.setUserId(userId);
                            msg.setTitle("账号状态变更");
                            msg.setContent("您的账号已被管理员禁用。" +
                                (reason != null ? "\n原因：" + reason : ""));
                            msg.setType(NotificationType.PAYMENT_SUCCESS); // 支付成功通知类型
                            rocketMQTemplate.convertAndSend(MqConstants.NOTIFICATION_TOPIC, MessageBuilder.withPayload(msg).build());
                        } catch (Exception notifyEx) {
                            log.warn("[批量操作] 发送禁用通知失败 - userId:{}, error:{}", userId, notifyEx.getMessage());
                        }
                        break;

                    default:
                        failCount++;
                        failedIds.add(String.valueOf(userId));
                }

            } catch (Exception e) {
                failCount++;
                failedIds.add(String.valueOf(userId));
                log.error("[批量操作] 用户操作失败 - userId:{}, error:{}", userId, e.getMessage(), e);
            }
        }

        log.info("[批量操作] 用户操作完成 - operation:{}, reason:{}, total:{}, success:{}, fail:{}",
                operation.name(), reason, ids.size(), successCount, failCount);

        Map<String, Object> result = new HashMap<>();
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("failedIds", failedIds);

        return Result.ok(result);
    }

    /**
     * 记录管理员登录失败次数（防爆破），原子操作（S8修复：INCR+EXPIRE在Lua内原子执行）
     */
    private void recordAdminLoginFail(String failKey) {
        try {
            stringRedisTemplate.execute(
                    atomicCounterScript,
                    java.util.Collections.singletonList(failKey),
                    String.valueOf(LOGIN_FAIL_LOCK_TTL * 60)
            );
        } catch (Exception e) {
            log.warn("[管理员登录失败计数] 原子计数器异常:{}", e.getMessage());
        }
    }

    /**
     * 将指定用户的所有登录Token踢下线（禁用用户/批量禁用时调用）
     * 按 login:token:* 前缀匹配，检查 Hash 中 id 字段过滤目标用户。
     * 注意：项目 spring-data-redis 2.3.0 无 scan() API，此处用 keys()；
     * 生产环境 token 量极大时应升级依赖后改用 SCAN 避免阻塞 Redis。
     */
    private void kickUserTokens(Long userId) {
        try {
            Set<String> keys = stringRedisTemplate.keys(LOGIN_USER_KEY + "*");
            if (keys == null || keys.isEmpty()) {
                return;
            }
            int kicked = 0;
            for (String key : keys) {
                Object idVal = stringRedisTemplate.opsForHash().get(key, "id");
                if (idVal != null && userId.toString().equals(idVal.toString())) {
                    stringRedisTemplate.delete(key);
                    kicked++;
                }
            }
            if (kicked > 0) {
                log.info("[踢下线] 已清除用户{}的{}个登录Token", userId, kicked);
            }
        } catch (Exception e) {
            log.warn("[踢下线] 清除用户登录Token失败，userId:{}, error:{}", userId, e.getMessage());
        }
    }

    /**
     * 刷新演出的平均票价（触发式计算）
     * <p>
     * 在以下场景调用：
     * 1. 管理员创建票种
     * 2. 管理员更新票种（修改票价等）
     * 3. 管理员删除票种
     * 4. 管理员创建/更新秒杀票
     * <p>
     * 计算逻辑：基于该演出所有上架票种的payValue求平均值
     *
     * @param showId 演出ID
     */
    private void refreshShowAvgPrice(Long showId) {
        if (showId == null || showId <= 0) {
            log.warn("[刷新平均票价] 演出ID无效，跳过 - showId:{}", showId);
            return;
        }

        try {
            log.info("[刷新平均票价] 开始计算演出{}的平均票价", showId);

            // 查询该演出所有上架的票种
            List<Ticket> tickets = ticketService.list(
                new QueryWrapper<Ticket>()
                    .eq("show_id", showId)
                    .eq("status", 1)  // 只统计上架的票种
                    .isNotNull("pay_value")
                    .gt("pay_value", 0)
            );

            if (tickets.isEmpty()) {
                log.info("[刷新平均票价] 演出{}无有效票种，设置avgPrice=0", showId);

                // 更新DB
                Show showUpdate = new Show();
                showUpdate.setId(showId);
                showUpdate.setAvgPrice(0L);
                showService.updateById(showUpdate);
            } else {
                // 计算平均票价
                long totalPrice = 0;
                int validTicketCount = 0;

                for (Ticket ticket : tickets) {
                    totalPrice += ticket.getPayValue();
                    validTicketCount++;
                }

                long avgPriceCalculated = totalPrice / validTicketCount;

                log.info("[刷新平均票价] 演出{}的平均票价: {}分 (基于{}个票种, 总价:{}分)",
                        showId, avgPriceCalculated, validTicketCount, totalPrice);

                // 更新DB
                Show showUpdate = new Show();
                showUpdate.setId(showId);
                showUpdate.setAvgPrice(avgPriceCalculated);
                showService.updateById(showUpdate);
            }

            // 关键：清除Redis缓存，确保下次查询获取最新值
            try {
                String cacheKey = CACHE_SHOW_KEY + showId;
                stringRedisTemplate.delete(cacheKey);
                log.info("[刷新平均票价] 已清除演出Redis缓存 - key:{}", cacheKey);

                // 同时清除Caffeine本地缓存（通过MQ广播）
                try {
                    com.echo.message.CacheInvalidateMessage msg =
                        com.echo.message.CacheInvalidateMessage.of(CACHE_SHOW_KEY, showId);
                    rocketMQTemplate.syncSend(MqConstants.CACHE_INVALIDATE_TOPIC, msg);
                    log.info("[刷新平均票价] 已发送MQ广播通知所有节点清除本地缓存 - showId:{}", showId);
                } catch (Exception mqEx) {
                    log.warn("[刷新平均票价] MQ广播失败（不影响主流程）- error:{}", mqEx.getMessage());
                }
            } catch (Exception redisEx) {
                log.warn("[刷新平均票价] 清除Redis缓存失败（不影响主流程）- error:{}", redisEx.getMessage());
            }

            log.info("[刷新平均票价] 完成 - showId:{}", showId);

        } catch (Exception e) {
            log.error("[刷新平均票价] 失败 - showId:{}, error:{}", showId, e.getMessage(), e);
            // 不抛异常，不影响主流程
        }
    }
}
