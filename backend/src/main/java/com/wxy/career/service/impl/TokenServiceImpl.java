package com.wxy.career.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 令牌服务实现。
 */
@Service
public class TokenServiceImpl implements TokenService {

    private static final String TOKEN_TYPE = "Bearer";

    @Resource
    private JwtService jwtService;

    @Resource
    private JwtProperties jwtProperties;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private SysTokenMapper sysTokenMapper;

    @Resource
    private SysRefreshTokenMapper sysRefreshTokenMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TokenPairVO issueTokens(SysUser user) {
        return createTokenPair(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthRespVO refresh(String refreshToken) {
        String tokenHash = RefreshTokenUtil.sha256(refreshToken);
        SysRefreshToken storedToken = sysRefreshTokenMapper.selectOne(
                new LambdaQueryWrapper<SysRefreshToken>().eq(SysRefreshToken::getTokenHash, tokenHash));
        if (storedToken == null || isInvalid(storedToken.getRevoked(), storedToken.getExpiresAt())) {
            throw new BizException(ErrorConstant.REFRESH_TOKEN_INVALID, HttpStatus.UNAUTHORIZED);
        }
        SysUser user = sysUserMapper.selectById(storedToken.getUserId());
        if (user == null) {
            throw new BizException(ErrorConstant.REFRESH_TOKEN_INVALID, HttpStatus.UNAUTHORIZED);
        }
        revokeAccessToken(storedToken.getAccessJti(), storedToken.getUserId());
        storedToken.setRevoked(SysRefreshToken.STATUS_REVOKED);
        storedToken.setRevokedAt(LocalDateTime.now());
        storedToken.setUpdateBy(storedToken.getUserId());
        sysRefreshTokenMapper.updateById(storedToken);
        return buildAuthResp(user, createTokenPair(user));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(String jti, Long userId) {
        revokeAccessToken(jti, userId);
        LocalDateTime now = LocalDateTime.now();
        SysRefreshToken refreshToken = sysRefreshTokenMapper.selectOne(
                new LambdaQueryWrapper<SysRefreshToken>()
                        .eq(SysRefreshToken::getAccessJti, jti)
                        .eq(SysRefreshToken::getRevoked, SysRefreshToken.STATUS_ACTIVE));
        if (refreshToken != null) {
            refreshToken.setRevoked(SysRefreshToken.STATUS_REVOKED);
            refreshToken.setRevokedAt(now);
            refreshToken.setUpdateBy(userId);
            sysRefreshTokenMapper.updateById(refreshToken);
        }
    }

    @Override
    public boolean isValid(LoginUser loginUser) {
        SysToken storedToken = sysTokenMapper.selectOne(
                new LambdaQueryWrapper<SysToken>().eq(SysToken::getJti, loginUser.getJti()));
        return storedToken != null
                && storedToken.getUserId().equals(loginUser.getUserId())
                && !isInvalid(storedToken.getRevoked(), storedToken.getExpiresAt());
    }

    private TokenPairVO createTokenPair(SysUser user) {
        LocalDateTime now = LocalDateTime.now();
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

    private AuthRespVO buildAuthResp(SysUser user, TokenPairVO tokenPair) {
        return new AuthRespVO(
                tokenPair.getAccessToken(),
                tokenPair.getRefreshToken(),
                TOKEN_TYPE,
                tokenPair.getExpiresIn(),
                UserInfoRespVO.from(user));
    }

    private void revokeAccessToken(String jti, Long userId) {
        SysToken token = sysTokenMapper.selectOne(
                new LambdaQueryWrapper<SysToken>().eq(SysToken::getJti, jti));
        if (token == null) {
            return;
        }
        token.setRevoked(SysToken.STATUS_REVOKED);
        token.setRevokedAt(LocalDateTime.now());
        token.setUpdateBy(userId);
        sysTokenMapper.updateById(token);
    }

    private boolean isInvalid(Integer revoked, LocalDateTime expiresAt) {
        return revoked == null
                || revoked == SysToken.STATUS_REVOKED
                || expiresAt == null
                || !expiresAt.isAfter(LocalDateTime.now());
    }
}
