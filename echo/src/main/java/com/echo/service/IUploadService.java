package com.echo.service;

import com.echo.dto.Result;
import org.springframework.web.multipart.MultipartFile;


/**
 * 文件上传服务接口
 *
 * @author tulip
 */
public interface IUploadService {

    /**
     * 上传博客图片（含用户身份验证）
     * <p>
     * 流程：
     * 1. 校验用户登录状态（Controller层已校验，此处记录上传者）
     * 2. 生成唯一文件名（UUID）
     * 3. 按 hash 分片存储到子目录（避免单目录文件过多）
     * 4. 保存到本地文件系统（生产环境可替换为 OSS）
     * 5. 记录操作日志（包含userId用于审计追踪）
     *
     * @param image 上传的图片文件
     * @param userId 用户ID（用于日志记录和审计追踪）
     * @return 文件名（相对路径）
     */
    Result<?> uploadImage(MultipartFile image, Long userId);

    /**
     * 上传用户头像
     * <p>
     * 流程：
     * 1. 校验文件类型和大小（≤5MB）
     * 2. 生成唯一文件名（UUID）
     * 3. 存储到avatars目录
     * 4. 返回相对路径
     *
     * @param avatarFile 头像图片文件
     * @param userId 用户ID（用于目录隔离或日志记录）
     * @return 头像路径
     */
    Result<?> uploadAvatar(MultipartFile avatarFile, Long userId);

    /**
     * 删除博客图片
     *
     * @param filename 要删除的文件名
     * @return 操作结果
     */
    Result<?> deleteBlogImg(String filename);
}
