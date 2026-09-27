package com.wxy.career.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 服务层 Token 对。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenPairVO {

    /**
     * Access Token。
     */
    private String accessToken;

    /**
     * Refresh Token。
     */
    private String refreshToken;

    /**
     * Access Token 有效期秒数。
     */
    private Long expiresIn;
}
