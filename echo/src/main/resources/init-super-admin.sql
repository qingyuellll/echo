-- ========================================
-- 超级管理员账号初始化脚本（模板）
-- ========================================
-- 用途：首次部署时创建 id = 1 的超级管理员
--
-- 使用前修改下面两个变量：
--   @admin_phone  管理员手机号
--   @admin_hash   该手机号登录密码的 BCrypt 哈希
--
-- 生成密码哈希的两种方式：
--   1. 启动时加 --spring.init.debug=true，访问
--      GET /init/generate-password?password=你的密码
--      取返回的哈希字符串（该接口只在调试开关打开时注册）
--   2. 在项目内写一个一次性 main 方法调用
--      com.echo.utils.PasswordEncoder.encode("你的密码")
--
-- 执行：
--   mysql -u root -p echo < init-super-admin.sql
--
-- 登录后立即在「个人中心」修改密码。
-- ========================================

SET @admin_phone = '13800000000';
SET @admin_hash  = '$2a$10$REPLACE_WITH_YOUR_OWN_BCRYPT_HASH';

-- 已存在 id = 1 时跳过插入，脚本可重复执行
INSERT IGNORE INTO tb_admin (id, user_id, name, role_level, phone, password, status, create_time, update_time)
VALUES (
    1,
    NULL,
    '超级管理员',
    0,
    @admin_phone,
    @admin_hash,
    0,
    NOW(),
    NOW()
);

-- 验证结果
SELECT
    id,
    name,
    phone,
    IF(role_level = 0, '超级管理员', '普通管理员') AS role_name,
    IF(status = 0, '正常', '禁用')                AS status_name,
    create_time
FROM tb_admin
WHERE id = 1;
