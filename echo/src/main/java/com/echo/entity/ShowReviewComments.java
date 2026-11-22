package com.echo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.echo.constants.ReviewMessages;
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
import java.util.List;

/**
 * 评测评论实体类 - 支持嵌套回复的评论系统
 * <p>
 * 业务场景：
 * - 用户可以对演出评测发表评论，进行互动交流
 * - 支持两级评论结构：一级评论（直接评论评测）+ 子评论（回复某条评论）
 * - 评论内容构成社区讨论氛围，提升用户粘性
 * - 支持点赞功能，识别优质评论
 * <p>
 * 评论层级设计：
 * - 一级评论（parentId=0）：直接针对评测的评论，显示在评论区顶层
 * - 子评论（parentId>0）：回复某条一级评论，缩进显示形成嵌套结构
 * - 最多支持两层嵌套（一级评论 + 子评论），避免过深的层级影响阅读
 * <p>
 * 对应数据库表：tb_show_review_comments
 * 索引设计：
 * - PRIMARY KEY (id) -- 主键索引
 * - INDEX idx_review_id (review_id) -- 按评测查询评论列表（最常用）
 * - INDEX idx_user_id (user_id) -- 查询用户的评论历史
 * - INDEX idx_parent_id (parent_id) -- 查询某条评论的子评论
 * - INDEX idx_create_time (create_time) -- 按时间排序（最新评论优先）
 * - INDEX idx_status (status) -- 过滤已删除评论（软删除场景）
 * <p>
 * 数据关系：
 * - 属于：ShowReview（一条评测可有多条评论）
 * - 作者：User（userId指向评论者）
 * - 父级：自身关联（parentId指向父评论，0表示顶级）
 * - 回复目标：User（answerId指向被回复的用户）
 * <p>
 * 软删除机制：
 * - 使用 status 字段标记删除状态（true=正常, false=已删除）
 * - 删除时仅更新status字段，不物理删除数据
 * - 好处：保留历史记录、可恢复、不影响关联数据统计
 * <p>
 * 统计数据一致性：
 * - ShowReview.comments 字段需与实际评论数保持同步
 * - 新增评论：comments + 1
 * - 删除评论：comments - 1
 * - 更新时机：评论增删操作时实时更新
 *
 * @see com.echo.service.IShowReviewCommentsService 评论业务逻辑接口
 * @see ShowReview 父级评测实体（一条评测对应多条评论）
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_show_review_comments")
public class ShowReviewComments implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ==================== 数据库字段 ====================

    /**
     * 主键ID（自增）
     * <p>
     * MySQL: BIGINT UNSIGNED AUTO_INCREMENT
     * 用途：唯一标识一条评论记录，用于查询、删除、回复、点赞等操作
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "评论ID", example = "1")
    private Long id;

    /**
     * 评论者用户ID（tb_user.id）✅ 标准字段
     * <p>
     * 数据库字段：tb_show_review_comments.user_id
     * 关联表：tb_user（用户表）
     * 索引：INDEX idx_user_id (user_id)
     * 约束：NOT NULL，外键关联（逻辑外键）
     * <p>
     * 业务含义：谁发表了这条评论（评论作者）
     * 用途：
     * - 展示评论者信息（昵称、头像）
     * - 校验操作权限（只有作者或管理员可删除）
     * - 统计用户的评论活跃度
     * - 查询用户的评论历史（个人中心）
     */
    @NotNull(message = ReviewMessages.PARAM_EMPTY)
    @Schema(description = "评论者用户ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "100")
    private Long userId;

    /**
     * 关联评测ID（tb_show_review.id）✅ 标准字段
     * <p>
     * 数据库字段：tb_show_review_comments.review_id
     * 关联表：tb_show_review（评测表）
     * 索引：INDEX idx_review_id (review_id) 【核心索引】
     * 约束：NOT NULL，外键关联（逻辑外键）
     * <p>
     * 业务含义：这条评论属于哪篇评测
     * 用途：
     * - 查询某评测的所有评论（最常用查询）
     * - 评测删除时级联处理相关评论（软删除）
     * - 统计评测的评论数量（ShowReview.comments）
     * - 评论计数器更新触发点
     */
    @NotNull(message = ReviewMessages.PARAM_EMPTY)
    @Schema(description = "关联评测ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "50")
    private Long reviewId;

    /**
     * 父评论ID ✅ 核心字段（决定评论层级）
     * <p>
     * 数据库字段：tb_show_review_comments.parent_id
     * 类型：BIGINT UNSIGNED
     * 默认值：0（表示一级评论）
     * 约束：>= 0
     * <p>
     * 层级规则：
     * - parent_id = 0 → 一级评论（直接评论评测，显示在顶层）
     * - parent_id > 0 → 子评论（回复某条一级评论，缩进显示）
     * <p>
     * 设计限制：
     * - 最多支持两层嵌套（一级 + 二级），避免过深影响阅读
     * - 子评论的 parent_id 必须指向一个存在的一级评论ID
     * - 不允许子评论再拥有子评论（防止无限嵌套）
     * <p>
     * 前端展示：
     * - 一级评论：左对齐，正常显示
     * - 子评论：右侧缩进，显示"回复 @xxx"前缀
     * - 点击"回复"按钮：自动设置 parentId 为当前评论ID
     */
    @Min(value = 0, message = SystemMessages.PARENT_COMMENT_ID_NEGATIVE)
    @Schema(description = "父评论ID(0=一级评论, >0=子评论/回复)", example = "0")
    private Long parentId;

    /**
     * 被回复的目标用户ID（tb_user.id）
     * <p>
     * 数据库字段：tb_show_review_comments.answer_id
     * 关联表：tb_user（用户表）
     * 类型：BIGINT UNSIGNED
     * 约束：选填（一级评论可为null，子评论必填）
     * <p>
     * 业务含义：这条评论在回复谁（@提及的用户）
     * 使用场景：
     * - 一级评论（parentId=0）：answerId 可为 null（不是回复特定用户）
     * - 子评论（parentId>0）：answerId 必须指定（明确回复目标）
     * <p>
     * 前端展示：
     * - 显示格式："回复 @userName"
     * - 点击 userName 可跳转到该用户主页
     * - answerId 对应用户的头像和昵称会额外展示
     * <p>
     * 通知推送：
     * - 当 answerId != userId 时，向被回复用户发送通知
     * - 通知类型：NOTIFICATION_TYPE.REVIEW_FOLLOW（关注评测评论）
     * - 通知内容："xxx 回复了你的评论：{content摘要}"
     */
    @Schema(description = "被回复的目标用户ID(一级评论可为null)", example = "101")
    private Long answerId;

    /**
     * 评论正文内容 [核心字段]
     * <p>
     * 数据库字段：tb_show_review_comments.content
     * 类型：VARCHAR(1000)
     * 约束：NOT NULL，长度限制1-1000字符
     * <p>
     * 业务规范：
     * - 必填项，用户必须输入评论内容
     * - 支持纯文本（暂不支持富文本/表情包）
     * - 内容审核：敏感词过滤、违禁内容检测
     * - 长度限制：1000字符（约500个中文字）
     * <p>
     * 内容规范：
     * - 文明用语，禁止辱骂、人身攻击
     * - 不涉及政治敏感话题
     * - 不发布广告、垃圾信息
     * - 鼓励有价值的讨论和观点分享
     * <p>
     * 前端展示：
     * - 列表页：截断显示（超过3行显示"查看全文"）
     * - 详情页：完整显示，支持换行
     * - 支持 @用户名 高亮显示
     */
    @NotNull(message = ReviewMessages.COMMENT_CONTENT_EMPTY)
    @Size(max = 1000, message = SystemMessages.COMMENT_CONTENT_TOO_LONG)
    @Schema(description = "评论正文内容(1-1000字)", requiredMode = Schema.RequiredMode.REQUIRED, example = "同意楼主的评价，现场效果确实很棒！")
    private String content;

    /**
     * 点赞数量
     * <p>
     * 数据库字段：tb_show_review_comments.liked
     * 类型：INT UNSIGNED DEFAULT 0
     * 数据来源：
     * - 实时值：Redis 或 MySQL 实时统计
     * - 初始值：0（新评论无点赞）
     * <p>
     * 统计方式：
     * - 点赞时：liked + 1
     * - 取消点赞：liked - 1
     * - 保证强一致性（评论数需准确）
     * <p>
     * 业务用途：
     * - 展示评论热度（"xxx人觉得有用"）
     * - 按点赞数排序（热门评论优先）
     * - 识别优质评论（可用于置顶推荐）
     */
    @Min(value = 0, message = SystemMessages.COUNT_NEGATIVE)
    @Schema(description = "点赞数量", example = "25")
    private Integer liked;

    /**
     * 评论状态（软删除标记）[核心字段]
     * <p>
     * 数据库字段：tb_show_review_comments.status
     * 类型：TINYINT(1)
     * 约束：NOT NULL，默认值为 true
     * <p>
     * 状态值：
     * - true：正常状态（可见、可交互）
     * - false：已删除（不可见，但数据保留）
     * <p>
     * 软删除机制：
     * - 用户点击"删除"：仅更新 status=false，不物理删除
     * - 管理员删除：同上（软删除）
     * - 恢复评论：更新 status=true（管理员权限）
     * <p>
     * 软删除好处：
     * - 保留历史记录（审计追踪）
     * - 可恢复误删数据
     * - 不影响关联统计数据（如 ShowReview.comments 已扣除）
     * - 避免外键约束问题
     * <p>
     * 查询过滤：
     * - 所有查询默认添加 WHERE status=true 条件
     * - 管理员后台可查看已删除评论（包含 status=false）
     */
    @NotNull(message = SystemMessages.COMMENT_STATUS_NOT_EMPTY)
    @Schema(description = "评论状态(true=正常, false=已删除/软删除)", allowableValues = {"true", "false"}, example = "true")
    private Boolean status;

    /**
     * 创建时间（自动填充）
     * <p>
     * 数据库字段：tb_show_review_comments.create_time
     * 类型：DATETIME
     * 填充策略：INSERT（插入时自动填充当前时间）
     * <p>
     * 用途：
     * - 按时间排序（最新评论优先显示）
     * - 展示相对时间（"刚刚"、"5分钟前"、"昨天"）
     * - 数据分析（评论时间分布、活跃时段）
     * - 评论时效性判断（如"30天前的旧评论"）
     */
    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间", example = "2024-01-01T10:00:00")
    private LocalDateTime createTime;

    /**
     * 更新时间（自动填充）
     * <p>
     * 数据库字段：tb_show_review_comments.update_time
     * 类型：DATETIME
     * 填充策略：INSERT_UPDATE（插入和更新时自动填充）
     * <p>
     * 触发场景：
     * - 状态变更（软删除/恢复）
     * - 点赞数更新
     * - 管理员编辑（如果允许）
     * - 系统修正数据
     * <p>
     * 注意：
     * - 正常情况下评论内容提交后不允许修改
     * - updateTime 主要用于系统维护和数据审计
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间", example = "2024-01-01T15:30:00")
    private LocalDateTime updateTime;

    // ==================== 非数据库字段（查询时动态填充） ====================

    /**
     * 评论者昵称 —— 非数据库字段 [标准命名]
     * <p>
     * 数据来源：查询时从 tb_user.nick_name 关联填充
     * 用途：前端展示评论者的昵称
     * <p>
     * 展示场景：
     * - 评论列表：头像右侧显示昵称
     * - 子评论："回复 @userName" 格式
     * - 个人中心：显示"你发表的评论"
     * <p>
     * 命名规范：使用 userName（符合项目规范）
     * 参考：TicketOrder.userName、ShowReview.userName
     */
    @TableField(exist = false)
    @Schema(description = "评论者昵称(来自User.nickName)", example = "张三丰")
    private String userName;

    /**
     * 评论者头像URL —— 非数据库字段
     * <p>
     * 数据来源：查询时从 tb_user.icon 关联填充
     * 用途：前端展示评论者的头像
     * <p>
     * 展示场景：
     * - 评论列表：左侧小头像（圆形裁剪，40x40px）
     * - 子评论：更小的头像（30x30px）
     * - 悬停显示大图（点击可查看原图）
     * <p>
     * 默认值：空字符串或默认头像URL
     * 命名建议：可考虑改为 userIcon 以与 ShowReview 保持一致
     */
    @TableField(exist = false)
    @Schema(description = "评论者头像URL(来自User.icon)", example = "https://example.com/avatar.jpg")
    private String icon;

    /**
     * 子评论列表 —— 非数据库字段（嵌套结构）
     * <p>
     * 数据类型：List<ShowReviewComments>（递归结构）
     * 数据来源：查询时根据 parentId 关联查询并组装
     * 用途：构建嵌套评论树形结构
     * <p>
     * 数据结构说明：
     * - 仅一级评论（parentId=0）的此字段有值
     * - 子评论（parentId>0）的此字段始终为 null 或空列表
     * - 列表中的元素都是 parentId = 当前评论ID 的子评论
     * <p>
     * 查询逻辑：
     * 1. 先查询所有 parentId=0 的一级评论
     * 2. 再查询所有 parentId > 0 的子评论
     * 3. 在内存中将子评论归类到对应的一级评论的 replies 列表中
     * 4. 返回带有嵌套结构的完整评论树
     * <p>
     * 前端渲染：
     * - 一级评论：正常显示，下方展示 replies 列表
     * - 子评论：缩进显示（左侧 padding: 20px）
     * - 支持展开/收起（当子评论过多时）
     * - 分页加载：子评论支持"查看更多"分页
     * <p>
     * 性能优化：
     * - 批量查询减少数据库访问次数
     * - 使用 Map<parentId, List<子评论>> 快速组装
     * - 限制每条一级评论最多加载 N 条子评论（如20条）
     * - 超出部分使用"查看全部 xx 条回复"按钮加载
     */
    @TableField(exist = false)
    @Schema(description = "子评论列表(仅一级评论有值，嵌套结构)")
    private List<ShowReviewComments> replies;

    /**
     * 获取子评论列表
     *
     * @return 子评论列表，可能为 null 或空列表
     */
    public List<ShowReviewComments> getReplies() {
        return replies;
    }

    /**
     * 设置子评论列表
     *
     * @param replies 子评论列表（由查询时动态组装）
     */
    public void setReplies(List<ShowReviewComments> replies) {
        this.replies = replies;
    }

    // ==================== 工具方法 ====================

    /**
     * 判断是否为一级评论
     *
     * @return true=一级评论（parentId==0）, false=子评论（parentId>0）
     * <p>
     * 使用示例：
     * <pre>
     * if (comment.isTopLevel()) {
     *     // 渲染为一级评论（不缩进）
     * } else {
     *     // 渲染为子评论（缩进 + "回复 @xxx"前缀）
     * }
     * </pre>
     */
    public boolean isTopLevel() {
        return parentId == null || parentId == 0;
    }

    /**
     * 判断是否为子评论（回复）
     *
     * @return true=子评论（parentId>0）, false=一级评论
     */
    public boolean isReply() {
        return parentId != null && parentId > 0;
    }

    /**
     * 判断评论是否处于正常状态（未删除）
     *
     * @return true=正常可见, false=已删除
     */
    public boolean isActive() {
        return Boolean.TRUE.equals(status);
    }

    /**
     * 判断评论是否已被删除（软删除）
     *
     * @return true=已删除, false=正常
     */
    public boolean isDeleted() {
        return Boolean.FALSE.equals(status);
    }
}
