package com.echo.service.strategy.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.echo.constants.AppMessages;
import com.echo.entity.PreOrder;
import com.echo.entity.Seat;
import com.echo.entity.Ticket;
import com.echo.entity.TicketOrder;
import com.echo.exception.BusinessException;
import com.echo.service.ISeatService;
import com.echo.service.strategy.SeatStrategy;
import com.echo.utils.OrderUtils;
import com.echo.utils.RedisConstants;
import com.echo.utils.RedisWorker;
import com.echo.utils.UserHolder;
import com.echo.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 有座票座位处理策略实现
 *
 * @author tulip
 */
@Slf4j
@Component
public class WithSeatStrategy implements SeatStrategy {

    @Resource
    private ISeatService seatService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisWorker redisWorker;

    private static final String STRATEGY_TYPE = "WITH_SEAT";
    private static final String DESCRIPTION = "有座票策略（需要选座）";

    @Override
    public String getStrategyType() {
        return STRATEGY_TYPE;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    /**
     * 验证座位合法性
     */
    @Override
    public SeatValidationResult validateSeats(Long ticketId, List<Long> seatIds, int quantity) {
        log.info("[有座票验证] 开始验证 - ticketId:{}, 座位数:{}, 购买数量:{}",
            ticketId, seatIds != null ? seatIds.size() : 0, quantity);

        if (seatIds == null || seatIds.isEmpty()) {
            return SeatValidationResult.error("请选择至少一个座位");
        }

        if (seatIds.size() != quantity) {
            return SeatValidationResult.error("座位数量与购买数量不一致");
        }

        if (seatIds.size() > 10) {
            return SeatValidationResult.error("单次最多选择10个座位");
        }

        // 获取当前登录用户ID（校验锁的主人必须是下单用户本人）
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return SeatValidationResult.error("用户未登录，请先登录");
        }
        String currentUserId = currentUser.getId().toString();

        for (Long seatId : seatIds) {
            // 第1步：Redis 先查（快），锁不在直接拒，省一次 MySQL 查询
            String lockKey = RedisConstants.LOCK_SEAT_KEY + ticketId + ":" + seatId;
            String lockHolder = stringRedisTemplate.opsForValue().get(lockKey);

            if (lockHolder == null) {
                // 锁不存在（TTL已过期或从未锁过），座位已释放，让他重新选
                return SeatValidationResult.error("座位锁定已过期，请重新选座 (seatId:" + seatId + ")");
            }

            if (!currentUserId.equals(lockHolder)) {
                // 锁存在，但不是当前用户的——说明另一个人锁了这个座位
                return SeatValidationResult.error("座位已被他人锁定，请重新选择 (seatId:" + seatId + ")");
            }

            // 第2步：Redis锁验证通过，才查 MySQL（双重保险）
            Seat seat = seatService.getById(seatId);
            if (seat == null) {
                return SeatValidationResult.error("座位不存在 (seatId:" + seatId + ")");
            }

            if (!seat.getTicketId().equals(ticketId)) {
                return SeatValidationResult.error("座位与票种不匹配 (seatId:" + seatId + ")");
            }

            Integer status = seat.getStatus();
            if (status != null && status != 1) {
                String statusDesc = switch (status) {
                    case 0 -> "座位已被释放，请重新选座";
                    case 2 -> "座位已被他人购买";
                    default -> "座位状态异常(" + status + ")";
                };
                return SeatValidationResult.error(statusDesc + " (seatId:" + seatId + ")");
            }
        }

        log.info("[有座票验证] 通过 - 共{}个座位", seatIds.size());
        return SeatValidationResult.ok();
    }

