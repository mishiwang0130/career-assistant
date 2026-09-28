package com.wxy.career.common.auth;

import com.wxy.career.common.config.JwtProperties;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT 签发与解析。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Service
public class JwtService {

    /**
     * 用户 ID 声明名。
     */
    private static final String CLAIM_USER_ID = "userId";

    /**
     * 用户名声明名。
     */
    private static final String CLAIM_USERNAME = "username";

    /**
     * JWT 配置。
     */
    @Resource
    private JwtProperties jwtProperties;

    /**
     * 签发 Access Token。
     *
     * @param userId 用户 ID
     * @param username 用户名
     * @param jti 令牌唯一标识
     * @return JWT 字符串
     */
    public String generateToken(Long userId, String username, String jti) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(jwtProperties.getAccessTokenExpireMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(jti)
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_USERNAME, username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 解析并校验 Access Token。
     *
     * @param token JWT 字符串
     * @return 登录用户信息
     * @throws BizException 令牌无效或已过期
     */
    public LoginUser parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Object userIdValue = claims.get(CLAIM_USER_ID);
            if (!(userIdValue instanceof Number userId)) {
                throw new BizException(ErrorConstant.UNAUTHORIZED);
            }
            String username = claims.get(CLAIM_USERNAME, String.class);
            if (username == null || claims.getId() == null) {
                throw new BizException(ErrorConstant.UNAUTHORIZED);
            }
            return new LoginUser(userId.longValue(), username, claims.getId());
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
    }

    /**
     * 获取 Access Token 有效期秒数。
     *
     * @return Access Token 有效期秒数
     */
    public long getAccessTokenExpireSeconds() {
        return jwtProperties.getAccessTokenExpireMinutes() * 60L;
    }

    /**
     * 获取 Refresh Token 有效期秒数。
     *
     * @return Refresh Token 有效期秒数
     */
    public long getRefreshTokenExpireSeconds() {
        return jwtProperties.getRefreshTokenExpireDays() * 24L * 60L * 60L;
    }

    /**
     * 构建 HMAC 签名密钥。
     *
     * @return 签名密钥
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
