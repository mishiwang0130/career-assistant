package com.wxy.career.common.auth;

import com.wxy.career.common.config.JwtProperties;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT 签发与解析。
 */
@Service
public class JwtService {

    private static final String CLAIM_USER_ID = "userId";

    private static final String CLAIM_USERNAME = "username";

    @Resource
    private JwtProperties jwtProperties;

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

    public LoginUser parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Object userIdValue = claims.get(CLAIM_USER_ID);
            if (!(userIdValue instanceof Number userId)) {
                throw new BizException(ErrorConstant.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
            }
            String username = claims.get(CLAIM_USERNAME, String.class);
            if (username == null || claims.getId() == null) {
                throw new BizException(ErrorConstant.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
            }
            return new LoginUser(userId.longValue(), username, claims.getId());
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BizException(ErrorConstant.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
    }

    public long getAccessTokenExpireSeconds() {
        return jwtProperties.getAccessTokenExpireMinutes() * 60L;
    }

    public long getRefreshTokenExpireSeconds() {
        return jwtProperties.getRefreshTokenExpireDays() * 24L * 60L * 60L;
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
