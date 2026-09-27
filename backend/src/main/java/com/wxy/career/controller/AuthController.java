package com.wxy.career.controller;

import com.wxy.career.common.result.Result;
import com.wxy.career.service.AuthService;
import com.wxy.career.vo.AuthRespVO;
import com.wxy.career.vo.TokenRefreshReqVO;
import com.wxy.career.vo.UserInfoRespVO;
import com.wxy.career.vo.UserLoginReqVO;
import com.wxy.career.vo.UserRegisterReqVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号接口。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Resource
    private AuthService authService;

    @PostMapping("/register")
    public Result<AuthRespVO> register(@Valid @RequestBody UserRegisterReqVO reqVO) {
        return Result.success(authService.register(reqVO));
    }

    @PostMapping("/login")
    public Result<AuthRespVO> login(@Valid @RequestBody UserLoginReqVO reqVO) {
        return Result.success(authService.login(reqVO));
    }

    @PostMapping("/refresh")
    public Result<AuthRespVO> refresh(@Valid @RequestBody TokenRefreshReqVO reqVO) {
        return Result.success(authService.refresh(reqVO));
    }

    @GetMapping("/info")
    public Result<UserInfoRespVO> info() {
        return Result.success(authService.getCurrentUser());
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }
}
