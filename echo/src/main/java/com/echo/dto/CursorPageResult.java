package com.echo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 游标分页响应格式
 * <p>
 * 专门用于游标分页（Cursor-based Pagination）场景的标准化返回，
 * 与传统分页的 {@link PageResult} 形成互补。
 * <p>
 * 设计特点：
 * <ul>
 *     <li>无总记录数：游标分页通常不返回总数（性能考虑）</li>
 *     <li>下一页游标：用于获取下一页数据</li>
 *     <li>是否有更多数据：控制前端是否显示"加载更多"按钮</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // Service层构建游标分页结果
 * List&lt;Show&gt; records = showMapper.selectList(wrapper);
 * String nextCursor = null;
 * boolean hasMore = false;
 *
 * if (records.size() == pageSize) {
 *     Show lastRecord = records.get(records.size() - 1);
 *     CursorCodec.CursorInfo cursorInfo = new CursorCodec.CursorInfo();
 *     cursorInfo.setLastId(lastRecord.getId());
 *     cursorInfo.setLastTime(lastRecord.getCreateTime().toString());
 *     nextCursor = CursorCodec.encode(cursorInfo);
 *     hasMore = true;
 * }
 *
 * return Result.ok(CursorPageResult.of(records, nextCursor, hasMore));
 * </pre>
 *
 * <h3>前端使用：</h3>
 * <pre>
 * const { records, nextCursor, hasMore } = response.data;
 *
 * // 追加到列表
 * this.list.push(...records);
 *
 * // 保存下一页游标
 * this.nextCursor = nextCursor;
 *
 * // 控制是否显示"加载更多"
 * this.showLoadMore = hasMore;
 * </pre>
 *
 * @author echo
 * @version 1.0
 * @see PageResult 传统分页响应
 * @see com.echo.utils.CursorCodec 游标编解码器
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CursorPageResult<T> {

    /**
     * 当前页数据列表
     */
    private List<T> records;

    /**
     * 下一页的游标（null表示没有更多数据）
     */
    private String nextCursor;

    /**
     * 是否有更多数据
     */
    private boolean hasMore;

    /**
     * 当前页实际返回的记录数
     */
    private int count;

    /**
     * 快速创建空结果
     */
    public static <T> CursorPageResult<T> empty() {
        return new CursorPageResult<>(
                Collections.emptyList(),
                null,
                false,
                0
        );
    }

    /**
     * 使用完整参数创建游标分页结果（工厂方法）
     *
     * @param records   当前页数据列表
     * @param nextCursor 下一页游标（null=没有更多数据）
     * @param hasMore    是否有更多数据
     * @return 游标分页结果对象
     */
    public static <T> CursorPageResult<T> of(List<T> records, String nextCursor, boolean hasMore) {
        return new CursorPageResult<>(
                records,
                nextCursor,
                hasMore,
                records != null ? records.size() : 0
        );
    }

    /**
     * 从传统分页结果转换（用于兼容层）
     * <p>
     * 注意：此方法会丢失游标信息，仅用于向后兼容场景
     *
     * @param pageResult 传统分页结果
     * @return 游标分页结果（nextCursor=null）
     */
    public static <T> CursorPageResult<T> fromPageResult(PageResult<T> pageResult) {
        return new CursorPageResult<>(
                pageResult.getRecords(),
                null,
                pageResult.hasNext(),
                pageResult.getRecords() != null ? pageResult.getRecords().size() : 0
        );
    }
}
