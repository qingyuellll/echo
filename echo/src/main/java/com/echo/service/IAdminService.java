package com.echo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.echo.dto.*;
import com.echo.entity.Admin;
import com.echo.entity.SeckillTicket;
import com.echo.entity.Show;
import com.echo.entity.Ticket;

/**
 * 管理员服务接口
 *
 * @author tulip
 */
public interface IAdminService extends IService<Admin> {

    Result<?> sendCode(String phone);

    Result<?> login(AdminLoginDTO loginForm);

    Result<?> logout();

    Result<?> sendPasswordCode();

    Result<?> changePassword(AdminChangePasswordDTO dto);

    Result<?> getAdminInfo();

    /**
     * 重置超级管理员密码为 123456
     * <p>
     * 调用方负责完成授权：引导通道由 {@code X-Bootstrap-Token} 授权，
     * 已登录场景由超级管理员身份授权。两种授权都通过后传入 true/false 区分来源，
     * 服务层在非引导来源时仍会二次校验当前登录身份。
     *
     * @param authorizedByBootstrap true=已由引导令牌授权
     * @return 重置结果（含手机号与初始密码）
     */
    Result<?> initSuperAdminPassword(boolean authorizedByBootstrap);

    // ==================== 演出管理 ====================

    /**
     * 管理员专用：查询演出列表（含所有状态）
     * <p>
     * 与用户端 /show/of/name 的区别：
     * - 返回所有状态演出（含下架/待发布）
     * - 支持按状态、类型ID筛选
     * - 支持多字段排序
     * - 仅管理员可调用
     */
    Result<?> listShows(String name, Long id, Integer status, Integer typeId,
                       com.echo.dto.PageQuery pageQuery);

    /**
     * 清除所有演出缓存（仅超级管理员）
     */
    Result<?> clearShowCache();

    Result<?> createShow(Show show);

    Result<?> offlineShow(Long id);

    Result<?> onlineShow(Long id);

    Result<?> updateShow(Show show);

    Result<?> deleteShow(Long id);

    // ==================== 票种管理 ====================

    Result<?> listTickets(Long showId, String keyword, Integer current, Integer pageSize, String sortBy, String sortOrder);

    Result<?> createTicket(Ticket ticket);

    /**
     * 创建票种并生成座位（事务性原子操作）
     * <p>
     * 将"创建票种"和"批量生成座位"合并为一个数据库事务，
     * 避免出现"有票种但无座位"的不一致状态。
     *
     * @param dto 包含票种信息和座位区域配置
     * @return 操作结果（包含票种ID和座位生成统计）
     */
    Result<?> createTicketWithSeats(com.echo.dto.TicketWithSeatsDTO dto);

    Result<?> createSeckillTicket(SeckillTicket seckillTicket);

    Result<?> updateSeckillTicket(SeckillTicket seckillTicket);

    Result<?> updateTicket(Ticket ticket);

    Result<?> deleteTicket(Long id);

    Result<?> updateStock(Long id, Integer stock);

    Result<?> batchCreateSeats(Long ticketId, SeatBatchCreateDTO dto);

    // ==================== 订单管理 ====================

    Result<?> listOrders(Integer status, String keyword, String userName, com.echo.dto.PageQuery pageQuery);

    Result<?> getOrderDetail(Long orderId);

    Result<?> approveRefund(Long orderId);

    Result<?> dashboard();

    Result<?> exportOrders(Integer status);

    // ==================== 员工管理 ====================

    Result<?> listStaff();

    Result<?> addStaff(Admin admin);

    Result<?> removeStaff(Long userId);

    Result<?> disableStaff(Long adminId);

    Result<?> enableStaff(Long adminId);

    // ==================== 用户管理 ====================

    Result<?> listUsers(String keyword, String phone, Long id, Integer current, Integer pageSize, String sortBy, String sortOrder);

    Result<?> getUserDetail(Long userId);

    Result<?> disableUser(Long userId);

    Result<?> enableUser(Long userId);

    // ==================== 批量操作 ====================

    /**
     * 批量操作演出（上架/下架/删除）
     */
    Result<?> batchOperationShows(BatchOperationDTO batchDTO);

    /**
     * 批量操作票种（更新/删除）
     */
    Result<?> batchOperationTickets(BatchOperationDTO batchDTO);

    /**
     * 批量操作订单（审批退款/拒绝退款）
     */
    Result<?> batchOperationOrders(BatchOperationDTO batchDTO);

    /**
     * 批量操作用户（启用/禁用）
     */
    Result<?> batchOperationUsers(BatchOperationDTO batchDTO);
}
