# Echo 票务平台 数据库设计文档

## 一、基本信息

| 项 | 值 |
|---|---|
| 数据库 | MySQL 8.0 |
| 库名 | `echo` |
| 字符集 / 排序规则 | `utf8mb4` / `utf8mb4_unicode_ci` |
| 存储引擎 | InnoDB |
| ORM | MyBatis-Plus 3.5.3，实体位于 `com.echo.entity` |
| 建表脚本 | `deploy/mysql-init/01-schema.sql`（容器首次启动自动执行，仅结构） |
| 连接串 | `jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/echo?useSSL=false&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true` |

表总数为 19 张，按职责分为四类：

| 分类 | 数据表 |
|---|---|
| 用户与社区 | `tb_user`、`tb_user_info`、`tb_follow`、`tb_sign`、`tb_points_record`、`tb_coupon`、`tb_notification` |
| 演出与票务 | `tb_show`、`tb_show_type`、`tb_show_follow`、`tb_show_review`、`tb_show_review_comments`、`tb_ticket`、`tb_seckill_ticket`、`tb_seat` |
| 交易 | `tb_pre_order`、`tb_ticket_order` |
| 运营管理 | `tb_admin`、`operation_log` |

## 二、表关系

```
tb_user 1---1 tb_user_info
tb_user 1---n tb_follow            （user_id 关注 follow_user_id）
tb_user 1---n tb_ticket_order
tb_user 1---n tb_pre_order
tb_user 1---n tb_notification
tb_user 1---n tb_points_record
tb_user 1---n tb_coupon
tb_user 1---n tb_sign

tb_show_type 1---n tb_show
tb_show      1---n tb_ticket
tb_show      1---n tb_show_review
tb_show      1---n tb_show_follow
tb_ticket    1---1 tb_seckill_ticket   （ticket_id 同时是主键）
tb_ticket    1---n tb_seat
tb_ticket    1---n tb_pre_order
tb_ticket    1---n tb_ticket_order

tb_show_review 1---n tb_show_review_comments
tb_ticket_order 1---n tb_seat           （seat.order_id 回指已售订单）

tb_admin 1---n operation_log
```

状态流转的两条主链：

- 演出：`0 下架` → `1 上架` → `2 已结束`，上架前的定时发布由 `tb_show.scheduled_publish_time` 驱动。
- 订单：`0 预占` → `2 已支付` → `3 已核销`；`0 预占` 超时进入 `4 已取消`；支付后申请退款进入 `6 申请退款中`，审批通过为 `5 已退款`，驳回为 `7 已拒绝`。

## 三、表结构

以下字段、类型、默认值、索引均取自运行库的实际结构。

| 表 | 职责 |
|---|---|
| `tb_user` | 用户账号：手机号、密码哈希、昵称、头像、积分、状态 |
| `tb_user_info` | 用户扩展资料：性别、城市、生日、简介、信用分 |
| `tb_follow` | 用户之间的关注关系 |
| `tb_show` | 演出主表：名称、类型、地址与经纬度、票价、销量、评分、状态 |
| `tb_show_type` | 演出分类 |
| `tb_show_follow` | 用户收藏演出 |
| `tb_show_review` | 演出评测 |
| `tb_show_review_comments` | 评测下的评论 |
| `tb_ticket` | 票种：价格、库存、是否有座、状态、锁定标记 |
| `tb_seckill_ticket` | 秒杀配置：秒杀库存、开售与停售时间 |
| `tb_seat` | 座位：区域、排号、座号、状态与所属订单 |
| `tb_pre_order` | 预订单：下单到支付的中间态，30 分钟有效 |
| `tb_ticket_order` | 订单：数量、金额、支付方式、核销码、状态 |
| `tb_coupon` | 用户持有的优惠券 |
| `tb_points_record` | 积分流水，正数为获得、负数为消耗 |
| `tb_sign` | 用户签到记录 |
| `tb_notification` | 站内通知与已读状态 |
| `tb_admin` | 管理员账号与角色等级 |
| `operation_log` | 管理端操作日志，支撑审计与回滚 |

