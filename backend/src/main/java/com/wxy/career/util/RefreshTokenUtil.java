package com.wxy.career.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Refresh Token 生成与摘要工具。
 *
 * @author wxy
 * @date 2026-09-27
 */
public final class RefreshTokenUtil {

    /**
     * 安全随机数生成器。
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Refresh Token 随机字节数。
     */
    private static final int TOKEN_BYTES = 32;

    /**
     * 工具类禁止实例化。
     */
    private RefreshTokenUtil() {
    }

    /**
     * 生成 URL 安全的 Refresh Token。
     *
     * @return Refresh Token
     */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 计算 SHA-256 十六进制摘要。
     *
     * @param value 原始字符串
     * @return SHA-256 摘要
     */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
