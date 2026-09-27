package com.wxy.career.service;

import com.wxy.career.common.auth.LoginTokenValidator;
import com.wxy.career.po.SysUser;
import com.wxy.career.vo.AuthRespVO;
import com.wxy.career.vo.TokenPairVO;

/**
 * 令牌服务。
 *
 * @author wxy
 * @date 2026-09-27
 */
public interface TokenService extends LoginTokenValidator {

    /**
     * 为用户签发并持久化一组 token。
     *
     * @param user 用户实体
     * @return token 对
     */
    TokenPairVO issueTokens(SysUser user);

    /**
     * 校验 Refresh Token 并执行轮换。
     *
     * @param refreshToken 客户端 Refresh Token
     * @return 新登录态
     */
    AuthRespVO refresh(String refreshToken);

    /**
     * 撤销指定 Access Token 及关联 Refresh Token。
     *
     * @param jti Access Token 唯一标识
     * @param userId 用户 ID
     */
    void revoke(String jti, Long userId);
}