#### `operation_log`　操作日志表

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 | 自增 | 主键ID |
| `admin_id` | bigint | 是 | NULL | 管理员ID |
| `admin_name` | varchar(100) | 是 | NULL | 管理员名称 |
| `operation_type` | varchar(50) | 否 |  | 操作类型（CREATE/UPDATE/DELETE/BATCH等） |
| `target_type` | varchar(50) | 是 | NULL | 目标对象类型（show/ticket/user等） |
| `target_ids` | varchar(500) | 是 | NULL | 目标ID列表（逗号分隔） |
| `operation_detail` | text | 是 |  | 操作详情 |
| `result_status` | tinyint | 是 | 0 | 结果状态：0-进行中, 1-成功, 2-失败, 3-部分成功 |
| `success_count` | int | 是 | 0 | 成功数量 |
| `fail_count` | int | 是 | 0 | 失败数量 |
| `error_message` | text | 是 |  | 错误信息 |
| `ip_address` | varchar(45) | 是 | NULL | IP地址 |
| `user_agent` | varchar(500) | 是 | NULL | 用户代理 |
| `request_id` | varchar(100) | 是 | NULL | 请求唯一标识 |
| `duration_ms` | bigint | 是 | NULL | 耗时（毫秒） |
| `created_at` | datetime | 否 | CURRENT_TIMESTAMP | 创建时间 |

索引与约束：
- `PRIMARY KEY (`id`)`
- `KEY `idx_admin_id` (`admin_id`)`
- `KEY `idx_operation_type` (`operation_type`)`
- `KEY `idx_target_type` (`target_type`)`
- `KEY `idx_result_status` (`result_status`)`
- `KEY `idx_created_at` (`created_at`)`
- `KEY `idx_admin_type_time` (`admin_id`,`operation_type`,`created_at`)`

#### `tb_admin`　管理员表

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 | 自增 | 主键 |
| `user_id` | bigint | 否 |  | 关联用户ID |
| `name` | varchar(64) | 否 |  | 管理员姓名 |
| `role_level` | tinyint | 是 | 1 | 角色级别：0=超级管理员, 1=普通管理员 |
| `phone` | varchar(11) | 否 |  | 手机号 |
| `password` | varchar(255) | 是 | NULL | 密码，加密存储 |
| `status` | tinyint | 是 | 0 | 状态：0=正常, 1=禁用 |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | timestamp | 否 | CURRENT_TIMESTAMP | 更新时间 |

索引与约束：
- `PRIMARY KEY (`id`)`
- `KEY `idx_user_id` (`user_id`)`
- `KEY `idx_admin_user_id` (`user_id`)`
- `KEY `idx_admin_role_level` (`role_level`)`
- `KEY `idx_admin_status` (`status`)`

#### `tb_coupon`　优惠券表

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 | 自增 | 主键 |
| `user_id` | bigint | 否 |  | 用户ID |
| `name` | varchar(128) | 否 |  | 优惠券名称 |
| `discount_amount` | int | 是 | 0 | 优惠金额（分） |
| `min_order_amount` | int | 是 | 0 | 最低消费金额（分） |
| `status` | tinyint | 是 | 0 | 状态：0=未使用, 1=已使用, 2=已过期 |
| `expire_time` | datetime | 否 |  | 过期时间 |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |

索引与约束：
- `PRIMARY KEY (`id`)`
- `KEY `idx_user_id` (`user_id`)`
- `KEY `idx_status` (`status`)`
- `KEY `idx_coupon_user_id` (`user_id`)`
- `KEY `idx_coupon_status` (`status`)`
- `KEY `idx_coupon_expire_time` (`expire_time`)`

#### `tb_follow`

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 | 自增 | 主键 |
| `user_id` | bigint | 否 |  | 用户id |
| `follow_user_id` | bigint | 否 |  | 关联的用户id |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |

索引与约束：
- `PRIMARY KEY (`id`)`
- `UNIQUE KEY `uk_follow_user_follow` (`user_id`,`follow_user_id`)`
- `KEY `idx_follow_follow_user_id` (`follow_user_id`)`

#### `tb_notification`　通知表

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 | 自增 | 主键 |
| `user_id` | bigint | 否 |  | 用户id |
| `type` | tinyint | 是 | 0 | 通知类型 |
| `title` | varchar(255) | 否 |  | 通知标题 |
| `content` | varchar(1024) | 否 |  | 通知内容 |
| `is_read` | tinyint | 是 | 0 | 是否已读：0=未读, 1=已读 |
| `related_id` | bigint | 是 | NULL | 关联id |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |

