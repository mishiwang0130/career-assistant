package com.wxy.career.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户登录请求。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Data
public class UserLoginReqVO {

    /**
     * 用户名。
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 登录密码。
     */
    @NotBlank(message = "密码不能为空")
    private String password;
}
