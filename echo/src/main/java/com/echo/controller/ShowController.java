package com.echo.controller;

import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.entity.Show;
import com.echo.service.IShowFollowService;
import com.echo.service.IShowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/**
 * 演出用户端控制器
 *
 * @author tulip
 */
@Slf4j
@Tag(name = "演出服务-用户端", description = "演出查询和用户交互接口")
@RestController
@RequestMapping("/show")
@Validated
public class ShowController {

    @Resource
    private IShowService showService;

    @Resource
    private IShowFollowService showFollowService;

    // ==================== 用户查询接口 ====================

    @Operation(summary = "按类型浏览演出", description = "分页查询指定类型的演出，支持LBS附近推荐")
    @GetMapping("/of/type")
    public Result<?> queryShowByType(
            @NotNull(message = "演出类型ID不能为空")
            @Min(value = 1, message = "演出类型ID必须大于0")
            @Parameter(description = "演出类型ID", required = true)
            @RequestParam("typeId") Integer typeId,

            @Valid
            @Parameter(description = "分页参数（current/size，兼容 pageSize 拼写）")
            com.echo.dto.PageQuery pageQuery,

            @Parameter(description = "用户经度（LBS附近搜索）")
            @RequestParam(value = "x", required = false) Double x,

            @Parameter(description = "用户纬度（LBS附近搜索）")
            @RequestParam(value = "y", required = false) Double y) {

        log.debug("[用户端] 按类型浏览演出 - typeId:{}, 页码:{}, 每页:{}, LBS({},{})",
                typeId, pageQuery.getCurrent(), pageQuery.getSize(), x, y);
        return showService.queryShowByType(typeId, pageQuery, x, y);
    }

    @Operation(summary = "搜索演出", description = "按名称模糊搜索或ID精确查询（仅返回上架演出）")
    @GetMapping("/of/name")
    public Result<?> queryShowByName(
            @Parameter(description = "演出名称（模糊匹配，可选）")
            @RequestParam(value = "name", required = false) String name,

            @Parameter(description = "演出ID（精确匹配，可选）")
            @RequestParam(value = "id", required = false) Long id,

            @Valid com.echo.dto.PageQuery pageQuery) {

        log.info("[用户端] 搜索演出（仅上架） - 名称:{}, ID:{}, 页码:{}", name, id, pageQuery.getCurrent());
        return showService.queryShowByName(name, id, pageQuery, true);
    }

    @Operation(summary = "快速搜索", description = "简化版搜索，仅传关键词（仅返回上架演出）")
    @GetMapping("/search")
    public Result<?> searchShows(
            @Parameter(description = "搜索关键词")
            @RequestParam(value = "keyword", required = false) String keyword,

            @Valid com.echo.dto.PageQuery pageQuery) {

        log.info("[用户端] 快速搜索（仅上架） - 关键词:{}", keyword);
        String searchName = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;
        com.echo.dto.PageQuery fixedPageQuery = com.echo.dto.PageQuery.of(
                pageQuery.getCurrent(), 20, "createTime", "desc");
        return showService.queryShowByName(searchName, null, fixedPageQuery, true);
    }

    @Operation(summary = "演出详情", description = "根据ID查看演出详细信息（仅上架演出可查看）")
    @GetMapping("/{id:\\d+}")
    public Result<?> queryShowDetail(
            @NotNull(message = "演出ID不能为空")
            @Min(value = 1, message = "演出ID必须大于0")
            @Parameter(description = "演出ID", required = true)
            @PathVariable("id") Long id) {

        log.debug("[用户端] 查看演出详情（仅上架） - ID:{}", id);
        return showService.queryById(id, true);
    }

    // ==================== 用户交互接口 ====================

    @Operation(summary = "收藏/取消收藏", description = "切换收藏状态")
    @PostMapping("/{id}/favorite")
    public Result<?> toggleFavorite(
            @NotNull(message = "演出ID不能为空")
            @Min(value = 1, message = "演出ID必须大于0")
            @PathVariable("id") Long showId) {

        log.info("[用户端] 切换收藏 - 演出ID:{}", showId);
        return showService.toggleFavorite(showId);
    }

    @Operation(summary = "我的收藏", description = "查看当前用户的收藏列表")
    @GetMapping("/favorite/my")
    public Result<?> queryMyFavorites(
            @Min(value = 1, message = "页码必须大于0")
            @RequestParam(value = "current", defaultValue = "1") Integer current) {

        log.debug("[用户端] 我的收藏列表 - 页码:{}", current);
        return showService.queryMyFavorites(current);
    }

    @Operation(summary = "演出滚动列表（游标分页）", description = "使用游标分页获取演出列表，适用于移动端无限滚动场景，性能优于传统分页")
    @GetMapping("/scroll")
    public Result<?> scrollShows(@Valid com.echo.dto.PageQuery pageQuery) {
        log.info("[用户端] 游标分页查询演出 - size:{}, cursorMode:{}", 
                pageQuery.getSize(), pageQuery.isCursorMode());
        return showService.scrollShows(pageQuery);
    }

    @Operation(summary = "关注演出", description = "关注后可接收上架通知")
    @PostMapping("/{id}/follow")
    public Result<?> followShow(
            @NotNull(message = "演出ID不能为空")
            @Min(value = 1, message = "演出ID必须大于0")
            @PathVariable("id") Long showId) {

        log.info("[用户端] 关注演出 - 演出ID:{}", showId);
        return showFollowService.followShow(showId);
    }

    @Operation(summary = "取消关注", description = "取消接收通知")
    @DeleteMapping("/{id}/follow")
    public Result<?> unfollowShow(
            @NotNull(message = "演出ID不能为空")
            @Min(value = 1, message = "演出ID必须大于0")
            @PathVariable("id") Long showId) {

        log.info("[用户端] 取消关注 - 演出ID:{}", showId);
        return showFollowService.unfollowShow(showId);
    }

    @Operation(summary = "检查关注状态", description = "查询是否已关注该演出")
    @GetMapping("/{id}/follow")
    public Result<?> checkFollowStatus(
            @NotNull(message = "演出ID不能为空")
            @Min(value = 1, message = "演出ID必须大于0")
            @PathVariable("id") Long showId) {

        log.debug("[用户端] 检查关注状态 - 演出ID:{}", showId);
        return showFollowService.isFollowed(showId);
    }

    @Operation(summary = "我的关注", description = "查看当前用户的关注列表")
    @GetMapping("/follow/my")
    public Result<?> queryMyFollows(
            @Min(value = 1, message = "页码必须大于0")
            @RequestParam(value = "current", defaultValue = "1") Integer current) {

        log.debug("[用户端] 我的关注列表 - 页码:{}", current);
        return showFollowService.queryMyFollowedShows(current);
    }
}