索引与约束：
- `PRIMARY KEY (`id`)`
- `KEY `idx_user_id` (`user_id`)`
- `KEY `idx_user_read` (`user_id`,`is_read`)`
- `KEY `idx_notif_user_id` (`user_id`)`
- `KEY `idx_notif_is_read` (`is_read`)`
- `KEY `idx_notif_type` (`type`)`
- `KEY `idx_notif_create_time` (`create_time`)`

#### `tb_points_record`

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 | 自增 |  |
| `user_id` | bigint | 否 |  |  |
| `points` | int | 是 | 0 | 积分数（正数=获得，负数=消耗） |
| `type` | tinyint | 是 | 1 | 类型：1=获得, 2=消耗 |
| `description` | varchar(255) | 是 | NULL |  |
| `related_id` | bigint | 是 | NULL |  |
| `create_time` | datetime | 否 | CURRENT_TIMESTAMP |  |

索引与约束：
- `PRIMARY KEY (`id`)`
- `KEY `idx_user_id` (`user_id`)`
- `KEY `idx_create_time` (`create_time`)`
- `KEY `idx_points_user_id` (`user_id`)`
- `KEY `idx_points_type` (`type`)`
- `KEY `idx_points_create_time` (`create_time`)`

#### `tb_pre_order`　预订单表

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `temp_id` | varchar(64) | 否 |  | 临时ID（TEMP_格式） |
| `user_id` | bigint | 否 |  | 用户ID |
| `ticket_id` | bigint | 否 |  | 票种ID |
| `show_id` | bigint | 是 | NULL | 演出ID |
| `quantity` | int | 否 | 1 | 购买数量 |
| `pay_value` | bigint | 是 | NULL | 冻结销售单价(分) |
| `actual_value` | bigint | 是 | NULL | 冻结原价(分) |
| `status` | tinyint | 否 | 0 | 状态：0=待支付, 4=已取消 |
| `create_time` | datetime | 否 |  | 创建时间 |
| `expire_time` | datetime | 否 |  | 过期时间（创建时间+30分钟） |

索引与约束：
- `PRIMARY KEY (`temp_id`)`
- `KEY `idx_user_id` (`user_id`)`
- `KEY `idx_ticket_id` (`ticket_id`)`
- `KEY `idx_expire_time` (`expire_time`)`
- `KEY `idx_status` (`status`)`

#### `tb_seat`　座位表

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 | 自增 | 主键 |
| `ticket_id` | bigint | 否 |  | 票种id |
| `area` | varchar(50) | 否 |  | 区域 A区/B区/看台 |
| `row_num` | int | 否 |  | 排号 |
| `col_num` | int | 否 |  | 座号 |
| `status` | tinyint | 是 | 0 | 座位状态：0=可售, 1=已锁定, 2=已售, 3=维护中 |
| `order_id` | bigint | 是 | NULL | 订单id |
| `pre_order_id` | varchar(64) | 是 | NULL | 预订单临时ID |

索引与约束：
- `PRIMARY KEY (`id`)`
- `KEY `idx_ticket_id` (`ticket_id`)`
- `KEY `idx_ticket_status` (`ticket_id`,`status`)`
- `KEY `idx_seat_ticket_id` (`ticket_id`)`
- `KEY `idx_seat_status` (`status`)`
- `KEY `idx_seat_order_id` (`order_id`)`
- `KEY `idx_pre_order_id` (`pre_order_id`)`

#### `tb_seckill_ticket`　秒杀门票表，与门票是一对一关系

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `ticket_id` | bigint | 否 |  | 关联的门票id |
| `stock` | int | 是 | 0 | 秒杀库存 |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `begin_time` | timestamp | 是 | NULL | 开售时间 |
| `end_time` | timestamp | 是 | NULL | 停售时间 |
| `scheduled_start_time` | datetime | 是 | NULL | Scheduled start time |
| `update_time` | timestamp | 否 | CURRENT_TIMESTAMP | 更新时间 |

索引与约束：
- `PRIMARY KEY (`ticket_id`)`
- `UNIQUE KEY `uk_seckill_ticket_id` (`ticket_id`)`
- `KEY `idx_seckill_scheduled` (`scheduled_start_time`)`

