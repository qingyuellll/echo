package com.echo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.echo.constants.OrderMessages;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import javax.validation.constraints.NotNull;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 演出关注实体类（用户-演出 多对多关系中间表）
 * <p>
 * 业务场景：
 * - 用户关注感兴趣的演出，接收开售提醒、动态更新等通知
 * - 演出方可查看关注者列表，进行精准营销
 * - 支持双向查询：用户关注了哪些演出 + 某演出被哪些用户关注
 * <p>
 * 对应数据库表：tb_show_follow
 * 索引设计：
 * - UNIQUE KEY uk_user_show (user_id, show_id) -- 防止重复关注
 * - INDEX idx_user_id (user_id) -- 查询用户关注列表
 * - INDEX idx_show_id (show_id) -- 查询演出关注者
 * <p>
 * Redis缓存策略（双写一致性）：
 * - show:follow:{userId} → Set<showId> （用户关注的演出ID集合）
 *   用途：快速判断用户是否关注某演出、展示用户关注列表
 *   操作：SADD/SREM/SISMEMBER/SMEMBERS
 * <p>
 * - show:followers:{showId} → Set<userId> （演出关注者ID集合）
 *   用途：获取演出粉丝数量、粉丝列表、推送通知
 *   操作：SCARD（粉丝数）/SMEMBERS（粉丝列表）
 * <p>
 * 数据一致性保障：
 * - 写入时先更Redis，再写MySQL（最终一致性）
 * - 删除关注时同步清理两个方向的缓存
 * - 缓存失效场景：用户取消关注、演出下架/删除
 *
 * @see com.echo.service.IShowFollowService 关注业务逻辑接口
 * @see com.echo.service.impl.ShowFollowServiceImpl 关注业务逻辑实现
 * @see Follow 用户关注用户（类似模式）
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_show_follow")
public class ShowFollow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ==================== 数据库字段 ====================

    /**
     * 主键ID（自增）
     * <p>
     * MySQL: BIGINT UNSIGNED AUTO_INCREMENT
     * 用途：唯一标识一条关注记录，用于删除、查询等操作
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "关注记录ID", example = "1")
    private Long id;

    /**
     * 用户ID（tb_user.id） 标准字段
     * <p>
     * 数据库字段：tb_show_follow.user_id
     * 关联表：tb_user（用户表）
     * 索引：INDEX idx_user_id (user_id)
     * 约束：NOT NULL，联合唯一索引的一部分 (user_id, show_id)
     * <p>
     * 业务含义：谁关注了演出（关注者）
     */
    @NotNull(message = OrderMessages.USER_ID_NOT_EMPTY)
    @Schema(description = "用户ID(关注者)", requiredMode = Schema.RequiredMode.REQUIRED, example = "100")
    private Long userId;

    /**
     * 演出ID（tb_show.id） 标准字段
     * <p>
     * 数据库字段：tb_show_follow.show_id
     * 关联表：tb_show（演出表）
     * 索引：INDEX idx_show_id (show_id)
     * 约束：NOT NULL，联合唯一索引的一部分 (user_id, show_id)
     * <p>
     * 业务含义：被关注的演出（被关注对象）
     */
    @NotNull(message = OrderMessages.SHOW_ID_NOT_EMPTY)
    @Schema(description = "演出ID(被关注对象)", requiredMode = Schema.RequiredMode.REQUIRED, example = "50")
    private Long showId;

    /**
     * 关注时间（自动填充）
     * <p>
     * 数据库字段：tb_show_follow.create_time
     * 填充策略：INSERT（插入时自动填充当前时间）
     * 用途：
     * - 按时间排序显示关注列表（最新关注的在前）
     * - 统计用户关注活跃度
     * - 数据分析（关注时间分布）
     */
    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "关注时间", example = "2024-01-01T10:00:00")
    private LocalDateTime createTime;

    // ==================== Redis 缓存常量 & 工具方法 ====================

    /**
     * 用户关注列表的Redis Key前缀
     * <p>
     * 完整格式：show:follow:{userId}
     * 数据结构：Set<String>（存储showId的字符串形式）
     * 示例值：show:follow:100 → ["50", "51", "52"]
     * <p>
     * 使用场景：
     * - ShowFollow.buildUserFollowsKey(100L) → "show:follow:100"
     * - SISMEMBER show:follow:100 50 → 判断用户100是否关注演出50
     * - SMEMBERS show:follow:100 → 获取用户100关注的所有演出
     */
    public static final String REDIS_USER_FOLLOWS_KEY = "show:follow:";

    /**
     * 演出关注者列表的Redis Key前缀
     * <p>
     * 完整格式：show:followers:{showId}
     * 数据结构：Set<String>（存储userId的字符串形式）
     * 示例值：show:followers:50 → ["100", "101", "102"]
     * <p>
     * 使用场景：
     * - ShowFollow.buildShowFollowersKey(50L) → "show:followers:50"
     * - SCARD show:followers:50 → 获取演出50的关注人数
     * - SMEMBERS show:followers:50 → 获取演出50的所有关注者
     */
    public static final String REDIS_SHOW_FOLLOWERS_KEY = "show:followers:";

    /**
     * 构建用户关注列表的Redis Key
     *
     * @param userId 用户ID
     * @return Redis Key，格式：show:follow:{userId}
     * @throws IllegalArgumentException 如果userId为null
     * <p>
     * 使用示例：
     * <pre>
     * String key = ShowFollow.buildUserFollowsKey(100L);
     * // key = "show:follow:100"
     *
     * // 判断是否关注
     * Boolean isFollow = stringRedisTemplate.opsForSet().isMember(key, showId.toString());
     * </pre>
     */
    public static String buildUserFollowsKey(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId不能为null");
        }
        return REDIS_USER_FOLLOWS_KEY + userId;
    }

    /**
     * 构建演出关注者列表的Redis Key
     *
     * @param showId 演出ID
     * @return Redis Key，格式：show:followers:{showId}
     * @throws IllegalArgumentException 如果showId为null
     * <p>
     * 使用示例：
     * <pre>
     * String key = ShowFollow.buildShowFollowersKey(50L);
     * // key = "show:followers:50"
     *
     * // 获取关注人数
     * Long followerCount = stringRedisTemplate.opsSet().size(key);
     * </pre>
     */
    public static String buildShowFollowersKey(Long showId) {
        if (showId == null) {
            throw new IllegalArgumentException("showId不能为null");
        }
        return REDIS_SHOW_FOLLOWERS_KEY + showId;
    }
}
