package com.echo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.echo.constants.ShowMessages;
import com.echo.constants.SystemMessages;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 演出类型实体类 - 基础数据字典实体
 * <p>
 * 业务场景：
 * - 管理演出类型分类（演唱会、话剧、音乐剧、舞蹈、戏曲等）
 * - 为 Show 实体提供类型关联（Show.typeId → ShowType.id）
 * - 前端展示类型筛选条件（按类型浏览演出）
 * - 统计分析各类型的演出数量和热度
 * <p>
 * 数据特点：
 * - 基础数据：数据量小，变动频率低（通常由管理员维护）
 * - 树形结构：支持多级分类（暂未实现，当前为扁平结构）
 * - 全局共享：所有演出共用同一套类型体系
 * <p>
 * 对应数据库表：tb_show_type
 * 索引设计：
 * - PRIMARY KEY (id) -- 主键索引
 * - UNIQUE KEY uk_name (name) -- 类型名称唯一（防止重复）
 * - INDEX idx_sort (sort) -- 按排序字段查询（用于排序显示）
 * <p>
 * 数据关系：
 * - 被引用：Show.typeId（一对多，一个类型对应多个演出）
 * - 独立实体：无外键依赖（基础数据）
 * <p>
 * 使用场景：
 * - 演出发布时选择类型（下拉框/单选）
 * - 演出列表页的类型筛选（标签/筛选项）
 * - 首页的类型导航栏（横向滚动列表）
 * - 统计报表（各类型演出数量占比）
 * <p>
 * 管理后台功能：
 * - CRUD 操作（增删改查）
 * - 排序调整（拖拽排序或手动设置 sort 字段）
 * - 启用/禁用（如果需要 status 字段）
 * - 图标上传和管理
 *
 * @see com.echo.service.IShowTypeService 类型管理业务逻辑接口
 * @see Show 关联的演出实体（通过 typeId 关联）
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_show_type")
public class ShowType implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ==================== 数据库字段 ====================

    /**
     * 主键ID（自增）
     * <p>
     * MySQL: BIGINT UNSIGNED AUTO_INCREMENT
     * 用途：唯一标识一种演出类型，用于关联、查询、删除等操作
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "类型ID", example = "1")
    private Long id;

    /**
     * 类型名称 [核心字段]
     * <p>
     * 数据库字段：tb_show_type.name
     * 类型：VARCHAR(50)
     * 约束：NOT NULL, UNIQUE（唯一索引）
     * <p>
     * 命名规范：
     * - 简洁明了，2-10个字符
     * - 示例："演唱会"、"话剧"、"音乐剧"、"舞蹈"、"戏曲"、"脱口秀"
     * - 避免使用模糊词汇（如"其他"、"杂项"）
     * <p>
     * 唯一性约束：
     * - 数据库层面：UNIQUE KEY uk_name (name)
     * - 业务层面：添加前需检查是否已存在
     * - 大小写敏感：通常不区分大小写（取决于数据库配置）
     * <p>
     * 使用场景：
     * - 前端展示：类型名称直接显示给用户
     * - 下拉选项：发布演出时的类型选择器
     * - 筛选条件：演出列表的类型过滤
     * - SEO优化：作为页面标题和关键词的一部分
     */
    @NotBlank(message = SystemMessages.SHOW_TYPE_NAME_NOT_EMPTY)
    @Size(max = 50, message = SystemMessages.SHOW_TYPE_NAME_TOO_LONG)
    @Schema(description = "类型名称(唯一，如：演唱会、话剧、音乐剧)", requiredMode = Schema.RequiredMode.REQUIRED, example = "演唱会")
    private String name;

    /**
     * 类型图标URL [展示字段]
     * <p>
     * 数据库字段：tb_show_type.icon
     * 类型：VARCHAR(200)
     * 约束：选填（可为空字符串或null）
     * <p>
     * 图标规范：
     * - 格式：PNG/SVG/WEBP（推荐SVG，支持缩放不失真）
     * - 尺寸：建议 64x64px 或 128x128px
     * - 大小：≤ 50KB（优化加载速度）
     * - 风格：线性图标/面性图标（保持统一风格）
     * <p>
     * 存储方式：
     * - OSS/CDN URL（推荐）：https://cdn.example.com/icons/concert.png
     * - 相对路径（不推荐）：/static/icons/concert.png
     * - Base64（不推荐）：数据量大，影响性能
     * <p>
     * 展示场景：
     * - 类型导航栏：小图标 + 名称（如：[icon] 演唱会）
     * - 演出详情页：类型标签（带图标）
     * - 筛选栏：图标按钮组
     * - 移动端：底部Tab或顶部导航
     * <p>
     * 默认值处理：
     * - 如果为空：前端显示默认图标或纯文字
     * - 加载失败：显示占位图或隐藏图标
     */
    @Size(max = 200, message = SystemMessages.SHOW_TYPE_ICON_TOO_LONG)
    @Schema(description = "类型图标URL(OSS/CDN地址，可选)", example = "https://cdn.example.com/icons/concert.png")
    private String icon;

    /**
     * 排序权重 [排序字段]
     * <p>
     * 数据库字段：tb_show_type.sort
     * 类型：INT
     * 约束：默认值为0，范围 >= 0
     * <p>
     * 排序规则：
     * - 按 sort 字段升序排列（ASC）
     * - sort 值越小，排越靠前
     * - 相同 sort 值时，按 id 升序排列
     * <p>
     * 典型值设定：
     * - 热门类型：sort = 0-10（如：演唱会=1, 话剧=2, 音乐剧=3）
     * - 普通类型：sort = 11-100
     * - 冷门类型：sort = 101+（或不设置，使用默认值0）
     * <p>
     * 管理操作：
     * - 拖拽排序：前端拖拽后批量更新 sort 值
     * - 手动设置：管理员在后台手动输入数值
     * - 自动排序：按创建时间或使用频率自动计算
     * <p>
     * 查询示例：
     * <pre>
     * // 查询所有类型，按 sort 升序
     * List<ShowType> types = showTypeService.list(
     *     new QueryWrapper<ShowType>().orderByAsc("sort", "id")
     * );
     * </pre>
     */
    @Min(value = 0, message = SystemMessages.SHOW_TYPE_SORT_NEGATIVE)
    @Max(value = 999999, message = SystemMessages.SHOW_TYPE_SORT_MAX)
    @Schema(description = "排序权重(越小越靠前，默认0)", example = "1")
    private Integer sort;

    /**
     * 创建时间（自动填充）[隐藏字段]
     * <p>
     * 数据库字段：tb_show_type.create_time
     * 类型：DATETIME
     * 填充策略：INSERT（插入时自动填充当前时间）
     * <p>
     * 用途：
     * - 记录类型的创建时间（审计日志）
     * - 数据分析（类型新增趋势）
     * - 默认排序（如果 sort 相同，按时间排序）
     * <p>
     * 注意：
     * - 使用 @JsonIgnore 注解，API响应中不返回此字段
     * - 仅在管理后台或内部系统可见
     */
    @JsonIgnore
    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间(隐藏字段)", hidden = true)
    private LocalDateTime createTime;

    /**
     * 更新时间（自动填充）[隐藏字段]
     * <p>
     * 数据库字段：tb_show_type.update_time
     * 类型：DATETIME
     * 填充策略：INSERT_UPDATE（插入和更新时自动填充）
     * <p>
     * 触发场景：
     * - 修改类型名称
     * - 更换类型图标
     * - 调整排序权重
     * - 其他任何字段变更
     * <p>
     * 用途：
     * - 记录最后修改时间（审计日志）
     * - 缓存失效判断（比较 updateTime 判断是否需要刷新）
     * - 数据同步（增量同步时使用 updateTime 作为条件）
     * <p>
     * 注意：
     * - 使用 @JsonIgnore 注解，API响应中不返回此字段
     * - 仅在管理后台或内部系统可见
     */
    @JsonIgnore
    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间(隐藏字段)", hidden = true)
    private LocalDateTime updateTime;

    // ==================== 工具方法 ====================

    /**
     * 判断是否有图标
     *
     * @return true=有图标（icon不为null且不为空字符串）, false=无图标
     * <p>
     * 使用示例：
     * <pre>
     * if (showType.hasIcon()) {
     *     // 显示图标 + 名称
     *     renderIconAndName(showType.getIcon(), showType.getName());
     * } else {
     *     // 只显示名称
     *     renderTextOnly(showType.getName());
     * }
     * </pre>
     */
    public boolean hasIcon() {
        return icon != null && !icon.trim().isEmpty();
    }

    /**
     * 获取显示用的图标URL（带默认值处理）
     *
     * @param defaultIcon 默认图标URL（当icon为空时返回）
     * @return 图标URL（如果有），否则返回默认图标
     * <p>
     * 使用示例：
     * <pre>
     * String iconUrl = showType.getIconOrDefault("/icons/default-type.png");
     * // 如果 showType 有图标则返回其图标，否则返回默认图标
     * </pre>
     */
    public String getIconOrDefault(String defaultIcon) {
        return (icon != null && !icon.trim().isEmpty()) ? icon : defaultIcon;
    }
}
