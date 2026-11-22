package com.echo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一分页响应格式
 * <p>
 * 用于所有列表查询接口的标准化返回，确保前后端数据结构一致。
 * <p>
 * 使用示例：
 * <pre>
 * PageResult<Show> pageResult = new PageResult<>(
 *     showList,      // records - 当前页数据
 *     100L,          // total   - 总记录数
 *     1,             // current - 当前页码
 *     20             // pageSize- 每页条数
 * );
 * return Result.ok(pageResult);
 * </pre>
 *
 * 前端使用：
 * <pre>
 * const { records, total, current, pageSize } = response.data
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /**
     * 当前页数据列表
     */
    private List<T> records;

    /**
     * 总记录数（非总页数）
     */
    private Long total;

    /**
     * 当前页码（从1开始）
     */
    private Integer current;

    /**
     * 每页条数
     */
    private Integer pageSize;

    /**
     * 总页数（计算字段，不存储）
     */
    public Integer getPages() {
        if (pageSize == null || pageSize <= 0 || total == null) {
            return 0;
        }
        return (int) Math.ceil((double) total / pageSize);
    }

    /**
     * 是否有上一页
     */
    public boolean hasPrevious() {
        return current != null && current > 1;
    }

    /**
     * 是否有下一页
     */
    public boolean hasNext() {
        if (current == null || pageSize == null || total == null) {
            return false;
        }
        return (long) current * pageSize < total;
    }

    /**
     * 快速创建空结果
     */
    public static <T> PageResult<T> empty(Integer current, Integer pageSize) {
        return new PageResult<>(java.util.Collections.emptyList(), 0L, current, pageSize);
    }

    /**
     * 从MyBatis-Plus的IPage转换
     */
    public static <T> PageResult<T> from(com.baomidou.mybatisplus.core.metadata.IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(),
                (int) page.getCurrent(), (int) page.getSize());
    }

    /**
     * 使用完整参数创建分页结果（工厂方法）
     *
     * @param records  当前页数据列表
     * @param total    总记录数
     * @param current  当前页码
     * @param pageSize 每页条数
     * @return 分页结果对象
     */
    public static <T> PageResult<T> of(List<T> records, long total, int current, int pageSize) {
        return new PageResult<>(records, total, current, pageSize);
    }
}
