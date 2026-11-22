package com.echo.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.echo.constants.AppMessages;
import com.echo.dto.Result;
import com.echo.service.IUploadService;
import com.echo.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;


/**
 * 文件上传服务实现类
 *
 * @author tulip
 */
@Slf4j
@Service
public class UploadServiceImpl implements IUploadService {

    /**
     * 允许的图片Content-Type白名单
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES = new HashSet<>(Arrays.asList(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp"
    ));

    /**
     * 允许的文件扩展名白名单
     */
    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp"
    ));

    /**
     * 最大文件大小限制：10MB
     */
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    /**
     * 上传博客图片（含完整安全校验）
     * <p>
     * 处理流程：
     * 1. 文件非空校验
     * 2. 文件大小校验（≤10MB）
     * 3. Content-Type白名单校验
     * 4. 扩展名白名单校验（防双扩展名攻击）
     * 5. 路径遍历攻击检测
     * 6. 生成唯一文件名（UUID + 原始扩展名）
     * 7. 按 hash 值计算分片存储目录
     * 8. 创建目录（如不存在）
     * 9. 保存文件到目标路径
     *
     * @param image 上传的图片文件
     * @param userId 用户ID（用于审计日志）
     * @return 文件名（包含相对路径，如 /blogs/a/b/uuid.png）
     */
    @Override
    public Result<?> uploadImage(MultipartFile image, Long userId) {
        try {
            // ==================== 安全校验开始 ====================

            // 1. 文件非空校验
            if (image == null || image.isEmpty()) {
                log.warn("[上传失败] 文件为空, userId:{}", userId);
                return Result.fail(AppMessages.UPLOAD_FILE_EMPTY);
            }

            String originalFilename = image.getOriginalFilename();
            log.info("[文件上传] 接收博客图片，userId:{}, 原始文件名:{}, 大小:{}bytes, Content-Type:{}",
                    userId,
                    originalFilename,
                    image.getSize(),
                    image.getContentType());

            // 2. 文件大小校验（≤10MB）
            if (image.getSize() > MAX_FILE_SIZE) {
                log.warn("[上传失败] 文件过大，size:{}bytes, 限制:{}MB",
                        image.getSize(), MAX_FILE_SIZE / (1024 * 1024));
                return Result.fail(AppMessages.format(AppMessages.UPLOAD_FILE_SIZE_EXCEED, MAX_FILE_SIZE / (1024 * 1024) + "MB"));
            }

            // 3. Content-Type白名单校验
            String contentType = image.getContentType();
            if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
                log.warn("[上传失败] 不支持的文件类型，contentType:{}", contentType);
                return Result.fail(AppMessages.UPLOAD_FILE_FORMAT_INVALID);
            }

            // 4. 扩展名白名单校验（防双扩展名攻击）
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                log.warn("[上传失败] 原始文件名为空");
                return Result.fail(AppMessages.UPLOAD_FILENAME_EMPTY);
            }

            String extension = StrUtil.subAfter(originalFilename, ".", true);
            if (extension == null || !ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
                log.warn("[上传失败] 不支持的文件扩展名，extension:{}", extension);
                return Result.fail(AppMessages.UPLOAD_FILE_FORMAT_INVALID);
            }

            // 5. 路径遍历攻击检测（检查原始文件名是否包含../或..\\）
            if (containsPathTraversal(originalFilename)) {
                log.warn("[上传失败] 检测到路径遍历攻击尝试，filename:{}", originalFilename);
                return Result.fail(AppMessages.UPLOAD_FILENAME_INVALID);
            }

            // ==================== 安全校验结束，开始保存文件 ====================

            String fileName = createNewFileName(originalFilename);
            image.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName));

            log.info("[上传成功] 文件已保存，userId:未知, fileName:{}, 大小:{}bytes, 类型:{}",
                    fileName,
                    image.getSize(),
                    contentType);

            String accessUrl = "/ssn/imgs" + fileName;
            log.info("[上传成功] 访问URL:{}", accessUrl);

            return Result.ok(accessUrl);

        } catch (IOException e) {
            log.error("[上传失败] 文件保存异常，error:{}", e.getMessage(), e);
            throw new com.echo.exception.BusinessException(
                    AppMessages.FILE_UPLOAD_FAIL,
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    e);
        }
    }

    /**
     * 上传用户头像
     * <p>
     * 处理流程：
     * 1. 文件非空校验
     * 2. 文件大小校验（≤5MB，比博客图片更严格）
     * 3. Content-Type白名单校验
     * 4. 扩展名白名单校验
     * 5. 路径遍历攻击检测
     * 6. 生成唯一文件名（UUID）
     * 7. 存储到avatars目录
     * 8. 返回相对路径
     *
     * @param avatarFile 头像图片文件
     * @param userId 用户ID（用于日志记录）
     * @return 头像路径（如 /avatars/uuid.jpg）
     */
    @Override
    public Result<?> uploadAvatar(MultipartFile avatarFile, Long userId) {
        try {
            // 1. 文件非空校验
            if (avatarFile == null || avatarFile.isEmpty()) {
                log.warn("[头像上传失败] 文件为空");
                return Result.fail(AppMessages.UPLOAD_FILE_EMPTY);
            }

            String originalFilename = avatarFile.getOriginalFilename();
            log.debug("[头像上传] 接收文件，userId:{}, 原始文件名:{}, 大小:{}bytes, Content-Type:{}",
                    userId,
                    originalFilename,
                    avatarFile.getSize(),
                    avatarFile.getContentType());

            // 2. 文件大小校验（≤5MB，头像不需要太大）
            long MAX_AVATAR_SIZE = 5 * 1024 * 1024;
            if (avatarFile.getSize() > MAX_AVATAR_SIZE) {
                log.warn("[头像上传失败] 文件过大，size:{}bytes, 限制:5MB",
                        avatarFile.getSize());
                return Result.fail(AppMessages.UPLOAD_AVATAR_SIZE_EXCEED);
            }

            // 3. Content-Type白名单校验
            String contentType = avatarFile.getContentType();
            if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
                log.warn("[头像上传失败] 不支持的文件类型，contentType:{}", contentType);
                return Result.fail(AppMessages.UPLOAD_FILE_FORMAT_INVALID);
            }

            // 4. 扩展名白名单校验
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                log.warn("[头像上传失败] 原始文件名为空");
                return Result.fail(AppMessages.UPLOAD_FILENAME_EMPTY);
            }

            String extension = StrUtil.subAfter(originalFilename, ".", true);
            if (extension == null || !ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
                log.warn("[头像上传失败] 不支持的文件扩展名，extension:{}", extension);
                return Result.fail(AppMessages.UPLOAD_FILE_FORMAT_INVALID);
            }

            // 5. 路径遍历攻击检测
            if (containsPathTraversal(originalFilename)) {
                log.warn("[头像上传失败] 检测到路径遍历攻击尝试，filename:{}", originalFilename);
                return Result.fail(AppMessages.UPLOAD_FILENAME_INVALID);
            }

            // 6-8. 生成唯一文件名并保存到avatars目录
            String suffix = StrUtil.subAfter(originalFilename, ".", true);
            String name = UUID.randomUUID().toString().substring(0, 8); // 简短UUID

            File avatarsDir = new File(SystemConstants.IMAGE_UPLOAD_DIR, "/avatars");
            if (!avatarsDir.exists() && !avatarsDir.mkdirs()) {
                log.warn("[创建目录失败] 头像存储目录创建失败，path:{}", avatarsDir.getAbsolutePath());
            }

            String fileName = "/avatars/" + name + "." + suffix;
            avatarFile.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName));

            log.info("[头像上传成功] userId:{}, fileName:{}, 大小:{}bytes, 类型:{}",
                    userId,
                    fileName,
                    avatarFile.getSize(),
                    avatarFile.getContentType());

            String accessUrl = "/ssn/imgs" + fileName;
            log.info("[头像上传成功] 访问URL:{}", accessUrl);

            return Result.ok(accessUrl);

        } catch (IOException e) {
            log.error("[头像上传失败] 文件保存异常，userId:{}, error:{}", userId, e.getMessage(), e);
            throw new com.echo.exception.BusinessException(
                    AppMessages.FILE_UPLOAD_FAIL,
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    e);
        }
    }

    /**
     * 删除博客图片（含路径遍历攻击防护）
     * <p>
     * 安全检查：
     *  防止目录遍历攻击（校验 ../ ..\\ 等恶意路径）
     *  防止删除目录（校验是否为目录）
     *  防止删除系统关键文件（限制在uploads目录内）
     *
     * @param filename 要删除的文件名
     * @return 操作结果
     */
    @Override
    public Result<?> deleteBlogImg(String filename) {
        try {
            // 参数校验
            if (filename == null || filename.trim().isEmpty()) {
                log.warn("[删除失败] 文件名为空");
                return Result.fail(AppMessages.UPLOAD_FILENAME_CANNOT_EMPTY);
            }

            log.debug("[删除文件] 请求删除文件，filename:{}", filename);

            // 路径遍历攻击防护
            if (containsPathTraversal(filename)) {
                log.warn("[删除失败] 检测到路径遍历攻击尝试，filename:{}", filename);
                return Result.fail(AppMessages.UPLOAD_FILENAME_INVALID);
            }

            File file = new File(SystemConstants.IMAGE_UPLOAD_DIR, filename);

            // 安全检查：确保文件在上传目录内（防止删除系统文件）
            try {
                String canonicalPath = file.getCanonicalPath();
                String baseCanonicalPath = new File(SystemConstants.IMAGE_UPLOAD_DIR).getCanonicalPath();

                if (!canonicalPath.startsWith(baseCanonicalPath)) {
                    log.error("[删除失败] 尝试删除上传目录外的文件，filename:{}, canonicalPath:{}, basePath:{}",
                            filename, canonicalPath, baseCanonicalPath);
                    return Result.fail(AppMessages.UPLOAD_PATH_INVALID);
                }
            } catch (IOException e) {
                log.error("[删除失败] 路径解析异常，filename:{}", filename, e);
                return Result.fail(AppMessages.UPLOAD_PATH_PARSE_FAIL);
            }

            // 检查是否为目录
            if (file.isDirectory()) {
                log.warn("[删除失败] 文件名指向目录，拒绝删除，filename:{}", filename);
                return Result.fail(AppMessages.INVALID_FILENAME);
            }

            // 检查文件是否存在
            if (!file.exists()) {
                log.warn("[删除失败] 文件不存在，filename:{}", filename);
                return Result.fail(AppMessages.UPLOAD_FILE_NOT_EXIST);
            }

            // 执行删除
            boolean deleted = FileUtil.del(file);
            if (!deleted) {
                log.error("[删除失败] 文件删除操作返回false，filename:{}", filename);
                return Result.fail(AppMessages.UPLOAD_FILE_DELETE_FAIL);
            }

            log.info("[删除成功] 文件已删除，filename:{}", filename);
            return Result.ok();

        } catch (Exception e) {
            log.error("[删除失败] 删除文件异常，filename:{}, error:{}", filename, e.getMessage(), e);
            return Result.fail(AppMessages.UPLOAD_DELETE_FILE_FAIL_PREFIX + e.getMessage());
        }
    }

    /**
     * 检测字符串是否包含路径遍历攻击特征
     * <p>
     * 检测规则：
     * - 包含 "../" 或 "..\\" （Unix/Windows路径遍历）
     * - 以 ".." 开头
     * - 包含绝对路径前缀（如 / 或 C:）
     *
     * @param str 待检测字符串
     * @return true=包含路径遍历特征，false=安全
     */
    private boolean containsPathTraversal(String str) {
        if (str == null) return false;

        String lowerStr = str.toLowerCase();

        return lowerStr.contains("../")
                || lowerStr.contains("..\\")
                || lowerStr.startsWith("..")
                || lowerStr.startsWith("/")
                || lowerStr.startsWith("\\")
                || (lowerStr.length() > 2 && lowerStr.charAt(1) == ':');
    }

    /**
     * 生成唯一文件名（含分片存储路径）
     * <p>
     * 命名规则：
     * - 文件名：UUID 随机生成（防止冲突和遍历攻击）
     * - 扩展名：保留原始扩展名（已通过白名单校验）
     * - 存储路径：/blogs/{hash低4位}/{hash高4位}/{uuid}.{ext}
     * <p>
     * 分片存储优势：
     * - 避免单目录下文件过多导致文件系统性能下降
     * - 分布均匀，每个子目录最多 256 个文件
     * - 提高文件检索效率
     *
     * @param originalFilename 原始文件名（已通过安全校验）
     * @return 包含路径的新文件名
     */
    private String createNewFileName(String originalFilename) {
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;

        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR, StrUtil.format("/blogs/{}/{}", d1, d2));
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("[创建目录失败] 存储目录创建失败，path:{}", dir.getAbsolutePath());
        }

        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