#### `tb_show`

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 | 自增 | 主键 |
| `name` | varchar(128) | 否 |  | 演出名称 |
| `type_id` | bigint | 否 |  | 演出类型id |
| `images` | varchar(1024) | 否 |  | 演出宣传图，多个图片以逗号隔开 |
| `area` | varchar(128) | 是 | NULL | 商圈，例如五棵松、三里屯 |
| `address` | varchar(255) | 否 |  | 演出场地地址 |
| `x` | double | 否 |  | 经度 |
| `y` | double | 否 |  | 维度 |
| `avg_price` | decimal(10,2) | 是 | 0.00 | 平均票价（单位：分） |
| `sold` | int | 是 | 0 | 已售数量 |
| `comments` | int | 是 | 0 | 评测/评论数量 |
| `score` | decimal(3,2) | 是 | 0.00 | 平均评分（1-5星） |
| `open_hours` | varchar(32) | 是 | NULL | 营业时间 |
| `open_time` | datetime | 是 | NULL | 开演时间 |
| `end_time` | datetime | 是 | NULL | 结束时间 |
| `description` | text | 是 |  | 演出详细描述/简介 |
| `create_time` | timestamp | 是 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | timestamp | 是 | CURRENT_TIMESTAMP | 更新时间 |
| `status` | tinyint | 是 | 0 | 状态：0=下架, 1=上架, 2=已结束 |
| `scheduled_publish_time` | datetime | 是 | NULL | Scheduled publish time |

索引与约束：
- `PRIMARY KEY (`id`)`
- `KEY `foreign_key_type` (`type_id`)`
- `KEY `idx_show_scheduled` (`scheduled_publish_time`,`status`)`
- `KEY `idx_end_time` (`end_time`)`
- `KEY `idx_show_end_time` (`end_time`)`
- `KEY `idx_show_open_time` (`open_time`)`
- `KEY `idx_show_status` (`status`)`
- `KEY `idx_status_scheduled_publish_time` (`status`,`scheduled_publish_time`)`

#### `tb_show_follow`　演出关注表

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 | 自增 | 主键 |
| `user_id` | bigint | 否 |  | 用户ID |
| `show_id` | bigint | 否 |  | 演出ID |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 关注时间 |

索引与约束：
- `PRIMARY KEY (`id`)`
- `UNIQUE KEY `uk_user_show` (`user_id`,`show_id`)`
- `UNIQUE KEY `uk_show_follow_user_show` (`user_id`,`show_id`)`
- `KEY `idx_user_id` (`user_id`)`
- `KEY `idx_show_id` (`show_id`)`
- `KEY `idx_show_follow_show_id` (`show_id`)`

#### `tb_show_review`

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 | 自增 | 主键 |
| `show_id` | bigint | 否 |  | 演出id |
| `user_id` | bigint | 否 |  | 用户id |
| `title` | varchar(255) | 是 | '' | 评测标题（纯评分时为空字符串） |
| `score` | tinyint | 否 | 5 | 评分（1-5分） |
| `images` | varchar(2048) | 是 | '' |  |
| `content` | varchar(2048) | 是 | '' | 评测内容（纯评分时为空字符串） |
| `liked` | int | 是 | 0 | 点赞数 |
| `comments` | int | 是 | 0 | 评论数 |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | timestamp | 否 | CURRENT_TIMESTAMP | 更新时间 |

索引与约束：
- `PRIMARY KEY (`id`)`
- `KEY `idx_review_show_id` (`show_id`)`
- `KEY `idx_review_user_id` (`user_id`)`
- `KEY `idx_review_create_time` (`create_time`)`
- `KEY `idx_show_score` (`show_id`,`score`)`

#### `tb_show_review_comments`

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 | 自增 | 主键 |
| `user_id` | bigint | 否 |  | 用户id |
| `review_id` | bigint | 否 |  | 演出回顾id |
| `parent_id` | bigint | 否 |  | 关联的1级评论id，如果是一级评论，则值为0 |
| `answer_id` | bigint | 是 | NULL | 被回复的目标用户ID |
| `content` | varchar(255) | 否 |  | 回复的内容 |
| `liked` | int | 是 | NULL | 点赞数 |
| `status` | tinyint | 是 | NULL | 状态，0：正常，1：被举报，2：禁止查看 |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | timestamp | 否 | CURRENT_TIMESTAMP | 更新时间 |

索引与约束：
- `PRIMARY KEY (`id`)`

