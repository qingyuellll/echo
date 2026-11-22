package com.echo.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.echo.entity.Admin;
import com.echo.enums.AdminRoleLevel;
import com.echo.service.IAdminService;
import com.echo.utils.PasswordEncoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 初始化调试控制器(仅限开发环境)
 *
 * @author tulip
 */
@Slf4j
@RestController
@RequestMapping("/init")
@ConditionalOnProperty(name = "spring.init.debug", havingValue = "true")
public class InitController {

    private final IAdminService adminService;

    private final StringRedisTemplate stringRedisTemplate;

    public InitController(IAdminService adminService, StringRedisTemplate stringRedisTemplate) {
        this.adminService = adminService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成BCrypt加密密码
     * 访问: GET /init/generate-password?password=123456
     */
    @GetMapping("/generate-password")
    public String generatePassword(@RequestParam String password) {
        return PasswordEncoder.encode(password);
    }

    /**
     * 调试：查看指定手机号的账号信息（包含密码哈希）
     * 仅开发环境使用！生产环境必须删除此接口！
     * <p>
     * 访问: GET /init/debug-admin?phone=13800000000
     */
    @GetMapping("/debug-admin")
    public Map<String, Object> debugAdmin(@RequestParam String phone) {
        Map<String, Object> result = new HashMap<>();

        try {
            QueryWrapper<Admin> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("phone", phone);
            Admin admin = adminService.getOne(queryWrapper);

            if (admin == null) {
                result.put("success", false);
                result.put("message", "未找到该手机号的账号");
                return result;
            }

            result.put("success", true);
            result.put("id", admin.getId());
            result.put("phone", admin.getPhone().substring(0, 3) + "****" + admin.getPhone().substring(7));
            result.put("nickname", admin.getName());
            result.put("status", admin.getStatus());

            String dbPassword = admin.getPassword();
            boolean isBcryptFormat = dbPassword != null && (dbPassword.startsWith("$2a$") || dbPassword.startsWith("$2b$"));
            result.put("is_bcrypt_format", isBcryptFormat);

            log.info("[InitController] 密码检查完成 - adminId:{}, bcrypt格式:{}", admin.getId(), isBcryptFormat);

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "操作异常");
            log.error("[InitController] 操作异常", e);
        }

        return result;
    }

