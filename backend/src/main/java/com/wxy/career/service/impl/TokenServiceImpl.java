package com.wxy.career.service.impl;

import com.wxy.career.common.auth.JwtService;
import com.wxy.career.common.auth.LoginUser;
import com.wxy.career.common.config.JwtProperties;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.mapper.SysRefreshTokenMapper;
import com.wxy.career.mapper.SysTokenMapper;
import com.wxy.career.mapper.SysUserMapper;
import com.wxy.career.po.SysRefreshToken;
import com.wxy.career.po.SysToken;
import com.wxy.career.po.SysUser;
import com.wxy.career.service.TokenService;
import com.wxy.career.util.RefreshTokenUtil;
import com.wxy.career.vo.AuthRespVO;
import com.wxy.career.vo.TokenPairVO;
import com.wxy.career.vo.UserInfoRespVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 令牌服务实现。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Service
public class TokenServiceImpl implements TokenService {

    /**
     * 令牌类型。
     */
    private static final String TOKEN_TYPE = "Bearer";

    /**
     * JWT 签发与解析服务。
     */
    @Resource
    private JwtService jwtService;

    /**
     * JWT 配置。
     */
    @Resource
    private JwtProperties jwtProperties;

    /**
     * 用户 Mapper。
     */
    @Resource
    private SysUserMapper sysUserMapper;

    /**
     * Access Token Mapper。
     */
    @Resource
    private SysTokenMapper sysTokenMapper;

    /**
     * Refresh Token Mapper。
     */
    @Resource
    private SysRefreshTokenMapper sysRefreshTokenMapper;

    /**
     * 为用户签发并持久化一组 token。
     *
     * @param user 用户实体
     * @return token 对
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TokenPairVO issueTokens(SysUser user) {
        return createTokenPair(user);
    }

    /**
     * 校验 Refresh Token 并执行轮换。
     *
     * @param refreshToken 客户端 Refresh Token
     * @return 新登录态
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthRespVO refresh(String refreshToken) {
        // Refresh Token 只以摘要形式落库，避免数据库泄露后可直接使用原始令牌。
        String tokenHash = RefreshTokenUtil.sha256(refreshToken);
        SysRefreshToken storedToken = sysRefreshTokenMapper.selectByTokenHash(tokenHash);
        // 过期、撤销或逻辑删除的刷新令牌都视为不可继续使用。
        if (storedToken == null || isInvalid(storedToken.getRevoked(), storedToken.getExpiresAt())) {
            throw new BizException(ErrorConstant.REFRESH_TOKEN_INVALID);
        }
        SysUser user = sysUserMapper.selectById(storedToken.getUserId());
        if (user == null) {
            throw new BizException(ErrorConstant.REFRESH_TOKEN_INVALID);
        }
        // 通过条件更新抢占旧 Refresh Token，防止并发请求重复轮换同一令牌。
        int updated = sysRefreshTokenMapper.revokeIfActive(storedToken.getId(), storedToken.getUserId());
        if (updated == 0) {
            throw new BizException(ErrorConstant.REFRESH_TOKEN_INVALID);
        }
        // 刷新采用轮换策略，旧 Access Token 和 Refresh Token 必须同时失效。
        revokeAccessToken(storedToken.getAccessJti(), storedToken.getUserId());
        return buildAuthResp(user, createTokenPair(user));
    }

    /**
     * 撤销指定 Access Token 及关联 Refresh Token。
     *
     * @param jti Access Token 唯一标识
     * @param userId 用户 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(String jti, Long userId) {
        // 退出登录既要撤销当前 Access Token，也要撤销与它关联的 Refresh Token。
        revokeAccessToken(jti, userId);
        LocalDateTime now = LocalDateTime.now();
        SysRefreshToken refreshToken = sysRefreshTokenMapper.selectActiveByAccessJti(jti);
        if (refreshToken != null) {
            refreshToken.setRevoked(SysRefreshToken.STATUS_REVOKED);
            refreshToken.setRevokedAt(now);
            refreshToken.setUpdateBy(userId);
            sysRefreshTokenMapper.updateById(refreshToken);
        }
    }

    /**
     * 校验 Access Token 对应的数据库记录是否有效。
     *
     * @param loginUser 当前令牌信息
     * @return true 表示令牌有效
     */
    @Override
    public boolean isValid(LoginUser loginUser) {
        SysToken storedToken = sysTokenMapper.selectByJti(loginUser.getJti());
        return storedToken != null
                && storedToken.getUserId().equals(loginUser.getUserId())
                && !isInvalid(storedToken.getRevoked(), storedToken.getExpiresAt());
    }

