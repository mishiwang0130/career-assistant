package com.wxy.career.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新令牌请求。
 */
@Data
public class TokenRefreshReqVO {

    @NotBlank(message = "刷新令牌不能为空")
    private String refreshToken;
}
