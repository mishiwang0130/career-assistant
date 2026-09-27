package com.wxy.career.common.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 配置。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    @NotBlank
    private String secret;

    @Min(1)
    private long accessTokenExpireMinutes = 120L;

    @Min(1)
    private long refreshTokenExpireDays = 7L;
}