    /**
     * 创建并持久化 Access Token 和 Refresh Token。
     *
     * @param user 用户实体
     * @return token 对
     */
    private TokenPairVO createTokenPair(SysUser user) {
        LocalDateTime now = LocalDateTime.now();
        // jti 用于把无状态 JWT 与数据库中的可撤销记录关联起来。
        String jti = UUID.randomUUID().toString().replace("-", "");
        String accessToken = jwtService.generateToken(user.getId(), user.getUsername(), jti);

        SysToken token = new SysToken();
        token.setUserId(user.getId());
        token.setJti(jti);
        token.setExpiresAt(now.plusMinutes(jwtProperties.getAccessTokenExpireMinutes()));
        token.setRevoked(SysToken.STATUS_ACTIVE);
        token.setCreateBy(user.getId());
        token.setUpdateBy(user.getId());
        sysTokenMapper.insert(token);

        // Refresh Token 明文只返回客户端，数据库仅保存 SHA-256 摘要。
        String refreshToken = RefreshTokenUtil.generate();
        SysRefreshToken refreshTokenEntity = new SysRefreshToken();
        refreshTokenEntity.setUserId(user.getId());
        refreshTokenEntity.setAccessJti(jti);
        refreshTokenEntity.setTokenHash(RefreshTokenUtil.sha256(refreshToken));
        refreshTokenEntity.setExpiresAt(now.plusDays(jwtProperties.getRefreshTokenExpireDays()));
        refreshTokenEntity.setRevoked(SysRefreshToken.STATUS_ACTIVE);
        refreshTokenEntity.setCreateBy(user.getId());
        refreshTokenEntity.setUpdateBy(user.getId());
        sysRefreshTokenMapper.insert(refreshTokenEntity);

        return new TokenPairVO(accessToken, refreshToken, jwtService.getAccessTokenExpireSeconds());
    }

    /**
     * 构建登录响应。
     *
     * @param user 用户实体
     * @param tokenPair token 对
     * @return 登录响应
     */
    private AuthRespVO buildAuthResp(SysUser user, TokenPairVO tokenPair) {
        return new AuthRespVO(
                tokenPair.getAccessToken(),
                tokenPair.getRefreshToken(),
                TOKEN_TYPE,
                tokenPair.getExpiresIn(),
                UserInfoRespVO.from(user));
    }

    /**
     * 撤销指定 Access Token。
     *
     * @param jti Access Token 唯一标识
     * @param userId 用户 ID
     */
    private void revokeAccessToken(String jti, Long userId) {
        SysToken token = sysTokenMapper.selectByJti(jti);
        if (token == null) {
            return;
        }
        token.setRevoked(SysToken.STATUS_REVOKED);
        token.setRevokedAt(LocalDateTime.now());
        token.setUpdateBy(userId);
        sysTokenMapper.updateById(token);
    }

    /**
     * 判断令牌是否无效。
     *
     * @param revoked 撤销状态
     * @param expiresAt 过期时间
     * @return true 表示无效
     */
    private boolean isInvalid(Integer revoked, LocalDateTime expiresAt) {
        return revoked == null
                || revoked == SysToken.STATUS_REVOKED
                || expiresAt == null
                || !expiresAt.isAfter(LocalDateTime.now());
    }
}
