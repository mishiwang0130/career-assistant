package com.wxy.career.util;

import com.wxy.career.common.redis.RedisUtil;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 带过期时间的 Agent 会话状态存储。
 *
 * <p>装饰 {@code RedisAgentStateStore}：读操作直接委托，写操作成功后按槽位刷新 TTL，
 * 避免会话状态在 Redis 中无限增长。TTL 刷新属于旁路能力，任何异常都只打日志，绝不阻断主流程。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Slf4j
public class AgentScopeExpiringStateStore implements AgentStateStore {

    /**
     * 被装饰的 Redis 会话状态存储。
     */
    private final AgentStateStore delegate;

    /**
     * Redis 操作工具，用于刷新过期时间。
     */
    private final RedisUtil redisUtil;

    /**
     * 会话状态过期时间，单位为小时。
     */
    private final long ttlHours;

    /**
     * 构造带过期时间的会话状态存储。
     *
     * @param delegate 被装饰的会话状态存储
     * @param redisUtil Redis 操作工具
     * @param ttlHours 会话状态过期时间，单位为小时
     */
    public AgentScopeExpiringStateStore(AgentStateStore delegate, RedisUtil redisUtil, long ttlHours) {
        this.delegate = delegate;
        this.redisUtil = redisUtil;
        this.ttlHours = ttlHours;
    }

    /**
     * 保存单条状态并在成功后刷新该会话槽位的过期时间。
     *
     * @param userId 用户 ID，AgentScope 传入的 namespace
     * @param sessionId 会话 ID
     * @param key 状态名
     * @param state 状态内容
     */
    @Override
    public void save(String userId, String sessionId, String key, State state) {
        delegate.save(userId, sessionId, key, state);
        renewTtl(userId, sessionId);
    }

    /**
     * 保存多条状态并在成功后刷新该会话槽位的过期时间。
     *
     * @param userId 用户 ID，AgentScope 传入的 namespace
     * @param sessionId 会话 ID
     * @param key 状态名
     * @param states 状态内容列表
     */
    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        delegate.save(userId, sessionId, key, states);
        renewTtl(userId, sessionId);
    }

    /**
     * 按版本保存状态并在成功后刷新过期时间。
     *
     * @param userId 用户 ID，AgentScope 传入的 namespace
     * @param sessionId 会话 ID
     * @param key 状态名
     * @param state 状态内容
     * @param expectedVersion 期望版本号
     * @return 写入后的版本号，-1 表示版本冲突
     */
    @Override
    public long saveIfVersion(String userId, String sessionId, String key, State state, long expectedVersion) {
        long version = delegate.saveIfVersion(userId, sessionId, key, state, expectedVersion);
        renewTtl(userId, sessionId);
        return version;
    }

    /**
     * 读取单条状态。
     *
     * @param userId 用户 ID，AgentScope 传入的 namespace
     * @param sessionId 会话 ID
     * @param key 状态名
     * @param type 状态类型
     * @param <T> 状态泛型
     * @return 状态内容
     */
    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        return delegate.get(userId, sessionId, key, type);
    }

    /**
     * 读取带版本号的状态。
     *
     * @param userId 用户 ID，AgentScope 传入的 namespace
     * @param sessionId 会话 ID
     * @param key 状态名
     * @param type 状态类型
     * @param <T> 状态泛型
     * @return 带版本号的状态
     */
    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.getVersioned(userId, sessionId, key, type);
    }

    /**
     * 读取多条状态。
     *
     * @param userId 用户 ID，AgentScope 传入的 namespace
     * @param sessionId 会话 ID
     * @param key 状态名
     * @param type 状态类型
     * @param <T> 状态泛型
     * @return 状态内容列表
     */
    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> type) {
        return delegate.getList(userId, sessionId, key, type);
    }

    /**
     * 判断会话是否存在。
     *
     * @param userId 用户 ID，AgentScope 传入的 namespace
     * @param sessionId 会话 ID
     * @return true 表示存在
     */
    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(userId, sessionId);
    }

    /**
     * 删除整个会话的状态。
     *
     * @param userId 用户 ID，AgentScope 传入的 namespace
     * @param sessionId 会话 ID
     */
    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(userId, sessionId);
    }

    /**
     * 删除会话中的单条状态。
     *
     * @param userId 用户 ID，AgentScope 传入的 namespace
     * @param sessionId 会话 ID
     * @param key 状态名
     */
    @Override
    public void delete(String userId, String sessionId, String key) {
        delegate.delete(userId, sessionId, key);
        renewTtl(userId, sessionId);
    }

    /**
     * 列出用户的全部会话 ID。
     *
     * @param userId 用户 ID，AgentScope 传入的 namespace
     * @return 会话 ID 集合
     */
    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(userId);
    }

    /**
     * 是否支持版本号控制。
     *
     * @return true 表示支持
     */
    @Override
    public boolean supportsVersioning() {
        return delegate.supportsVersioning();
    }

    /**
     * 释放底层资源。
     */
    @Override
    public void close() {
        delegate.close();
    }

    /**
     * 刷新会话槽位的过期时间。
     *
     * <p>TTL 刷新失败不影响会话读写，只记录日志。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     */
    private void renewTtl(String userId, String sessionId) {
        try {
            Set<String> keys = redisUtil.scanKeys(AgentScopeStateKeyUtil.slotKeyPattern(userId, sessionId));
            for (String key : keys) {
                redisUtil.expire(key, ttlHours, TimeUnit.HOURS);
            }
            redisUtil.expire(AgentScopeStateKeyUtil.userKeysKey(userId), ttlHours, TimeUnit.HOURS);
        } catch (Exception exception) {
            log.warn("刷新 Agent 会话状态过期时间失败，userId={}，sessionId={}", userId, sessionId, exception);
        }
    }
}
