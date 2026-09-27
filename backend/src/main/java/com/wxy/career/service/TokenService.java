package com.wxy.career.service;

import com.wxy.career.common.auth.LoginTokenValidator;
import com.wxy.career.po.SysUser;
import com.wxy.career.vo.AuthRespVO;
import com.wxy.career.vo.TokenPairVO;

/**
 * 令牌服务。
 */
public interface TokenService extends LoginTokenValidator {

    TokenPairVO issueTokens(SysUser user);

    AuthRespVO refresh(String refreshToken);

    void revoke(String jti, Long userId);
}
