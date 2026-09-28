package com.wxy.career.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.redis.RedisUtil;
import com.wxy.career.mapper.SysUserMapper;
import com.wxy.career.util.AgentScopeExpiringStateStore;
import com.wxy.career.util.AgentScopeStateKeyUtil;
import com.wxy.career.util.AgentSettingsValidator;
import com.wxy.career.tool.GetCurrentUserTool;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.StringUtils;

/**
 * AgentScope 装配配置。
 *
 * <p>负责模型、Redis 会话状态存储、工具与 SSE 心跳调度器的装配。配置错误（provider 非法、
 * 缺少 API Key）在此阶段直接抛出，保证启动即失败。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Configuration
public class AgentScopeConfiguration {

    /**
     * Redis 地址前缀。
     */
    private static final String REDIS_ADDRESS_PREFIX = "redis://";

    /**
     * Redis 连接超时，单位毫秒。
     */
    private static final int REDIS_CONNECT_TIMEOUT_MILLIS = 5000;

    /**
     * Redis 命令超时，单位毫秒。
     */
    private static final int REDIS_OPERATION_TIMEOUT_MILLIS = 5000;

    /**
     * Redis 连接池大小。
     */
    private static final int REDIS_CONNECTION_POOL_SIZE = 8;

    /**
     * Redis 最小空闲连接数。
     */
    private static final int REDIS_MIN_IDLE_SIZE = 1;

    /**
     * SSE 心跳调度线程数。
     */
    private static final int SSE_SCHEDULER_POOL_SIZE = 2;

    /**
     * 装配 DashScope 对话模型。
     *
     * @param agentProperties Agent 配置
     * @return 对话模型
     */
    @Bean
    public Model agentModel(AgentProperties agentProperties) {
        // 启动即校验配置，避免运行期才暴露 Key 缺失或 provider 非法。
        AgentSettingsValidator.validate(agentProperties);
        return DashScopeChatModel.builder()
                .apiKey(agentProperties.getApiKey())
                .modelName(agentProperties.getModel())
                .stream(true)
                .build();
    }

    /**
     * 装配 Redisson 客户端，用于 AgentScope 的 Redis 会话状态存储。
     *
     * <p>连接参数复用 Spring 的 {@code spring.data.redis.*} 配置，避免出现两套 Redis 地址来源。
     *
     * @param redisProperties Spring Redis 配置
     * @return Redisson 客户端
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress(REDIS_ADDRESS_PREFIX + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase())
                .setConnectTimeout(REDIS_CONNECT_TIMEOUT_MILLIS)
                .setTimeout(REDIS_OPERATION_TIMEOUT_MILLIS)
                .setConnectionPoolSize(REDIS_CONNECTION_POOL_SIZE)
                .setConnectionMinimumIdleSize(REDIS_MIN_IDLE_SIZE);
        if (StringUtils.hasText(redisProperties.getPassword())) {
            serverConfig.setPassword(redisProperties.getPassword());
        }
        return Redisson.create(config);
    }

    /**
     * 装配带过期时间的 Redis 会话状态存储。
     *
     * @param redissonClient Redisson 客户端
     * @param redisUtil Redis 操作工具
     * @param agentProperties Agent 配置
     * @return 会话状态存储
     */
    @Bean
    public AgentStateStore agentStateStore(
            RedissonClient redissonClient, RedisUtil redisUtil, AgentProperties agentProperties) {
        RedisAgentStateStore redisStateStore = RedisAgentStateStore.builder()
                .redissonClient(redissonClient)
                .keyPrefix(AgentScopeStateKeyUtil.KEY_PREFIX)
                .build();
        return new AgentScopeExpiringStateStore(
                redisStateStore, redisUtil, agentProperties.getSessionTtlHours());
    }

    /**
     * 装配只读的当前用户查询工具。
     *
     * @param sysUserMapper 用户 Mapper
     * @param objectMapper JSON 序列化组件
     * @return 当前用户查询工具
     */
    @Bean
    public GetCurrentUserTool getCurrentUserTool(SysUserMapper sysUserMapper, ObjectMapper objectMapper) {
        return new GetCurrentUserTool(sysUserMapper, objectMapper);
    }

    /**
     * 装配 SSE 心跳调度器。
     *
     * @return 心跳调度器
     */
    @Bean
    public ThreadPoolTaskScheduler sseTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(SSE_SCHEDULER_POOL_SIZE);
        scheduler.setThreadNamePrefix("sse-heartbeat-");
        // 连接结束后立即移除已取消的心跳任务，避免任务队列堆积。
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
