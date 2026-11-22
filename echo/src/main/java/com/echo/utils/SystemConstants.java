package com.echo.utils;

import java.util.Set;

/**
 * 系统级常量配置中心
 *
 * @author tulip
 */
public class SystemConstants {

    // ==================== 私有构造方法（防止实例化） ====================

    /** 私有构造函数，防止实例化 */
    private SystemConstants() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }


    // ==================== 文件存储相关 ====================

    /** 图片上传根目录(本地文件系统) */
    public static final String IMAGE_UPLOAD_DIR = "D:\\echo\\nginx-1.18.0\\nginx-1.18.0\\html\\ssn\\imgs";

    /** 允许上传的图片MIME类型 */
    public static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg",    // .jpg, .jpeg
            "image/png",     // .png
            "image/gif",     // .gif
            "image/webp"     // .webp (现代格式，体积更小)
    );

    /** 单次上传文件大小限制(5MB) */
    public static final long MAX_UPLOAD_FILE_SIZE = 5L * 1024L * 1024L;

    /** 默认头像URL */
    public static final String DEFAULT_AVATAR_URL = "/ssn/imgs/default-avatar.png";


    // ==================== 分页查询相关 ====================

    /** 默认分页大小(每页记录数) */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 最大分页大小(单次请求记录数上限) */
    public static final int MAX_PAGE_SIZE = 100;


    // ==================== 用户相关 ====================

    /** 新用户默认昵称前缀 */
    public static final String USER_NICK_NAME_PREFIX = "user_";

    /** 默认昵称随机字符长度 */
    public static final int NICK_NAME_RANDOM_LENGTH = 10;

    /** 昵称最大长度(数据库字段限制) */
    public static final int NICK_NAME_MAX_LENGTH = 20;


    // ==================== 缓存策略相关 ====================

    /** 缓存空值防穿透时间(分钟) */
    public static final long CACHE_NULL_TTL_MINUTES = 2L;

    /** 逻辑过期缓冲时间(秒) */
    public static final long LOGICAL_EXPIRE_BUFFER_SECONDS = 30L;

    /** 热点数据访问阈值 */
    public static final int HOT_DATA_ACCESS_THRESHOLD = 100;


    // ==================== 安全认证相关 ====================

    /** 登录Token有效期(30天) */
    public static final long TOKEN_EXPIRE_SECONDS = 30L * 24L * 60L * 60L;

    /** 验证码有效期(5分钟) */
    public static final long CODE_EXPIRE_MINUTES = 5L;

    /** 分布式锁默认超时时间(秒) */
    public static final long DEFAULT_LOCK_TIMEOUT_SEC = 10L;

    /** 用户密码最小长度 */
    public static final int PASSWORD_MIN_LENGTH = 6;

    /** 用户密码最大长度 */
    public static final int PASSWORD_MAX_LENGTH = 32;


    // ==================== 应用基础信息 ====================

    /** 应用名称(用于日志、Redis Key前缀等) */
    public static final String APP_NAME = "echo-sns";

    /** 应用版本号 */
    public static final String APP_VERSION = "2.0.0";
}
