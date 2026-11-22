package com.echo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.echo.constants.ReviewMessages;
import com.echo.constants.ShowMessages;
import com.echo.constants.SystemMessages;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 演出评测（笔记）实体类 - UGC内容核心实体
 * <p>
 * 业务场景：
 * - 用户对已观看的演出进行评分（1-5星）和图文评测
 * - 评测内容构成社区核心UGC，支持其他用户决策
 * - 支持点赞、评论、收藏等社交互动功能
 * - 评测数据用于推荐算法、热门排行、搜索结果等
 * <p>
 * 内容规范：
 * - 标题：必填，1-100字，简洁概括评测主题
 * - 评分：必填，1-5分整数，即使只有评分也需记录
 * - 正文：选填，0-10000字，支持图文混排
 * - 图片：选填，JSON数组格式，最多9张
 * <p>
 * 对应数据库表：tb_show_review
 * 索引设计：
 * - PRIMARY KEY (id) -- 主键索引
 * - INDEX idx_show_id (show_id) -- 按演出查询评测列表
 * - INDEX idx_user_id (user_id) -- 查询用户的评测历史
 * - INDEX idx_create_time (create_time) -- 按时间排序（最新/热门）
 * - INDEX idx_score (score) -- 按评分筛选（高分优先）
 * <p>
 * Redis缓存策略（读写分离+最终一致性）：
 * <p>
 * 1. 点赞系统（ZSet有序集合）：
 * - Key格式：review:liked:{reviewId}
 * - 数据结构：ZSet<userId, timestamp>
 * - 用途：
 *   · 记录用户点赞行为（member=userId, score=timestamp）
 *   · 统计点赞数量：ZCARD review:liked:{reviewId}
 *   · 判断是否已赞：ZSCORE review:liked:{reviewId} {userId}
 *   · 取消点赞：ZREM review:liked:{reviewId} {userId}
 * - TTL：永不过期（与评测生命周期一致）
 * <p>
 * 2. Feed流推送（ZSet时间线）：
 * - Key格式：feed:{userId}
 * - 数据结构：ZSet<reviewId, timestamp>
 * - 用途：
 *   · 用户关注的人发布新评测时，推送到Feed流
 *   · 按时间倒序查询：ZREVRANGE feed:{userId} 0 -1
 *   · 分页加载：ZREVRANGEBYSCORE feed:{userId} +inf -inf LIMIT offset count
 * - TTL：最近30天（滚动窗口）
 * <p>
 * 3. 热门排行榜（ZSet分数排序）：
 * - Key格式：review:hot
 * - 数据结构：ZSet<reviewId, hotScore>
 * - 算法：hotScore = liked * 2 + comments * 1.5 + timeDecay
 * - 用途：展示热门评测，提升优质内容曝光
 * - 更新频率：定时任务每5分钟刷新
 * <p>
 * 数据一致性保障：
 * - 写入流程：MySQL → Redis（先持久化再更新缓存）
 * - 读取策略：Cache-Aside（先查Redis，未命中再查MySQL并回填）
 * - 最终一致性：允许秒级延迟，通过消息队列异步同步
 * - 缓存失效：删除评测时清理所有相关Redis Key
 * <p>
 * 统计数据实时性：
 * - liked（点赞数）：Redis ZCARD 实时统计，定时同步到MySQL
 * - comments（评论数）：评论增删时实时更新Redis和MySQL
 * - 热度分数：后台定时任务计算，用于热门排行
 *
 * @see com.echo.service.IShowReviewService 评测业务逻辑接口（15个方法）
 * @see ShowReviewComments 评测评论子实体
 * @see ReviewLike 评测点赞实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_show_review")
