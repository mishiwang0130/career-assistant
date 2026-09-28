package com.wxy.career.common.storage;

import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 基于 MinIO 的文件存储实现。
 *
 * <p>物理对象名固定使用 UUID，业务传入的原始文件名只经过安全化后返回给业务层保存。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Slf4j
@Component
@Lazy
public class MinioFileStorage implements FileStorage {

    /**
     * 对象 key 中的年月格式。
     */
    private static final DateTimeFormatter YEAR_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * MinIO 客户端。
     */
    @Resource
    private MinioClient minioClient;

    /**
     * MinIO 配置。
     */
    @Resource
    private StorageProperties storageProperties;

    /**
     * 保存文件。
     *
     * @param content 文件字节内容
     * @param originalFilename 原始文件名
     * @param bizType 业务类型
     * @return 存储文件元数据
     */
    @Override
    public StoredFile store(byte[] content, String originalFilename, String bizType) {
        Long userId = currentUserId();
        ValidatedFile validatedFile = FileStorageValidator.validate(content, originalFilename);
        String objectKey = buildObjectKey(bizType, userId, validatedFile.extension());
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(storageProperties.getBucket())
                    .object(objectKey)
                    .stream(inputStream, content.length, -1)
                    .contentType(resolveContentType(validatedFile.extension()))
                    .build());
        } catch (Exception exception) {
            log.error("文件保存到 MinIO 失败, objectKey={}", objectKey, exception);
            throw new BizException(ErrorConstant.FILE_STORAGE_ERROR);
        }
        return new StoredFile(objectKey, validatedFile.fileName(), content.length, validatedFile.extension());
    }

    /**
     * 按对象 key 读取文件内容。
     *
     * @param objectKey 对象 key
     * @return 文件字节内容
     */
    @Override
    public byte[] load(String objectKey) {
        try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(storageProperties.getBucket())
                .object(objectKey)
                .build())) {
            return inputStream.readAllBytes();
        } catch (Exception exception) {
            log.error("从 MinIO 读取文件失败, objectKey={}", objectKey, exception);
            throw new BizException(ErrorConstant.FILE_STORAGE_ERROR);
        }
    }

    /**
     * 按对象 key 删除文件。
     *
     * @param objectKey 对象 key
     */
    @Override
    public void delete(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(storageProperties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            log.error("从 MinIO 删除文件失败, objectKey={}", objectKey, exception);
            throw new BizException(ErrorConstant.FILE_STORAGE_ERROR);
        }
    }

    /**
     * 安全化原始文件名。
     *
     * @param originalFilename 原始文件名
     * @return 安全化文件名
     */
    static String sanitizeFilename(String originalFilename) {
        return FileStorageValidator.sanitizeFilename(originalFilename);
    }

    /**
     * 构造对象 key。
     *
     * @param bizType 业务类型
     * @param userId 用户 ID
     * @param extension 小写扩展名
     * @return 对象 key
     */
    static String buildObjectKey(String bizType, long userId, String extension) {
        if (bizType == null || !StorageConstants.BIZ_TYPE_PATTERN.matcher(bizType).matches()) {
            throw new IllegalArgumentException("bizType 非法");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId 非法");
        }
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return bizType + "/" + userId + "/" + YearMonth.now().format(YEAR_MONTH_FORMATTER)
                + "/" + uuid + "." + extension;
    }

    /**
     * 获取当前登录用户 ID。
     *
     * @return 当前用户 ID
     */
    private Long currentUserId() {
        Long userId = LoginUserHolder.getUserId();
        if (userId == null || userId <= 0) {
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * 根据扩展名返回固定的内容类型，不读取也不信任前端 Content-Type。
     *
     * @param extension 小写扩展名
     * @return 内容类型
     */
    private String resolveContentType(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "txt" -> "text/plain";
            case "md" -> "text/markdown";
            default -> "application/octet-stream";
        };
    }
}
