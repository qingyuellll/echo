package com.echo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;


/**
 * 用户实体类
 * <p>
 * 对应数据库表：tb_user
 * 状态：0=正常, 1=禁用
 * <p>
 * ✅ 标准字段命名规范（Strict Mode v2.0）
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_user")
public class User implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "用户ID", example = "1")
    private Long id;

    /**
     * 手机号码 ✅ 标准字段
     */
    @Schema(description = "手机号码", requiredMode = Schema.RequiredMode.REQUIRED, example = "13800138000")
    private String phone;

    /**
     * 密码，加密存储
     * <p>
     * 该字段只参与服务端校验，禁止出现在任何 HTTP 响应中：管理端用户列表等接口
     * 直接返回实体，一旦序列化就会把哈希泄露给浏览器。
     */
    @Schema(description = "密码(加密存储)", hidden = true)
    @JsonIgnore
    private String password;

    /**
     * 用户昵称（禁止使用 name/username/nickname）
     */
    @Schema(description = "用户昵称", requiredMode = Schema.RequiredMode.REQUIRED, example = "张三丰")
    private String nickName;

    /**
     * 用户头像URL
     */
    @Schema(description = "头像URL", example = "https://example.com/avatar.jpg")
    private String icon = "";

    /**
     * 积分余额
     */
    @Schema(description = "积分余额", example = "5000")
    private Integer points;

    /**
     * 用户等级（根据积分自动计算）
     */
    @Schema(description = "用户等级", example = "3")
    private Integer level;

    /**
     * 状态：0=正常, 1=禁用
     */
    @Schema(description = "状态: 0=正常, 1=禁用", example = "0")
    private Integer status;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间", example = "2024-01-01T10:00:00")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间", example = "2024-01-01T10:00:00")
    private LocalDateTime updateTime;


}
