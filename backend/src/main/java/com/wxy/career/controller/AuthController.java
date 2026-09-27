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
 *
 * @author wxy
 * @date 2026-09-27
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * 账号服务。
     */
    @Resource
    private AuthService authService;

    /**
     * 注册接口。
     *
     * @param reqVO 注册请求
     * @return 登录态
     */
    @PostMapping("/register")
    public Result<AuthRespVO> register(@Valid @RequestBody UserRegisterReqVO reqVO) {
        return Result.success(authService.register(reqVO));
    }

    /**
     * 登录接口。
     *
     * @param reqVO 登录请求
     * @return 登录态
     */
    @PostMapping("/login")
    public Result<AuthRespVO> login(@Valid @RequestBody UserLoginReqVO reqVO) {
        return Result.success(authService.login(reqVO));
    }

    /**
     * 刷新登录态。
     *
     * @param reqVO 刷新请求
     * @return 新登录态
     */
    @PostMapping("/refresh")
    public Result<AuthRespVO> refresh(@Valid @RequestBody TokenRefreshReqVO reqVO) {
        return Result.success(authService.refresh(reqVO));
    }

    /**
     * 获取当前登录用户。
     *
     * @return 当前用户信息
     */
    @GetMapping("/info")
    public Result<UserInfoRespVO> info() {
        return Result.success(authService.getCurrentUser());
    }

    /**
     * 退出当前登录会话。
     *
     * @return 成功响应
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }
}