#### `tb_show_type`

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 | 自增 | 主键 |
| `name` | varchar(32) | 是 | NULL | 演出类型名称 |
| `icon` | varchar(255) | 是 | NULL | 图标 |
| `sort` | int | 是 | 0 | 排序权重 |
| `create_time` | timestamp | 是 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | timestamp | 是 | CURRENT_TIMESTAMP | 更新时间 |

索引与约束：
- `PRIMARY KEY (`id`)`

#### `tb_sign`

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 | 自增 | 主键 |
| `user_id` | bigint | 否 |  | 用户id |
| `year` | year | 否 |  | 签到的年 |
| `month` | tinyint | 否 |  | 签到的月 |
| `date` | date | 否 |  | 签到的日期 |
| `is_backup` | tinyint | 是 | NULL | 是否补签 |

索引与约束：
- `PRIMARY KEY (`id`)`

#### `tb_ticket`

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 | 自增 | 主键 |
| `show_id` | bigint | 否 |  | 演出id |
| `title` | varchar(255) | 否 |  | 门票标题 |
| `sub_title` | varchar(255) | 是 | NULL | 副标题 |
| `rules` | varchar(1024) | 是 | NULL | 使用规则 |
| `pay_value` | bigint | 否 |  | 支付金额（分） |
| `actual_value` | bigint | 否 |  | 抵扣金额（分） |
| `type` | tinyint | 是 | 0 | 票种类型：0=普通票, 1=秒杀票 |
| `stock` | int | 是 | 0 | 总库存数量 |
| `sold_count` | int | 否 | 0 | 已售数量 |
| `total_stock` | int | 否 | 0 | 总库存（固定不变，初始等于stock） |
| `status` | tinyint | 是 | 1 | 状态：0=下架, 1=上架 |
| `scheduled_publish_time` | datetime | 是 | NULL | Scheduled publish time |
| `locked` | int | 是 | 0 | 锁定库存（秒杀预占） |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | timestamp | 否 | CURRENT_TIMESTAMP | 更新时间 |
| `has_seats` | tinyint(1) | 否 | 0 | 是否需要选座: 0=否(默认), 1=是 |

索引与约束：
- `PRIMARY KEY (`id`)`
- `KEY `idx_ticket_scheduled` (`scheduled_publish_time`,`status`)`
- `KEY `idx_ticket_locked` (`locked`,`status`)`
- `KEY `idx_ticket_show_id` (`show_id`)`
- `KEY `idx_ticket_status` (`status`)`
- `KEY `idx_ticket_type` (`type`)`
- `KEY `idx_status_scheduled_publish_time` (`status`,`scheduled_publish_time`)`

#### `tb_ticket_order`

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 |  | 主键 |
| `user_id` | bigint | 否 |  | 下单的用户id |
| `ticket_id` | bigint | 否 |  | 购买的门票id |
| `show_id` | bigint | 是 | NULL | 演出ID(来自tb_ticket.show_id) |
| `quantity` | int | 是 | 1 | 购票数量 |
| `use_points` | int | 是 | 0 | 使用积分数量 |
| `points_discount` | int | 是 | 0 | 积分抵扣金额（分） |
| `pay_type` | tinyint | 是 | 1 | 支付方式：1=模拟支付 |
| `status` | tinyint | 是 | 0 | 订单状态：0=预占, 2=已支付, 3=已核销, 4=已取消, 5=已退款, 6=申请退款中, 7=已拒绝 |
| `verification_code` | varchar(8) | 是 | NULL | 核销码(8位大写字母数字) |
| `sub_order_index` | varchar(10) | 是 | NULL | 子订单序号(格式: 当前/总数，如 1/3) |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 下单时间 |
| `pay_time` | timestamp | 是 | NULL | 支付时间 |
| `use_time` | timestamp | 是 | NULL | 核销时间 |
| `refund_time` | timestamp | 是 | NULL | 退款时间 |
| `update_time` | timestamp | 否 | CURRENT_TIMESTAMP | 更新时间 |

索引与约束：
- `PRIMARY KEY (`id`)`
- `KEY `idx_order_user_id` (`user_id`)`
- `KEY `idx_order_ticket_id` (`ticket_id`)`
- `KEY `idx_order_status` (`status`)`
- `KEY `idx_order_create_time` (`create_time`)`
- `KEY `idx_verification_code` (`verification_code`)`
- `KEY `idx_show_id_status` (`show_id`,`status`)`
- `KEY `idx_ticket_id_status` (`ticket_id`,`status`)`

