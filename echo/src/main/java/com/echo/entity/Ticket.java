package com.echo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.echo.constants.TicketMessages;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 票种实体类 - 核心交易实体（支持普通票+秒杀票双模式）
 * <p>
 * 业务场景：
 * - 管理演出票种信息（早鸟票、VIP票、普通票、秒杀票等）
 * - 支持两种票务模式：普通票（常规售卖）+ 秒杀票（限时抢购）
 * - 控制票种的上下架状态和库存管理
 * - 为订单系统提供票种数据和库存校验
 * <p>
 * 票种类型说明：
 * - 普通票 (type=0)：常规售卖模式，库存存储在 tb_ticket.stock
 * - 秒杀票 (type=1)：限时抢购模式，真实库存在 tb_seckill_ticket + Redis
 * <p>
 * 对应数据库表：tb_ticket
 * 索引设计：
 * - PRIMARY KEY (id) -- 主键索引
 * - INDEX idx_show_id (show_id) -- 按演出查询票种列表（最常用）
 * - INDEX idx_status (status) -- 过滤上架/下架票种
 * - INDEX idx_type (type) -- 区分普通票和秒杀票
 * <p>
 * 数据关系：
 * - 属于：Show（一个演出可有多个票种）
 * - 被引用：TicketOrder（订单关联票种）
 * - 关联：SeckillTicket（秒杀票的详细信息和库存）
 * <p>
 * 金额单位：分（前端展示时除以100转为元）
 * 示例：payValue=50000 表示 500.00 元
 *
 * @see ITicketService 票种管理业务逻辑接口
 * @see Show 关联的演出实体
 * @see TicketOrder 关联的订单实体
 * @see SeckillTicket 秒杀票详细信息
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_ticket")
public class Ticket implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ==================== 数据库字段 ====================

    /**
     * 主键ID（自增）
     * <p>
     * MySQL: BIGINT UNSIGNED AUTO_INCREMENT
     * 用途：唯一标识一种票种，用于查询、下单、库存扣减等操作
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "票种ID", example = "100")
    private Long id;

    /**
     * 关联演出ID（tb_show.id）[标准字段]
     * <p>
     * 数据库字段：tb_ticket.show_id
     * 关联表：tb_show（演出表）
     * 索引：INDEX idx_show_id (show_id)
     * 约束：NOT NULL，外键关联（逻辑外键）
     * <p>
     * 业务含义：该票种属于哪个演出
     * 用途：
     * - 查询某演出的所有票种（最常用查询）
     * - 演出删除时级联处理相关票种
     * - 统计演出的票种数量和总库存
     */
    @NotNull(message = TicketMessages.TICKET_SHOW_ID_NOT_EMPTY)
    @Schema(description = "关联演出ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long showId;

    /**
     * 票种标题 [标准字段]
     * <p>
     * 数据库字段：tb_ticket.title
     * 类型：VARCHAR(100)
     * 约束：NOT NULL
     * <p>
     * 命名规范：
     * - 简洁明了，2-20个字符
     * - 示例："早鸟票"、"VIP票"、"普通票"、"学生票"
     * - 可包含优惠信息："限时8折早鸟票"
     * <p>
     * 使用场景：
     * - 前端票种选择器显示名称
     * - 订单详情页展示票种名称
     * - 购物车中展示票种信息
     */
    @NotBlank(message = TicketMessages.TICKET_TITLE_NOT_EMPTY)
    @Schema(description = "票种标题", requiredMode = Schema.RequiredMode.REQUIRED, example = "VIP票")
    private String title;

    /**
     * 副标题（辅助说明）
     * <p>
     * 数据库字段：tb_ticket.sub_title
     * 类型：VARCHAR(100)
     * 约束：选填
     * <p>
     * 用途：
     * - 补充说明票种特点（如"限时优惠"、"含周边礼包"）
     * - 营销文案（如"仅剩最后100张"）
     * - 特殊权益提示（如"可提前30分钟入场"）
     */
    @Schema(description = "副标题", example = "限时优惠")
    private String subTitle;

    /**
     * 购票规则说明
     * <p>
     * 数据库字段：tb_ticket.rules
     * 类型：TEXT
     * 约束：选填
     * <p>
     * 内容示例：
     * - "每人限购2张，不可退改"
     * - "需凭身份证入场，1.2米以下儿童免票"
     * - "此票种不含餐饮，现场另有售卖"
     * <p>
     * 展示位置：
     * - 票种详情页的"购买须知"区域
     * - 下单前的确认弹窗
     * - 订单详情页的票种信息区
     */
    @Schema(description = "购票规则说明", example = "每人限购2张，不可退改")
    private String rules;

    /**
     * 支付金额（单位：分）[标准字段]
     * <p>
     * 数据库字段：tb_ticket.pay_value
     * 类型：BIGINT UNSIGNED
     * 约束：NOT NULL, >= 0
     * <p>
     * 金额单位：分（避免浮点数精度问题）
     * 转换公式：元 = 分 / 100
     * 示例：50000分 = 500.00元
     * <p>
     * 业务含义：用户实际需要支付的金额
     * 用途：
     * - 订单金额计算的基础
     * - 前端价格展示（需除以100）
     * - 支付接口调用参数
     * - 退款金额计算
     */
    @Min(value = 0, message = TicketMessages.TICKET_PAY_VALUE_NEGATIVE)
    @Schema(description = "支付金额(单位:分)", requiredMode = Schema.RequiredMode.REQUIRED, example = "50000")
    private Long payValue;

    /**
     * 实际价值/原价（单位：分）[标准字段]
     * <p>
     * 数据库字段：tb_ticket.actual_value
     * 类型：BIGINT UNSIGNED
     * 约束：>= 0（可为0或null表示无原价）
     * <p>
     * 业务含义：票种的原始市场价格（用于展示折扣）
     * 展示效果：
     * - 原价：800.00元（删除线样式）
     * - 现价：500.00元（加粗红色）
     * - 折扣：6.25折
     * <p>
     * 使用场景：
     * - 营销展示（突出优惠力度）
     * - 价格对比（让用户感觉划算）
     * - 数据分析（计算折扣率、客单价）
     */
    @Min(value = 0, message = TicketMessages.TICKET_ACTUAL_VALUE_NEGATIVE)
    @Schema(description = "实际价值/原价(单位:分)", example = "80000")
    private Long actualValue;

    /**
     * 票种类型 [标准字段]
     * <p>
     * 数据库字段：tb_ticket.type
     * 类型：TINYINT
     * 约束：NOT NULL，默认值为0
     * <p>
     * 类型值：
     * - 0：普通票（常规售卖模式）
     * - 1：秒杀票（限时抢购模式）
     * <p>
     * 库存差异：
     * - 普通票：库存存储在 tb_ticket.stock（MySQL直接扣减）
     * - 秒杀票：tb_ticket.stock 固定为0，真实库存在 tb_seckill_ticket + Redis
     * <p>
     * 购买流程差异：
     * - 普通票：直接下单 -> 扣减MySQL库存 -> 支付
     * - 秒杀票：Redis预占库存 -> 下单 -> Redis Lua脚本原子扣减 -> 支付
     */
    @NotNull(message = TicketMessages.TICKET_TYPE_NOT_EMPTY)
    @Min(value = 0, message = TicketMessages.TICKET_TYPE_INVALID)
    @Max(value = 1, message = TicketMessages.TICKET_TYPE_INVALID)
    @Schema(description = "票种类型: 0=普通票, 1=秒杀票", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Integer type;

    /**
     * 是否需要选座 [标准字段]
     * <p>
     * 数据库字段：tb_ticket.has_seats
     * 类型：TINYINT(1)
     * 约束：NOT NULL，默认值为false
     * <p>
     * 取值：
     * - false：不需要选座（站票、随机座位）
     * - true：需要选座（指定座位号）
     * <p>
     * 业务影响：
     * - hasSeats=true：下单前必须先选座，订单中记录座位信息
     * - hasSeats=false：无需选座，按顺序分配或随机分配
     * <p>
     * 前端交互：
     * - true：展示座位图供用户选择
     * - false：直接进入填写订单页面
     */
    @NotNull(message = TicketMessages.TICKET_HAS_SEATS_NOT_EMPTY)
    @Schema(description = "是否需要选座", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    private Boolean hasSeats;

    /**
     * 票种锁定标记（业务安全字段）
     * <p>
     * 数据库字段：tb_ticket.locked
     * 类型：TINYINT
     * 默认值：0
     * <p>
     * 取值：
     * - 0：正常状态（可正常售卖）
     * - 1：锁定状态（孤儿票种，禁止上架）
     * <p>
     * 触发场景：
     * - 演出被删除时，系统自动将该演出下所有票种的 locked 设为 1
     * - 目的：防止孤儿票种被误售（演出不存在但票种还在）
     * <p>
     * 解锁条件：
     * - 管理员重新关联有效演出后，系统自动解锁（locked 设为 0）
     * - 或管理员手动解锁（需确认安全性）
     * <p>
     * 注意：
     * - 此字段与"库存锁定 / 秒杀预占"无关
     * - 不要用它判断库存状态
     * - 仅用于标记票种的业务可用性
     */
    @Min(value = 0, message = TicketMessages.TICKET_LOCKED_INVALID)
    @Max(value = 1, message = TicketMessages.TICKET_LOCKED_INVALID)
    @Schema(description = "票种锁定标记(0=正常, 1=孤儿票种禁止上架)", example = "0")
    private Integer locked;

    /**
     * 剩余可卖库存数量（动态变化）[标准字段]
     * <p>
     * 数据库字段：tb_ticket.stock
     * 类型：INT UNSIGNED
     * 默认值：等于 total_stock（创建时初始化）
     * <p>
     * 适用范围：
     * - 仅对普通票 (type=0) 有效
     * - 秒杀票 (type=1) 的此字段固定为0（仅作占位）
     * <p>
     * 变化规则：
     * - 创建票种时：stock = total_stock（初始库存）
     * - 用户购买时：stock -= quantity（扣减库存）
     * - 用户退款时：stock += quantity（恢复库存）
     * - 订单取消时：stock += quantity（释放库存）
     * - 管理员补货时：stock += quantity（增加库存）
     * <p>
     * 范围约束：0 <= stock <= total_stock
     * <p>
     * 并发控制：
     * - 使用 MySQL 的 UPDATE ... SET stock = stock - ? WHERE stock >= ?
     * - 防止超卖（库存不足时更新失败）
     * - 或使用乐观锁（version 字段）控制并发
     */
    @NotNull(message = TicketMessages.TICKET_STOCK_NOT_EMPTY)
    @Min(value = 0, message = TicketMessages.TICKET_STOCK_NEGATIVE)
    @Schema(description = "剩余可卖库存(动态变化)", requiredMode = Schema.RequiredMode.REQUIRED, example = "950")
    private Integer stock;

    /**
     * 总库存数量（固定不变）[标准字段]
     * <p>
     * 数据库字段：tb_ticket.total_stock
     * 类型：INT UNSIGNED
     * 约束：NOT NULL, > 0
     * <p>
     * 业务含义：该票种总共能卖多少张票（理论最大销量）
     * 初始化时机：
     * - 创建票种时由管理员设置
     * - 或通过数据修复脚本从 stock 字段复制
     * <p>
     * 变更场景：
     * - 仅管理员手动调整（如增加放票量：total_stock += 100）
     * - 通常情况下创建后不再修改
     * <p>
     * 关系公式（理论上应该成立）：
     * total_stock = stock + soldCount
     * - stock：当前剩余库存
     * - soldCount：已售数量（实时计算）
     * <p>
     * 使用场景：
     * - 展示总库存信息（"共1000张，已售350张"）
     * - 计算售罄进度条（soldCount / total_stock * 100%）
     * - 数据分析（总放票量、售罄率等）
     */
    @NotNull(message = TicketMessages.TOTAL_STOCK_NOT_EMPTY)
    @Min(value = 1, message = TicketMessages.TOTAL_STOCK_MUST_GREATER_THAN_ZERO)
    @Schema(description = "总库存(固定不变)", requiredMode = Schema.RequiredMode.REQUIRED, example = "1000")
    private Integer totalStock;

    /**
     * 上架状态 [标准字段]
     * <p>
     * 数据库字段：tb_ticket.status
     * 类型：TINYINT
     * 约束：NOT NULL，默认值为0
     * <p>
     * 状态值：
     * - 0：下架（不可购买，前端不展示）
     * - 1：上架（可购买，前端正常展示）
     * <p>
     * 状态转换：
     * - 下架 -> 上架：管理员手动上架，或到达定时上架时间
     * - 上架 -> 下架：管理员手动下架，或库存售罄自动下架
     * <p>
     * 业务影响：
     * - status=0：用户无法看到该票种，无法下单
     * - status=1：用户可以看到并购买（还需满足其他条件如库存>0）
     * <p>
     * 定时上架：
     * - 如果设置了 scheduledPublishTime，到达时间后自动 status=1
     * - 如果 scheduledPublishTime=null，需手动上架
     */
    @NotNull(message = TicketMessages.TICKET_STATUS_NOT_EMPTY)
    @Min(value = 0, message = TicketMessages.TICKET_STATUS_INVALID)
    @Max(value = 1, message = TicketMessages.TICKET_STATUS_INVALID)
    @Schema(description = "状态: 0=下架, 1=上架", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer status;

    /**
     * 定时上架时间
     * <p>
     * 数据库字段：tb_ticket.scheduled_publish_time
     * 类型：DATETIME
     * 约束：选填（null表示立即上架或手动管理）
     * <p>
     * 用途：
     * - 预设未来的某个时间点自动上架
     * - 适用于预售场景（提前创建票种，到点自动开售）
     * <p>
     * 工作机制：
     * - 定时任务扫描：每分钟检查是否有到达上架时间的票种
     * - 自动操作：到达时间且 status=0 时，自动设为 status=1
     * - 通知机制：可选（上架后通知关注该演出的用户）
     * <p>
     * 前端展示：
     * - 未到时间：显示"将于 xx:xx 开售"倒计时
     * - 已到时间且已上架：正常显示购买按钮
     */
    @Schema(description = "定时上架时间(null=立即上架)", example = "2025-06-01T10:00:00")
    private LocalDateTime scheduledPublishTime;

    // ==================== 非数据库字段（秒杀相关） ====================

    /**
     * 秒杀剩余库存（运行时，来自 tb_seckill_ticket.stock）
     * <p>
     * 数据来源：LEFT JOIN tb_seckill_ticket sv ON v.id = sv.ticket_id → sv.stock
     * 适用范围：仅对秒杀票 (type=1) 有效
     * <p>
     * 注意事项：
     * - 这是真实的秒杀库存（可能比 MySQL 中的值更准确）
     * - 实际扣减使用 Redis Lua 脚本（防超卖）
     * - 此字段主要用于前端展示和后台管理
     */
    @TableField(exist = false)
    @Schema(description = "秒杀剩余库存(运行时)", example = "50")
    private Integer seckillStock;

    /**
     * 定时开始秒杀时间（运行时，前端提交用）
     * <p>
     * 实际存储位置：tb_seckill_ticket.scheduled_start_time
     * 用途：前端创建/编辑秒杀票时传递此参数
     */
    @TableField(exist = false)
    @Schema(description = "定时开始秒杀时间", example = "2025-01-01T00:00:00")
    private LocalDateTime scheduledStartTime;

    /**
     * 秒杀开始时间（运行时，来自 tb_seckill_ticket.begin_time）
     * <p>
     * SQL: LEFT JOIN tb_seckill_ticket sv ON v.id = sv.ticket_id → sv.begin_time
     * 用途：判断秒杀是否已经开始
     */
    @TableField(exist = false)
    @Schema(description = "秒杀开始时间(来自seckill_ticket)", example = "2025-06-01T10:00:00")
    private LocalDateTime beginTime;

    /**
     * 秒杀结束时间（运行时，来自 tb_seckill_ticket.end_time）
     * <p>
     * SQL: LEFT JOIN tb_seckill_ticket sv ON v.id = sv.ticket_id → sv.end_time
     * 用途：判断秒杀是否已经结束
     */
    @TableField(exist = false)
    @Schema(description = "秒杀结束时间(来自seckill_ticket)", example = "2025-06-02T23:59:59")
    private LocalDateTime endTime;

    // ==================== 非数据库字段（运行时计算） ====================

    /**
     * 是否为秒杀票标记（运行时计算）
     * <p>
     * 计算逻辑：isSeckill = (type == 1)
     * 用途：前端快速判断票种类型，避免重复判断 type 字段
     */
    @TableField(exist = false)
    @Schema(description = "是否秒杀票(运行时:type===1)", example = "false")
    private Boolean isSeckill;

    /**
     * 是否处于"可售卖状态"（运行时计算，前后端并发安全验证）
     * <p>
     * 判断逻辑：
     * - 普通票 (type=0)：status=1（上架）→ isActive=true
     * - 秒杀票 (type=1)：status=1 且 beginTime <= now <= endTime → isActive=true
     * <p>
     * 用途：
     * - 前端控制"立即购买"按钮的启用/禁用状态
     * - 后端下单前的二次校验（防止前端绕过限制）
     * - 并发安全：即使前端篡改参数，后端也会根据此字段拒绝请求
     */
    @TableField(exist = false)
    @Schema(description = "是否可售卖(运行时)", example = "true")
    private Boolean isActive;

    // ==================== 统计字段（运行时计算） ====================

    /**
     * 已售数量（运行时计算，非持久化维护）
     * <p>
     * 计算方式：
     * SELECT COUNT(*) FROM tb_ticket_order
     * WHERE ticket_id = ? AND status IN (2, 3)
     * - status=2：已支付
     * - status=3：已核销
     * <p>
     * 设计原则（方案A - 单一数据源）：
     * - 查询时实时计算，绝对准确，无漂移风险
     * - 通过领域事件驱动缓存刷新（支付/退款/取消/超时时清理Redis缓存）
     * - 不再在业务代码中直接自增/自减（避免双轨维护导致的不一致）
     * <p>
     * 性能优化：
     * - Redis缓存：票种列表查询时会缓存计算结果（TTL=30-40分钟随机）
     * - 缓存失效：订单状态变更时通过 OrderStatusChangeEvent 异步清理
     * - 懒加载策略：仅在查询时计算，不预计算或定时批量更新
     *
     * @see com.echo.service.impl.TicketServiceImpl#setSoldCountAndRemainingStock(List)
     * @see com.echo.event.listener.TicketSoldCountUpdater
     */
    @Schema(description = "已售数量（实时计算）", example = "350")
    private Integer soldCount;

    /**
     * 剩余库存（运行时计算）
     * <p>
     * 计算方式：remainingStock = MAX(0, stock - soldCount)
     * <p>
     * 注意：
     * - 仅对普通票 (type=0) 有效
     * - 秒杀票 (type=1) 的库存由 Redis 维护，使用 seckillStock 字段
     * - 用于前端展示和后台管理
     */
    @TableField(exist = false)
    @Schema(description = "剩余库存(运行时，仅普通票有效)", example = "650")
    private Integer remainingStock;

    /**
     * 扩展信息（运行时动态字段）
     * <p>
     * 数据类型：Map<String, Object>
     * 典型用途：
     * - 存储秒杀相关的时间信息（等待开始状态判断）
     * - 存储动态计算的营销标签（"即将售罄"、"热卖中"）
     * - 存储前端需要的自定义扩展数据
     * <p>
     * 使用示例：
     * <pre>
     * Map<String, Object> extInfo = new HashMap<>();
     * extInfo.put("seckillStartRemainSeconds", 3600);  // 秒杀开始倒计时
     * extInfo.put("salesRate", 0.35);  // 售罄率35%
     * extInfo.put("tags", Arrays.asList("热卖", "即将售罄"));
     * ticket.setExtInfo(extInfo);
     * </pre>
     */
    @TableField(exist = false)
    @Schema(description = "扩展信息(运行时动态数据)")
    private Map<String, Object> extInfo;

    // ==================== 时间字段 ====================

    /**
     * 更新时间（自动填充）
     * <p>
     * 数据库字段：tb_ticket.update_time
     * 类型：DATETIME
     * 填充策略：INSERT_UPDATE（插入和更新时自动填充）
     * <p>
     * 触发场景：
     * - 修改票种信息（标题、价格、库存等）
     * - 状态变更（上架/下架）
     * - 库存变化（购买、退款、补货）
     * <p>
     * 用途：
     * - 记录最后修改时间（审计日志）
     * - 缓存失效判断（比较 updateTime 判断是否需要刷新缓存）
     * - 数据同步（增量同步时使用 updateTime 作为条件）
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间", example = "2024-01-01T10:00:00")
    private LocalDateTime updateTime;

    /**
     * 创建时间（自动填充）
     * <p>
     * 数据库字段：tb_ticket.create_time
     * 类型：DATETIME
     * 填充策略：INSERT（插入时自动填充当前时间）
     * <p>
     * 用途：
     * - 记录票种的创建时间（审计日志）
     * - 数据分析（票种创建趋势、上新频率）
     * - 默认排序（如果其他排序条件相同，按创建时间排序）
     */
    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间", example = "2024-01-01T10:00:00")
    private LocalDateTime createTime;

    // ==================== 工具方法 ====================

    /**
     * 判断是否为普通票
     *
     * @return true=普通票(type==0), false=秒杀票或其他
     */
    public boolean isNormalTicket() {
        return type != null && type == 0;
    }

    /**
     * 判断是否为秒杀票
     *
     * @return true=秒杀票(type==1), false=普通票或其他
     */
    public boolean isSeckillTicket() {
        return type != null && type == 1;
    }

    /**
     * 判断是否已上架
     *
     * @return true=已上架(status==1), false=已下架
     */
    public boolean isOnSale() {
        return status != null && status == 1;
    }

    /**
     * 判断是否已下架
     *
     * @return true=已下架(status==0), false=已上架
     */
    public boolean isOffSale() {
        return status != null && status == 0;
    }

    /**
     * 判断是否有库存（普通票）
     *
     * @return true=有库存(stock > 0), false=无库存
     */
    public boolean hasStock() {
        return stock != null && stock > 0;
    }

    /**
     * 判断是否已售罄
     *
     * @return true=已售罄(stock <= 0), false=还有库存
     */
    public boolean isSoldOut() {
        return stock == null || stock <= 0;
    }

    /**
     * 获取支付金额（转换为元）
     *
     * @return 金额（元），如果 payValue 为 null 则返回 0.0
     */
    public double getPayValueInYuan() {
        return payValue != null ? payValue / 100.0 : 0.0;
    }

    /**
     * 获取实际价值（转换为元）
     *
     * @return 金额（元），如果 actualValue 为 null 则返回 0.0
     */
    public double getActualValueInYuan() {
        return actualValue != null ? actualValue / 100.0 : 0.0;
    }

    /**
     * 计算折扣率
     *
     * @return 折扣率（0.0-1.0），如果没有原价或原价为0则返回 1.0
     *         示例：payValue=500, actualValue=800 → 返回 0.625（6.25折）
     */
    public double getDiscountRate() {
        if (actualValue == null || actualValue == 0 || payValue == null) {
            return 1.0;
        }
        return (double) payValue / actualValue;
    }

    /**
     * 获取售罄率（已售/总库存）
     *
     * @return 售罄率（0.0-1.0），如果 totalStock 为0则返回 0.0
     *         示例：soldCount=350, totalStock=1000 → 返回 0.35（35%）
     */
    public double getSalesRate() {
        if (totalStock == null || totalStock == 0 || soldCount == null) {
            return 0.0;
        }
        return Math.min(1.0, (double) soldCount / totalStock);
    }
}
