package com.echo.utils;

/**
 * Redis Key常量定义
 *
 * @author tulip
 */
public final class RedisConstants {

    private RedisConstants() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }

    // ==================== 核心工具方法 ====================

    /**
     * 构建完整的 Redis Key（带格式校验）
     *
     * @param prefix Key 前缀（如 "cache:show:"）
     * @param id     业务 ID（不能为 null 或包含冒号）
     * @return 完整的 Key（如 "cache:show:1001"）
     * @throws IllegalArgumentException 如果参数非法
     */
    public static String key(String prefix, Object id) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("Redis Key 前缀不能为空");
        }
        if (id == null) {
            throw new IllegalArgumentException("Redis Key 的 ID 不能为 null, prefix=" + prefix);
        }
        String idStr = id.toString();
        if (idStr.isBlank()) {
            throw new IllegalArgumentException("Redis Key 的 ID 不能为空白, prefix=" + prefix);
        }
        if (idStr.contains(":")) {
            throw new IllegalArgumentException("Redis Key 的 ID 不能包含冒号(':'), id=" + idStr);
        }
        return prefix + idStr;
    }

    /**
     * 计算带随机偏移的过期时间（防止缓存雪崩）
     *
     * @param baseTtl     基础过期时间（秒）
     * @param randomRange 随机偏移范围（秒），实际范围为 [-randomRange, +randomRange]
     * @return 随机化后的过期时间（秒）
     */
    public static long randomExpireTime(long baseTtl, long randomRange) {
        return baseTtl + (long) (Math.random() * randomRange * 2 - randomRange);
    }

    // ==================== 登录认证模块 ====================

    public static class Login {
        public static final String CODE_KEY = "login:code:";
        public static final long CODE_TTL = 120L;

        public static final String USER_KEY = "login:token:";
        public static final long USER_TTL = 600L;

        public static final String CODE_FAIL_KEY = "login:code:fail:";
        public static final String PWD_FAIL_KEY = "login:pwd:fail:";
        public static final int FAIL_MAX_ATTEMPTS = 5;
        public static final long FAIL_LOCK_TTL = 900L;

        public static String buildCodeKey(String phone) {
            return RedisConstants.key(CODE_KEY, phone);
        }

        public static String buildUserKey(String token) {
            return RedisConstants.key(USER_KEY, token);
        }

        private Login() {}
    }

    // ==================== 管理员认证模块 ====================

    public static class AdminAuth {
        public static final String TOKEN_KEY = "admin:token:";
        public static final long TOKEN_TTL = 600L;

        public static final String CODE_KEY = "admin:code:";
        public static final long CODE_TTL = 120L;
        public static final String CODE_RATE_LIMIT_KEY = "rate:admin:code:";
        public static final long CODE_RATE_LIMIT_TTL = 60L;

        public static final String PASSWORD_CODE_KEY = "admin:password:code:";
        public static final long PASSWORD_CODE_TTL = 120L;
        public static final String PASSWORD_CODE_RATE_LIMIT_KEY = "rate:admin:password:code:";
        public static final long PASSWORD_CODE_RATE_LIMIT_TTL = 60L;

        public static final String LOGIN_FAIL_KEY = "admin:login:fail:";

        public static String buildTokenKey(String token) {
            return RedisConstants.key(TOKEN_KEY, token);
        }

        private AdminAuth() {}
    }

    // ==================== 密码重置模块 ====================

    public static class Password {
        public static final String CODE_KEY = "password:code:";
        public static final long CODE_TTL = 120L;

        public static final String CODE_RATE_LIMIT_KEY = "rate:password:code:";
        public static final long CODE_RATE_LIMIT_TTL = 60L;

        public static final String CODE_FAIL_KEY = "password:code:fail:";

        public static String buildCodeKey(String phone) {
            return RedisConstants.key(CODE_KEY, phone);
        }

        private Password() {}
    }

    // ==================== 缓存模块 ====================

    public static class Cache {
        public static final long NULL_TTL = 120L;

        public static class Show {
            public static final String KEY = "cache:show:";
            public static final long TTL = 1800L;
            public static final long RANDOM_TTL = 600L;

            public static String buildKey(Long showId) {
                return RedisConstants.key(KEY, showId);
            }

            private Show() {}
        }

        public static class ShowType {
            public static final String KEY = "cache:show-type:";
            public static final long TTL = 3600L;

            public static String buildKey(Long typeId) {
                return RedisConstants.key(KEY, typeId);
            }

            private ShowType() {}
        }

        public static class User {
            public static final String KEY = "cache:user:";
            public static final long TTL = 1800L;
            public static final long RANDOM_TTL = 600L;

            public static String buildKey(Long userId) {
                return RedisConstants.key(KEY, userId);
            }

            private User() {}
        }

        public static class UserInfo {
            public static final String KEY = "cache:user-info:";
            public static final long TTL = 1800L;
            public static final long RANDOM_TTL = 600L;

            public static String buildKey(Long userId) {
                return RedisConstants.key(KEY, userId);
            }

            private UserInfo() {}
        }

        public static class Ticket {
            public static final String KEY = "cache:ticket:show:";
            public static final long TTL = 1800L;
            public static final long RANDOM_TTL = 600L;

            public static String buildKey(Long showId) {
                return RedisConstants.key(KEY, showId);
            }

            private Ticket() {}
        }

        public static class Statistics {
            public static final String KEY = "cache:statistics:";
            public static final long TTL = 300L;

            public static String buildKey(String statisticsType) {
                return RedisConstants.key(KEY, statisticsType);
            }

            private Statistics() {}
        }

        public static class PointsBalance {
            public static final String KEY = "cache:points:balance:";

            public static String buildKey(Long userId) {
                return RedisConstants.key(KEY, userId);
            }

            private PointsBalance() {}
        }

        private Cache() {}
    }

    // ==================== 分布式锁模块 ====================

    public static class Lock {
        public static class Show {
            public static final String KEY = "lock:show:";
            public static final long TTL = 600L;

            public static String buildKey(Long showId) {
                return RedisConstants.key(KEY, showId);
            }

            private Show() {}
        }

        public static class Seat {
            public static final String KEY = "lock:seat:";
            public static final long TTL = 300L;

            public static String buildKey(Long showId, Integer row, Integer col) {
                return KEY + showId + ":" + row + "-" + col;
            }

            private Seat() {}
        }

        public static class Order {
            public static final String KEY = "lock:order:";

            public static String buildKey(Long orderId) {
                return RedisConstants.key(KEY, orderId);
            }

            private Order() {}
        }

        private Lock() {}
    }

    // ==================== 秒杀模块 ====================

    public static class Seckill {
        public static final String STOCK_KEY = "seckill:stock:";
        public static final String ORDER_KEY = "seckill:order:";
        public static final String PENDING_ORDER_KEY = "pending:seckill:order:";
        public static final String PENDING_PRE_ORDER_KEY = "pending:pre_order:";

        // ==================== 排队模式 ====================
        /** 排队队列：ZSET（key=QUEUE_KEY+ticketId），member=userId，score=入队时间戳（去重+FIFO） */
        public static final String QUEUE_KEY = "seckill:queue:";
        /** 活跃队列集合：SET，记录存在排队请求的ticketId，供消费者发现队列 */
        public static final String QUEUE_ACTIVE_KEY = "seckill:queue:active";
        /** 排队明细：HASH（key=QUEUE_DETAIL_KEY+ticketId，field=userId），value=JSON{quantity,seatIds} */
        public static final String QUEUE_DETAIL_KEY = "seckill:queue:detail:";
        /** 排队结果：String（key=QUEUE_RESULT_KEY+ticketId+":"+userId），value=JSON{status,...}，供前端轮询 */
        public static final String QUEUE_RESULT_KEY = "seckill:queue:result:";
        /** 售罄标记：String（key=SOLD_OUT_KEY+ticketId），置位后余票为0时不再入队 */
        public static final String SOLD_OUT_KEY = "seckill:soldout:";
        /** 幂等键：String（key=IDEM_KEY+ticketId+":"+userId），值三态 PROCESSING/SUCCESS/FAILED。
         *  请求级幂等标记——回答"这次请求处理过没有"，与 ORDER_KEY（买过没有，业务状态）正交；
         *  退款 SREM 只释放购买资格、绝不删除本键（"买过"可被业务事件改写，"处理过"不能） */
        public static final String IDEM_KEY = "seckill:idem:";
        /** 幂等键有效期（分钟）：需覆盖排队结果TTL与MQ异步补偿重试窗口；
         *  过期后自然释放，退款用户的再次购买请求不会被陈旧的 SUCCESS 永久阻断 */
        public static final long IDEM_TTL_MINUTES = 30L;

        public static String buildStockKey(Long seckillTicketId) {
            return RedisConstants.key(STOCK_KEY, seckillTicketId);
        }

        public static String buildOrderKey(Long userId, Long seckillTicketId) {
            return ORDER_KEY + userId + ":" + seckillTicketId;
        }

        public static String buildIdemKey(Long ticketId, Long userId) {
            return IDEM_KEY + ticketId + ":" + userId;
        }

        private Seckill() {}
    }

    // ==================== 社交关注模块 ====================

    public static class Follows {
        public static final String KEY = "follows:";
        public static final String FANS_KEY = "fans:";
        public static final String COUNT_KEY = "follow:count:";
        public static final String FANS_COUNT_KEY = "follow:fans:count:";
        public static final long COUNT_TTL = 1800L;
        public static final long COUNT_RANDOM_TTL = 600L;

        public static String buildKey(Long userId) {
            return RedisConstants.key(KEY, userId);
        }

        public static String buildFansKey(Long userId) {
            return RedisConstants.key(FANS_KEY, userId);
        }

        public static String buildCountKey(Long userId) {
            return RedisConstants.key(COUNT_KEY, userId);
        }

        private Follows() {}
    }

    public static class ShowFollow {
        public static final String KEY = "show:follow:";
        public static final String FOLLOWERS_KEY = "show:followers:";
        public static final String LIST_KEY = "show:follow:list:";
        public static final long LIST_TTL = 1800L;
        public static final long LIST_RANDOM_TTL = 600L;

        public static String buildKey(Long showId) {
            return RedisConstants.key(KEY, showId);
        }

        public static String buildListKey(Long showId) {
            return RedisConstants.key(LIST_KEY, showId);
        }

        private ShowFollow() {}
    }

    // ==================== 通知模块 ====================

    public static class Notification {
        public static final String UNREAD_KEY = "notification:unread:";
        public static final long UNREAD_TTL = 300L;
        public static final String RATE_LIMIT_KEY = "notification:rate:";
        public static final long RATE_LIMIT_TTL = 3600L;

        public static String buildUnreadKey(Long userId) {
            return RedisConstants.key(UNREAD_KEY, userId);
        }

        public static String buildRateLimitKey(Long userId) {
            return RedisConstants.key(RATE_LIMIT_KEY, userId);
        }

        private Notification() {}
    }

    // ==================== 限流模块 ====================

    public static class RateLimit {
        public static final String SEND_CODE_KEY = "rate:code:";
        public static final long SEND_CODE_TTL = 60L;
        public static final String ORDER_KEY = "rate:order:user:";
        public static final long ORDER_TTL = 60L;
        public static final String COMMENT_KEY = "rate:comment:";
        public static final long COMMENT_TTL = 30L;

        public static String buildSendCodeKey(String phone) {
            return RedisConstants.key(SEND_CODE_KEY, phone);
        }

        public static String buildOrderKey(Long userId) {
            return RedisConstants.key(ORDER_KEY, userId);
        }

        public static String buildCommentKey(Long userId) {
            return RedisConstants.key(COMMENT_KEY, userId);
        }

        private RateLimit() {}
    }

    // ==================== 其他业务常量 ====================

    public static final String REVIEW_LIKED_KEY = "review:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOW_GEO_KEY = "show:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String POINTS_LOTTERY_KEY = "points:lottery:";
    public static final String SHOW_FAVORITE_KEY = "show:favorite:";

    // ==================== 向后兼容层（@Deprecated）====================

    @Deprecated public static final String LOGIN_CODE_KEY = Login.CODE_KEY;
    @Deprecated public static final Long LOGIN_CODE_TTL = Login.CODE_TTL;
    @Deprecated public static final String LOGIN_USER_KEY = Login.USER_KEY;
    @Deprecated public static final Long LOGIN_USER_TTL = Login.USER_TTL;
    @Deprecated public static final String LOGIN_CODE_FAIL_KEY = Login.CODE_FAIL_KEY;
    @Deprecated public static final String LOGIN_PWD_FAIL_KEY = Login.PWD_FAIL_KEY;
    @Deprecated public static final Integer LOGIN_FAIL_MAX_ATTEMPTS = Login.FAIL_MAX_ATTEMPTS;
    @Deprecated public static final Long LOGIN_FAIL_LOCK_TTL = Login.FAIL_LOCK_TTL;

    @Deprecated public static final String LOGIN_ADMIN_KEY = AdminAuth.TOKEN_KEY;
    @Deprecated public static final Long LOGIN_ADMIN_TTL = AdminAuth.TOKEN_TTL;
    @Deprecated public static final String ADMIN_CODE_KEY = AdminAuth.CODE_KEY;
    @Deprecated public static final Long ADMIN_CODE_TTL = AdminAuth.CODE_TTL;
    @Deprecated public static final String ADMIN_CODE_RATE_LIMIT_KEY = AdminAuth.CODE_RATE_LIMIT_KEY;
    @Deprecated public static final Long ADMIN_CODE_RATE_LIMIT_TTL = AdminAuth.CODE_RATE_LIMIT_TTL;
    @Deprecated public static final String ADMIN_PASSWORD_CODE_KEY = AdminAuth.PASSWORD_CODE_KEY;
    @Deprecated public static final Long ADMIN_PASSWORD_CODE_TTL = AdminAuth.PASSWORD_CODE_TTL;
    @Deprecated public static final String ADMIN_PASSWORD_CODE_RATE_LIMIT_KEY = AdminAuth.PASSWORD_CODE_RATE_LIMIT_KEY;
    @Deprecated public static final Long ADMIN_PASSWORD_CODE_RATE_LIMIT_TTL = AdminAuth.PASSWORD_CODE_RATE_LIMIT_TTL;
    @Deprecated public static final String ADMIN_LOGIN_FAIL_KEY = AdminAuth.LOGIN_FAIL_KEY;

    @Deprecated public static final String PASSWORD_CODE_KEY = Password.CODE_KEY;
    @Deprecated public static final Long PASSWORD_CODE_TTL = Password.CODE_TTL;
    @Deprecated public static final String PASSWORD_CODE_RATE_LIMIT_KEY = Password.CODE_RATE_LIMIT_KEY;
    @Deprecated public static final Long PASSWORD_CODE_RATE_LIMIT_TTL = Password.CODE_RATE_LIMIT_TTL;
    @Deprecated public static final String PASSWORD_CODE_FAIL_KEY = Password.CODE_FAIL_KEY;

    @Deprecated public static final Long CACHE_NULL_TTL = Cache.NULL_TTL;

    @Deprecated public static final String CACHE_SHOW_KEY = Cache.Show.KEY;
    @Deprecated public static final Long CACHE_SHOW_TTL = Cache.Show.TTL;
    @Deprecated public static final Long CACHE_SHOW_RANDOM_TTL = Cache.Show.RANDOM_TTL;

    @Deprecated public static final String CACHE_SHOW_TYPE_KEY = Cache.ShowType.KEY;
    @Deprecated public static final Long CACHE_SHOW_TYPE_TTL = Cache.ShowType.TTL;

    @Deprecated public static final String LOCK_SHOW_KEY = Lock.Show.KEY;
    @Deprecated public static final Long LOCK_SHOW_TTL = Lock.Show.TTL;

    @Deprecated public static final String LOCK_SEAT_KEY = Lock.Seat.KEY;
    @Deprecated public static final Long LOCK_SEAT_TTL = Lock.Seat.TTL;

    @Deprecated public static final String LOCK_ORDER_KEY = Lock.Order.KEY;

    @Deprecated public static final String SECKILL_STOCK_KEY = Seckill.STOCK_KEY;
    @Deprecated public static final String SECKILL_ORDER_KEY = Seckill.ORDER_KEY;
    @Deprecated public static final String PENDING_SECKILL_ORDER = Seckill.PENDING_ORDER_KEY;
    @Deprecated public static final String PENDING_PRE_ORDER = Seckill.PENDING_PRE_ORDER_KEY;

    @Deprecated public static final String FOLLOW_KEY = Follows.KEY;
    @Deprecated public static final String FOLLOW_FANS_KEY = Follows.FANS_KEY;
    @Deprecated public static final String FOLLOW_COUNT_KEY = Follows.COUNT_KEY;
    @Deprecated public static final String FOLLOW_FANS_COUNT_KEY = Follows.FANS_COUNT_KEY;
    @Deprecated public static final Long FOLLOW_COUNT_TTL = Follows.COUNT_TTL;
    @Deprecated public static final Long FOLLOW_COUNT_RANDOM_TTL = Follows.COUNT_RANDOM_TTL;

    @Deprecated public static final String SHOW_FOLLOW_KEY = ShowFollow.KEY;
    @Deprecated public static final String SHOW_FOLLOWERS_KEY = ShowFollow.FOLLOWERS_KEY;
    @Deprecated public static final String SHOW_FOLLOW_LIST_KEY = ShowFollow.LIST_KEY;
    @Deprecated public static final Long SHOW_FOLLOW_LIST_TTL = ShowFollow.LIST_TTL;
    @Deprecated public static final Long SHOW_FOLLOW_LIST_RANDOM_TTL = ShowFollow.LIST_RANDOM_TTL;

    @Deprecated public static final String NOTIFICATION_RATE_LIMIT_KEY = Notification.RATE_LIMIT_KEY;
    @Deprecated public static final Long NOTIFICATION_RATE_LIMIT_TTL = Notification.RATE_LIMIT_TTL;

    @Deprecated public static final String SEND_CODE_RATE_LIMIT_KEY = RateLimit.SEND_CODE_KEY;
    @Deprecated public static final Long SEND_CODE_RATE_LIMIT_TTL = RateLimit.SEND_CODE_TTL;
    @Deprecated public static final String COMMENT_RATE_LIMIT_KEY = RateLimit.COMMENT_KEY;
    @Deprecated public static final Long COMMENT_RATE_LIMIT_TTL = RateLimit.COMMENT_TTL;

    @Deprecated public static final String RATE_LIMIT_ORDER_KEY = RateLimit.ORDER_KEY;
    @Deprecated public static final Long RATE_LIMIT_ORDER_TTL = RateLimit.ORDER_TTL;

    @Deprecated public static final String CACHE_USER_KEY = Cache.User.KEY;
    @Deprecated public static final Long CACHE_USER_TTL = Cache.User.TTL;
    @Deprecated public static final Long CACHE_USER_RANDOM_TTL = Cache.User.RANDOM_TTL;
    @Deprecated public static final String CACHE_USER_INFO_KEY = Cache.UserInfo.KEY;
    @Deprecated public static final Long CACHE_USER_INFO_TTL = Cache.UserInfo.TTL;
    @Deprecated public static final Long CACHE_USER_INFO_RANDOM_TTL = Cache.UserInfo.RANDOM_TTL;
    @Deprecated public static final String CACHE_TICKET_KEY = Cache.Ticket.KEY;
    @Deprecated public static final Long CACHE_TICKET_TTL = Cache.Ticket.TTL;
    @Deprecated public static final Long CACHE_TICKET_RANDOM_TTL = Cache.Ticket.RANDOM_TTL;
    @Deprecated public static final String CACHE_STATISTICS_KEY = Cache.Statistics.KEY;
    @Deprecated public static final Long CACHE_STATISTICS_TTL = Cache.Statistics.TTL;
    @Deprecated public static final String CACHE_NOTIFICATION_UNREAD_KEY = Notification.UNREAD_KEY;
    @Deprecated public static final Long CACHE_NOTIFICATION_UNREAD_TTL = Notification.UNREAD_TTL;

    @Deprecated public static final String CACHE_POINTS_BALANCE_KEY = Cache.PointsBalance.KEY;
}
