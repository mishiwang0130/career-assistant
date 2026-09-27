package com.wxy.career.common.redis;

import java.util.Objects;

/**
 * Redis key 常量。
 */
public final class RedisKeyConstants {

    public static final String PREFIX = "career:";

    public static final String AUTH = PREFIX + "auth:";

    public static final String SYSTEM = PREFIX + "system:";

    private RedisKeyConstants() {
    }

    public static String buildKey(String module, String business, String identifier) {
        return PREFIX
                + Objects.requireNonNull(module, "module") + ":"
                + Objects.requireNonNull(business, "business") + ":"
                + Objects.requireNonNull(identifier, "identifier");
    }
}
