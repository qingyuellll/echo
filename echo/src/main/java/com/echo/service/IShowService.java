package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.dto.Result;
import com.echo.entity.Show;

/**
 * 演出服务接口
 *
 * @author tulip
 */
public interface IShowService extends IService<Show> {

    /**
     * 查询演出详情（支持用户端/管理端模式）
     * @param id 演出ID
     * @param userOnly true=仅查询上架演出（用户端），false=查询所有状态（管理端）
     */
    Result<?> queryById(Long id, boolean userOnly);

    /**
     * 按名称搜索演出（支持用户端/管理端模式）
     * @param name 演出名称（模糊匹配）
     * @param id 演出ID（精确匹配）
     * @param current 页码
     * @param pageSize 每页条数
     * @param sortBy 排序字段
     * @param sortOrder 排序方向
     * @param userOnly true=仅返回上架演出（用户端），false=返回所有状态（管理端）
     */
    Result<?> queryShowByName(String name, Long id, com.echo.dto.PageQuery pageQuery, boolean userOnly);

    Result<?> update(Show show);
    Result<?> listShows();

    /**
     * 按类型浏览演出（用户端）
     *
     * @param typeId    演出类型ID
     * @param pageQuery 分页参数（页码与每页条数）
     * @param x         用户经度（可选，传入则按距离排序）
     * @param y         用户纬度（可选，传入则按距离排序）
     * @return 分页的演出列表
     */
    Result<?> queryShowByType(Integer typeId, com.echo.dto.PageQuery pageQuery, Double x, Double y);
    Result<?> loadShowGeoData();
    Result<?> toggleFavorite(Long showId);
    Result<?> queryMyFavorites(Integer current);

    /**
     * 游标分页查询演出列表（适用于移动端无限滚动）
     *
     * @param pageQuery 分页参数（支持游标模式）
     * @return 游标分页结果
     */
    Result<?> scrollShows(com.echo.dto.PageQuery pageQuery);
}
