package com.wxy.career.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新令牌请求。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Data
public class TokenRefreshReqVO {

    /**
     * 客户端持有的 Refresh Token。
     */
    @NotBlank(message = "刷新令牌不能为空")
    private String refreshToken;
}
