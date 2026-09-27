package com.wxy.career.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthRespVO {

    /**
     * Access Token。
     */
    private String accessToken;

    /**
     * Refresh Token。
     */
    private String refreshToken;

    /**
     * Token 类型，固定为 Bearer。
     */
    private String tokenType;

    /**
     * Access Token 有效期秒数。
     */
    private Long expiresIn;

    /**
     * 用户信息。
     */
    private UserInfoRespVO user;
}
