package com.wxy.career.service;

import com.wxy.career.vo.AuthRespVO;
import com.wxy.career.vo.TokenRefreshReqVO;
import com.wxy.career.vo.UserInfoRespVO;
import com.wxy.career.vo.UserLoginReqVO;
import com.wxy.career.vo.UserRegisterReqVO;

/**
 * 账号服务。
 */
public interface AuthService {

    AuthRespVO register(UserRegisterReqVO reqVO);

    AuthRespVO login(UserLoginReqVO reqVO);

    AuthRespVO refresh(TokenRefreshReqVO reqVO);

    UserInfoRespVO getCurrentUser();

    void logout();
}