#### `tb_user`

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `id` | bigint | 否 | 自增 | 主键 |
| `phone` | varchar(11) | 否 |  | 手机号码 |
| `password` | varchar(128) | 是 | '' | 密码，加密存储 |
| `nick_name` | varchar(32) | 是 | '' | 昵称，默认是用户id |
| `icon` | varchar(255) | 是 | '' | 人物头像 |
| `points` | int | 是 | 0 | 积分余额 |
| `level` | int | 是 | 0 | 用户等级 |
| `status` | tinyint | 是 | 0 | 状态：0=正常, 1=禁用 |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | timestamp | 否 | CURRENT_TIMESTAMP | 更新时间 |

索引与约束：
- `PRIMARY KEY (`id`)`
- `UNIQUE KEY `uniqe_key_phone` (`phone`)`
- `KEY `idx_user_phone` (`phone`)`
- `KEY `idx_user_status` (`status`)`

#### `tb_user_info`

| 字段 | 类型 | 空 | 默认 | 说明 |
|---|---|:--:|---|---|
| `user_id` | bigint | 否 |  | 主键，用户id |
| `city` | varchar(64) | 是 | '' | 城市名称 |
| `introduce` | varchar(128) | 是 | NULL | 个人介绍，不要超过128个字符 |
| `fans` | int | 是 | 0 | 粉丝数 |
| `followee` | int | 是 | 0 | 关注数 |
| `gender` | tinyint | 是 | 0 | 性别，0：男，1：女，2：保密 |
| `birthday` | date | 是 | NULL | 生日 |
| `credits` | int | 是 | 0 | 积分 |
| `level` | int | 是 | 0 | 等级 |
| `create_time` | timestamp | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | timestamp | 否 | CURRENT_TIMESTAMP | 更新时间 |
| `credit_score` | int | 是 | 100 | 信用分 |
| `credit_penalty_end_time` | datetime | 是 | NULL | 信誉分惩罚结束时间（低于60分时设置为当前时间+24小时） |

索引与约束：
- `PRIMARY KEY (`user_id`)`

## 四、Redis 键设计

键名统一定义在 `com.echo.utils.RedisConstants`，业务代码通过常量拼接键名。

### 4.1 认证与限流

| 键 | 结构 | 说明 |
|---|---|---|
| `login:token:{token}` | String | 用户端登录态，值为用户 DTO |
| `login:code:{phone}` | String | 用户端短信验证码 |
| `login:pwd:fail:{phone}` | String | 密码登录失败计数 |
| `admin:token:{token}` | String | 管理端登录态 |
| `admin:code:{phone}` | String | 管理端登录验证码 |
| `admin:login:fail:{phone}` | String | 管理端登录失败计数 |
| `password:code:{phone}` | String | 修改密码验证码 |
| `csrf:token:{userId}` | String | 会话级 CSRF Token，滑动续期 |
| `rate:code:{phone}` | String | 验证码发送频控 |
| `rate:order:user:{userId}` | String | 下单频控（5 次/秒） |
| `rate:comment:{userId}` | String | 评论频控 |
| `rate:admin:code:{phone}` | String | 管理端验证码频控 |

### 4.2 业务缓存

| 键 | 结构 | 说明 |
|---|---|---|
| `cache:show:{id}` | String(JSON) | 演出详情，L1 Caffeine + L2 Redis |
| `cache:show-type:` | String(JSON) | 演出类型列表 |
| `cache:user:{id}` | String(JSON) | 用户概要 |
| `cache:user-info:{id}` | String(JSON) | 用户详细资料 |
| `cache:ticket:show:{showId}` | String(JSON) | 演出的票种列表 |
| `cache:statistics:` | String(JSON) | 统计面板数据 |
| `cache:points:balance:{userId}` | String | 积分余额 |
| `show:geo:{typeId}` | ZSet | 按演出类型分组的 GEO 索引，用于附近查询 |
| `review:liked:{reviewId}` | Set | 评测点赞用户集合 |
| `feed:` | ZSet | 关注流 |

### 4.3 秒杀