    /**
     * 锁定座位
     */
    @Override
    public SeatOperationResult lockSeats(TicketOrder order, List<Long> seatIds) {
        log.info("[有座票锁定] 开始锁定 - orderId:{}, ticketId:{}, 数量:{}",
            order.getId(), order.getTicketId(), seatIds.size());

        int successCount = 0;
        StringBuilder errors = new StringBuilder();

        for (Long seatId : seatIds) {
            try {
                boolean updated = seatService.update()
                        .set("status", 2)
                        .set("order_id", order.getId())
                        .eq("id", seatId)
                        .eq("status", 1)
                        .update();

                if (updated) {
                    String lockKey = RedisConstants.LOCK_SEAT_KEY + order.getTicketId() + ":" + seatId;
                    stringRedisTemplate.delete(lockKey);
                    successCount++;
                    log.debug("[有座票锁定] seatId:{}", seatId);
                } else {
                    log.error("[有座票锁定] 座位已被他人购买或状态异常，seatId:{}, orderId:{}",
                            seatId, order.getId());
                    throw BusinessException.ofUserFriendly(AppMessages.format(AppMessages.SEAT_SOLD_BY_OTHERS_WITH_ID, seatId));
                }
            } catch (RuntimeException lockEx) {
                log.error("[有座票锁定] 乐观锁冲突上抛 - seatId:{}, msg:{}", seatId, lockEx.getMessage());
                throw lockEx;
            } catch (Exception e) {
                log.error("[有座票锁定] seatId:{}, error:{}", seatId, e.getMessage());
                errors.append(seatId).append("(异常); ");
            }
        }

        if (successCount == seatIds.size()) {
            log.info("[有座票锁定] 全部成功:{}", successCount);
            return SeatOperationResult.success(successCount);
        } else if (successCount > 0) {
            log.warn("[有座票锁定] 部分:{}/{}", successCount, seatIds.size());
            return SeatOperationResult.fail("部分失败：" + errors);
        } else {
            log.error("[有座票锁定] 全部失败");
            return SeatOperationResult.fail("全部失败：" + errors);
        }
    }

    /**
     * 释放座位
     */
    @Override
    public int releaseSeats(TicketOrder order) {
        log.info("[有座票释放] 开始释放 - orderId:{}, ticketId:{}", order.getId(), order.getTicketId());

        List<Seat> soldSeats = seatService.list(
            new QueryWrapper<Seat>().eq("order_id", order.getId())
        );

        if (soldSeats.isEmpty()) {
            log.info("[有座票释放] 无需释放");
            return 0;
        }

        int releasedCount = 0;
        for (Seat seat : soldSeats) {
            try {
                boolean updated = seatService.update()
                        .set("status", 0)
                        .set("order_id", null)
                        .set("pre_order_id", null)
                        .eq("id", seat.getId())
                        .in("status", 1, 2)
                        .update();

                if (updated) {
                    String lockKey = RedisConstants.LOCK_SEAT_KEY + order.getTicketId() + ":" + seat.getId();
                    stringRedisTemplate.delete(lockKey);
                    releasedCount++;
                    log.debug("[有座票释放] seatId:{}", seat.getId());
                }
            } catch (Exception e) {
                log.error("[有座票释放] seatId:{}, error:{}", seat.getId(), e.getMessage());
            }
        }

        log.info("[有座票释放] 完成 - 总数:{}, 成功:{}", soldSeats.size(), releasedCount);
        return releasedCount;
    }

