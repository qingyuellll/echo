package com.echo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_NORMAL = 0;
    public static final int STATUS_DISABLED = 1;
    public static final int STATUS_LOCKED = 2;

    private Long id;
    private String nickName;
    private String icon;
    private Integer status;

    public boolean isNormal() {
        return Objects.equals(status, STATUS_NORMAL);
    }

    public boolean isDisabled() {
        return Objects.equals(status, STATUS_DISABLED);
    }

    public boolean isLocked() {
        return Objects.equals(status, STATUS_LOCKED);
    }

    public boolean isActive() {
        return isNormal();
    }

    public boolean isValid() {
        if (id == null || id <= 0) {
            return false;
        }
        if (nickName == null || nickName.trim().isEmpty()) {
            return false;
        }
        return status != null && (status == STATUS_NORMAL || status == STATUS_DISABLED);
    }

    public boolean hasAvatar() {
        return icon != null && !icon.trim().isEmpty() && !icon.equals("default-avatar.png");
    }

    public String getStatusText() {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case STATUS_NORMAL:
                return "正常";
            case STATUS_DISABLED:
                return "已禁用";
            case STATUS_LOCKED:
                return "已锁定";
            default:
                return "未知(" + status + ")";
        }
    }

    public String getDisplayName() {
        if (nickName != null && !nickName.trim().isEmpty()) {
            return nickName.trim();
        }
        return "用户" + (id != null ? id : "");
    }

    public static UserDTO fromIdAndNick(Long id, String nickName) {
        return UserDTO.builder()
                .id(id)
                .nickName(nickName)
                .status(STATUS_NORMAL)
                .build();
    }

    public static UserDTO createNormalUser(Long id, String nickName, String avatarUrl) {
        return UserDTO.builder()
                .id(id)
                .nickName(nickName)
                .icon(avatarUrl != null ? avatarUrl : "")
                .status(STATUS_NORMAL)
                .build();
    }

    public static UserDTO createDisabledUser(Long id, String nickName) {
        return UserDTO.builder()
                .id(id)
                .nickName(nickName)
                .status(STATUS_DISABLED)
                .build();
    }

    @Override
    public String toString() {
        return String.format("UserDTO{id=%d, nickName='%s', hasAvatar=%b, status=%s(%d)}",
                id,
                nickName,
                hasAvatar(),
                getStatusText(),
                status);
    }
}
