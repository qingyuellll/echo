package com.echo.controller;

import com.echo.dto.Result;
import com.echo.service.IShowTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;


/**
 * 演出类型管理控制器
 *
 * 提供演出类型列表查询接口，支持Redis缓存加速
 *
 * @author tulip
 */
@Tag(name = "演出类型管理")
@Slf4j
@Validated
@RestController
@RequestMapping("/show-type")
public class ShowTypeController {

    @Resource
    private IShowTypeService showTypeService;

    @Operation(summary = "查询演出类型列表", description = "获取所有演出类型，按排序字段升序排列（含Redis缓存）")
    @GetMapping("/list")
    public Result<?> queryTypeList() {
        log.debug("[Controller] 查询演出类型列表");
        return showTypeService.queryTypeList();
    }
}
