package com.wxy.career.common.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 配置。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /**
     * JWT HMAC 密钥，长度不得少于 32 字节。
     */
    @NotBlank
    private String secret;

    /**
     * Access Token 有效期，单位为分钟。
     */
    @Min(1)
    private long accessTokenExpireMinutes = 120L;

    /**
     * Refresh Token 有效期，单位为天。
     */
    @Min(1)
    private long refreshTokenExpireDays = 7L;
}
