package com.wxy.career.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 服务层 Token 对。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenPairVO {

    private String accessToken;

    private String refreshToken;

    private Long expiresIn;
}
