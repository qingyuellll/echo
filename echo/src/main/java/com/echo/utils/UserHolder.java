package com.echo.utils;

import com.echo.dto.AdminDTO;
import com.echo.dto.UserDTO;

/**
 * 用户上下文持有者(ThreadLocal)
 *
 * @author tulip
 */
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();
    private static final ThreadLocal<AdminDTO> adminTL = new ThreadLocal<>();
    private static final ThreadLocal<String> tokenHolder = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void saveAdmin(AdminDTO admin) {
        adminTL.set(admin);
    }

    public static AdminDTO getAdmin() {
        return adminTL.get();
    }

    /**
     * 仅清除用户身份（不影响管理员身份和Token）
     */
    public static void removeUser(){
        tl.remove();
    }

    /**
     * 仅清除管理员身份（不影响用户身份和Token）
     */
    public static void removeAdmin() {
        adminTL.remove();
    }

    /**
     * 清除所有身份信息（用户、管理员、Token）
     * 仅在请求结束时调用，避免影响同一线程中的其他请求
     */
    public static void clearAll() {
        tl.remove();
        adminTL.remove();
        tokenHolder.remove();
    }

    @Deprecated
    public static void removeUserLegacy(){
        clearAll();
    }

    public static void saveToken(String token) {
        tokenHolder.set(token);
    }

    public static String getToken() {
        return tokenHolder.get();
    }
}
