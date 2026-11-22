package com.echo.controller;

import com.echo.dto.Result;
import com.echo.service.IStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;


/**
 * 数据统计管理控制器
 *
 * 提供热门演出排行、销售统计、用户活跃度等数据分析接口
 *
 * @author tulip
 */
@Tag(name = "数据统计管理")
@Slf4j
@Validated
@RestController
@RequestMapping("/statistics")
public class StatisticsController {

    @Resource
    private IStatisticsService statisticsService;

    @Operation(summary = "热门演出排行", description = "按支付订单数统计热门演出TopN，支持LBS距离排序（传入用户坐标后按距离排序）")
    @GetMapping("/hot-shows")
    public Result<?> hotShows(
            @Min(value = 1, message = "数量必须大于0")
            @Max(value = 50, message = "数量不能超过50")
            @Parameter(description = "返回数量", example = "10")
            @RequestParam(value = "limit", defaultValue = "10") Integer limit,

            @Parameter(description = "用户经度坐标（可选，用于距离排序）", example = "113.324520")
            @RequestParam(value = "x", required = false) Double x,

            @Parameter(description = "用户纬度坐标（可选，用于距离排序）", example = "23.106414")
            @RequestParam(value = "y", required = false) Double y) {

        log.debug("[Controller] 查询热门演出排行，limit:{}, 位置:({}, {})", limit, x, y);

        if (x != null && y != null) {
            return statisticsService.hotShows(limit, x, y);
        }

        return statisticsService.hotShows(limit);
    }

    @Operation(summary = "销售趋势统计", description = "按天统计最近N天的订单数和销售额，用于图表展示")
    @GetMapping("/sales-trend")
    public Result<?> salesTrend(
            @Min(value = 1, message = "天数必须大于0")
            @Max(value = 90, message = "天数不能超过90")
            @Parameter(description = "统计天数", example = "7")
            @RequestParam(value = "days", defaultValue = "7") Integer days) {

        log.debug("[Controller] 查询销售趋势，days:{}", days);
        return statisticsService.salesTrend(days);
    }

    @Operation(summary = "用户画像统计", description = "统计分析用户的性别、城市、年龄分布，用于运营决策")
    @GetMapping("/user-profile")
    public Result<?> userProfile() {
        log.debug("[Controller] 查询用户画像统计");
        return statisticsService.userProfile();
    }

    @Operation(summary = "仪表盘统计数据", description = "返回平台核心数据：演出数量、用户数、订单数、平均评分")
    @GetMapping("/dashboard")
    public Result<?> dashboard() {
        log.debug("[Controller] 查询仪表盘统计数据");
        return statisticsService.dashboard();
    }
}
