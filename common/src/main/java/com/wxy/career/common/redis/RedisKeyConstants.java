package com.wxy.career.common.redis;

import java.util.Objects;

/**
 * Redis key 常量。
 *
 * @author wxy
 * @date 2026-09-27
 */
public final class RedisKeyConstants {

    /**
     * 全局 Redis key 前缀。
     */
    public static final String PREFIX = "career:";

    /**
     * 认证模块 Redis key 前缀。
     */
    public static final String AUTH = PREFIX + "auth:";

    /**
     * 系统模块 Redis key 前缀。
     */
    public static final String SYSTEM = PREFIX + "system:";

    /**
     * Agent 模块 Redis key 前缀，用于 AgentScope 会话状态等键。
     */
    public static final String AGENT = PREFIX + "agent:";

    /**
     * 工具类禁止实例化。
     */
    private RedisKeyConstants() {
    }

    /**
     * 按统一格式构建 Redis key。
     *
     * @param module 模块名
     * @param business 业务名
     * @param identifier 业务标识
     * @return Redis key
     */
    public static String buildKey(String module, String business, String identifier) {
        return PREFIX
                + Objects.requireNonNull(module, "module") + ":"
                + Objects.requireNonNull(business, "business") + ":"
                + Objects.requireNonNull(identifier, "identifier");
    }
}
