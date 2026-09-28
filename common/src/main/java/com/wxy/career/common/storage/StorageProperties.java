package com.wxy.career.common.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 文件存储配置。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.storage.minio")
public class StorageProperties {

    /**
     * MinIO 服务地址，格式为协议://主机[:端口]，例如 http://localhost:9000。
     */
    private String endpoint;

    /**
     * MinIO Access Key，必须从环境变量 MINIO_ACCESS_KEY 读取。
     */
    private String accessKey;

    /**
     * MinIO Secret Key，必须从环境变量 MINIO_SECRET_KEY 读取。
     */
    private String secretKey;

    /**
     * 存储桶名称。
     */
    private String bucket;

    /**
     * 是否使用 HTTPS，endpoint 使用 https 协议时会自动按安全连接处理。
     */
    private boolean secure;
}