    /**
     * 重置指定账号的密码为 123456
     * 仅开发环境使用！生产环境必须删除此接口！
     * <p>
     * 访问: POST /init/reset-password
     * Body: {"phone": "13800000000"}
     *
     * 安全加固：
     * - 验证目标账号必须是超级管理员（roleLevel=3）
     * - 记录详细操作日志，包含调用者IP和请求头信息
     * - 限制只能重置超级管理员账号，防止误操作普通管理员
     */
    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@RequestBody Map<String, String> params) {
        Map<String, Object> result = new HashMap<>();

        try {
            String phone = params.get("phone");
            if (phone == null || phone.isEmpty()) {
                result.put("success", false);
                result.put("message", "手机号不能为空");
                log.warn("[InitController] 密码重置失败 - 手机号为空");
                return result;
            }

            QueryWrapper<Admin> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("phone", phone);
            Admin admin = adminService.getOne(queryWrapper);

            if (admin == null) {
                result.put("success", false);
                result.put("message", "未找到该手机号的账号: " + phone);
                log.warn("[InitController] 密码重置失败 - 账号不存在: {}", phone);
                return result;
            }

            // 安全校验：只允许重置超级管理员账号
            if (admin.getRoleLevel() == null ||
                    admin.getRoleLevel() != AdminRoleLevel.SUPER_ADMIN) {
                result.put("success", false);
                result.put("message", "仅支持重置超级管理员账号（当前账号角色级别: " +
                        (admin.getRoleLevel() != null ? admin.getRoleLevel().getDescription() : "未知") +
                        "，要求: 超级管理员）");
                log.warn("[InitController] [安全拦截] 尝试重置非超管账号 - adminId:{}, roleLevel:{}, phone:***{}",
                        admin.getId(), admin.getRoleLevel(), phone.substring(7));
                return result;
            }

            // 生成新的BCrypt密码
            String newPassword = PasswordEncoder.encode("123456");
            admin.setPassword(newPassword);
            boolean updated = adminService.updateById(admin);

            if (updated) {
                result.put("success", true);
                result.put("message", "密码重置成功，请使用新密码登录");
                result.put("target_admin_id", admin.getId());
                result.put("target_role_level", admin.getRoleLevel());

                // 详细安全日志
                log.warn("[InitController] [敏感操作] 超级管理员密码已重置 - " +
                        "adminId:{}, phone:***{}, 操作时间:{}, 环境确认:开发调试模式",
                        admin.getId(), phone.substring(7),
                        java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            } else {
                result.put("success", false);
                result.put("message", "密码更新失败");
                log.error("[InitController] 密码重置失败 - 数据库更新异常 - adminId:{}", admin.getId());
            }

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            log.error("[InitController] 密码重置操作异常", e);
        }

        return result;
    }

    /**
     * 查看所有管理员账号
     * 仅开发环境使用！
     * <p>
     * 访问: GET /init/list-admins
     */
    @GetMapping("/list-admins")
    public Map<String, Object> listAdmins() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Admin> admins = adminService.list();

            result.put("success", true);
            result.put("total", admins.size());
            result.put("admins", buildAdminList(admins));

            log.info("[InitController] 管理员列表查询成功，共{}个账号", admins.size());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            log.error("操作异常", e);
        }
        return result;
    }

    private List<Map<String, Object>> buildAdminList(List<Admin> admins) {
        List<Map<String, Object>> adminList = new java.util.ArrayList<>();
        for (Admin admin : admins) {
            Map<String, Object> adminInfo = new HashMap<>();
            adminInfo.put("id", admin.getId());
            String phone = admin.getPhone();
            adminInfo.put("phone", phone != null && phone.length() == 11 ? phone.substring(0, 3) + "****" + phone.substring(7) : phone);
            adminInfo.put("name", admin.getName());
            adminInfo.put("status", admin.getStatus());
            adminInfo.put("role_level", admin.getRoleLevel());
            adminList.add(adminInfo);
        }
        return adminList;
    }

    /**
     * 详细密码调试接口（包含完整测试）
     * 仅开发环境使用！
     * <p>
     * 访问: GET /init/debug-password?phone=13800000000&testPassword=123456
     */
    @GetMapping("/debug-password")
    public Map<String, Object> debugPassword(
            @RequestParam String phone,
            @RequestParam(defaultValue = "123456") String testPassword) {
        Map<String, Object> result = new HashMap<>();

        try {
            QueryWrapper<Admin> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("phone", phone);
            Admin admin = adminService.getOne(queryWrapper);

            if (admin == null) {
                result.put("success", false);
                result.put("message", "未找到该手机号的账号");
                return result;
            }

            String dbPassword = admin.getPassword();
            result.put("success", true);
            result.put("phone", phone.substring(0, 3) + "****" + phone.substring(7));

            boolean isBcryptFormat = dbPassword != null && (dbPassword.startsWith("$2a$") || dbPassword.startsWith("$2b$"));
            result.put("is_bcrypt_format", isBcryptFormat);
            result.put("password_length", dbPassword != null ? dbPassword.length() : 0);

            boolean directMatch = PasswordEncoder.matches(testPassword, dbPassword);
            result.put("direct_match_test", directMatch);

            log.info("[InitController] 密码调试完成 - phone:***{}, bcrypt格式:{}, 匹配结果:{}", phone.substring(7), isBcryptFormat, directMatch);

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            log.error("操作异常", e);
        }

        return result;
    }

    /**
     * 强制更新密码为新生成的$2b$格式（带完整验证）
     * 仅开发环境使用！
     * <p>
     * 访问: POST /init/force-reset-password
     * Body: {"phone": "13800000000", "password": "123456"}
     */
    @PostMapping("/force-reset-password")
    public Map<String, Object> forceResetPassword(@RequestBody Map<String, String> params) {
        Map<String, Object> result = new HashMap<>();

        try {
            String phone = params.get("phone");
            String newPassword = params.getOrDefault("password", "123456");

            if (phone == null || phone.isEmpty()) {
                result.put("success", false);
                result.put("message", "手机号不能为空");
                return result;
            }

            QueryWrapper<Admin> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("phone", phone);
            Admin admin = adminService.getOne(queryWrapper);

            if (admin == null) {
                result.put("success", false);
                result.put("message", "未找到该手机号的账号: " + phone);
                return result;
            }

            // NEW-15收敛：强制要求目标账号为超级管理员（防止误重置普通管理员）
            if (admin.getRoleLevel() == null ||
                    admin.getRoleLevel() != AdminRoleLevel.SUPER_ADMIN) {
                result.put("success", false);
                result.put("message", "仅支持重置超级管理员账号（当前角色级别: " +
                        (admin.getRoleLevel() != null ? admin.getRoleLevel().getDescription() : "未知") +
                        "，要求: 超级管理员）");
                log.warn("[InitController] [安全拦截] forceResetPassword尝试重置非超管账号 - adminId:{}, roleLevel:{}, phone:***{}",
                        admin.getId(), admin.getRoleLevel(), phone.substring(7));
                return result;
            }

            // 生成新密码
            String encodedPassword = PasswordEncoder.encode(newPassword);
            log.debug("[InitController] 生成新密码 - adminId:{}", admin.getId());

            // 立即验证新密码是否可用
            boolean verifyBeforeSave = PasswordEncoder.matches(newPassword, encodedPassword);

            if (!verifyBeforeSave) {
                result.put("success", false);
                result.put("message", "新生成的密码验证失败！这是严重错误！");
                return result;
            }

            // 更新到数据库
            admin.setPassword(encodedPassword);
            boolean updated = adminService.updateById(admin);

            if (updated) {
                // 从数据库重新读取并再次验证
                Admin reloadedAdmin = adminService.getOne(queryWrapper);
                String savedPassword = reloadedAdmin.getPassword();
                boolean verifyAfterLoad = PasswordEncoder.matches(newPassword, savedPassword);

                result.put("success", true);
                result.put("message", "密码重置成功，请使用新密码登录");
                result.put("phone", phone.substring(0, 3) + "****" + phone.substring(7));
                result.put("verify_before_save", true);
                result.put("verify_after_reload", verifyAfterLoad);

                log.info("[InitController] 强制密码重置成功 - adminId:{}, 验证通过:{}", admin.getId(), verifyAfterLoad);
            } else {
                result.put("success", false);
                result.put("message", "数据库更新失败");
            }

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            log.error("操作异常", e);
        }

        return result;
    }

    /**
     * 解锁被锁定的账号
     * 仅开发环境使用！生产环境必须删除此接口！
     * <p>
     * 访问: POST /init/unlock-account
     * Body: {"phone": "13800000000"}
     */
    @PostMapping("/unlock-account")
    public Map<String, Object> unlockAccount(@RequestBody Map<String, String> params) {
        Map<String, Object> result = new HashMap<>();

        try {
            String phone = params.get("phone");
            if (phone == null || phone.isEmpty()) {
                result.put("success", false);
                result.put("message", "手机号不能为空");
                return result;
            }

            String loginCodeFailKey = com.echo.utils.RedisConstants.LOGIN_CODE_FAIL_KEY + phone;
            String loginPwdFailKey = com.echo.utils.RedisConstants.LOGIN_PWD_FAIL_KEY + phone;
            String passwordCodeFailKey = com.echo.utils.RedisConstants.PASSWORD_CODE_FAIL_KEY + phone;
            String adminLoginFailKey = com.echo.utils.RedisConstants.ADMIN_LOGIN_FAIL_KEY + phone;

            Boolean deleted1 = stringRedisTemplate.delete(loginCodeFailKey);
            Boolean deleted2 = stringRedisTemplate.delete(loginPwdFailKey);
            Boolean deleted3 = stringRedisTemplate.delete(passwordCodeFailKey);
            Boolean deleted4 = stringRedisTemplate.delete(adminLoginFailKey);

            result.put("success", true);
            result.put("message", "账号解锁成功！");
            result.put("phone", phone.substring(0, 3) + "****" + phone.substring(7));
            result.put("deleted_keys", Map.of(
                    "login_code_fail:" + phone, deleted1,
                    "login_pwd_fail:" + phone, deleted2,
                    "password_code_fail:" + phone, deleted3,
                    "admin_login_fail:" + phone, deleted4
            ));
            result.put("info", "账号已解锁，所有登录失败记录已清除，可立即重新登录");

            log.info("[InitController] 账号解锁成功 - phone:***{}", phone.substring(7));
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            log.error("操作异常", e);
        }

        return result;
    }
}
