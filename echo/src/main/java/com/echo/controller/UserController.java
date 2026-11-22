package com.echo.controller;


import com.echo.constants.AppMessages;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.echo.dto.*;
import com.echo.service.IFollowService;
import com.echo.service.IUserInfoService;
import com.echo.service.IUserService;
import com.echo.utils.UserHolder;
import com.echo.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * 用户管理控制器
 *
 * 提供用户注册、登录、信息查询、搜索等核心用户功能接口
 *
 * @author tulip
 */
@Tag(name = "用户管理")
@Slf4j
@Validated
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private org.springframework.data.redis.core.script.DefaultRedisScript<Long> atomicCounterScript;

    private static final String USER_QUERY_RATE_LIMIT_KEY = "rate:user:query:";

    /** 用户详情查询频控：窗口内允许的请求次数 */
    private static final int USER_QUERY_WINDOW_LIMIT = 60;

    /** 用户详情查询频控：窗口时长（秒） */
    private static final long USER_QUERY_WINDOW_SECONDS = 60L;

    @Operation(summary = "发送手机验证码", description = "向指定手机号发送6位短信验证码。60秒内同一手机号只能发送1次（防刷限制）。验证码有效期2分钟。")
    @PostMapping("code")
    public Result<?> sendCode(
            @NotBlank(message = "手机号不能为空")
            @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
            @Parameter(description = "手机号码（11位数字）", required = true)
            @RequestParam("phone") String phone,

            HttpSession session) {

        log.info("[Controller] 发送验证码请求，phone:{}",
                phone != null && phone.length() == 11 ?
                        phone.substring(0, 3) + "****" + phone.substring(7) : phone);

        return userService.sendCode(phone, session);
    }

    @Operation(summary = "用户登录",
              description = """
                      支持两种登录方式：
                      1. 手机号+验证码（适用于新用户注册/快捷登录）
                      2. 手机号+密码（适用于已设置密码的老用户）

                      登录成功后返回Token，后续请求需携带Token。""")
    @PostMapping("/login")
    public Result<?> login(
            @Valid
            @Parameter(description = "登录参数（手机号 + 验证码/密码）", required = true)
            @RequestBody LoginFormDTO loginForm,

            HttpSession session) {

        log.info("[Controller] 用户登录请求，phone:{}",
                loginForm != null && loginForm.getPhone() != null && loginForm.getPhone().length() == 11 ?
                        loginForm.getPhone().substring(0, 3) + "****" + loginForm.getPhone().substring(7) :
                        (loginForm != null ? loginForm.getPhone() : null));

        return userService.login(loginForm, session);
    }

    @Operation(summary = "用户登出", description = "退出当前登录状态，清除服务端Token缓存")
    @PostMapping("/logout")
    public Result<?> logout() {
        log.info("[Controller] 用户登出请求");
        return userService.logout();
    }

    @Operation(summary = "获取当前登录用户信息", description = "从当前会话（Token）中获取已登录用户的详细信息，包含userId、昵称、头像等基本信息。")
    @GetMapping("/me")
    public Result<?> me() {
        UserDTO userDTO = UserHolder.getUser();

        if (userDTO != null) {
            log.debug("[Controller] 获取当前用户信息，userId:{}, nickName:{}", userDTO.getId(), userDTO.getNickName());
            return Result.ok(userDTO);
        } else {
            log.warn("[Controller] 获取当前用户信息失败，用户未登录");
            return Result.fail(AppMessages.AUTH_NOT_LOGGED_IN);
        }
    }

    @Operation(summary = "查询用户详细信息", description = "根据用户ID查询用户的完整个人资料（性别、城市、生日、简介等），含频率限制：同一用户60秒内最多查询60次（防止爬虫）。")
    @GetMapping("/info/{id}")
    public Result<?> info(
            @NotNull(message = "用户ID不能为空")
            @Min(value = 1, message = "用户ID必须大于0")
            @Parameter(description = "用户ID", required = true)
            @PathVariable("id") Long userId) {

        log.debug("[Controller] 查询用户详细信息，userId:{}", userId);

        if (isQueryRateLimited(userId)) {
            log.warn("[Controller] 用户信息查询触发限流，userId:{}, 窗口:{}秒/{}次",
                    userId, USER_QUERY_WINDOW_SECONDS, USER_QUERY_WINDOW_LIMIT);
            return Result.fail(AppMessages.USER_QUERY_RATE_LIMIT);
        }

        return userInfoService.getUserInfoById(userId);
    }

    /**
     * 判断用户详情查询是否超出窗口配额
     * <p>
     * 采用固定窗口计数：窗口内累计到上限后拒绝，窗口到期自动归零。
     *
     * @param userId 被查询的用户ID
     * @return true=已超出配额
     */
    private boolean isQueryRateLimited(Long userId) {
        String rateLimitKey = USER_QUERY_RATE_LIMIT_KEY + userId;
        Long count = stringRedisTemplate.execute(
                atomicCounterScript,
                java.util.Collections.singletonList(rateLimitKey),
                String.valueOf(USER_QUERY_WINDOW_SECONDS));
        return count != null && count > USER_QUERY_WINDOW_LIMIT;
    }

    @Operation(summary = "根据ID查询用户", description = "根据用户ID查询脱敏后的用户基本信息（昵称、头像），不含敏感信息，结果会缓存到Redis以提高查询性能。")
    @GetMapping("/{id}")
    public Result<?> queryUserById(
            @NotNull(message = "用户ID不能为空")
            @Min(value = 1, message = "用户ID必须大于0")
            @Parameter(description = "用户ID", required = true)
            @PathVariable("id") Long userId) {

        log.debug("[Controller] 查询用户基本信息，userId:{}", userId);

        try {
            Result<?> result = userService.queryUserById(userId);

            if (result != null && result.getData() instanceof com.echo.dto.UserDTO userDTO) {

                log.info("[Controller] 查询用户信息 - userId:{}, nickName:{}, icon:{}, 来源:{}",
                        userId,
                        userDTO.getNickName(),
                        userDTO.getIcon(),
                        result.toString().contains("cached") ? "缓存" : "数据库");

                if (userDTO.getNickName() == null || userDTO.getNickName().trim().isEmpty()) {
                    userDTO.setNickName("用户" + userId);
                    log.warn("[Controller] 用户昵称为空！使用默认昵称 - userId:{}, 最终nickName:{}",
                            userId, userDTO.getNickName());
                }

                if (userDTO.getIcon() == null || userDTO.getIcon().trim().isEmpty()) {
                    userDTO.setIcon("/default-avatar.png");
                    log.info("[Controller] 用户头像为空，设置默认头像，userId:{}", userId);
                }

                return Result.ok(userDTO);
            }

            return result;
        } catch (Exception e) {
            log.error("[Controller] 查询用户异常，userId:{}, error:{}", userId, e.getMessage(), e);
            return Result.fail(AppMessages.USER_QUERY_FAIL);
        }
    }

    @Operation(summary = "搜索用户",
              description = """
                      根据关键词搜索用户（模糊匹配昵称、手机号），返回匹配的用户列表。
                      支持分页，每页20条。""")
    @GetMapping("/search")
    public Result<?> searchUsers(
            @Parameter(description = "搜索关键词（昵称/手机号）")
            @RequestParam(value = "keyword", required = false) String keyword,

            @Min(value = 1, message = "页码必须大于0")
            @Parameter(description = "页码", example = "1")
            @RequestParam(value = "current", defaultValue = "1") Integer current) {

        log.info("[Controller] 搜索用户 - keyword:{}, current:{}",
                keyword != null && !keyword.isEmpty() ? keyword : "(空)", current);

        try {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.echo.entity.User> page =
                    new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, 20);

            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.echo.entity.User> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();

            if (keyword != null && !keyword.trim().isEmpty()) {
                String trimmedKeyword = keyword.trim();
                wrapper.and(w -> w
                        .like(com.echo.entity.User::getNickName, trimmedKeyword)
                        .or()
                        .like(com.echo.entity.User::getPhone, trimmedKeyword)
                );
            }

            wrapper.orderByDesc(com.echo.entity.User::getCreateTime);

            com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.echo.entity.User> pageResult = userService.page(page, wrapper);
            java.util.List<com.echo.entity.User> users = pageResult.getRecords();
            long total = pageResult.getTotal();

            java.util.List<com.echo.dto.UserDTO> userDTOList = users.stream()
                    .map(user -> {
                        com.echo.dto.UserDTO dto = cn.hutool.core.bean.BeanUtil.copyProperties(user, com.echo.dto.UserDTO.class);
                        if (dto.getNickName() == null || dto.getNickName().trim().isEmpty()) {
                            dto.setNickName("用户" + user.getId());
                        }
                        if (dto.getIcon() == null || dto.getIcon().trim().isEmpty()) {
                            dto.setIcon("/default-avatar.png");
                        }
                        return dto;
                    })
                    .toList();

            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("records", userDTOList);
            result.put("total", total);
            result.put("current", current);

            log.info("[Controller] 用户搜索结果 - total:{}, records count:{}", total, userDTOList.size());
            return Result.ok(result);
        } catch (Exception e) {
            log.error("[Controller] 搜索用户异常 - error:{}", e.getMessage(), e);
            return Result.fail(AppMessages.USER_QUERY_FAIL_RETRY);
        }
    }

    @Operation(summary = "用户主页",
              description = """
                      获取用户完整主页信息，包括：
                      - 用户基本信息（头像、昵称等）
                      - 关注数/粉丝数
                      - 是否已关注（需登录）
                      - 用户最近的评测列表""")
    @GetMapping("/profile/{id}")
    public Result<?> userProfile(
            @NotNull(message = "用户ID不能为空")
            @Min(value = 1, message = "用户ID必须大于0")
            @Parameter(description = "用户ID", required = true)
            @PathVariable("id") Long userId,

            @Min(value = 1, message = "页码必须大于0")
            @Parameter(description = "评测列表页码", example = "1")
            @RequestParam(value = "current", defaultValue = "1") Integer current) {

        log.info("[Controller] 查询用户主页，userId:{}, current:{}", userId, current);

        java.util.Map<String, Object> profileData = new java.util.HashMap<>();

        com.echo.dto.Result<?> userResult = userService.queryUserById(userId);

        if (userResult == null || userResult.getData() == null) {
            log.warn("[Controller] 用户不存在或查询失败，userId:{}", userId);
            return Result.fail(AppMessages.USER_NOT_EXIST);
        }

        Object userData = userResult.getData();

        if (userData instanceof com.echo.dto.UserDTO userDTO) {

            if (userDTO.getNickName() == null || userDTO.getNickName().trim().isEmpty()) {
                log.warn("[Controller] 用户昵称为空，userId:{}", userId);
                userDTO.setNickName("用户" + userId);
            }

            if (userDTO.getIcon() == null || userDTO.getIcon().trim().isEmpty()) {
                userDTO.setIcon("/default-avatar.png");
            }

            profileData.put("user", userDTO);
            log.info("[Controller] 用户信息获取成功 - userId:{}, nickName:{}, icon:{}",
                    userId, userDTO.getNickName(), userDTO.getIcon());
        } else {
            log.error("[Controller] 用户数据类型异常，userId:{}, type:{}", userId,
                    userData.getClass().getName());
            return Result.fail(AppMessages.USER_INFO_GET_FAIL);
        }

        try {
            com.echo.dto.Result<?> followCountResult = followService.followCount(userId);
            if (followCountResult != null && followCountResult.getData() != null) {
                profileData.put("followInfo", followCountResult.getData());
                log.debug("[Controller] 关注信息获取成功，userId:{}", userId);
            } else {
                profileData.put("followInfo", new java.util.HashMap<String, Object>() {{
                    put("followee", 0L);
                    put("fans", 0L);
                }});
            }
        } catch (Exception e) {
            log.warn("[Controller] 获取关注信息失败，userId:{}, error:{}", userId, e.getMessage(), e);
            profileData.put("followInfo", new java.util.HashMap<String, Object>() {{
                put("followee", 0L);
                put("fans", 0L);
            }});
        }

        UserDTO currentUser = UserHolder.getUser();
        if (currentUser != null) {
            try {
                com.echo.dto.Result<?> isFollowResult = followService.isFollow(userId);
                if (isFollowResult != null && isFollowResult.getData() != null) {
                    profileData.put("isFollow", isFollowResult.getData());
                    log.debug("[Controller] 关注状态获取成功，userId:{}, isFollow:{}",
                            userId, isFollowResult.getData());
                } else {
                    profileData.put("isFollow", false);
                }
            } catch (Exception e) {
                log.warn("[Controller] 获取关注状态失败，userId:{}, error:{}", userId, e.getMessage(), e);
                profileData.put("isFollow", false);
            }
        } else {
            profileData.put("isFollow", false);
        }

        log.info("[Controller] 用户主页数据组装完成，userId:{}", userId);
        return Result.ok(profileData);
    }

    @Operation(summary = "用户签到")
    @PostMapping("/sign")
    public Result<?> sign() {
        UserDTO user = UserHolder.getUser();
        if (user != null) {
            log.info("[Controller] 用户签到请求，userId:{}", user.getId());
        } else {
            log.warn("[Controller] 签到请求但用户未登录");
        }
        return userService.sign();
    }

    @Operation(summary = "查询连续签到天数")
    @GetMapping("/sign/count")
    public Result<?> signCount() {
        UserDTO user = UserHolder.getUser();
        if (user != null) {
            log.debug("[Controller] 查询签到天数，userId:{}", user.getId());
        } else {
            log.warn("[Controller] 查询签到天数但用户未登录");
        }
        return userService.signCount();
    }

    @Operation(summary = "更新用户个人资料",
              description = """
                      更新当前登录用户的个人信息，包括：
                      - 性别（男/女）
                      - 生日（yyyy-MM-dd格式）
                      - 城市（如：北京、上海）
                      - 个人简介（最多200字）""")
    @PutMapping("/info")
    public Result<?> updateInfo(
            @Valid
            @Parameter(description = "用户资料更新参数", required = true)
            @RequestBody UserInfoUpdateDTO updateDTO) {

        UserDTO user = UserHolder.getUser();
        if (user != null) {
            log.info("[Controller] 更新用户资料请求，userId:{}", user.getId());
        } else {
            log.warn("[Controller] 更新用户资料但用户未登录");
        }
        return userInfoService.updateUserInfo(updateDTO);
    }

    @Operation(summary = "发送修改密码验证码")
    @PostMapping("/password/code")
    public Result<?> sendPasswordCode() {
        log.info("[Controller] 发送修改密码验证码请求");
        return userService.sendPasswordCode();
    }

    @Operation(summary = "修改密码",
              description = """
                      通过短信验证码校验后修改密码。

                      规则说明：
                      1. 已设置密码的用户：需提供旧密码+新密码+验证码
                      2. 未设置密码的用户：只需提供新密码+验证码
                      3. 新密码长度：6-20个字符
                      4. 新密码不能与旧密码相同

                      安全提醒：修改密码后旧Token仍然有效，建议重新登录。""")
    @PutMapping("/password")
    public Result<?> changePassword(
            @Valid
            @Parameter(description = "修改密码参数（旧密码、新密码、验证码）", required = true)
            @RequestBody ChangePasswordDTO dto) {

        UserDTO user = UserHolder.getUser();
        if (user != null) {
            log.info("[Controller] 修改密码请求，userId:{}", user.getId());
        } else {
            log.warn("[Controller] 修改密码但用户未登录");
        }
        return userService.changePassword(dto);
    }

    @Operation(summary = "【调试】清除用户缓存")
    @DeleteMapping("/debug/cache/{id}")
    public Result<?> clearUserCache(
            @PathVariable("id") Long userId) {

        // 【P1修复】仅管理员可调用，防止任意登录用户查看/清除他人缓存
        if (UserHolder.getAdmin() == null) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        log.warn("[DEBUG] 清除用户缓存 - userId:{}", userId);

        try {
            // 【P1修复】修正缓存Key：原"user:cache:"与实际写入的 CACHE_USER_KEY("cache:user:") 不一致，永远清不到
            String cacheKey = RedisConstants.CACHE_USER_KEY + userId;
            String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);

            if (cachedJson != null) {
                stringRedisTemplate.delete(cacheKey);

                Map<String, Object> result = new HashMap<>();
                result.put("cleared", true);
                result.put("cacheKey", cacheKey);
                result.put("cachedData", cachedJson);

                log.info("[DEBUG] 缓存已清除 - userId:{}, 原始数据:{}", userId, cachedJson);

                return Result.ok(result);
            } else {
                log.info("[DEBUG] 无缓存可清除 - userId:{}", userId);
                return Result.ok(Map.of("cleared", false, "message", AppMessages.USER_CACHE_NONE));
            }
        } catch (Exception e) {
            log.error("[DEBUG] 清除缓存失败 - userId:{}, error:{}", userId, e.getMessage(), e);
            return Result.fail(AppMessages.CACHE_CLEAR_FAIL_PREFIX + e.getMessage());
        }
    }

    @Operation(summary = "【调试】查看用户缓存")
    @GetMapping("/debug/cache/{id}")
    public Result<?> getUserCache(@PathVariable("id") Long userId) {

        // 【P1修复】仅管理员可调用
        if (UserHolder.getAdmin() == null) {
            return Result.fail(AppMessages.PERMISSION_ADMIN_REQUIRED);
        }

        log.warn("[DEBUG] 查看用户缓存 - userId:{}", userId);

        try {
            // 【P1修复】修正缓存Key
            String cacheKey = RedisConstants.CACHE_USER_KEY + userId;
            String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);

            if (cachedJson != null) {
                Map<String, Object> result = new HashMap<>();
                result.put("hasCache", true);
                result.put("cacheKey", cacheKey);
                result.put("cachedJson", cachedJson);

                log.info("[DEBUG] 缓存内容 - userId:{}, json:{}", userId, cachedJson);

                return Result.ok(result);
            } else {
                log.info("[DEBUG] 无缓存 - userId:{}", userId);
                return Result.ok(Map.of("hasCache", false, "message", AppMessages.USER_CACHE_NONE));
            }
        } catch (Exception e) {
            log.error("[DEBUG] 查看缓存失败 - userId:{}, error:{}", userId, e.getMessage(), e);
            return Result.fail(AppMessages.CACHE_VIEW_FAIL_PREFIX + e.getMessage());
        }
    }
}
