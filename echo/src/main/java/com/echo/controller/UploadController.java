package com.echo.controller;

import com.echo.constants.AppMessages;
import com.echo.dto.AdminDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.echo.dto.Result;
import com.echo.dto.UserDTO;
import com.echo.service.IUploadService;
import com.echo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;


/**
 * 文件上传控制器
 *
 * @author tulip
 */
@Tag(name = "文件上传管理")
@Slf4j
@Validated
@RestController
@RequestMapping("/upload")
public class UploadController {

    @Resource
    private IUploadService uploadService;

    /**
     * 获取当前登录用户的ID（支持普通用户和管理员）
     *
     * @return 用户ID
     * @throws RuntimeException 如果未登录
     */
    private Long getCurrentUserId() {
        UserDTO user = UserHolder.getUser();
        AdminDTO admin = UserHolder.getAdmin();

        if (user != null) {
            log.debug("[UploadController] 当前身份: 普通用户, userId:{}", user.getId());
            return user.getId();
        }

        if (admin != null) {
            log.info("[UploadController] 当前身份: 管理员, adminId:{}", admin.getId());
            return admin.getId();
        }

        log.warn("[UploadController] 上传失败，用户未登录");
        throw new RuntimeException(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
    }

    @Operation(summary = "上传博客图片/演出海报")
    @PostMapping("blog")
    public Result<?> uploadImage(
            @NotNull(message = "文件不能为空")
            @Parameter(description = "图片文件（jpg/png/gif/webp，≤10MB）", required = true)
            @RequestParam("file") MultipartFile image) {

        try {
            Long userId = getCurrentUserId();

            log.info("[Controller] 收到博客图片/演出海报上传请求，userId:{}, 原始文件名:{}, 大小:{}bytes, 类型:{}",
                    userId,
                    image != null ? image.getOriginalFilename() : null,
                    image != null ? image.getSize() : 0,
                    image != null ? image.getContentType() : null);

            return uploadService.uploadImage(image, userId);
        } catch (RuntimeException e) {
            if (e.getMessage().contains(AppMessages.PERMISSION_USER_LOGIN_REQUIRED)) {
                return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
            }
            throw e;
        }
    }

    @Operation(summary = "上传用户头像")
    @PostMapping("avatar")
    public Result<?> uploadAvatar(
            @NotNull(message = "文件不能为空")
            @Parameter(description = "头像图片文件（jpg/png/gif/webp，≤5MB）", required = true)
            @RequestParam("file") MultipartFile avatarFile) {

        try {
            Long userId = getCurrentUserId();

            log.info("[Controller] 收到头像上传请求，userId:{}, 原始文件名:{}, 大小:{}bytes, 类型:{}",
                    userId,
                    avatarFile != null ? avatarFile.getOriginalFilename() : null,
                    avatarFile != null ? avatarFile.getSize() : 0,
                    avatarFile != null ? avatarFile.getContentType() : null);

            return uploadService.uploadImage(avatarFile, userId);
        } catch (RuntimeException e) {
            if (e.getMessage().contains(AppMessages.PERMISSION_USER_LOGIN_REQUIRED)) {
                return Result.fail(AppMessages.PERMISSION_USER_LOGIN_REQUIRED);
            }
            throw e;
        }
    }
}