    /**
     * 支付时构建子订单（有座票）
     * <p>
     * 核心流程（一次循环内完成所有操作）：
     * 1. 生成真正订单号（雪花算法）
     * 2. 锁座钉死（预占状态1 → 已售状态2，删除Redis锁）
     * 3. 生成核销码
     * 4. 构建子订单对象（status=2已支付）
     *
     * @param preOrder      预订单（包含座位ID列表）
     * @param ticket        票种信息
     * @param pointsToUse   使用积分数量
     * @param discountAmount 积分抵扣金额
     * @return 已构建完成的子订单列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<TicketOrder> buildSubOrdersOnPayment(PreOrder preOrder, Ticket ticket, int pointsToUse, long discountAmount) {
        List<Long> seatIds = preOrder.getSeatIds();

        if (seatIds == null || seatIds.isEmpty()) {
            log.error("[有座票支付拆单] 座位列表为空，tempId:{}", preOrder.getTempId());
            throw BusinessException.ofBadRequest("座位列表不能为空");
        }

        List<TicketOrder> subOrders = new ArrayList<>();
        int totalSeats = seatIds.size();

        log.info("[有座票支付拆单] 开始构建子订单 - tempId:{}, 座位数:{}, 冻结票价:{}",
                preOrder.getTempId(), totalSeats, preOrder.getPayValue());

        for (int i = 0; i < totalSeats; i++) {
            Long seatId = seatIds.get(i);

            try {
                // Step1: 生成真正订单号（雪花算法）
                long realOrderId = redisWorker.nextId("order");

                // Step2: 锁座钉死（从预占状态1 → 已售状态2）
                lockSeatFinally(seatId, realOrderId, preOrder.getTicketId(), preOrder.getTempId());

                // Step3: 构建子订单
                TicketOrder subOrder = new TicketOrder();
                subOrder.setId(realOrderId);
                subOrder.setUserId(preOrder.getUserId());
                subOrder.setTicketId(preOrder.getTicketId());
                subOrder.setShowId(preOrder.getShowId());
                subOrder.setStatus(2);  // 已支付
                subOrder.setQuantity(1);
                subOrder.setSubOrderIndex((i + 1) + "/" + totalSeats);  // 序列号：1/3, 2/3, 3/3
                subOrder.setPayType(1);  // 模拟支付
                subOrder.setPayTime(LocalDateTime.now());
                subOrder.setCreateTime(LocalDateTime.now());
                subOrder.setUpdateTime(LocalDateTime.now());
                subOrder.setPayValue(preOrder.getPayValue() != null ? preOrder.getPayValue() : 0L);
                subOrder.setActualValue(preOrder.getActualValue() != null ? preOrder.getActualValue() : 0L);

                // Step4: 设置积分抵扣（仅第一张票扣积分）
                if (i == 0) {
                    subOrder.setPointsUsed(pointsToUse);
                    subOrder.setDiscountAmount(discountAmount);
                } else {
                    subOrder.setPointsUsed(0);
                    subOrder.setDiscountAmount(0L);
                }

                // Step5: 生成核销码（同一循环内生成）
                String verificationCode = OrderUtils.generateVerificationCode();
                subOrder.setVerificationCode(verificationCode);

                subOrders.add(subOrder);

                log.info("[有座票支付拆单] 子订单创建成功 - orderId:{}, seatId:{}, 核销码:{}, 序号:{}/{}",
                        realOrderId, seatId, verificationCode, (i + 1), totalSeats);

            } catch (Exception e) {
                log.error("[有座票支付拆单] 第{}个座位处理失败，seatId:{}, error:{}",
                        (i + 1), seatId, e.getMessage(), e);
                throw new BusinessException(AppMessages.format(AppMessages.SEAT_LOCK_FAIL_PREFIX, e.getMessage()), org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, e);
            }
        }

        log.info("[有座票支付拆单] 全部完成 - tempId:{}, 共构建{}个子订单",
                preOrder.getTempId(), subOrders.size());

        return subOrders;
    }

    /**
     * 最终锁定座位（从预占 → 已售）
     * <p>
     * 业务规则：
     * - 座位必须处于预占状态(status=1)且属于当前预订单(pre_order_id匹配)
     * - 更新为已售状态(status=2)并关联真正订单号(order_id)
     * - 清除预订单ID(pre_order_id=null)
     * - 删除前端选座阶段的Redis分布式锁
     *
     * @param seatId    座位ID
     * @param orderId   真正订单号（雪花算法）
     * @param ticketId  票种ID
     * @param tempId    预订单临时ID
     */
    private void lockSeatFinally(Long seatId, Long orderId, Long ticketId, String tempId) {
        log.debug("[锁座钉死] 开始 - seatId:{}, orderId:{}, tempId:{}", seatId, orderId, tempId);

        boolean updated = seatService.update()
                .set("status", 2)
                .set("order_id", orderId)
                .set("pre_order_id", null)
                .eq("id", seatId)
                .eq("status", 1)
                .eq("pre_order_id", tempId)
                .update();

        if (!updated) {
            Seat current = seatService.getById(seatId);
            String reason = "座位状态异常";
            if (current != null) {
                if (current.getStatus() != null && current.getStatus() != 1) {
                    reason = switch (current.getStatus()) {
                        case 0 -> AppMessages.ORDER_SEAT_RELEASED;
                        case 2 -> AppMessages.ORDER_SEAT_SOLD_BY_OTHERS;
                        default -> AppMessages.format(AppMessages.SEAT_STATUS_INVALID_WITH_CODE, current.getStatus());
                    };
                } else if (current.getPreOrderId() == null || !current.getPreOrderId().equals(tempId)) {
                    reason = AppMessages.SEAT_NOT_BELONG_TO_ORDER_WITH_ID;
                }
            }
            throw BusinessException.ofUserFriendly(AppMessages.format(AppMessages.SEAT_OPTIMISTIC_LOCK_FAIL_WITH_ID + " (" + reason + ")", seatId));
        }

        String lockKey = RedisConstants.LOCK_SEAT_KEY + ticketId + ":" + seatId;
        stringRedisTemplate.delete(lockKey);

        log.debug("[锁座钉死] 成功 - seatId:{}, orderId:{}", seatId, orderId);
    }
}
