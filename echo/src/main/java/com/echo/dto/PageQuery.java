package com.echo.dto;

import com.echo.utils.CursorCodec;
import com.echo.utils.SystemConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 分页查询参数DTO(支持传统分页和游标分页)
 * <p>
 * <b>设计特点：</b>
 * <ul>
 *   <li>支持传统分页（current + size）</li>
 *   <li>支持游标分页（cursor）</li>
 *   <li>自动边界检查（防止非法参数）</li>
 *   <li>引用 {@link SystemConstants} 统一管理默认值</li>
 * </ul>
 *
 * @author tulip
 * @version 2.0 (2026-10-24 优化版)
 * @see SystemConstants#DEFAULT_PAGE_SIZE
 * @see SystemConstants#MAX_PAGE_SIZE
 */
@Data
@Slf4j
@Schema(description = "分页查询参数（支持传统分页和游标分页）")
public class PageQuery {

    @Schema(description = "当前页码（从1开始，传统分页模式）", example = "1", minimum = "1")
    private Integer current = 1;

    @Schema(description = "每页记录数", example = "10", minimum = "1", maximum = "100")
    private Integer size = SystemConstants.DEFAULT_PAGE_SIZE;

    @Schema(description = "排序字段（如：id, createTime, name）", example = "createTime")
    private String sortBy;

    @Schema(description = "排序方式：asc(升序)/desc(降序)", example = "desc", allowableValues = {"asc", "desc"})
    private String sortOrder = "desc";

    @Schema(description = "游标（游标分页模式，与current二选一）", example = "eyJpZCI6MTAwfQ==")
    private String cursor;

    /**
     * 获取当前页码（带null检查和默认值）
     */
    public Integer getCurrent() {
        return current != null ? current : 1;
    }

    /**
     * 获取每页大小（带边界检查，限制在 1-MAX_PAGE_SIZE 之间）
     * <p>
     * <b>边界处理逻辑：</b>
     * <ul>
     *   <li>null → 返回 {@link SystemConstants#DEFAULT_PAGE_SIZE}</li>
     *   <li>&lt;1 → 强制为 1（至少返回1条）</li>
     *   <li>&gt;{@link SystemConstants#MAX_PAGE_SIZE} → 强制截断为最大值</li>
     * </ul>
     *
     * @return 合法的分页大小
     */
    public Integer getSize() {
        if (size == null) {
            return SystemConstants.DEFAULT_PAGE_SIZE;
        }
        return Math.min(Math.max(size, 1), SystemConstants.MAX_PAGE_SIZE);
    }

    /**
     * 获取排序方向（自动规范化，只允许asc/desc）
     */
    public String getSortOrder() {
        if (sortOrder == null) {
            return "desc";
        }
        String order = sortOrder.toLowerCase();
        return "asc".equals(order) || "desc".equals(order) ? order : "desc";
    }

    /**
     * 是否为游标分页模式
     */
    public boolean isCursorMode() {
        return cursor != null && !cursor.isEmpty();
    }

    /**
     * 是否为传统分页模式
     */
    public boolean isTraditionalMode() {
        return !isCursorMode();
    }

    /**
     * 解码游标信息
     *
     * @return 游标信息对象
     * @throws IllegalArgumentException 如果游标无效
     */
    public CursorCodec.CursorInfo decodeCursor() {
        if (!isCursorMode()) {
            throw new IllegalStateException("当前不是游标分页模式");
        }
        try {
            CursorCodec.CursorInfo cursorInfo = CursorCodec.decode(cursor);
            log.debug("[PageQuery] 游标解码成功 - lastId:{}, lastTime:{}",
                    cursorInfo.getLastId(), cursorInfo.getLastTime());
            return cursorInfo;
        } catch (Exception e) {
            log.error("[PageQuery] 游标解码失败 - cursor:{}, error:{}", cursor, e.getMessage());
            throw new IllegalArgumentException("无效的游标: " + e.getMessage(), e);
        }
    }

    /**
     * 验证游标是否有效（不抛异常）
     *
     * @return true=有效, false=无效或非游标模式
     */
    public boolean isCursorValid() {
        if (!isCursorMode()) {
            return false;
        }
        return CursorCodec.isValid(cursor);
    }

    /**
     * 每页记录数别名
     * <p>
     * 管理端列表接口早期使用 {@code pageSize}，页面与接口封装沿用该拼写；
     * 本 DTO 的属性名是 {@code size}。提供该 setter 后两种拼写都能绑定到同一个字段，
     * 使列表接口的每页条数不会因为参数名差异被静默丢弃。
     *
     * @param pageSize 每页记录数
     */
    public void setPageSize(Integer pageSize) {
        this.size = pageSize;
    }

    /**
     * 创建MyBatis-Plus的传统分页对象
     */
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<?> toPage() {
        return new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(getCurrent(), getSize());
    }

    /**
     * 创建MyBatis-Plus的传统分页对象（泛型版本）
     */
    @SuppressWarnings("unchecked")
    public <T> com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> toTypedPage() {
        return (com.baomidou.mybatisplus.extension.plugins.pagination.Page<T>) toPage();
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建默认分页参数（第1页，10条/页）
     */
    public static PageQuery defaultPage() {
        return new PageQuery();
    }

    /**
     * 创建指定页码的分页参数（默认10条/页）
     */
    public static PageQuery of(int current) {
        PageQuery query = new PageQuery();
        query.setCurrent(current);
        return query;
    }

    /**
     * 创建完整指定的分页参数
     */
    public static PageQuery of(int current, int size) {
        PageQuery query = new PageQuery();
        query.setCurrent(current);
        query.setSize(size);
        return query;
    }

    /**
     * 创建包含排序信息的分页参数
     */
    public static PageQuery of(int current, int size, String sortBy, String sortOrder) {
        PageQuery query = of(current, size);
        query.setSortBy(sortBy);
        query.setSortOrder(sortOrder);
        return query;
    }

    /**
     * 创建游标分页参数
     *
     * @param cursor Base64编码的游标字符串
     * @param size   每页记录数
     * @return 游标分页参数
     */
    public static PageQuery cursor(String cursor, int size) {
        PageQuery query = new PageQuery();
        query.setCursor(cursor);
        query.setSize(size);
        return query;
    }

    /**
     * 创建初始游标分页参数（无游标，用于第一页请求）
     *
     * @param size 每页记录数
     * @return 游标分页参数（cursor=null）
     */
    public static PageQuery firstCursorPage(int size) {
        PageQuery query = new PageQuery();
        query.setSize(size);
        query.setCursor(null);  // 显式设置为null，表示第一页
        return query;
    }
}