| 键 | 结构 | 说明 |
|---|---|---|
| `seckill:stock:{ticketId}` | String | 可售库存，Lua 原子扣减 |
| `seckill:order:{ticketId}` | Set | 已购用户集合，保证一人一单 |
| `seckill:soldout:{ticketId}` | String | 售罄标记，命中后直接快速失败 |
| `seckill:queue:{ticketId}` | ZSet | 排队队列，score 为入队时间戳 |
| `seckill:queue:active` | Set | 正在消费的票种集合 |
| `seckill:queue:result:{ticketId}:{userId}` | String | 排队结果，供前端轮询 |
| `seckill:idem:{ticketId}:{userId}` | String | 幂等键，拦截重复提交 |
| `pending:seckill:order:{userId}` | String | 秒杀预订单暂存，用于超时回滚 |
| `pending:pre_order:{tempId}` | String | 普通下单预订单暂存 |

### 4.4 分布式锁与社交

| 键 | 结构 | 说明 |
|---|---|---|
| `lock:show:{id}` / `lock:seat:{seatId}` / `lock:order:{id}` | String | 基于 `unlock.lua` 的分布式锁 |
| `follows:{userId}` / `fans:{userId}` | Set | 关注列表 / 粉丝列表 |
| `follow:count:{userId}` / `follow:fans:count:{userId}` | String | 关注数 / 粉丝数 |
| `show:follow:{userId}` / `show:followers:{showId}` | Set | 用户收藏演出 / 演出的收藏者 |
| `notification:unread:{userId}` | String | 未读通知数 |

## 五、枚举字典

枚举类位于 `com.echo.enums`，数据库以 `tinyint` 存储 `code`。

| 枚举 | 取值 |
|---|---|
| `OrderStatus` | 0 预占、2 已支付、3 已核销、4 已取消、5 已退款、6 申请退款中、7 已拒绝 |
| `PreOrderStatus` | 0 待支付、4 已取消 |
| `ShowStatus` | 0 下架、1 上架、2 已结束 |
| `TicketStatus` | 0 下架、1 上架 |
| `TicketType` | 0 普通票、1 秒杀票 |
| `TicketLock` | 0 正常可编辑、1 孤儿票种禁止上架 |
| `AdminRoleLevel` | 0 超级管理员、1 普通管理员 |
| `AdminStatus` | 0 正常、1 禁用 |
| `NotificationType` | 1 支付成功、2 关注动态、3 退款成功、4 核销成功、5 关注评测、6 演出开售 |
| `CouponStatus` | 0 未使用、1 已使用、2 已过期 |
| `OperationResultStatus` | 0 进行中、1 成功、2 失败、3 部分成功 |

`OrderStatus` 的编码不连续，`1` 当前未定义，读取时以 `fromCode` 的返回值判断。

## 六、一致性约束

数据库层依靠唯一索引承担幂等职责，业务层在其之上做前置判断：

| 约束 | 作用 |
|---|---|
| `tb_follow.uk_follow_user_follow(user_id, follow_user_id)` | 同一用户对同一目标只保留一条关注记录 |
| `tb_show_follow.uk_user_show(user_id, show_id)` | 同一用户对同一演出只收藏一次 |
| `tb_seckill_ticket` 主键 `ticket_id` | 秒杀配置与票种一对一 |
| `tb_user.phone` 唯一 | 手机号即账号 |
| `tb_pre_order` 主键 `temp_id` | 预订单以 TEMP_ 前缀的字符串作主键 |

秒杀链路的强一致由 Redis Lua 保证：库存扣减、已购集合写入、幂等键写入在同一个脚本内完成；MySQL 侧落单由 `SeckillOrderConsumer` 异步补偿，`PreOrderTimeoutScheduler` 定期核对并回滚孤儿预订单。

## 七、备份与恢复

```bash
# 全量备份
mysqldump -uroot -p --default-character-set=utf8mb4 --single-transaction --routines echo > echo_$(date +%F).sql

# 恢复
mysql -uroot -p --default-character-set=utf8mb4 echo < echo_2026-11-10.sql
```

导出时指定 `--default-character-set=utf8mb4`，保证中文注释与数据在导入后保持原样。

## 八、维护约定

- 新增字段时同步更新本文件的表结构章节与 `deploy/mysql-init/01-schema.sql`。
- 状态类字段的取值以 `com.echo.enums` 为准，字段注释与枚举保持一致。
- 查询走 `tb_show.status`、`tb_ticket.status`、`tb_ticket_order.status` 的语句均已有对应索引，新增查询条件时先确认索引覆盖。
- 大表分页使用 `PageQuery` 的游标模式（`CursorPageResult`），`offset` 随页码线性增长时改用游标分页。
