package com.wxy.career.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.redis.RedisUtil;
import com.wxy.career.config.AgentProperties;
import com.wxy.career.middleware.MetricsMiddleware;
import com.wxy.career.middleware.SystemPromptMiddleware;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.SystemPromptProvider;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.tool.GetUserProfileTool;
import com.wxy.career.tool.UpdateUserProfileTool;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HarnessAgent 装配与工具白名单测试。
 *
 * <p>不启动 Spring 上下文、不连接 MySQL / Redis、不访问模型服务，只校验 Agent 类型与最终工具集。
 *
 * @author wxy
 * @date 2026-09-28
 */
class AgentFactoryHarnessTest {

    /**
     * 被测 Agent 工厂。
     */
    private AgentFactoryImpl agentFactory;

    /**
     * 初始化工厂依赖。
     */
    @BeforeEach
    void setUp() {
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setProvider("dashscope");
        agentProperties.setApiKey("test-key");
        agentProperties.setMaxIters(6);

        SystemPromptProvider systemPromptProvider = () -> "测试系统提示词";
        SystemPromptMiddleware systemPromptMiddleware = new SystemPromptMiddleware();
        ReflectionTestUtils.setField(systemPromptMiddleware, "systemPromptProvider", systemPromptProvider);

        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.getHash(anyString(), anyString(), eq(Long.class))).thenReturn(null);
        MetricsMiddleware metricsMiddleware = new MetricsMiddleware();
        ReflectionTestUtils.setField(metricsMiddleware, "redisUtil", redisUtil);

        agentFactory = new AgentFactoryImpl();
        ReflectionTestUtils.setField(agentFactory, "agentModel", new StubModel());
        ReflectionTestUtils.setField(agentFactory, "agentStateStore", new InMemoryAgentStateStore());
        ReflectionTestUtils.setField(agentFactory, "agentProperties", agentProperties);
        ReflectionTestUtils.setField(agentFactory, "systemPromptProvider", systemPromptProvider);
        ReflectionTestUtils.setField(agentFactory, "systemPromptMiddleware", systemPromptMiddleware);
        ReflectionTestUtils.setField(agentFactory, "metricsMiddleware", metricsMiddleware);
        ObjectMapper objectMapper = new ObjectMapper();
        UserProfileService userProfileService = mock(UserProfileService.class);
        ReflectionTestUtils.setField(agentFactory, "getUserProfileTool",
                new GetUserProfileTool(userProfileService, objectMapper));
        ReflectionTestUtils.setField(agentFactory, "updateUserProfileTool",
                new UpdateUserProfileTool(userProfileService, objectMapper));
    }

    /**
     * 验证构建出的是 HarnessAgent，而不是底层 ReActAgent。
     */
    @Test
    void shouldBuildHarnessAgent() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.MAIN_AGENT_NAME);

        assertThat(agent).isInstanceOf(HarnessAgent.class);
        assertThat(agent.getClass()).isEqualTo(HarnessAgent.class);
        assertThat(agent.getName()).isEqualTo(AgentFactory.MAIN_AGENT_NAME);
        // 同一名字走缓存，避免重复装配状态存储与中间件。
        assertThat(agentFactory.getAgent(AgentFactory.MAIN_AGENT_NAME)).isSameAs(agent);
    }

    /**
     * 验证工具白名单只有求职目标的两个工具，框架默认工具（文件、Shell、Web、异步等待等）没有混入。
     */
    @Test
    void shouldExposeOnlyWhitelistedTools() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.MAIN_AGENT_NAME);

        assertThat(agent.getToolkit().getToolNames()).containsExactlyInAnyOrder(
                GetUserProfileTool.TOOL_NAME, UpdateUserProfileTool.TOOL_NAME);
    }

    /**
     * 验证未登记的 Agent 名返回业务错误码。
     */
    @Test
    void shouldRejectUnknownAgentName() {
        assertThatThrownBy(() -> agentFactory.getAgent("unknown-agent"))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> {
                    BizException bizException = (BizException) exception;
                    assertThat(bizException.getErrorCode().getCode()).isEqualTo(404);
                });
    }

    /**
     * 测试用桩模型：仅用于装配，不会被调用。
     *
     * @author wxy
     * @date 2026-09-28
     */
    private static final class StubModel implements Model {

        /**
         * 返回空响应流。
         *
         * @param messages 上下文消息
         * @param tools 可用工具
         * @param options 生成参数
         * @return 空响应流
         */
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }

        /**
         * 模型名。
         *
         * @return 模型名
         */
        @Override
        public String getModelName() {
            return "stub-model";
        }
    }
}