public class ShowReview implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ==================== 数据库字段 ====================

    /**
     * 主键ID（自增）
     * <p>
     * MySQL: BIGINT UNSIGNED AUTO_INCREMENT
     * 用途：唯一标识一条评测记录，用于查询、删除、点赞、评论等操作
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "评测ID", example = "1")
    private Long id;

    /**
     * 关联演出ID（tb_show.id）✅ 标准字段
     * <p>
     * 数据库字段：tb_show_review.show_id
     * 关联表：tb_show（演出表）
     * 索引：INDEX idx_show_id (show_id)
     * 约束：NOT NULL，外键关联（逻辑外键）
     * <p>
     * 业务含义：评测针对哪场演出
     * 用途：
     * - 查询某演出的所有评测（演出详情页展示）
     * - 统计演出的平均评分（SHOW.avg_score）
     * - 演出下架/删除时级联处理相关评测
     */
    @NotNull(message = ReviewMessages.SHOW_REQUIRED)
    @Schema(description = "演出ID(被评测的演出)", requiredMode = Schema.RequiredMode.REQUIRED, example = "50")
    private Long showId;

    /**
     * 作者用户ID（tb_user.id）✅ 标准字段
     * <p>
     * 数据库字段：tb_show_review.user_id
     * 关联表：tb_user（用户表）
     * 索引：INDEX idx_user_id (user_id)
     * 约束：NOT NULL，外键关联（逻辑外键）
     * <p>
     * 业务含义：谁写了这篇评测（评测作者）
     * 用途：
     * - 查询用户的评测历史（个人中心）
     * - 校验操作权限（只有作者可删除/编辑）
     * - 展示作者信息（头像、昵称）
     */
    @NotNull(message = ReviewMessages.PARAM_EMPTY)
    @Schema(description = "用户ID(评测作者)", requiredMode = Schema.RequiredMode.REQUIRED, example = "100")
    private Long userId;

    /**
     * 评测标题 ✅ 核心字段
     * <p>
     * 数据库字段：tb_show_review.title
     * 类型：VARCHAR(100)
     * 约束：NOT NULL，长度限制1-100字符
     * <p>
     * 业务规范：
     * - 必填项，用户必须提供标题
     * - 简洁概括评测主题或观点
     * - 示例："周杰伦演唱会超值体验"、"舞台效果震撼但座位较远"
     * <p>
     * 前端展示：
     * - 列表页：显示完整标题（截断显示）
     * - 详情页：显示完整标题（加粗展示）
     * - 搜索：作为搜索关键词之一
     */
    @NotNull(message = ReviewMessages.TITLE_NOT_EMPTY)
    @Size(max = 100, message = SystemMessages.REVIEW_TITLE_TOO_LONG)
    @Schema(description = "评测标题(1-100字)", requiredMode = Schema.RequiredMode.REQUIRED, example = "周杰伦2024演唱会超值体验")
    private String title;

    /**
     * 评分（1-5星）✅ 核心字段
     * <p>
     * 数据库字段：tb_show_review.score
     * 类型：INT
     * 约束：NOT NULL，范围1-5
     * <p>
     * 评分标准：
     * - 1星：非常差，强烈不推荐
     * - 2星：较差，存在明显问题
     * - 3星：一般，中规中矩
     * - 4星：良好，值得推荐
     * - 5星：优秀，强烈推荐
     * <p>
     * 业务规则：
     * - 必填字段，即使只有评分没有正文也需要记录
     * - 同一用户对同一演出只能评价一次（防止刷分）
     * - 提交后不可修改（保证评分真实性）
     * <p>
     * 数据用途：
     * - 计算演出的平均评分（SHOW.avg_score = AVG(score)）
     * - 按评分筛选评测（高分优先/低分优先）
     * - 统计评分分布（5星占比、平均分等）
     */
    @NotNull(message = ReviewMessages.SCORE_INVALID)
    @Min(value = 1, message = ReviewMessages.SCORE_INVALID)
    @Max(value = 5, message = ReviewMessages.SCORE_INVALID)
    @Schema(description = "评分(1-5星)", requiredMode = Schema.RequiredMode.REQUIRED, example = "5")
    private Integer score;

    /**
     * 评测图片URL列表（JSON数组格式）
     * <p>
     * 数据库字段：tb_show_review.images
     * 类型：TEXT（存储JSON字符串）
     * 格式：JSON Array，示例：["url1", "url2", "url3"]
     * <p>
     * 业务规范：
     * - 选填项，用户可上传0-9张图片
     * - 图片需经过OSS/CDN处理，存储URL
     * - 支持格式：JPG、PNG、WEBP
     * - 单张图片大小限制：≤ 5MB
     * <p>
     * 存储优化：
     * - 使用JSON数组格式，便于前端直接解析
     * - 图片URL使用绝对路径（包含域名）
     * - 支持缩略图（自动生成多尺寸）
     * <p>
     * 前端展示：
     * - 详情页：图片轮播/瀑布流展示
     * - 列表页：显示首张图作为封面
     * - 支持点击查看大图
     */
    @Size(max = 2000, message = SystemMessages.REVIEW_IMAGES_TOO_LONG)
    @Schema(description = "评测图片URL列表(JSON数组，最多9张)", example = "[\"https://example.com/img1.jpg\"]")
    private String images;

    /**
     * 评测正文内容 [核心字段]
     * <p>
     * 数据库字段：tb_show_review.content
     * 类型：TEXT
     * 约束：选填，长度限制0-10000字符
     * <p>
     * 业务规范：
     * - 支持纯文本或富文本（Markdown/HTML）
     * - 鼓励用户详细描述观演体验
     * - 内容审核：敏感词过滤、违禁内容检测
     * <p>
     * 推荐内容维度：
     * - 演出质量（音响、灯光、舞美）
     * - 座位视角（视野、舒适度）
     * - 性价比（票价是否物有所值）
     * - 交通便利性（场馆位置、停车）
     * - 其他建议（穿搭、注意事项）
     * <p>
     * SEO优化：
     * - 内容会被搜索引擎收录
     * - 关键词自然融入正文
     * - 有助于提升演出页面权重
     */
    @Size(max = 10000, message = ReviewMessages.CONTENT_TOO_LONG)
    @Schema(description = "评测正文内容(0-10000字，支持富文本)", example = "整体体验非常棒！舞台效果震撼...")
    private String content;

    /**
     * 点赞数量（Redis实时统计）
     * <p>
     * 数据库字段：tb_show_review.liked
     * 类型：INT UNSIGNED DEFAULT 0
     * 数据来源：
     * - 实时值：Redis ZCARD review:liked:{reviewId}
     * - 持久化值：MySQL（定时同步，允许延迟）
     * <p>
     * 统计方式：
     * - Redis：ZCARD review:liked:{reviewId} （O(1)时间复杂度）
     * - 同步策略：每5分钟批量同步到MySQL
     * - 读取优先级：Redis > MySQL
     * <p>
     * 业务用途：
     * - 评测列表按点赞数排序（热门评测）
     * - 影响热度分数计算（hotScore权重）
     * - 展示社交证明（"xxx人觉得有用"）
     */
    @Min(value = 0, message = SystemMessages.COUNT_NEGATIVE)
    @Schema(description = "点赞数量(Redis实时统计)", example = "128")
    private Integer liked;

    /**
     * 评论数量（实时更新）
     * <p>
     * 数据库字段：tb_show_review.comments
     * 类型：INT UNSIGNED DEFAULT 0
     * 数据来源：
     * - 实时值：评论增删时同步更新Redis和MySQL
     * - 保证强一致性（评论数需准确）
     * <p>
     * 更新时机：
     * - 新增评论：comments + 1
     * - 删除评论：comments - 1
     * - 恢复评论：comments + 1（软删除场景）
     * <p>
     * 业务用途：
     * - 展示评论热度（"xxx条评论"）
     * - 影响热度分数计算
     * - 判断是否需要加载评论列表
     */
    @Min(value = 0, message = SystemMessages.COUNT_NEGATIVE)
    @Schema(description = "评论数量", example = "36")
    private Integer comments;

    /**
     * 创建时间（自动填充）
     * <p>
     * 数据库字段：tb_show_review.create_time
     * 类型：DATETIME
     * 填充策略：INSERT（插入时自动填充当前时间）
     * <p>
     * 用途：
     * - 按时间排序（最新评测/最早评测）
     * - 计算时间衰减因子（热度算法）
     * - 数据分析（发布时间分布）
     * - 展示相对时间（"3小时前"、"昨天"）
     */
    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间", example = "2024-01-01T10:00:00")
    private LocalDateTime createTime;

    /**
     * 更新时间（自动填充）
     * <p>
     * 数据库字段：tb_show_review.update_time
     * 类型：DATETIME
     * 填充策略：INSERT_UPDATE（插入和更新时自动填充）
     * <p>
     * 触发场景：
     * - 编辑评测内容（如果允许）
     * - 系统修正数据（管理员操作）
     * - 统计数据同步（liked/comments更新）
     * <p>
     * 注意：
     * - 正常情况下评测提交后不允许修改
     * - updateTime主要用于系统维护和数据审计
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间", example = "2024-01-01T15:30:00")
    private LocalDateTime updateTime;

    // ==================== 非数据库字段（查询时动态填充） ====================

    /**
     * 作者头像URL —— 非数据库字段
     * <p>
     * 数据来源：查询时从 tb_user.icon 关联填充
     * 用途：前端展示评测作者的头像
     * <p>
     * 展示场景：
     * - 评测列表：左侧小头像（圆形裁剪）
     * - 评测详情：顶部作者信息区
     * - 评论区的@提及
     * <p>
     * 默认值：空字符串或默认头像URL
     */
    @TableField(exist = false)
    @Schema(description = "作者头像URL(来自User.icon)", example = "https://example.com/avatar.jpg")
    private String userIcon;

    /**
     * 作者昵称 —— 非数据库字段 ✅ 标准命名
     * <p>
     * 数据来源：查询时从 tb_user.nick_name 关联填充
     * 用途：前端展示评测作者的昵称
     * <p>
     * 命名规范：
     * - 使用 userName 而非 name（符合项目规范）
     * - 参考 TicketOrder.userName、ShowReviewComments.userName
     * <p>
     * 展示场景：
     * - 评测列表：头像右侧显示昵称
     * - 评测详情：标题下方显示"作者：xxx"
     * - 搜索结果：显示评测作者
     */
    @TableField(exist = false)
    @Schema(description = "作者昵称(来自User.nickName)", example = "张三丰")
    private String userName;

    /**
     * 当前登录用户是否已点赞 —— 非数据库字段
     * <p>
     * 数据来源：查询时根据当前用户动态计算
     * 计算方式：ZSCORE review:liked:{reviewId} {currentUserId}
     * 返回值：
     * - true：当前用户已点赞（ZSCORE返回非null）
     * - false：当前用户未点赞（ZSCORE返回null）
     * - null：用户未登录（前端根据此值控制按钮状态）
     * <p>
     * 用途：
     * - 控制点赞按钮的高亮状态（已赞/未赞）
     * - 避免重复点赞的前端校验
     * - 提升用户体验（无需额外请求判断是否已赞）
     * <p>
     * 性能优化：
     * - 批量查询时使用Pipeline一次获取多条评测的点赞状态
     * - Redis ZSCORE 时间复杂度 O(1)，性能优秀
     */
    @TableField(exist = false)
    @Schema(description = "当前用户是否已点赞(true=已赞, false=未赞, null=未登录)", example = "false")
    private Boolean isLike;

    // ==================== Redis 缓存常量 & 工具方法 ====================

    /**
     * 评测点赞集合的Redis Key前缀
     * <p>
     * 完整格式：review:liked:{reviewId}
     * 数据结构：ZSet<userId, timestamp>（有序集合）
     * 示例值：review:liked:100 → { "101":1704067200, "102":1704070800 }
     * <p>
     * ZSet成员说明：
     * - member： userId（点赞用户ID，字符串形式）
     * - score：timestamp（点赞时间戳，毫秒级）
     * <p>
     * 使用场景：
     * - ShowReview.buildLikedKey(100L) → "review:liked:100"
     * - ZADD review:liked:100 1704067200 101 → 用户101在某个时间点点赞
     * - ZREM review:liked:100 101 → 用户101取消点赞
     * - ZCARD review:liked:100 → 获取评测100的总点赞数
     * - ZSCORE review:liked:100 101 → 判断用户101是否已点赞（返回score或null）
     * <p>
     * TTL策略：永不过期（与评测生命周期一致，评测删除时主动清理）
     */
    public static final String REDIS_LIKED_KEY_PREFIX = "review:liked:";

    /**
     * 用户Feed流的Redis Key前缀
     * <p>
     * 完整格式：feed:{userId}
     * 数据结构：ZSet<reviewId, timestamp>（有序集合）
     * 示例值：feed:100 → { "200":1704067200, "201":1704070800, "202":1704074400 }
     * <p>
     * ZSet成员说明：
     * - member：reviewId（评测ID，字符串形式）
     * - score：timestamp（评测发布时间戳，用于排序）
     * <p>
     * 使用场景：
     * - ShowReview.buildFeedKey(100L) → "feed:100"
     * - ZADD feed:100 1704067200 200 → 将评测200添加到用户100的Feed流
     * - ZREVRANGE feed:100 0 9 → 获取用户100的最新10条Feed（倒序）
     * - ZREVRANGEBYSCORE feed:100 +inf -inf LIMIT 0 10 → 分页加载Feed
     * <p>
     * TTL策略：30天滚动窗口（自动清理旧数据，节省内存）
     * 清理机制：定时任务每天清理30天前的Feed数据
     */
    public static final String REDIS_FEED_KEY_PREFIX = "feed:";

    /**
     * 热门评测排行的Redis Key
     * <p>
     * 完整格式：review:hot（全局只有一个）
     * 数据结构：ZSet<reviewId, hotScore>（按热度分数排序）
     * 示例值：review:hot → { "200":95.5, "201":88.2, "202":76.8 }
     * <p>
     * 热度算法：hotScore = liked * 2.0 + comments * 1.5 + timeDecay
     * - liked：点赞数（权重2.0，高质量互动）
     * - comments：评论数（权重1.5，讨论热度）
     * - timeDecay：时间衰减因子（新内容加分，老内容降权）
     * <p>
     * 使用场景：
     * - ZREVRANGE review:hot 0 19 → 获取Top20热门评测
     * - 用于首页"热门评测"模块展示
     * - 用于推荐系统的候选池
     * <p>
     * 更新频率：后台定时任务每5分钟刷新一次
     * 数据来源：从MySQL聚合计算 + Redis实时数据
     */
    public static final String REDIS_HOT_KEY = "review:hot";

    /**
     * 构建评测点赞集合的Redis Key
     *
     * @param reviewId 评测ID
     * @return Redis Key，格式：review:liked:{reviewId}
     * @throws IllegalArgumentException 如果reviewId为null
     * <p>
     * 使用示例：
     * <pre>
     * String key = ShowReview.buildLikedKey(100L);
     * // key = "review:liked:100"
     *
     * // 判断当前用户是否已点赞
     * Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
     * boolean isLiked = score != null;
     *
     * // 获取点赞总数
     * Long likeCount = stringRedisTemplate.opsForZSet().size(key);
     * </pre>
     */
    public static String buildLikedKey(Long reviewId) {
        if (reviewId == null) {
            throw new IllegalArgumentException("reviewId不能为null");
        }
        return REDIS_LIKED_KEY_PREFIX + reviewId;
    }

    /**
     * 构建用户Feed流的Redis Key
     *
     * @param userId 用户ID
     * @return Redis Key，格式：feed:{userId}
     * @throws IllegalArgumentException 如果userId为null
     * <p>
     * 使用示例：
     * <pre>
     * String key = ShowReview.buildFeedKey(100L);
     * // key = "feed:100"
     *
     * // 获取最新的10条Feed（按时间倒序）
     * Set<String> reviewIds = stringRedisTemplate.opsForZSet()
     *     .reverseRange(key, 0, 9);
     *
     * // 分页加载（基于游标）
     * Set<String> pageData = stringRedisTemplate.opsForZSet()
     *     .reverseRangeByScore(key, maxScore, minScore, offset, count);
     * </pre>
     */
    public static String buildFeedKey(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId不能为null");
        }
        return REDIS_FEED_KEY_PREFIX + userId;
    }

    /**
     * 获取热门评测排行的Redis Key（全局唯一）
     *
     * @return 固定的Redis Key：review:hot
     * <p>
     * 使用示例：
     * <pre>
     * String key = ShowReview.getHotKey();
     * // key = "review:hot"
     *
     * // 获取Top10热门评测ID
     * Set<String> topReviewIds = stringRedisTemplate.opsForZSet()
     *     .reverseRange(key, 0, 9);
     *
     * // 获取某个评测的热度分数
     * Double hotScore = stringRedisTemplate.opsForZSet().score(key, reviewId.toString());
     * </pre>
     */
    public static String getHotKey() {
        return REDIS_HOT_KEY;
    }
}
