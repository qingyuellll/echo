package com.echo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.echo.constants.SystemMessages;
import com.echo.enums.AdminRoleLevel;
import com.echo.enums.AdminStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理员实体类
 * <p>
 * 对应数据库表：tb_admin
 * 角色等级：0=超级管理员, 1=普通管理员
 * 状态：0=正常, 1=禁用
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_admin")
public class Admin implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID（自增）
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联用户ID（tb_user.id）
     */
    @NotNull(message = SystemMessages.ADMIN_USER_ID_NOT_EMPTY)
    private Long userId;

    /** 管理员姓名 */
    @NotBlank(message = SystemMessages.ADMIN_NAME_NOT_EMPTY)
    private String name;

    /**
     * 角色等级：SUPER_ADMIN=超级管理员, NORMAL_ADMIN=普通管理员
     */
    private AdminRoleLevel roleLevel;

    /**
     * 手机号码
     */
    private String phone;

    /**
     * 密码，加密存储
     * <p>
     * 只允许写入、禁止回读：管理端员工列表直接返回实体，若参与序列化会把哈希
     * 送到浏览器。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /**
     * 状态：NORMAL=正常, DISABLED=禁用
     */
    private AdminStatus status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
