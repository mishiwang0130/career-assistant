package com.wxy.career.common.storage;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.net.URI;

/**
 * MinIO 客户端配置。
 *
 * <p>客户端采用懒加载，避免应用启动时因 MinIO 暂不可用而阻断其它模块。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {

    /**
     * 创建 MinioClient。
     *
     * @param properties MinIO 配置
     * @return MinIO 客户端
     */
    @Bean
    @Lazy
    public MinioClient minioClient(StorageProperties properties) {
        boolean secure = properties.isSecure();
        String endpoint = properties.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("MinIO endpoint 不能为空");
        }
        if (!endpoint.contains("://")) {
            endpoint = (secure ? "https://" : "http://") + endpoint;
        }
        URI uri = URI.create(endpoint);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("MinIO endpoint 格式非法");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            secure = true;
        }
        int port = uri.getPort();
        if (port < 0) {
            port = secure ? 443 : 9000;
        }
        return MinioClient.builder()
                .endpoint(host, port, secure)
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}
