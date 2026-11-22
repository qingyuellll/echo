package com.echo.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.echo.annotation.RequireRole;
import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.entity.Ticket;
import com.echo.enums.AdminRoleLevel;
import com.echo.service.ITicketService;
import com.echo.utils.PermissionUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping({"/admin/tickets", "/admin/ticket"})
@Tag(name = "管理端-票种管理（含权限控制）")
@Validated
public class AdminTicketController {

    @Resource
    private ITicketService ticketService;

    @Resource
    private com.echo.service.ISeatService seatService;

    @Resource
    private com.echo.service.ISeckillTicketService seckillTicketService;

    @Resource
    private com.echo.service.ITicketOrderService ticketOrderService;

    @Resource
    private com.echo.service.IShowService showService;

    @Resource
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Resource
    private org.springframework.context.ApplicationContext applicationContext;

    @Operation(summary = "获取票种列表", description = "分页查询票种列表，支持按演出ID、关键词、状态等条件筛选，返回已售数量和剩余库存")
    @GetMapping("/list")
    public Result<?> getTicketList(
            @Parameter(description = "当前页码（从1开始）", example = "1")
            @RequestParam(value = "current", defaultValue = "1") Integer current,

            @Parameter(description = "每页记录数（1-100）", example = "10")
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,

            @Parameter(description = "演出ID筛选")
            @RequestParam(value = "showId", required = false) Long showId,

            @Parameter(description = "关键词搜索（票种名称/副标题）")
            @RequestParam(value = "keyword", required = false) String keyword,

            @Parameter(description = "状态筛选：0=下架, 1=上架")
            @RequestParam(value = "status", required = false) Integer status,

            @Parameter(description = "排序字段（price/stock/createTime）")
            @RequestParam(value = "sortBy", required = false) String sortBy,

            @Parameter(description = "排序方向：asc/desc")
            @RequestParam(value = "sortOrder", required = false) String sortOrder) {

        log.info("[AdminTicket] [查询] 获取票种列表, params: current={}, pageSize={}, showId={}, keyword={}, status={}",
                current, pageSize, showId, keyword, status);

        QueryWrapper<Ticket> queryWrapper = buildQueryWrapper(showId, keyword, status, sortBy, sortOrder);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Ticket> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, pageSize);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Ticket> ticketPage =
                ticketService.page(page, queryWrapper);

        List<Ticket> tickets = ticketPage.getRecords();

        if (tickets.isEmpty()) {
            Map<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("records", Collections.emptyList());
            emptyResult.put("total", 0L);
            emptyResult.put("current", current);
            emptyResult.put("size", pageSize);
            return Result.ok(emptyResult);
        }

