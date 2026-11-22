package com.echo.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.component.AuthComponent;
import com.echo.constants.AppMessages;
import com.echo.dto.ChangePasswordDTO;
import com.echo.dto.LoginFormDTO;
import com.echo.dto.Result;
import com.echo.dto.UserDTO;
import com.echo.entity.User;
import com.echo.mapper.UserMapper;
import com.echo.service.IPointsService;
import com.echo.service.IUserService;
import com.echo.utils.PasswordEncoder;
import com.echo.utils.RegexUtils;
import com.echo.utils.UserHolder;
import com.echo.utils.CsrfTokenUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.echo.utils.BusinessConstants.*;
import static com.echo.utils.RedisConstants.*;
import static com.echo.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * 用户服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AuthComponent authComponent;

    @Resource
    private IPointsService pointsService;

    @Resource
    private CsrfTokenUtil csrfTokenUtil;

    /**
     * 发送短信验证码
     * <p>
     * 流程：
     * 1. 校验手机号格式
     * 2. 生成6位随机验证码
     * 3. 存储到 Redis（带过期时间）
     * 4. 调用短信服务发送（实际项目中对接短信平台）
     *
     * @param phone 手机号码
     * @param session HTTP会话（预留）
     * @return 操作结果
     */
    @Override
    public Result<?> sendCode(String phone, HttpSession session) {

        String maskedPhone = maskPhoneNumber(phone);
        log.info("[发送验证码] 开始处理验证码请求，phone:{}", maskedPhone);

        if (RegexUtils.isPhoneInvalid(phone)) {
            log.warn("[验证码失败] 手机号格式不合法，phone:{}", maskedPhone);
            return Result.fail(AppMessages.PHONE_FORMAT_ERROR);
        }

        if (authComponent.tryRateLimit(SEND_CODE_RATE_LIMIT_KEY + phone, SEND_CODE_RATE_LIMIT_TTL)) {
            log.warn("[验证码限流] 手机号{}请求过于频繁", maskedPhone);
            return Result.fail(AppMessages.AUTH_CODE_SEND_FREQUENT_SIMPLE);
        }

        try {
            String code = authComponent.generateCode();
            log.debug("[生成验证码] 验证码已生成，phone:{}, code:******", maskedPhone);

            authComponent.saveCode(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL);
            log.info("[存储验证码] 验证码已存入Redis，key:{}, TTL:{}分钟", LOGIN_CODE_KEY + phone, LOGIN_CODE_TTL);

            log.info("[发送短信] 模拟发送验证码到手机:{}, code:{}", maskedPhone, "******");
            return Result.ok();
        } catch (Exception e) {
            log.error("[发送验证码失败] 异常，phone:{}, error:{}", maskedPhone, e.getMessage(), e);
            return Result.fail(AppMessages.AUTH_CODE_SEND_FAIL_RETRY);
        }
    }

    /**
     * 手机号脱敏处理（中间4位替换为****）
     */
    private String maskPhoneNumber(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /**
     * 用户登录（基于 Redis 的无状态会话方案）
     * <p>
     * 支持两种登录方式：
     * - 手机号+验证码：适用于新用户注册/登录、老用户快捷登录
     * - 手机号+密码：适用于已设置密码的用户登录
     * <p>
     * 设计思路：
     * - 传统 Session 方案：服务器存储会话，不适合分布式
     * - Redis Token 方案：
     *   1. 登录成功后生成唯一 Token
     *   2. 将用户信息以 Hash 结构存入 Redis
     *   3. Token 返回给客户端（后续请求携带）
     *   4. 服务端通过 Token 从 Redis 获取用户信息
     *
     * @param loginForm 登录表单（手机号+验证码 或 手机号+密码）
     * @param session HTTP会话（预留）
     * @return 包含Token的登录结果
     */
    @Override
    public Result<?> login(LoginFormDTO loginForm, HttpSession session) {

        String phone = loginForm.getPhone();
        String maskedPhone = maskPhoneNumber(phone);
        log.info("[用户登录] 开始处理登录请求，phone:{}", maskedPhone);

        if (RegexUtils.isPhoneInvalid(phone)) {
            log.warn("[登录失败] 手机号格式不合法，phone:{}", maskedPhone);
            return Result.fail(AppMessages.PHONE_FORMAT_ERROR);
        }

        try {
            String password = loginForm.getPassword();
            String code = loginForm.getCode();

            if (password != null && !password.isEmpty()) {
                log.debug("[用户登录] 使用密码登录方式，phone:{}", maskedPhone);
                return loginByPassword(phone, password);
            }

            log.debug("[用户登录] 使用验证码登录方式，phone:{}", maskedPhone);
            return loginByCode(phone, code);
        } catch (Exception e) {
            log.error("[登录失败] 异常，phone:{}, error:{}", maskedPhone, e.getMessage(), e);
            return Result.fail(AppMessages.AUTH_LOGIN_FAIL_RETRY);
        }
    }

    /**
     * 验证码登录
     */
    private Result<?> loginByCode(String phone, String code) {
        String maskedPhone = maskPhoneNumber(phone);
        String failKey = LOGIN_CODE_FAIL_KEY + phone;

        // 【P4修复】防爆破：失败次数达上限后锁定，验证码登录也无法继续尝试
        if (authComponent.isLoginLocked(failKey)) {
            log.warn("[登录失败] 尝试次数过多已锁定，phone:{}", maskedPhone);
            return Result.fail(AppMessages.AUTH_CODE_INCORRECT);
        }

        String cachedCode = authComponent.getCode(LOGIN_CODE_KEY + phone);
        if (cachedCode == null) {
            log.warn("[登录失败] 验证码已过期或未发送，phone:{}", maskedPhone);
            return Result.fail(AppMessages.AUTH_CODE_INCORRECT);
        }

        if (!cachedCode.equals(code)) {
            long fails = authComponent.recordLoginFail(failKey);
            if (fails >= LOGIN_FAIL_MAX_ATTEMPTS) {
                // 锁定后作废当前验证码：攻击者必须重新获取（发送验证码有 60s 限流），无法连续枚举
                authComponent.deleteCode(LOGIN_CODE_KEY + phone);
                log.warn("[登录失败] 验证码连续错误{}次，已锁定并作废验证码，phone:{}", fails, maskedPhone);
            } else {
                log.warn("[登录失败] 验证码不匹配（第{}次失败），phone:{}, 输入:{}, 正确:{}", fails, maskedPhone, "******", "******");
            }
            return Result.fail(AppMessages.AUTH_CODE_INCORRECT);
        }
        authComponent.clearLoginFail(failKey);
        log.debug("[验证码正确] 用户:{} 验证码校验通过", maskedPhone);

        User user = query().eq("phone", phone).one();

        if (user == null) {
            log.info("[新用户注册] 手机号:{} 尚未注册，开始创建账号...", maskedPhone);
            // 并发注册保护：同一手机号并发首次登录时，DB 唯一索引/重复插入会失败，
            // 此处失败后重新按手机号查询，复用已注册成功的那条记录，避免产生重复用户
            try {
                user = createUserWithPhone(phone);
                log.info("[注册成功] 新用户创建完成，userId:{}, nickName:{}", user.getId(), user.getNickName());
            } catch (Exception e) {
                log.warn("[注册冲突] 手机号:{} 并发注册，回查已存在用户，error:{}", maskedPhone, e.getMessage());
                user = query().eq("phone", phone).one();
                if (user == null) {
                    log.error("[注册失败] 手机号:{} 创建用户失败且回查为空", maskedPhone);
                    return Result.fail(AppMessages.AUTH_LOGIN_FAIL_RETRY);
                }
            }
        } else {
            log.debug("[老用户登录] 用户已存在，userId:{}", user.getId());
        }

        // 禁用账号不允许登录
        if (isUserDisabled(user)) {
            log.warn("[登录失败] 账号已被禁用，userId:{}, phone:{}", user.getId(), maskedPhone);
            return Result.fail(AppMessages.USER_ACCOUNT_DISABLED);
        }

        authComponent.deleteCode(LOGIN_CODE_KEY + phone);

        String token = saveUserToRedis(user);
        log.info("[登录成功] 用户登录完成（验证码），phone:{}, userId:{}, token:{}", maskedPhone, user.getId(), maskToken(token));

        Map<String, Object> loginResult = new HashMap<>();
        loginResult.put("token", token);
        loginResult.put("userId", user.getId());
        loginResult.put("nickName", user.getNickName());

        String csrfToken = csrfTokenUtil.generateToken(
                CsrfTokenUtil.SubjectRole.USER, user.getId());
        loginResult.put("csrfToken", csrfToken);
        log.info("[用户登录] CSRF Token已生成 - userId:{}, token:{}...", user.getId(), csrfToken.substring(0, 8));

        return Result.ok(loginResult);
    }

    /**
     * 密码登录
     */
    private Result<?> loginByPassword(String phone, String password) {
        String maskedPhone = maskPhoneNumber(phone);
        String failKey = LOGIN_PWD_FAIL_KEY + phone;

        // 【P4修复】防爆破：密码连续错误达上限锁定
        if (authComponent.isLoginLocked(failKey)) {
            log.warn("[密码登录失败] 尝试次数过多已锁定，phone:{}", maskedPhone);
            return Result.fail(AppMessages.AUTH_ACCOUNT_OR_PASSWORD_ERROR);
        }

        User user = query().eq("phone", phone).one();

        if (user == null) {
            authComponent.recordLoginFail(failKey);
            log.warn("[密码登录失败] 用户不存在，phone:{}", maskedPhone);
            return Result.fail(AppMessages.AUTH_ACCOUNT_OR_PASSWORD_ERROR);
        }

        if (user.getPassword() == null || user.getPassword().isEmpty()) {
            authComponent.recordLoginFail(failKey);
            log.warn("[密码登录失败] 用户尚未设置密码，phone:{}", maskedPhone);
            return Result.fail(AppMessages.PASSWORD_NOT_SET);
        }

        if (!PasswordEncoder.matches(password, user.getPassword())) {
            long fails = authComponent.recordLoginFail(failKey);
            log.warn("[密码登录失败] 密码错误（第{}次失败），phone:{}", fails, maskedPhone);
            return Result.fail(AppMessages.AUTH_ACCOUNT_OR_PASSWORD_ERROR);
        }

        // 禁用账号不允许登录
        if (isUserDisabled(user)) {
            log.warn("[密码登录失败] 账号已被禁用，userId:{}, phone:{}", user.getId(), maskedPhone);
            return Result.fail(AppMessages.USER_ACCOUNT_DISABLED);
        }

        authComponent.clearLoginFail(failKey);
        String token = saveUserToRedis(user);
        log.info("[密码登录成功] 用户登录完成，phone:{}, userId:{}, token:{}", maskedPhone, user.getId(), maskToken(token));

        Map<String, Object> loginResult = new HashMap<>();
        loginResult.put("token", token);
        loginResult.put("userId", user.getId());
        loginResult.put("nickName", user.getNickName());

        String csrfToken = csrfTokenUtil.generateToken(
                CsrfTokenUtil.SubjectRole.USER, user.getId());
        loginResult.put("csrfToken", csrfToken);
        log.info("[用户登录] CSRF Token已生成 - userId:{}, token:{}...", user.getId(), csrfToken.substring(0, 8));

        return Result.ok(loginResult);
    }

    /**
     * 判断用户是否被禁用（status: 0=正常, 1=禁用）
     */
    private boolean isUserDisabled(User user) {
        return user != null && user.getStatus() != null && user.getStatus() == 1;
    }

    /**
     * Token脱敏处理（只显示前8位）
     */
    private String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return token;
        }
        return token.substring(0, 8) + "...";
    }

    /**
     * 将用户信息保存到 Redis（生成登录令牌）
     * <p>
     * 实现细节：
     * - 使用 Hash 结构存储用户信息（比 JSON 更灵活）
     * - 过滤掉敏感字段和 null 值
     * - 设置合理的过期时间（30分钟）
     *
     * @param user 用户实体
     * @return 生成的 Token
     */
    private String saveUserToRedis(User user) {
        String token = authComponent.generateToken();
        log.debug("[生成Token] 为用户:{} 创建令牌", user.getId());

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        Map<String, String> userMap = new HashMap<>();
        if (userDTO.getId() != null) {
            userMap.put("id", userDTO.getId().toString());
        }
        if (userDTO.getNickName() != null) {
            userMap.put("nickName", userDTO.getNickName());
        }
        if (userDTO.getIcon() != null) {
            userMap.put("icon", userDTO.getIcon());
        }
        // status 必须写入：LoginInterceptor 依赖它拦截被禁用账号，缺失会导致封禁失效
        userMap.put("status", userDTO.getStatus() != null ? userDTO.getStatus().toString() : "0");

        String redisKey = LOGIN_USER_KEY + token;
        authComponent.saveToken(redisKey, userMap, LOGIN_USER_TTL);
        log.debug("[缓存用户] 用户信息已存入Redis，key:{}, TTL:{}分钟", redisKey, LOGIN_USER_TTL);

        return token;
    }

    /**
     * 用户登出
     * <p>
     * 当前为简化实现，后续可扩展：
     * - 删除 Redis 中的 Token
     * - 记录登出日志
     * - 清理用户会话缓存
     *
     * @return 操作结果
     */
    @Override
    public Result<?> logout() {
        try {
            UserDTO currentUser = UserHolder.getUser();
            String token = UserHolder.getToken();
            if (token != null) {
                authComponent.deleteToken(LOGIN_USER_KEY + token);
                log.info("[用户登出] token已清除:{}", maskToken(token));
            } else {
                log.warn("[用户登出] 未找到Token，可能已经过期或未登录");
            }

            // 会话结束时一并作废 CSRF Token，避免登出后旧 Token 仍可用于写请求
            if (currentUser != null && currentUser.getId() != null) {
                csrfTokenUtil.clearToken(CsrfTokenUtil.SubjectRole.USER, currentUser.getId());
                log.info("[用户登出] CSRF Token已清理，userId:{}", currentUser.getId());
            }

            UserHolder.removeUser();
            log.info("[用户登出] 用户信息已清除");
            return Result.ok();
        } catch (Exception e) {
            log.error("[用户登出失败] 异常，error:{}", e.getMessage(), e);
            UserHolder.removeUser();
            return Result.ok();  // 即使失败也返回成功（客户端不需要关心）
        }
    }

    /**
     * 根据用户ID查询用户信息（脱敏）
     * <p>
     * 流程：
     * 1. 根据ID查询数据库
     * 2. 将 User 实体转换为 UserDTO（去除敏感字段）
     *
     * @param userId 用户ID
     * @return 脱敏后的用户信息
     */
    @Override
    public Result<?> queryUserById(Long userId) {
        log.debug("[用户查询] 查询用户详情，userId:{}", userId);

        if (userId == null || userId <= 0) {
            log.warn("[用户查询] 参数非法，userId:{}", userId);
            return Result.fail(AppMessages.PARAM_ERROR);
        }

        try {
            String cacheKey = CACHE_USER_KEY + userId;
            String json = stringRedisTemplate.opsForValue().get(cacheKey);

            // 缓存命中
            if (json != null) {
                if (json.isEmpty()) {
                    // 空值缓存命中：必须与“回源DB发现用户不存在”返回相同结果，
                    // 否则同一个用户会时而返回 success、时而返回 fail
                    log.debug("[用户查询] 缓存空值（防止穿透），userId:{}", userId);
                    return Result.fail(AppMessages.USER_NOT_EXIST);
                }

                UserDTO cached = cn.hutool.json.JSONUtil.toBean(json, UserDTO.class);

                log.debug("[用户查询] 缓存命中 - userId:{}, nickName:{}, icon:{}, status:{}",
                        userId, cached.getNickName(), cached.getIcon(), cached.getStatus());

                return Result.ok(cached);
            }

            // 查询数据库
            User user = getById(userId);

            log.debug("[用户查询] 数据库查询结果 - userId:{}, 命中:{}", userId, user != null);

            if (user == null) {
                // 缓存空值（防止缓存穿透）
                stringRedisTemplate.opsForValue().set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
                log.debug("[用户查询] 用户不存在，已缓存空值，userId:{}", userId);
                return Result.fail(AppMessages.USER_NOT_EXIST);
            }

            // 写入缓存（带随机TTL防止雪崩）
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

            String cacheJson = cn.hutool.json.JSONUtil.toJsonStr(userDTO);

            long ttl = randomExpireTime(CACHE_USER_TTL, CACHE_USER_RANDOM_TTL);
            stringRedisTemplate.opsForValue().set(cacheKey, cacheJson, ttl, TimeUnit.SECONDS);
            log.debug("[查询成功] 用户信息获取完成 - userId:{}, nickName:{}, TTL:{}分钟",
                    userId, userDTO.getNickName(), ttl);
            return Result.ok(userDTO);
        } catch (Exception e) {
            log.error("[用户查询失败] 异常，userId:{}, error:{}", userId, e.getMessage(), e);
            return Result.fail(AppMessages.USER_QUERY_FAIL_RETRY);
        }
    }

    /**
     * 用户签到
     * <p>
     * 实现原理：
     * - 使用 Redis BitMap 存储签到记录（节省内存，一位存一天）
     * - Redis Key: sign:{userId}:yyyyMM（如 sign:1001:202503）
     * - 第N天对应 offset = N-1（第1天对应bit 0）
     * <p>
     * 签到奖励：+5积分
     *
     * @return 操作结果
     */
    @Override
    public Result<?> sign() {
        try {
            UserDTO userDTO = UserHolder.getUser();
            if (userDTO == null) {
                log.warn("[签到失败] 用户未登录");
                return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
            }

            Long userId = userDTO.getId();
            log.info("[用户签到] 开始处理，userId:{}", userId);

            LocalDateTime now = LocalDateTime.now();
            String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
            String key = USER_SIGN_KEY + userId + keySuffix;
            int dayOfMonth = now.getDayOfMonth();

            // 原子签到：SETBIT 会返回该位的旧值，用它做“检查+置位”一步完成。
            // 旧实现是 getBit -> setBit 两步，重复点击/并发请求会同时通过检查，导致重复发放积分
            Boolean alreadySigned = stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
            if (Boolean.TRUE.equals(alreadySigned)) {
                log.info("[用户签到] 今天已经签过了，userId:{}, date:{}", userId, now.toLocalDate());
                return Result.ok(SIGN_ALREADY_TODAY);
            }

            // 签到位图按月存储，补上过期时间，避免 Key 永久堆积（保留 2 个月供统计）
            stringRedisTemplate.expire(key, 62, TimeUnit.DAYS);

            // 签到奖励：+5积分（失败时回滚 bit 位，否则用户既无积分也无法重试签到）
            try {
                pointsService.addPoints(userId, 5, 1, POINTS_SIGN_IN, null);
            } catch (Exception e) {
                stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, false);
                log.error("[签到失败] 积分发放异常，已回滚签到标记，userId:{}, error:{}", userId, e.getMessage(), e);
                return Result.fail(AppMessages.SIGN_FAIL_RETRY);
            }

            log.info("[用户签到成功] userId:{}, date:{}, 积分:+5", userId, now.toLocalDate());
            return Result.ok(SIGN_SUCCESS);
        } catch (Exception e) {
            log.error("[签到失败] 异常，error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.SIGN_FAIL_RETRY);
        }
    }

    /**
     * 查询用户连续签到天数
     * <p>
     * 实现原理：
     * - 使用 Redis BITFIELD 命令获取当月签到位图（返回无符号整型）
     * - 从当天开始逐位向历史方向遍历，统计连续为1的位数
     * - 位运算：num & 1 判断最低位，num >>>= 1 右移一位
     * <p>
     * Redis命令等价：
     *   BITFIELD sign:{userId}:yyyyMM GET u{dayOfMonth} 0
     *
     * @return 连续签到天数
     */
    @Override
    public Result<?> signCount() {
        try {
            UserDTO userDTO = UserHolder.getUser();
            if (userDTO == null) {
                log.warn("[查询签到天数失败] 用户未登录");
                return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
            }

            Long userId = userDTO.getId();
            log.debug("[查询签到天数] 开始处理，userId:{}", userId);

            LocalDateTime now = LocalDateTime.now();
            // 拼接签到Key: sign:{userId}:yyyyMM（如 sign:1001:202503）
            String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
            String key = USER_SIGN_KEY + userId + keySuffix;
            int dayOfMonth = now.getDayOfMonth();

            // Redis BITFIELD sign:{userId}:yyyyMM GET u{dayOfMonth} 0 → 获取当月签到位图
            List<Long> result = stringRedisTemplate.opsForValue().bitField(
                    key,
                    BitFieldSubCommands
                            .create()
                            .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                            .valueAt(0)
            );

            if (result == null || result.isEmpty()) {
                log.debug("[查询签到天数] 无签到记录，userId:{}", userId);
                return Result.ok(0);
            }

            Long num = result.get(0);
            if (num == null || num == 0) {
                log.debug("[查询签到天数] 签到位图为空，userId:{}", userId);
                return Result.ok(0);
            }

            // 逐位遍历：从最低位（今天）开始，连续为1则计数+1，遇到0则停止
            int count = 0;
            while ((num & 1) != 0) {
                count++;
                num >>>= 1;
            }

            log.info("[查询签到天数成功] userId:{}, 连续签到:{}天", userId, count);
            return Result.ok(count);
        } catch (Exception e) {
            log.error("[查询签到天数失败] 异常，error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.USER_QUERY_FAIL_RETRY);
        }
    }

    /**
     * 根据手机号创建新用户
     * <p>
     * 自动生成规则：
     * - 昵称前缀：user_ + 10位随机字符
     * - 默认头像：使用系统默认头像
     *
     * @param phone 手机号
     * @return 新创建的用户实体
     */
    private User createUserWithPhone(String phone) {
        User user = new User();

        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        save(user);  // 保存到数据库

        log.info("[创建用户] 新用户已入库，phone:{}, userId:{}", phone, user.getId());
        return user;
    }

    /**
     * 发送修改密码验证码（发送到当前登录用户手机号）
     * <p>
     * 流程：
     * 1. 获取当前登录用户
     * 2. 查询用户手机号
     * 3. 生成6位随机验证码
     * 4. 存储到 Redis（带过期时间、独立前缀，与登录验证码区分）
     *
     * @return 操作结果
     */
    @Override
    public Result<?> sendPasswordCode() {
        try {
            UserDTO userDTO = UserHolder.getUser();
            if (userDTO == null) {
                log.warn("[发送密码修改验证码失败] 用户未登录");
                return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
            }

            User user = getById(userDTO.getId());
            if (user == null) {
                log.warn("[发送密码修改验证码失败] 用户不存在，userId:{}", userDTO.getId());
                return Result.fail(AppMessages.USER_NOT_EXIST);
            }

            String phone = user.getPhone();
            String maskedPhone = maskPhoneNumber(phone);
            log.info("[发送密码修改验证码] 开始处理，userId:{}, phone:{}", user.getId(), maskedPhone);

            // 限流检查（60秒内只能发送1次）
            if (authComponent.tryRateLimit(PASSWORD_CODE_RATE_LIMIT_KEY + phone, PASSWORD_CODE_RATE_LIMIT_TTL)) {
                log.warn("[密码修改验证码限流] 手机号{}请求过于频繁", maskedPhone);
                return Result.fail(AppMessages.AUTH_CODE_SEND_FREQUENT_SIMPLE);
            }

            // 生成验证码
            String code = authComponent.generateCode();
            log.debug("[生成密码修改验证码] 验证码已生成，phone:{}, code:{}", maskedPhone, "******");

            // 存储到Redis
            authComponent.saveCode(PASSWORD_CODE_KEY + phone, code, PASSWORD_CODE_TTL);
            log.info("[存储密码修改验证码] 验证码已存入Redis，key:{}, TTL:{}分钟", PASSWORD_CODE_KEY + phone, PASSWORD_CODE_TTL);

            // 模拟发送短信
            log.info("[发送短信] 模拟发送密码修改验证码到手机:{}, code:{}", maskedPhone, "******");

            return Result.ok();
        } catch (Exception e) {
            log.error("[发送密码修改验证码失败] 异常，error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.AUTH_CODE_SEND_FAIL_RETRY);
        }
    }

    /**
     * 修改密码（短信验证码校验）
     * <p>
     * 流程：
     * 1. 校验用户是否登录
     * 2. 校验短信验证码
     * 3. 如果用户已有密码，校验旧密码
     * 4. 校验新密码格式
     * 5. 加密并更新密码
     *
     * @param dto 修改密码参数（旧密码、新密码、短信验证码）
     * @return 操作结果
     */
    @Override
    public Result<?> changePassword(ChangePasswordDTO dto) {
        try {
            UserDTO userDTO = UserHolder.getUser();
            if (userDTO == null) {
                log.warn("[修改密码失败] 用户未登录");
                return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
            }

            User user = getById(userDTO.getId());
            if (user == null) {
                log.warn("[修改密码失败] 用户不存在，userId:{}", userDTO.getId());
                return Result.fail(AppMessages.USER_NOT_EXIST);
            }

            String phone = user.getPhone();
            String maskedPhone = maskPhoneNumber(phone);
            log.info("[修改密码] 开始处理，userId:{}, phone:{}", user.getId(), maskedPhone);

            // 1. 校验验证码
            String code = dto.getCode();
            if (code == null || code.isEmpty()) {
                log.warn("[修改密码失败] 验证码为空，userId:{}", user.getId());
                return Result.fail(AppMessages.AUTH_CODE_INCORRECT);
            }

            String cachedCode = authComponent.getCode(PASSWORD_CODE_KEY + phone);
            if (cachedCode == null) {
                log.warn("[修改密码失败] 验证码已过期或未发送，phone:{}", maskedPhone);
                return Result.fail(AppMessages.AUTH_CODE_INCORRECT);
            }

            if (!cachedCode.equals(code)) {
                long fails = authComponent.recordLoginFail(PASSWORD_CODE_FAIL_KEY + phone);
                if (fails >= LOGIN_FAIL_MAX_ATTEMPTS) {
                    // 锁定后作废验证码，防止继续枚举
                    authComponent.deleteCode(PASSWORD_CODE_KEY + phone);
                    log.warn("[修改密码失败] 验证码连续错误{}次，已锁定并作废验证码，phone:{}", fails, maskedPhone);
                } else {
                    log.warn("[修改密码失败] 验证码不匹配（第{}次失败），phone:{}, 输入:{}, 正确:{}", fails, maskedPhone, "******", "******");
                }
                return Result.fail(AppMessages.AUTH_CODE_INCORRECT);
            }
            authComponent.clearLoginFail(PASSWORD_CODE_FAIL_KEY + phone);
            log.debug("[修改密码] 验证码校验通过，userId:{}", user.getId());

            // 2. 校验新密码格式
            String newPassword = dto.getNewPassword();
            if (newPassword == null || newPassword.isEmpty()) {
                log.warn("[修改密码失败] 新密码为空，userId:{}", user.getId());
                return Result.fail(AppMessages.PASSWORD_EMPTY);
            }

            if (newPassword.length() < 6) {
                log.warn("[修改密码失败] 新密码过短（{}字符），userId:{}", newPassword.length(), user.getId());
                return Result.fail(AppMessages.PASSWORD_MIN_LENGTH);
            }

            if (newPassword.length() > 20) {
                log.warn("[修改密码失败] 新密码过长（{}字符），userId:{}", newPassword.length(), user.getId());
                return Result.fail(AppMessages.PASSWORD_MAX_LENGTH);
            }

            // 3. 如果用户已有密码，校验旧密码
            if (user.getPassword() != null && !user.getPassword().isEmpty()) {
                String oldPassword = dto.getOldPassword();
                if (oldPassword == null || oldPassword.isEmpty()) {
                    log.warn("[修改密码失败] 旧密码为空（已设置密码的用户必须提供旧密码），userId:{}", user.getId());
                    return Result.fail(AppMessages.PASSWORD_OLD_REQUIRED);
                }

                if (!PasswordEncoder.matches(oldPassword, user.getPassword())) {
                    log.warn("[修改密码失败] 旧密码错误，userId:{}", user.getId());
                    return Result.fail(AppMessages.AUTH_ACCOUNT_OR_PASSWORD_ERROR);
                }

                if (PasswordEncoder.matches(newPassword, user.getPassword())) {
                    log.warn("[修改密码失败] 新密码与旧密码相同，userId:{}", user.getId());
                    return Result.fail(AppMessages.PASSWORD_SAME_AS_OLD);
                }
                log.debug("[修改密码] 旧密码校验通过，userId:{}", user.getId());
            } else {
                log.info("[修改密码] 用户尚未设置密码，直接设置新密码，userId:{}", user.getId());
            }

            // 4. 加密并保存新密码
            // 只更新 password 字段：避免把读取时的 nickName/icon/points 等旧值一并写回，
            // 造成并发修改（如同时换头像）被覆盖的丢失更新
            String encodedPassword = PasswordEncoder.encode(newPassword);
            User passwordUpdate = new User();
            passwordUpdate.setId(user.getId());
            passwordUpdate.setIcon(null);  // 实体 icon 有默认值 ""，必须置 null 才不会被写入
            passwordUpdate.setPassword(encodedPassword);
            boolean success = updateById(passwordUpdate);

            if (!success) {
                log.error("[修改密码失败] 数据库更新失败，userId:{}", user.getId());
                return Result.fail(AppMessages.PASSWORD_CHANGE_FAIL_RETRY);
            }

            // 5. 删除已使用的验证码
            authComponent.deleteCode(PASSWORD_CODE_KEY + phone);

            // 【P3修复】修改密码后使当前会话失效，强制重新登录（与管理员端行为一致，防止旧Token继续使用）
            String currentToken = UserHolder.getToken();
            if (currentToken != null) {
                authComponent.deleteToken(LOGIN_USER_KEY + currentToken);
                log.info("[修改密码成功] 已清除当前登录Token，需重新登录，userId:{}", user.getId());
            }
            UserHolder.removeUser();

            log.info("[修改密码成功] 密码已更新，userId:{}", user.getId());
            return Result.ok();
        } catch (Exception e) {
            log.error("[修改密码失败] 异常，error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.PASSWORD_CHANGE_FAIL_RETRY);
        }
    }
}
