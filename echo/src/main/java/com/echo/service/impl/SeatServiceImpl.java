package com.echo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.dto.SeatBatchCreateDTO;
import com.echo.dto.UserDTO;
import com.echo.entity.Seat;
import com.echo.mapper.SeatMapper;
import com.echo.service.ISeatService;
import com.echo.utils.RedisConstants;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 座位服务实现类
 *
 * @author tulip
 */
@SuppressWarnings("unchecked")
@Slf4j
@Service
public class SeatServiceImpl extends ServiceImpl<SeatMapper, Seat> implements ISeatService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private DefaultRedisScript<Long> unlockScript;

    /**
     * 字符串锁专用释放脚本：lockSeat 用 setIfAbsent 写入的是 string 类型，
     * 必须配对 unlock_simple.lua（GET+DEL）。此前误用哈希版 unlock.lua
     * （HEXISTS/HINCRBY）导致 WRONGTYPE → 释放座位接口报"系统繁忙"。
     */
    @Resource
    private DefaultRedisScript<Long> unlockSimpleScript;

    @Override
    public Result<?> querySeatsByTicket(Long ticketId) {
        QueryWrapper<Seat> wrapper = new QueryWrapper<>();
        wrapper.eq("ticket_id", ticketId)
               .orderByAsc("area")
               .orderByAsc("row_num")
               .orderByAsc("col_num");
        List<Seat> seats = list(wrapper);
        return Result.ok(seats);
    }

    @Resource
    private com.echo.service.ITicketService ticketService;

    @Override
    public Result<?> lockSeat(Long ticketId, Long seatId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        //并发安全检查：锁定座位前验证票种状态（关键！）
        log.info("[锁座] 开始验证票种状态，ticketId:{}", ticketId);
        boolean isTicketActive = ticketService.isTicketActiveForPurchase(ticketId);
        if (!isTicketActive) {
            log.warn("[锁座] 票种不可售卖，拒绝锁座请求！ticketId:{}, userId:{}",
                    ticketId, user.getId());

            // 查询具体原因用于返回友好提示
            com.echo.dto.Result<?> statusResult = ticketService.checkTicketStatus(ticketId);
            if (statusResult != null && statusResult.getData() != null) {
                java.util.Map<String, Object> statusInfo =
                        (java.util.Map<String, Object>) statusResult.getData();
                Integer status = (Integer) statusInfo.get("status");

                if (status == null || status != 1) {
                    return Result.fail(AppMessages.TICKET_OFFLINE_ERROR);  // 已下架或停用
                } else {
                    Boolean isSeckill = (Boolean) statusInfo.get("isSeckill");
                    if (Boolean.TRUE.equals(isSeckill)) {
                        return Result.fail(AppMessages.TICKET_SECKILL_NOT_STARTED_ERROR);  // 秒杀未开始/已结束
                    }
                }
            }

            return Result.fail(AppMessages.TICKET_STATUS_INVALID);  // 其他异常情况
        }
        log.info("[锁座] 票种状态验证通过，ticketId:{}, userId:{}", ticketId, user.getId());

        String lockKey = RedisConstants.LOCK_SEAT_KEY + ticketId + ":" + seatId;

        try {
            // 先抢占 Redis 分布式锁（原子操作，防并发超卖）
            Boolean locked = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, user.getId().toString(), RedisConstants.LOCK_SEAT_TTL, TimeUnit.SECONDS);

            if (!Boolean.TRUE.equals(locked)) {
                // 锁已被他人持有，检查是否为自己持有（TTL 内重复请求）
                String lockHolder = stringRedisTemplate.opsForValue().get(lockKey);
                if (lockHolder != null && lockHolder.equals(user.getId().toString())) {
                    log.info("[锁座] 用户已锁定该座位，seatId:{}", seatId);
                    return Result.ok();
                }
                return Result.fail(AppMessages.SEAT_LOCKED);
            }

            // 获得 Redis 锁后，再验证座位状态（TOCTOU 窗口在锁保护下无害）
            Seat seat = getById(seatId);
            if (seat == null) {
                // 座位不存在，释放 Redis 锁并返回
                stringRedisTemplate.delete(lockKey);
                return Result.fail(AppMessages.SEAT_NOT_FOUND);
            }
            if (!seat.getTicketId().equals(ticketId)) {
                stringRedisTemplate.delete(lockKey);
                return Result.fail(AppMessages.SEAT_NOT_FOUND);
            }
            if (seat.getStatus() == 2) {
                // 座位已售，释放 Redis 锁并返回
                stringRedisTemplate.delete(lockKey);
                return Result.fail(AppMessages.SEAT_SOLD);
            }
            if (seat.getStatus() == 1 && !Objects.equals(seat.getOrderId(), user.getId())) {
                // 座位已被他人锁定，释放 Redis 锁并返回
                stringRedisTemplate.delete(lockKey);
                return Result.fail(AppMessages.SEAT_LOCKED);
            }

            // 座位可锁定（status=0 或 status=1 且为自己持有）
            seat.setStatus(1);
            seat.setOrderId(null);
            updateById(seat);
            log.info("[锁座成功] ticketId:{}, seatId:{}, userId:{}, 区域:{}, {}排{}座",
                    ticketId, seatId, user.getId(), seat.getArea(), seat.getRowNum(), seat.getColNum());
            return Result.ok(seat);

        } catch (Exception e) {
            // 任何异常都尝试释放锁，避免锁泄漏
            try {
                stringRedisTemplate.delete(lockKey);
            } catch (Exception lockEx) {
                log.warn("[锁座-释放Redis锁失败] ticketId:{}, seatId:{}, lockKey:{}, error:{}", ticketId, seatId, lockKey, lockEx.getMessage());
            }
            log.error("[锁座失败] ticketId:{}, seatId:{}, userId:{}, error:{}", ticketId, seatId, user.getId(), e.getMessage(), e);
            return Result.fail(AppMessages.SEAT_LOCK_FAIL);
        }
    }

    @Override
    public Result<?> releaseSeat(Long ticketId, Long seatId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
        }

        Seat seat = getById(seatId);
        if (seat == null) {
            return Result.fail(AppMessages.SEAT_NOT_FOUND);
        }

        String lockKey = RedisConstants.LOCK_SEAT_KEY + ticketId + ":" + seatId;
        Long result = stringRedisTemplate.execute(
                unlockSimpleScript,
                Collections.singletonList(lockKey),
                user.getId().toString());

        if (result == null || result == 0) {
            return Result.fail(AppMessages.SEAT_NOT_LOCKED);
        }

        // 完全重置座位状态（含preOrderId清理）
        seat.setStatus(0);
        seat.setOrderId(null);
        seat.setPreOrderId(null);  // 清理预订单ID，防止残留数据影响后续选座
        updateById(seat);

        log.info("[释放座位] ticketId:{}, seatId:{}, userId:{}, 已清理preOrderId", ticketId, seatId, user.getId());
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> batchCreateSeats(Long ticketId, SeatBatchCreateDTO dto) {
        if (dto == null || dto.getAreas() == null || dto.getAreas().isEmpty()) {
            return Result.fail(AppMessages.SEAT_AREA_CONFIG_EMPTY);
        }

        QueryWrapper<Seat> wrapper = new QueryWrapper<>();
        wrapper.eq("ticket_id", ticketId);
        long existingCount = count(wrapper);

        if (existingCount > 0) {
            // F-Z修复：检查是否存在已售座位（status=2），防止误删导致订单引用悬空
            QueryWrapper<Seat> soldSeatCheck = new QueryWrapper<>();
            soldSeatCheck.eq("ticket_id", ticketId)
                    .in("status", 2);  // 2=已售

            long soldSeatCount = count(soldSeatCheck);

            if (soldSeatCount > 0) {
                log.error("[批量创建座位-安全拦截] 票种存在{}个已售座位，禁止重建！ticketId:{}, 已售座位将被保留以保护订单数据完整性",
                        soldSeatCount, ticketId);
                return Result.fail(
                        String.format("该票种已有 %d 个已售出座位，为保护订单数据完整性，禁止重新生成座位。如需调整座位布局，请联系技术支持。",
                                soldSeatCount)
                );
            }

            log.info("[批量创建座位] 安全检查通过（无已售座位），删除旧座位数据，ticketId:{}, 删除数量:{}",
                    ticketId, existingCount);
            remove(wrapper);
        }

        List<Seat> seatList = buildSeatList(ticketId, dto);

        int totalSeats = seatList.size();
        saveBatch(seatList);
        log.info("[批量创建座位] ticketId:{}, 共{}个区域, 生成{}个座位, 详情:{}",
                ticketId, dto.getAreas().size(), totalSeats,
                dto.getAreas().stream()
                        .map(a -> a.getName() + "(" + a.getRows() + "排×" + a.getCols() + "座)")
                        .reduce((a, b) -> a + ", " + b).orElse(""));

        return Result.ok(Map.of("ticketId", ticketId, "totalSeats", totalSeats));
    }

    /**
     * 根据区域配置构建座位列表
     */
    private List<Seat> buildSeatList(Long ticketId, SeatBatchCreateDTO dto) {
        List<Seat> seatList = new ArrayList<>();
        for (SeatBatchCreateDTO.AreaConfig area : dto.getAreas()) {
            int startRow = area.getStartRow();
            for (int row = 0; row < area.getRows(); row++) {
                for (int col = 1; col <= area.getCols(); col++) {
                    Seat seat = new Seat();
                    seat.setTicketId(ticketId);
                    seat.setArea(area.getName());
                    seat.setRowNum(startRow + row);
                    seat.setColNum(col);
                    seat.setStatus(0);
                    seatList.add(seat);
                }
            }
        }
        return seatList;
    }
}
