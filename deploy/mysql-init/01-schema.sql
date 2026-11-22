-- ============================================================
-- Echo 票务平台 建表脚本（仅结构）
-- 容器首次启动时由 MySQL 自动执行，建立 19 张业务表
-- 生成方式：mysqldump --no-data --single-transaction echo
-- 管理员账号：执行 echo/src/main/resources/init-super-admin.sql
-- 演示业务数据：按 docs/DEPLOY-部署文档.md 的说明自行准备
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `admin_id` bigint DEFAULT NULL COMMENT '管理员ID',
  `admin_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '管理员名称',
  `operation_type` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型（CREATE/UPDATE/DELETE/BATCH等）',
  `target_type` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目标对象类型（show/ticket/user等）',
  `target_ids` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目标ID列表（逗号分隔）',
  `operation_detail` text COLLATE utf8mb4_unicode_ci COMMENT '操作详情',
  `result_status` tinyint DEFAULT '0' COMMENT '结果状态：0-进行中, 1-成功, 2-失败, 3-部分成功',
  `success_count` int DEFAULT '0' COMMENT '成功数量',
  `fail_count` int DEFAULT '0' COMMENT '失败数量',
  `error_message` text COLLATE utf8mb4_unicode_ci COMMENT '错误信息',
  `ip_address` varchar(45) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'IP地址',
  `user_agent` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户代理',
  `request_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '请求唯一标识',
  `duration_ms` bigint DEFAULT NULL COMMENT '耗时（毫秒）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_admin_id` (`admin_id`),
  KEY `idx_operation_type` (`operation_type`),
  KEY `idx_target_type` (`target_type`),
  KEY `idx_result_status` (`result_status`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_admin_type_time` (`admin_id`,`operation_type`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=52 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_admin` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '关联用户ID',
  `name` varchar(64) NOT NULL COMMENT '管理员姓名',
  `role_level` tinyint DEFAULT '1' COMMENT '角色级别：0=超级管理员, 1=普通管理员',
  `phone` varchar(11) NOT NULL COMMENT '手机号',
  `password` varchar(255) DEFAULT NULL COMMENT '密码，加密存储',
  `status` tinyint DEFAULT '0' COMMENT '状态：0=正常, 1=禁用',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_admin_user_id` (`user_id`),
  KEY `idx_admin_role_level` (`role_level`),
  KEY `idx_admin_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理员表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_coupon` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  `name` varchar(128) NOT NULL COMMENT '优惠券名称',
  `discount_amount` int unsigned DEFAULT '0' COMMENT '优惠金额（分）',
  `min_order_amount` int unsigned DEFAULT '0' COMMENT '最低消费金额（分）',
  `status` tinyint DEFAULT '0' COMMENT '状态：0=未使用, 1=已使用, 2=已过期',
  `expire_time` datetime NOT NULL COMMENT '过期时间',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_coupon_user_id` (`user_id`),
  KEY `idx_coupon_status` (`status`),
  KEY `idx_coupon_expire_time` (`expire_time`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='优惠券表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_follow` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '用户id',
  `follow_user_id` bigint unsigned NOT NULL COMMENT '关联的用户id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_follow_user_follow` (`user_id`,`follow_user_id`),
  KEY `idx_follow_follow_user_id` (`follow_user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=47 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=COMPACT;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_notification` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '用户id',
  `type` tinyint DEFAULT '0' COMMENT '通知类型',
  `title` varchar(255) NOT NULL COMMENT '通知标题',
  `content` varchar(1024) NOT NULL COMMENT '通知内容',
  `is_read` tinyint DEFAULT '0' COMMENT '是否已读：0=未读, 1=已读',
  `related_id` bigint DEFAULT NULL COMMENT '关联id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_user_read` (`user_id`,`is_read`),
  KEY `idx_notif_user_id` (`user_id`),
  KEY `idx_notif_is_read` (`is_read`),
  KEY `idx_notif_type` (`type`),
  KEY `idx_notif_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=140 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_points_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `points` int DEFAULT '0' COMMENT '积分数（正数=获得，负数=消耗）',
  `type` tinyint DEFAULT '1' COMMENT '类型：1=获得, 2=消耗',
  `description` varchar(255) DEFAULT NULL,
  `related_id` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_points_user_id` (`user_id`),
  KEY `idx_points_type` (`type`),
  KEY `idx_points_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=173 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_pre_order` (
  `temp_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '临时ID（TEMP_格式）',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `ticket_id` bigint NOT NULL COMMENT '票种ID',
  `show_id` bigint DEFAULT NULL COMMENT '演出ID',
  `quantity` int NOT NULL DEFAULT '1' COMMENT '购买数量',
  `pay_value` bigint DEFAULT NULL COMMENT '冻结销售单价(分)',
  `actual_value` bigint DEFAULT NULL COMMENT '冻结原价(分)',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0=待支付, 4=已取消',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `expire_time` datetime NOT NULL COMMENT '过期时间（创建时间+30分钟）',
  PRIMARY KEY (`temp_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_ticket_id` (`ticket_id`),
  KEY `idx_expire_time` (`expire_time`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预订单表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_seat` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `ticket_id` bigint unsigned NOT NULL COMMENT '票种id',
  `area` varchar(50) NOT NULL COMMENT '区域 A区/B区/看台',
  `row_num` int NOT NULL COMMENT '排号',
  `col_num` int NOT NULL COMMENT '座号',
  `status` tinyint DEFAULT '0' COMMENT '座位状态：0=可售, 1=已锁定, 2=已售, 3=维护中',
  `order_id` bigint unsigned DEFAULT NULL COMMENT '订单id',
  `pre_order_id` varchar(64) DEFAULT NULL COMMENT '预订单临时ID',
  PRIMARY KEY (`id`),
  KEY `idx_ticket_id` (`ticket_id`),
  KEY `idx_ticket_status` (`ticket_id`,`status`),
  KEY `idx_seat_ticket_id` (`ticket_id`),
  KEY `idx_seat_status` (`status`),
  KEY `idx_seat_order_id` (`order_id`),
  KEY `idx_pre_order_id` (`pre_order_id`)
) ENGINE=InnoDB AUTO_INCREMENT=6098 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='座位表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_seckill_ticket` (
  `ticket_id` bigint unsigned NOT NULL COMMENT '关联的门票id',
  `stock` int unsigned DEFAULT '0' COMMENT '秒杀库存',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `begin_time` timestamp NULL DEFAULT NULL COMMENT '开售时间',
  `end_time` timestamp NULL DEFAULT NULL COMMENT '停售时间',
  `scheduled_start_time` datetime DEFAULT NULL COMMENT 'Scheduled start time',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`ticket_id`) USING BTREE,
  UNIQUE KEY `uk_seckill_ticket_id` (`ticket_id`),
  KEY `idx_seckill_scheduled` (`scheduled_start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=COMPACT COMMENT='秒杀门票表，与门票是一对一关系';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_show` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '演出名称',
  `type_id` bigint unsigned NOT NULL COMMENT '演出类型id',
  `images` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '演出宣传图，多个图片以逗号隔开',
  `area` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '商圈，例如五棵松、三里屯',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '演出场地地址',
  `x` double unsigned NOT NULL COMMENT '经度',
  `y` double unsigned NOT NULL COMMENT '维度',
  `avg_price` decimal(10,2) DEFAULT '0.00' COMMENT '平均票价（单位：分）',
  `sold` int unsigned DEFAULT '0' COMMENT '已售数量',
  `comments` int unsigned DEFAULT '0' COMMENT '评测/评论数量',
  `score` decimal(3,2) DEFAULT '0.00' COMMENT '平均评分（1-5星）',
  `open_hours` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '营业时间',
  `open_time` datetime DEFAULT NULL COMMENT '开演时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `description` text COLLATE utf8mb4_general_ci COMMENT '演出详细描述/简介',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `status` tinyint DEFAULT '0' COMMENT '状态：0=下架, 1=上架, 2=已结束',
  `scheduled_publish_time` datetime DEFAULT NULL COMMENT 'Scheduled publish time',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `foreign_key_type` (`type_id`) USING BTREE,
  KEY `idx_show_scheduled` (`scheduled_publish_time`,`status`),
  KEY `idx_end_time` (`end_time`),
  KEY `idx_show_end_time` (`end_time`),
  KEY `idx_show_open_time` (`open_time`),
  KEY `idx_show_status` (`status`),
  KEY `idx_status_scheduled_publish_time` (`status`,`scheduled_publish_time`)
) ENGINE=InnoDB AUTO_INCREMENT=29 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=COMPACT;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_show_follow` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '用户ID',
  `show_id` bigint unsigned NOT NULL COMMENT '演出ID',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_show` (`user_id`,`show_id`),
  UNIQUE KEY `uk_show_follow_user_show` (`user_id`,`show_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_show_id` (`show_id`),
  KEY `idx_show_follow_show_id` (`show_id`)
) ENGINE=InnoDB AUTO_INCREMENT=60 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='演出关注表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_show_review` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `show_id` bigint NOT NULL COMMENT '演出id',
  `user_id` bigint unsigned NOT NULL COMMENT '用户id',
  `title` varchar(255) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '评测标题（纯评分时为空字符串）',
  `score` tinyint NOT NULL DEFAULT '5' COMMENT '评分（1-5分）',
  `images` varchar(2048) COLLATE utf8mb4_general_ci DEFAULT '',
  `content` varchar(2048) COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '评测内容（纯评分时为空字符串）',
  `liked` int unsigned DEFAULT '0' COMMENT '点赞数',
  `comments` int unsigned DEFAULT '0' COMMENT '评论数',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_review_show_id` (`show_id`),
  KEY `idx_review_user_id` (`user_id`),
  KEY `idx_review_create_time` (`create_time`),
  KEY `idx_show_score` (`show_id`,`score`)
) ENGINE=InnoDB AUTO_INCREMENT=65 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=COMPACT;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_show_review_comments` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '用户id',
  `review_id` bigint unsigned NOT NULL COMMENT '演出回顾id',
  `parent_id` bigint unsigned NOT NULL COMMENT '关联的1级评论id，如果是一级评论，则值为0',
  `answer_id` bigint DEFAULT NULL COMMENT '被回复的目标用户ID',
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '回复的内容',
  `liked` int unsigned DEFAULT NULL COMMENT '点赞数',
  `status` tinyint unsigned DEFAULT NULL COMMENT '状态，0：正常，1：被举报，2：禁止查看',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=54 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=COMPACT;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_show_type` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '演出类型名称',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '图标',
  `sort` int unsigned DEFAULT '0' COMMENT '排序权重',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=COMPACT;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_sign` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '用户id',
  `year` year NOT NULL COMMENT '签到的年',
  `month` tinyint NOT NULL COMMENT '签到的月',
  `date` date NOT NULL COMMENT '签到的日期',
  `is_backup` tinyint unsigned DEFAULT NULL COMMENT '是否补签',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=COMPACT;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_ticket` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `show_id` bigint unsigned NOT NULL COMMENT '演出id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '门票标题',
  `sub_title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '副标题',
  `rules` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '使用规则',
  `pay_value` bigint unsigned NOT NULL COMMENT '支付金额（分）',
  `actual_value` bigint unsigned NOT NULL COMMENT '抵扣金额（分）',
  `type` tinyint DEFAULT '0' COMMENT '票种类型：0=普通票, 1=秒杀票',
  `stock` int unsigned DEFAULT '0' COMMENT '总库存数量',
  `sold_count` int NOT NULL DEFAULT '0' COMMENT '已售数量',
  `total_stock` int unsigned NOT NULL DEFAULT '0' COMMENT '总库存（固定不变，初始等于stock）',
  `status` tinyint DEFAULT '1' COMMENT '状态：0=下架, 1=上架',
  `scheduled_publish_time` datetime DEFAULT NULL COMMENT 'Scheduled publish time',
  `locked` int unsigned DEFAULT '0' COMMENT '锁定库存（秒杀预占）',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `has_seats` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否需要选座: 0=否(默认), 1=是',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_ticket_scheduled` (`scheduled_publish_time`,`status`),
  KEY `idx_ticket_locked` (`locked`,`status`),
  KEY `idx_ticket_show_id` (`show_id`),
  KEY `idx_ticket_status` (`status`),
  KEY `idx_ticket_type` (`type`),
  KEY `idx_status_scheduled_publish_time` (`status`,`scheduled_publish_time`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=COMPACT;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_ticket_order` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '下单的用户id',
  `ticket_id` bigint unsigned NOT NULL COMMENT '购买的门票id',
  `show_id` bigint DEFAULT NULL COMMENT '演出ID(来自tb_ticket.show_id)',
  `quantity` int unsigned DEFAULT '1' COMMENT '购票数量',
  `use_points` int unsigned DEFAULT '0' COMMENT '使用积分数量',
  `points_discount` int unsigned DEFAULT '0' COMMENT '积分抵扣金额（分）',
  `pay_type` tinyint DEFAULT '1' COMMENT '支付方式：1=模拟支付',
  `status` tinyint DEFAULT '0' COMMENT '订单状态：0=预占, 2=已支付, 3=已核销, 4=已取消, 5=已退款, 6=申请退款中, 7=已拒绝',
  `verification_code` varchar(8) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '核销码(8位大写字母数字)',
  `sub_order_index` varchar(10) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '子订单序号(格式: 当前/总数，如 1/3)',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `pay_time` timestamp NULL DEFAULT NULL COMMENT '支付时间',
  `use_time` timestamp NULL DEFAULT NULL COMMENT '核销时间',
  `refund_time` timestamp NULL DEFAULT NULL COMMENT '退款时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_order_user_id` (`user_id`),
  KEY `idx_order_ticket_id` (`ticket_id`),
  KEY `idx_order_status` (`status`),
  KEY `idx_order_create_time` (`create_time`),
  KEY `idx_verification_code` (`verification_code`),
  KEY `idx_show_id_status` (`show_id`,`status`),
  KEY `idx_ticket_id_status` (`ticket_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=COMPACT;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_user` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '手机号码',
  `password` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '密码，加密存储',
  `nick_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '昵称，默认是用户id',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '人物头像',
  `points` int unsigned DEFAULT '0' COMMENT '积分余额',
  `level` int unsigned DEFAULT '0' COMMENT '用户等级',
  `status` tinyint DEFAULT '0' COMMENT '状态：0=正常, 1=禁用',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uniqe_key_phone` (`phone`) USING BTREE,
  KEY `idx_user_phone` (`phone`),
  KEY `idx_user_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=1054 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=COMPACT;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tb_user_info` (
  `user_id` bigint unsigned NOT NULL COMMENT '主键，用户id',
  `city` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '城市名称',
  `introduce` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '个人介绍，不要超过128个字符',
  `fans` int unsigned DEFAULT '0' COMMENT '粉丝数',
  `followee` int unsigned DEFAULT '0' COMMENT '关注数',
  `gender` tinyint unsigned DEFAULT '0' COMMENT '性别，0：男，1：女，2：保密',
  `birthday` date DEFAULT NULL COMMENT '生日',
  `credits` int unsigned DEFAULT '0' COMMENT '积分',
  `level` int unsigned DEFAULT '0' COMMENT '等级',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `credit_score` int unsigned DEFAULT '100' COMMENT '信用分',
  `credit_penalty_end_time` datetime DEFAULT NULL COMMENT '信誉分惩罚结束时间（低于60分时设置为当前时间+24小时）',
  PRIMARY KEY (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=COMPACT;
/*!40101 SET character_set_client = @saved_cs_client */;

SET FOREIGN_KEY_CHECKS = 1;