        Set<Long> ticketIds = tickets.stream()
                .map(Ticket::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> showIds = tickets.stream()
                .map(Ticket::getShowId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        final Map<Long, Integer> soldCountMap = batchGetSoldCount(ticketIds);
        final Map<Long, com.echo.entity.Show> showMap = batchGetShows(showIds);
        final Map<Long, com.echo.entity.SeckillTicket> seckillMap = batchGetSeckillTickets(ticketIds);
        final Map<Long, Integer> seatCountMap = batchGetSeatCounts(ticketIds);
        final Map<Long, Integer> availableSeatMap = batchGetAvailableSeatCounts(ticketIds);

        List<Map<String, Object>> enrichedRecords = tickets.stream().map(ticket -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", ticket.getId());
            map.put("showId", ticket.getShowId());
            map.put("title", ticket.getTitle());
            map.put("subTitle", ticket.getSubTitle());
            map.put("rules", ticket.getRules());
            map.put("type", ticket.getType());
            map.put("payValue", ticket.getPayValue());
            map.put("actualValue", ticket.getActualValue());
            map.put("stock", ticket.getStock());
            map.put("status", ticket.getStatus());
            map.put("locked", ticket.getLocked() != null ? ticket.getLocked() : 0);
            map.put("hasSeats", ticket.getHasSeats() != null ? ticket.getHasSeats() : false);
            map.put("scheduledPublishTime", ticket.getScheduledPublishTime());
            map.put("createTime", ticket.getCreateTime());
            map.put("updateTime", ticket.getUpdateTime());

            // ===== 库存语义说明（关键）=====
            // tb_ticket.stock / tb_seckill_ticket.stock 都是"当前剩余"：
            //   普通票下单预扣、取消/退款回补；秒杀票支付时扣减、退款回补。
            // 因此：
            //   普通票：总库存 = ticket.totalStock（初始值），剩余 = stock，已售 = 总-剩余
            //   秒杀票：无 total_stock 列，总库存 = stock(剩余) + 成交订单数(status 2/3)，
            //           该公式恒等回创建时的原始总库存（退款回补 stock、退款单不计成交）
            int remainingStock = ticket.getStock() != null ? ticket.getStock() : 0;
            int soldCount = soldCountMap.getOrDefault(ticket.getId(), 0);
            int totalStock = (ticket.getTotalStock() != null && ticket.getTotalStock() > 0)
                    ? ticket.getTotalStock()
                    : remainingStock + soldCount;
            int soldTotal = Math.max(0, totalStock - remainingStock);

            map.put("totalStock", totalStock);
            map.put("soldCount", soldTotal);
            map.put("remainingStock", remainingStock);

            com.echo.entity.Show show = showMap.get(ticket.getShowId());
            map.put("showName", show != null ? show.getName() : "未知演出");

            com.echo.entity.SeckillTicket seckill = seckillMap.get(ticket.getId());
            if (seckill != null) {
                // 秒杀表 stock = 当前剩余（支付扣减/退款回补），总库存 = 剩余 + 成交订单数
                int seckillRemainingStock = seckill.getStock() != null ? seckill.getStock() : 0;
                int seckillSoldCount = soldCountMap.getOrDefault(ticket.getId(), 0);
                int seckillTotalStock = seckillRemainingStock + seckillSoldCount;

                // Redis 售卖计数器理论上与 DB 剩余同步（下单Lua扣减/取消回补/支付扣DB/退款双回补），
                // 出现偏离说明历史数据异常，展示以 DB 为准并记录对账告警
                if (ticket.getType() != null && ticket.getType() == 1) {
                    try {
                        String redisStockStr = stringRedisTemplate.opsForValue()
                                .get("seckill:stock:" + ticket.getId());
                        if (redisStockStr != null && !redisStockStr.isEmpty()
                                && Integer.parseInt(redisStockStr) != seckillRemainingStock) {
                            log.warn("[AdminTicket] 秒杀Redis计数与DB剩余不一致 - ticketId:{}, Redis:{}, DB:{}, 展示以DB为准",
                                    ticket.getId(), redisStockStr, seckillRemainingStock);
                        }
                    } catch (Exception ignored) {
                    }
                }

                map.put("seckillStock", seckillRemainingStock);
                map.put("seckillTotalStock", seckillTotalStock);
                map.put("seckillSoldCount", seckillSoldCount);
                map.put("beginTime", seckill.getBeginTime());
                map.put("endTime", seckill.getEndTime());
                map.put("isSeckill", true);
            } else {
                map.put("isSeckill", false);
                map.put("seckillStock", 0);
                map.put("seckillTotalStock", 0);
                map.put("seckillSoldCount", 0);
                map.put("beginTime", null);
                map.put("endTime", null);
            }

            int seatCount = seatCountMap.getOrDefault(ticket.getId(), 0);
            map.put("seatCount", seatCount);

            if (seatCount > 0) {
                map.put("availableSeats", availableSeatMap.getOrDefault(ticket.getId(), 0));
            }

            return map;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("records", enrichedRecords);
        result.put("total", ticketPage.getTotal());
        result.put("current", ticketPage.getCurrent());
        result.put("size", ticketPage.getSize());

        return Result.ok(result);
    }

    private Map<Long, Integer> batchGetSoldCount(Set<Long> ticketIds) {
        if (ticketIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<com.echo.entity.TicketOrder> orders = ticketOrderService.list(
                new QueryWrapper<com.echo.entity.TicketOrder>()
                        .in("ticket_id", ticketIds)
                        .in("status", 2, 3)
                        .select("ticket_id", "id")
        );

        return orders.stream()
                .collect(Collectors.groupingBy(
                        com.echo.entity.TicketOrder::getTicketId,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
    }

    private Map<Long, com.echo.entity.Show> batchGetShows(Set<Long> showIds) {
        if (showIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return showService.listByIds(showIds)
                .stream()
                .collect(Collectors.toMap(com.echo.entity.Show::getId, s -> s, (a, b) -> a));
    }

    private Map<Long, com.echo.entity.SeckillTicket> batchGetSeckillTickets(Set<Long> ticketIds) {
        if (ticketIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return seckillTicketService.listByIds(ticketIds)
                .stream()
                .collect(Collectors.toMap(
                        com.echo.entity.SeckillTicket::getTicketId,
                        s -> s,
                        (a, b) -> a
                ));
    }

    private Map<Long, Integer> batchGetSeatCounts(Set<Long> ticketIds) {
        if (ticketIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<com.echo.entity.Seat> seats = seatService.list(
                new QueryWrapper<com.echo.entity.Seat>()
                        .in("ticket_id", ticketIds)
                        .select("ticket_id", "id")
        );

        return seats.stream()
                .collect(Collectors.groupingBy(
                        com.echo.entity.Seat::getTicketId,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
    }

    private Map<Long, Integer> batchGetAvailableSeatCounts(Set<Long> ticketIds) {
        if (ticketIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<com.echo.entity.Seat> seats = seatService.list(
                new QueryWrapper<com.echo.entity.Seat>()
                        .in("ticket_id", ticketIds)
                        .eq("status", 0)
                        .select("ticket_id", "id")
        );

        return seats.stream()
                .collect(Collectors.groupingBy(
                        com.echo.entity.Seat::getTicketId,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
    }

    @Operation(summary = "新增普通票种", description = "创建新的普通票种，默认为下架状态（需手动上架）")
    @PostMapping
    public Result<?> createTicket(
            @Valid
            @Parameter(description = "票种信息", required = true)
            @RequestBody Ticket ticket) {

        log.info("[AdminTicket] [新增] 开始创建票种, title:{}, showId:{}, 操作者:{}",
                ticket.getTitle(), ticket.getShowId(), PermissionUtils.getCurrentAdminName());

        if (ticket.getShowId() == null) {
            return Result.fail(AppMessages.TICKET_SHOW_ID_REQUIRED);
        }

        if (ticket.getTitle() == null || ticket.getTitle().trim().isEmpty()) {
            return Result.fail(AppMessages.TICKET_TITLE_EMPTY);
        }

        if (ticket.getScheduledPublishTime() != null) {
            ticket.setStatus(0);
            log.info("[AdminTicket] [新增] 设置定时上架 - ticketId:{}, scheduledPublishTime:{}",
                    ticket.getTitle(), ticket.getScheduledPublishTime());
        } else {
            ticket.setStatus(1);
            log.info("[AdminTicket] [新增] 立即上架 - ticketId:{}", ticket.getTitle());
        }

        boolean success = ticketService.save(ticket);

        if (success) {
            log.info("[AdminTicket] [成功] 票种创建成功 - id:{}, title:{}",
                    ticket.getId(), ticket.getTitle());

            if (ticket.getType() != null && ticket.getType() == 1) {
                try {
                    com.echo.entity.SeckillTicket seckillConfig = new com.echo.entity.SeckillTicket();
                    seckillConfig.setTicketId(ticket.getId());

                    if (ticket.getSeckillStock() != null && ticket.getSeckillStock() >= 0) {
                        seckillConfig.setStock(ticket.getSeckillStock());
                        log.info("[AdminTicket] [创建-秒杀] 设置秒杀库存 - ticketId:{}, stock:{}",
                                ticket.getId(), ticket.getSeckillStock());
                    }

                    if (ticket.getBeginTime() != null) {
                        seckillConfig.setBeginTime(ticket.getBeginTime());
                        log.info("[AdminTicket] [创建-秒杀] 设置秒杀开始时间 - ticketId:{}, beginTime:{}",
                                ticket.getId(), ticket.getBeginTime());
                    }

                    if (ticket.getEndTime() != null) {
                        seckillConfig.setEndTime(ticket.getEndTime());
                        log.info("[AdminTicket] [创建-秒杀] 设置秒杀结束时间 - ticketId:{}, endTime:{}",
                                ticket.getId(), ticket.getEndTime());
                    }

                    seckillTicketService.save(seckillConfig);
                    log.info("[AdminTicket] [创建-秒杀] 秒杀票信息已写入数据库 - ticketId:{}", ticket.getId());

                    if (seckillConfig.getStock() != null) {
                        ticketService.syncSeckillStockToRedis(ticket.getId(), seckillConfig.getStock());
                        log.info("[AdminTicket] [创建-秒杀] Redis库存已初始化 - ticketId:{}, stock:{}",
                                ticket.getId(), seckillConfig.getStock());
                    }
                } catch (Exception e) {
                    log.error("[AdminTicket] [创建-秒杀] 秒杀票数据保存失败 - ticketId:{}, error:{}",
                            ticket.getId(), e.getMessage(), e);
                }
            }

            try {
                ticketService.refreshTicketCache(ticket.getShowId());
                log.info("[AdminTicket] 已刷新演出票种缓存 - showId:{}", ticket.getShowId());
            } catch (Exception e) {
                log.warn("[AdminTicket] 刷新缓存失败 - error:{}", e.getMessage());
            }

            return Result.ok(ticket.getId());
        } else {
            log.error("[AdminTicket] [失败] 票种创建失败");
            return Result.fail(AppMessages.TICKET_CREATE_FAIL);
        }
    }

    @Operation(summary = "编辑票种信息", description = "更新票种的基本信息，如标题、价格、库存等")
    @PutMapping
    public Result<?> updateTicket(
            @Valid
            @Parameter(description = "更新的票种信息", required = true)
            @RequestBody Ticket ticket) {

        log.info("[AdminTicket] [编辑] 编辑票种请求, ticketId:{}, title:{}, 操作者:{}",
                ticket.getId(), ticket.getTitle(), PermissionUtils.getCurrentAdminName());

        if (ticket.getId() == null || ticket.getId() <= 0) {
            return Result.fail(AppMessages.TICKET_NOT_EXIST);
        }

        Ticket existing = ticketService.getById(ticket.getId());

        if (existing == null) {
            return Result.fail(AppMessages.TICKET_NOT_EXIST);
        }

        if (ticket.getStatus() == null) {
            if (ticket.getScheduledPublishTime() != null) {
                ticket.setStatus(0);
                log.info("[AdminTicket] [编辑] 设置定时上架 - ticketId:{}, scheduledPublishTime:{}",
                        ticket.getId(), ticket.getScheduledPublishTime());
            } else {
                ticket.setStatus(1);
                log.info("[AdminTicket] [编辑] 立即上架 - ticketId:{}", ticket.getId());
            }
        }

        boolean success = ticketService.updateById(ticket);

        if (success) {
            log.info("[AdminTicket] [成功] 票种更新成功 - id:{}, title:{}",
                    ticket.getId(), ticket.getTitle());

            try {
                if (ticket.getType() != null && ticket.getType() == 1) {
                    com.echo.entity.SeckillTicket seckillConfig = seckillTicketService.getById(ticket.getId());

                    boolean isNewRecord = false;

                    if (seckillConfig == null) {
                        seckillConfig = new com.echo.entity.SeckillTicket();
                        seckillConfig.setTicketId(ticket.getId());
                        isNewRecord = true;
                        log.info("[AdminTicket] [编辑-秒杀] 创建新的秒杀票记录 - ticketId:{}", ticket.getId());
                    }

                    boolean hasSeckillData = false;

                    if (ticket.getSeckillStock() != null && ticket.getSeckillStock() >= 0) {
                        seckillConfig.setStock(ticket.getSeckillStock());
                        hasSeckillData = true;
                        log.info("[AdminTicket] [编辑-秒杀] 设置秒杀库存 - ticketId:{}, stock:{}",
                                ticket.getId(), ticket.getSeckillStock());
                    }

                    if (ticket.getBeginTime() != null) {
                        seckillConfig.setBeginTime(ticket.getBeginTime());
                        hasSeckillData = true;
                        log.info("[AdminTicket] [编辑-秒杀] 设置秒杀开始时间 - ticketId:{}, beginTime:{}",
                                ticket.getId(), ticket.getBeginTime());
                    }

                    if (ticket.getEndTime() != null) {
                        seckillConfig.setEndTime(ticket.getEndTime());
                        hasSeckillData = true;
                        log.info("[AdminTicket] [编辑-秒杀] 设置秒杀结束时间 - ticketId:{}, endTime:{}",
                                ticket.getId(), ticket.getEndTime());
                    }

                    if (hasSeckillData) {
                        if (isNewRecord) {
                            seckillTicketService.save(seckillConfig);
                            log.info("[AdminTicket] [编辑-秒杀] 新秒杀票记录已写入数据库 - ticketId:{}", ticket.getId());
                        } else {
                            seckillTicketService.updateById(seckillConfig);
                            log.info("[AdminTicket] [编辑-秒杀] 秒杀票信息已更新到数据库 - ticketId:{}", ticket.getId());
                        }

                        if (seckillConfig.getStock() != null) {
                            ticketService.syncSeckillStockToRedis(ticket.getId(), seckillConfig.getStock());
                            log.info("[AdminTicket] [编辑-秒杀] Redis库存已同步 - ticketId:{}, stock:{}",
                                    ticket.getId(), seckillConfig.getStock());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[AdminTicket] [编辑-秒杀] 秒杀票数据同步失败 - ticketId:{}, error:{}",
                        ticket.getId(), e.getMessage(), e);
            }

            try {
                ticketService.refreshTicketCache(existing.getShowId());
                log.info("[AdminTicket] 已刷新演出票种缓存 - showId:{}", existing.getShowId());
            } catch (Exception e) {
                log.warn("[AdminTicket] 刷新缓存失败 - error:{}", e.getMessage());
            }

            return Result.ok(AppMessages.format(AppMessages.TICKET_UPDATE_SUCCESS, ticket.getId()));
        } else {
            log.error("[AdminTicket] [失败] 票种更新失败 - id:{}", ticket.getId());
            return Result.fail(AppMessages.TICKET_UPDATE_FAIL);
        }
    }

    @RequireRole(AdminRoleLevel.SUPER_ADMIN)
    @Operation(summary = "删除票种", description = "删除指定票种（需先下架该票种，且无未完成订单）")
    @DeleteMapping("/{id}")
    public Result<?> deleteTicket(
            @NotNull(message = "票种ID不能为空")
            @Min(value = 1, message = "票种ID必须大于0")
            @Parameter(description = "票种ID", required = true)
            @PathVariable("id") Long id) {

        log.info("[AdminTicket] [删除] 删除票种请求, ticketId:{}, 操作者:{}",
                id, PermissionUtils.getCurrentAdminName());

        Ticket existing = ticketService.getById(id);

        if (existing == null) {
            return Result.fail(AppMessages.TICKET_NOT_EXIST);
        }

        boolean success = ticketService.removeById(id);

        if (success) {
            log.info("[AdminTicket] [成功] 票种已删除 - id:{}, title:{}", id, existing.getTitle());

            if (existing.getType() != null && existing.getType() == 1) {
                ticketService.clearSeckillStockFromRedis(id);
            }

            try {
                ticketService.refreshTicketCache(existing.getShowId());
                log.info("[AdminTicket] 已刷新演出票种缓存 - showId:{}", existing.getShowId());
            } catch (Exception e) {
                log.warn("[AdminTicket] 刷新缓存失败 - error:{}", e.getMessage());
            }

            return Result.ok(AppMessages.format(AppMessages.TICKET_DELETE_SUCCESS, id));
        } else {
            log.error("[AdminTicket] [失败] 票种删除失败 - id:{}", id);
            return Result.fail(AppMessages.TICKET_DELETE_FAIL);
        }
    }

    @RequireRole(AdminRoleLevel.SUPER_ADMIN)
    @Operation(summary = "上架/下架票种", description = "切换票种的售卖状态（上架/下架）")
    @PutMapping("/{id}/status")
    public Result<?> toggleTicketStatus(
            @NotNull(message = "票种ID不能为空")
            @Min(value = 1, message = "票种ID必须大于0")
            @Parameter(description = "票种ID", required = true)
            @PathVariable("id") Long id,

            @NotNull(message = "状态值不能为空")
            @Min(value = 0)
            @Max(value = 1)
            @Parameter(description = "状态：0=下架, 1=上架", required = true)
            @RequestParam("status") Integer status) {

        String action = Integer.valueOf(1).equals(status) ? "上架" : "下架";
        log.info("[AdminTicket] [{}] 票种状态变更请求, ticketId:{}, 目标状态:{}, 操作者:{}",
                action, id, status, PermissionUtils.getCurrentAdminName());

        Ticket existing = ticketService.getById(id);

        if (existing == null) {
            return Result.fail(AppMessages.TICKET_NOT_EXIST);
        }

        if (existing.getStatus() != null && existing.getStatus().equals(status)) {
            log.warn("[AdminTicket] [跳过] 票种已经是目标状态 - ticketId:{}, 当前状态:{}", id, status);
            return Result.fail(Integer.valueOf(1).equals(status) ? AppMessages.TICKET_ALREADY_ONLINE : AppMessages.TICKET_ALREADY_OFFLINE);
        }

        existing.setStatus(status);

        boolean success = ticketService.updateById(existing);

        if (success) {
            log.info("[AdminTicket] [成功] 票种{}成功 - id:{}, title:{}", action, id, existing.getTitle());

            try {
                ticketService.refreshTicketCache(existing.getShowId());
                log.info("[AdminTicket] 已刷新演出票种缓存 - showId:{}", existing.getShowId());
            } catch (Exception e) {
                log.warn("[AdminTicket] 刷新缓存失败 - error:{}", e.getMessage());
            }

            return Result.ok(Integer.valueOf(1).equals(status) ? AppMessages.TICKET_ONLINE_SUCCESS : AppMessages.TICKET_OFFLINE_SUCCESS);
        } else {
            log.error("[AdminTicket] [失败] 票种{}失败 - id:{}", action, id);
            return Result.fail(AppMessages.TICKET_STATUS_CHANGE_FAIL);
        }
    }

    @RequireRole(AdminRoleLevel.SUPER_ADMIN)
    @Operation(summary = "调整库存数量", description = "调整票种的库存数量")
    @PutMapping("/{id}/stock")
    public Result<?> adjustStock(
            @NotNull(message = "票种ID不能为空")
            @Min(value = 1, message = "票种ID必须大于0")
            @Parameter(description = "票种ID", required = true)
            @PathVariable("id") Long id,

            @NotNull(message = "库存值不能为空")
            @Min(value = 0, message = "库存不能为负数")
            @Parameter(description = "新的库存数量", required = true)
            @RequestParam("stock") Integer stock) {

        log.info("[AdminTicket] [库存] 调整库存请求, ticketId:{}, newStock:{}, 操作者:{}",
                id, stock, PermissionUtils.getCurrentAdminName());

        Ticket existing = ticketService.getById(id);

        if (existing == null) {
            return Result.fail(AppMessages.TICKET_NOT_EXIST);
        }

        int oldStock = existing.getStock() != null ? existing.getStock() : 0;
        existing.setStock(stock);

        boolean success = ticketService.updateById(existing);

        if (success) {
            log.info("[AdminTicket] [成功] 库存调整成功 - ticketId:{}, oldStock:{}, newStock:{}", id, oldStock, stock);

            if (existing.getType() != null && existing.getType() == 1) {
                ticketService.syncSeckillStockToRedis(id, stock);
            }

            try {
                ticketService.refreshTicketCache(existing.getShowId());
                log.info("[AdminTicket] 已刷新演出票种缓存 - showId:{}", existing.getShowId());
            } catch (Exception e) {
                log.warn("[AdminTicket] 刷新缓存失败 - error:{}", e.getMessage());
            }

            return Result.ok(AppMessages.format(AppMessages.TICKET_STOCK_ADJUST_SUCCESS, oldStock, stock));
        } else {
            log.error("[AdminTicket] [失败] 库存调整失败 - ticketId:{}", id);
            return Result.fail(AppMessages.TICKET_STOCK_ADJUST_FAIL);
        }
    }

    @RequireRole(AdminRoleLevel.SUPER_ADMIN)
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "批量生成座位", description = "为指定票种批量生成座位（事务性原子操作）")
    @PostMapping("/{ticketId}/seats/batch")
    public Result<?> batchCreateSeats(
            @NotNull(message = "票种ID不能为空")
            @Min(value = 1, message = "票种ID必须大于0")
            @PathVariable("ticketId") Long ticketId,

            @Parameter(description = "座位配置信息", required = true)
            @RequestBody java.util.Map<String, Object> seatConfig) {

        log.info("[AdminTicket] [座位] 批量生成座位请求, ticketId:{}, 操作者:{}",
                ticketId, PermissionUtils.getCurrentAdminName());

        Ticket ticket = ticketService.getById(ticketId);

        if (ticket == null) {
            return Result.fail(AppMessages.TICKET_NOT_EXIST);
        }

        java.util.List<java.util.Map<String, Object>> areas =
                (java.util.List<java.util.Map<String, Object>>) seatConfig.get("areas");

        if (areas == null || areas.isEmpty()) {
            return Result.fail(AppMessages.SEAT_AREA_CONFIG_EMPTY);
        }

        int totalSeats = 0;

        for (java.util.Map<String, Object> area : areas) {
            String areaName = (String) area.get("name");
            int rows = ((Number) area.get("rows")).intValue();
            int cols = ((Number) area.get("cols")).intValue();

            totalSeats += rows * cols;

            for (int row = 1; row <= rows; row++) {
                for (int col = 1; col <= cols; col++) {
                    com.echo.entity.Seat seat = new com.echo.entity.Seat();
                    seat.setTicketId(ticketId);
                    seat.setArea(areaName);
                    seat.setRowNum(row);
                    seat.setColNum(col);
                    seat.setStatus(0);

                    if (!seatService.save(seat)) {
                        throw new RuntimeException("座位保存失败");
                    }
                }
            }
        }

        ticket.setHasSeats(true);
        ticket.setTotalStock(totalSeats);
        ticket.setStock(totalSeats);
        ticketService.updateById(ticket);

        if (ticket.getType() != null && ticket.getType() == 1) {
            ticketService.syncSeckillStockToRedis(ticketId, totalSeats);
        }

        try {
            ticketService.refreshTicketCache(ticket.getShowId());
            log.info("[AdminTicket] 已刷新演出票种缓存 - showId:{}（确保前端刷新后能看到最新座位数）", ticket.getShowId());
        } catch (Exception e) {
            log.warn("[AdminTicket] 刷新缓存失败 - error:{}", e.getMessage());
        }

        log.info("[AdminTicket] [成功] 批量生成座位完成 - ticketId:{}, 总座位数:{}, 库存已自动同步更新", ticketId, totalSeats);

        return Result.ok(java.util.Map.of(
                "totalSeats", totalSeats,
                "ticketId", ticketId,
                "message", String.format("成功生成 %d 个座位，库存已自动同步。刷新票种列表后可查看最新座位数。", totalSeats)
        ));
    }

    @Operation(summary = "获取票种座位列表", description = "查询指定票种的所有座位信息（含状态）")
    @GetMapping("/{ticketId}/seats")
    public Result<?> getSeatList(
            @NotNull(message = "票种ID不能为空")
            @Min(value = 1, message = "票种ID必须大于0")
            @PathVariable("ticketId") Long ticketId,

            @RequestParam(value = "status", required = false) Integer status) {

        log.info("[AdminTicket] [座位列表] 查询座位列表, ticketId:{}, status:{}", ticketId, status);

        QueryWrapper<com.echo.entity.Seat> queryWrapper = new QueryWrapper<com.echo.entity.Seat>()
                .eq("ticket_id", ticketId);

        if (status != null) {
            queryWrapper.eq("status", status);
        }

        queryWrapper.orderByAsc("id");

        List<com.echo.entity.Seat> seats = seatService.list(queryWrapper);

        if (seats == null || seats.isEmpty()) {
            log.info("[AdminTicket] [座位列表] 该票种暂无座位数据 - ticketId:{}", ticketId);
            return Result.ok(java.util.Map.of(
                    "records", java.util.Collections.emptyList(),
                    "total", 0,
                    "ticketId", ticketId,
                    "message", "该票种暂无座位数据"
            ));
        }

        List<java.util.Map<String, Object>> seatRecords = seats.stream().map(seat -> {
            java.util.Map<String, Object> seatMap = new java.util.HashMap<>();
            seatMap.put("id", seat.getId());
            seatMap.put("rowNum", seat.getRowNum());
            seatMap.put("colNum", seat.getColNum());
            seatMap.put("areaName", seat.getArea());
            seatMap.put("status", seat.getStatus());
            seatMap.put("preOrderId", seat.getPreOrderId());
            return seatMap;
        }).collect(java.util.stream.Collectors.toList());

        log.info("[AdminTicket] [座位列表] 查询成功 - ticketId:{}, 座位数:{}", ticketId, seats.size());

        return Result.ok(java.util.Map.of(
                "records", seatRecords,
                "total", seats.size(),
                "ticketId", ticketId
        ));
    }

    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "创建带座位的票种", description = "创建票种并生成座位（事务性原子操作）")
    @PostMapping("/with-seats")
    public Result<?> createTicketWithSeats(
            @Parameter(description = "包含票种和座位配置的数据", required = true)
            @Valid @RequestBody com.echo.dto.TicketWithSeatsDTO dto) {

        log.info("[AdminTicket] [新增+座位] 创建带座位的票种, 操作者:{}",
                PermissionUtils.getCurrentAdminName());

        Ticket ticket = dto.getTicket();
        
        if (ticket.getTitle() != null) {
            log.info("[AdminTicket] [新增+座位] 票种名称: {}", ticket.getTitle());
        }

        if (ticket.getType() != null) {
            log.info("[AdminTicket] [新增+座位] 票种类型: {} ({})", 
                ticket.getType(), ticket.getType() == 1 ? "秒杀票" : "普通票");
        } else {
            ticket.setType(0);
            log.warn("[AdminTicket] [新增+座位] 未提供票种类型，默认设置为普通票(type=0)");
        }

        if (ticket.getScheduledPublishTime() != null) {
            if (ticket.getScheduledPublishTime().isBefore(java.time.LocalDateTime.now()) ||
                ticket.getScheduledPublishTime().isEqual(java.time.LocalDateTime.now())) {
                ticket.setStatus(1);
                log.info("[AdminTicket] [新增+座位] 定时发布时间已过或当前，立即上架");
            } else {
                ticket.setStatus(0);
                log.info("[AdminTicket] [新增+座位] 设置定时发布: {}", ticket.getScheduledPublishTime());
            }
        } else {
            ticket.setStatus(1);
            log.info("[AdminTicket] [新增+座位] 未设置定时发布时间，立即上架");
        }

        boolean saved = ticketService.save(ticket);

        if (!saved) {
            log.error("[AdminTicket] [失败] 带座位票种创建失败");
            return Result.fail(AppMessages.TICKET_CREATE_FAIL);
        }

        log.info("[AdminTicket] [成功] 票种基础信息创建成功 - id:{}, title:{}, type:{} ({})",
                ticket.getId(), ticket.getTitle(), ticket.getType(),
                ticket.getType() != null && ticket.getType() == 1 ? "秒杀票" : "普通票");

        com.echo.dto.SeatBatchCreateDTO seatConfig = dto.getSeatConfig();

        if (seatConfig != null && Boolean.TRUE.equals(ticket.getHasSeats()) &&
            seatConfig.getAreas() != null && !seatConfig.getAreas().isEmpty()) {

            int totalSeats = seatConfig.calculateTotalSeats();

            for (com.echo.dto.SeatBatchCreateDTO.AreaConfig area : seatConfig.getAreas()) {
                for (int row = 0; row < area.getRows(); row++) {
                    for (int col = 1; col <= area.getCols(); col++) {
                        com.echo.entity.Seat seat = new com.echo.entity.Seat();
                        seat.setTicketId(ticket.getId());
                        seat.setArea(area.getName());
                        seat.setRowNum(area.getStartRow() + row);
                        seat.setColNum(col);
                        seat.setStatus(0);

                        if (!seatService.save(seat)) {
                            throw new RuntimeException("座位保存失败");
                        }
                    }
                }
            }

            ticket.setTotalStock(totalSeats);
            ticket.setStock(totalSeats);
            ticketService.updateById(ticket);

            log.info("[AdminTicket] [成功] 座位生成完成 - ticketId:{}, 区域数:{}, 总座位数:{}, 库存已同步更新",
                    ticket.getId(), seatConfig.getAreas().size(), totalSeats);
        }

        if (ticket.getType() != null && ticket.getType() == 1) {
            try {
                com.echo.entity.SeckillTicket seckillTicket = new com.echo.entity.SeckillTicket();
                seckillTicket.setTicketId(ticket.getId());

                if (ticket.getSeckillStock() != null) {
                    seckillTicket.setStock(ticket.getSeckillStock());
                } else {
                    seckillTicket.setStock(100);
                }

                if (ticket.getBeginTime() != null) {
                    seckillTicket.setBeginTime(ticket.getBeginTime());
                    log.info("[AdminTicket] [新增+座位] 秒杀开始时间: {}", ticket.getBeginTime());
                } else {
                    seckillTicket.setBeginTime(java.time.LocalDateTime.now().plusMinutes(5));
                    log.info("[AdminTicket] [新增+座位] 未设置秒杀开始时间，默认5分钟后");
                }

                if (ticket.getEndTime() != null) {
                    seckillTicket.setEndTime(ticket.getEndTime());
                    log.info("[AdminTicket] [新增+座位] 秒杀结束时间: {}", ticket.getEndTime());
                } else {
                    seckillTicket.setEndTime(java.time.LocalDateTime.now().plusDays(3));
                    log.info("[AdminTicket] [新增+座位] 未设置秒杀结束时间，默认3天后");
                }

                boolean seckillSaved = seckillTicketService.save(seckillTicket);
                if (seckillSaved) {
                    log.info("[AdminTicket] [成功] 秒杀票记录已创建 - ticketId:{}, stock:{}, beginTime:{}, endTime:{}",
                            ticket.getId(), seckillTicket.getStock(), seckillTicket.getBeginTime(), seckillTicket.getEndTime());

                    try {
                        String redisKey = "seckill:stock:" + ticket.getId();
                        stringRedisTemplate.opsForValue().set(redisKey,
                                String.valueOf(seckillTicket.getStock()),
                                java.time.Duration.ofDays(7));
                        log.info("[AdminTicket] [成功] Redis秒杀库存已初始化 - key:{}, stock:{}", redisKey, seckillTicket.getStock());
                    } catch (Exception redisEx) {
                        log.warn("[AdminTicket] [警告] Redis库存初始化失败（非致命） - error:{}", redisEx.getMessage());
                    }
                } else {
                    log.error("[AdminTicket] [失败] 秒杀票记录创建失败 - ticketId:{}", ticket.getId());
                }
            } catch (Exception e) {
                log.error("[AdminTicket] [异常] 创建秒杀票时出错 - ticketId:{}, error:{}", ticket.getId(), e.getMessage(), e);
            }
        }

        try {
            ticketService.refreshTicketCache(ticket.getShowId());
            log.info("[AdminTicket] 已刷新演出票种缓存 - showId:{}", ticket.getShowId());
        } catch (Exception e) {
            log.warn("[AdminTicket] 刷新缓存失败 - error:{}", e.getMessage());
        }

        java.util.Map<String, Object> responseData = new java.util.HashMap<>();
        responseData.put("id", ticket.getId());
        responseData.put("ticketId", ticket.getId());
        responseData.put("title", ticket.getTitle());
        responseData.put("showId", ticket.getShowId());
        responseData.put("status", ticket.getStatus());
        responseData.put("type", ticket.getType());
        responseData.put("hasSeats", ticket.getHasSeats());

        int finalSeatCount = 0;
        if (Boolean.TRUE.equals(ticket.getHasSeats())) {
            try {
                finalSeatCount = (int) seatService.count(
                        new QueryWrapper<com.echo.entity.Seat>()
                                .eq("ticket_id", ticket.getId())
                );
            } catch (Exception e) {
                log.warn("[AdminTicket] 查询最终座位数失败 - error:{}", e.getMessage());
            }
        }

        responseData.put("totalSeats", finalSeatCount);
        responseData.put("seatCount", finalSeatCount);
        responseData.put("stock", ticket.getStock());
        responseData.put("message", String.format("成功创建票种【%s】，%s",
                ticket.getTitle(),
                Boolean.TRUE.equals(ticket.getHasSeats()) ?
                        String.format("已生成 %d 个座位", finalSeatCount) :
                        "无需选座"));

        log.info("[AdminTicket] [成功] 带座位票种创建完成 - id:{}, title:{}, type:{}, status:{}, seats:{}",
                ticket.getId(), ticket.getTitle(), ticket.getType(), ticket.getStatus(), finalSeatCount);

        return Result.ok(responseData);
    }

    @Operation(summary = "创建秒杀票", description = "为已有票种添加秒杀活动配置")
    @PostMapping("/seckill")
    public Result<?> createSeckillTicket(
            @Valid
            @Parameter(description = "秒杀票信息", required = true)
            @RequestBody com.echo.entity.SeckillTicket seckillTicket) {

        log.info("[AdminTicket] [秒杀] 创建秒杀票, ticketId:{}, 操作者:{}",
                seckillTicket.getTicketId(), PermissionUtils.getCurrentAdminName());

        boolean success = seckillTicketService.save(seckillTicket);

        if (success) {
            log.info("[AdminTicket] [成功] 秒杀票创建成功 - ticketId:{}", seckillTicket.getTicketId());
            return Result.ok(AppMessages.TICKET_SECKILL_CREATE_SUCCESS);
        } else {
            log.error("[AdminTicket] [失败] 秒杀票创建失败");
            return Result.fail(AppMessages.TICKET_SECKILL_SAVE_FAIL);
        }
    }

    @Operation(summary = "更新秒杀票", description = "更新秒杀活动的库存和时间")
    @PutMapping("/seckill")
    public Result<?> updateSeckillTicket(
            @Valid
            @Parameter(description = "秒杀票信息", required = true)
            @RequestBody com.echo.entity.SeckillTicket seckillTicket) {

        log.info("[AdminTicket] [秒杀] 更新秒杀票, ticketId:{}, 操作者:{}",
                seckillTicket.getTicketId(), PermissionUtils.getCurrentAdminName());

        boolean success = seckillTicketService.updateById(seckillTicket);

        if (success) {
            log.info("[AdminTicket] [成功] 秒杀票更新成功 - ticketId:{}", seckillTicket.getTicketId());
            return Result.ok(AppMessages.TICKET_SECKILL_UPDATE_SUCCESS);
        } else {
            log.error("[AdminTicket] [失败] 秒杀票更新失败");
            return Result.fail(AppMessages.TICKET_SECKILL_SAVE_FAIL);
        }
    }

    /**
     * 构建票种查询条件
     */
    private QueryWrapper<Ticket> buildQueryWrapper(Long showId, String keyword, Integer status, String sortBy, String sortOrder) {
        QueryWrapper<Ticket> queryWrapper = new QueryWrapper<>();

        if (showId != null && showId > 0) {
            queryWrapper.eq("show_id", showId);
        }

        if (keyword != null && !keyword.trim().isEmpty()) {
            queryWrapper.and(wrapper -> wrapper
                    .like("title", keyword.trim())
                    .or()
                    .like("rules", keyword.trim())
            );
        }

        if (status != null) {
            queryWrapper.eq("status", status);
        }

        // 排序字段白名单：只接受下表列出的键，其余（含空值）回落到创建时间倒序，
        // 避免把外部输入直接拼进 SQL
        String sortColumn = resolveSortColumn(sortBy);
        if (sortColumn == null) {
            queryWrapper.orderByDesc("create_time");
            return queryWrapper;
        }

        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        queryWrapper.orderBy(true, asc, sortColumn);

        // 类型只有 0/1 两个取值，同类型内部再按创建时间倒序，
        // 保证分页结果稳定，不会出现同一票种在两页里来回跳
        if ("type".equals(sortColumn)) {
            queryWrapper.orderByDesc("create_time");
        }

        return queryWrapper;
    }

    /**
     * 把前端传入的排序键映射为数据库列名
     *
     * @param sortBy 排序键（前端表头的 sortKey）
     * @return 数据库列名；无法识别时返回 null
     */
    private String resolveSortColumn(String sortBy) {
        if (sortBy == null || sortBy.trim().isEmpty()) {
            return null;
        }
        switch (sortBy.trim()) {
            case "id":
                return "id";
            case "title":
                return "title";
            case "type":
                return "type";
            case "showId":
                return "show_id";
            case "payValue":
            case "price":
                return "pay_value";
            case "actualValue":
                return "actual_value";
            case "stock":
                return "stock";
            case "status":
                return "status";
            case "createTime":
                return "create_time";
            case "updateTime":
                return "update_time";
            default:
                log.warn("[AdminTicket] 未识别的排序字段:{}，回落到创建时间倒序", sortBy);
                return null;
        }
    }
}
