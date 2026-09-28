package com.wxy.career.util;

import com.wxy.career.common.redis.RedisKeyConstants;

/**
 * AgentScope 会话状态 Redis key 工具。
 *
 * <p>键格式由 AgentScope 2.0.3 的 {@code RedisAgentStateStore} 决定，槽位为
 * {@code {KEY_PREFIX}{userId}/{sessionId}}，同一槽位下的键为
 * {@code :list}（会话消息列表）、{@code :{状态名}}（各状态片段），
 * 另外用户维度还有一个 {@code {KEY_PREFIX}{userId}:_keys} 集合记录该用户的会话 ID。
 *
 * @author wxy
 * @date 2026-09-28
 */
public final class AgentScopeStateKeyUtil {

    /**
     * AgentScope 会话状态 key 前缀，遵循统一 Redis key 规范 career:{模块}:。
     */
    public static final String KEY_PREFIX = RedisKeyConstants.AGENT;

    /**
     * 槽位与状态名之间的分隔符。
     */
    private static final String SLOT_SEPARATOR = "/";

    /**
     * 单个槽位下所有键的后缀分隔符。
     */
    private static final String STATE_SEPARATOR = ":";

    /**
     * 会话 ID 集合键后缀。
     */
    private static final String KEYS_SUFFIX = ":_keys";

    /**
     * TTL 续期节流键前缀。
     *
     * <p>该前缀刻意不以「用户 ID + 斜杠」开头，保证不会被槽位扫描模式命中，
     * 否则节流键本身会被续期成会话 TTL，节流随之失效。
     */
    private static final String TTL_RENEW_PREFIX = "ttl-renew:";

    /**
     * 会话并发锁键前缀。
     */
    private static final String SESSION_LOCK_PREFIX = "lock:";

    /**
     * 工具类禁止实例化。
     */
    private AgentScopeStateKeyUtil() {
    }

    /**
     * 构建某个用户某个会话下全部状态键的扫描模式。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 扫描模式
     */
    public static String slotKeyPattern(String userId, String sessionId) {
        return KEY_PREFIX + userId + SLOT_SEPARATOR + sessionId + STATE_SEPARATOR + "*";
    }

    /**
     * 构建用户维度的会话 ID 集合键。
     *
     * @param userId 用户 ID
     * @return 会话 ID 集合键
     */
    public static String userKeysKey(String userId) {
        return KEY_PREFIX + userId + KEYS_SUFFIX;
    }

    /**
     * 构建某个用户某个会话的 TTL 续期节流键。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 节流键
     */
    public static String ttlRenewKey(String userId, String sessionId) {
        return KEY_PREFIX + TTL_RENEW_PREFIX + userId + SLOT_SEPARATOR + sessionId;
    }

    /**
     * 构建某个用户某个会话的并发锁键。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 并发锁键
     */
    public static String sessionLockKey(String userId, String sessionId) {
        return KEY_PREFIX + SESSION_LOCK_PREFIX + userId + SLOT_SEPARATOR + sessionId;
    }
}
