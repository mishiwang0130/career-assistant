package com.wxy.career.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户注册请求。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Data
public class UserRegisterReqVO {

    /**
     * 用户名，4-20 位字母、数字或下划线。
     */
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_]{4,20}$", message = "用户名须为4-20位字母、数字或下划线")
    private String username;

    /**
     * 登录密码，6-32 位。
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度须为6-32位")
    private String password;

    /**
     * 用户昵称，1-20 位。
     */
    @NotBlank(message = "昵称不能为空")
    @Size(min = 1, max = 20, message = "昵称长度须为1-20位")
    private String nickname;
}
