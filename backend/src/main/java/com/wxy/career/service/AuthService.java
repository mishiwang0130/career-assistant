package com.wxy.career.service;

import com.wxy.career.vo.AuthRespVO;
import com.wxy.career.vo.TokenRefreshReqVO;
import com.wxy.career.vo.UserInfoRespVO;
import com.wxy.career.vo.UserLoginReqVO;
import com.wxy.career.vo.UserRegisterReqVO;

/**
 * 账号服务。
 *
 * @author wxy
 * @date 2026-09-27
 */
public interface AuthService {

    /**
     * 注册用户并签发登录态。
     *
     * @param reqVO 注册请求
     * @return 登录响应
     */
    AuthRespVO register(UserRegisterReqVO reqVO);

    /**
     * 用户登录。
     *
     * @param reqVO 登录请求
     * @return 登录响应
     */
    AuthRespVO login(UserLoginReqVO reqVO);

    /**
     * 刷新登录态。
     *
     * @param reqVO 刷新请求
     * @return 新登录响应
     */
    AuthRespVO refresh(TokenRefreshReqVO reqVO);

    /**
     * 获取当前登录用户。
     *
     * @return 当前用户信息
     */
    UserInfoRespVO getCurrentUser();

    /**
     * 退出当前登录会话。
     */
    void logout();
}
