package com.wxy.career.service.impl;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.redis.RedisUtil;
import com.wxy.career.config.AgentProperties;
import com.wxy.career.middleware.MetricsMiddleware;
import com.wxy.career.middleware.SystemPromptMiddleware;
import com.wxy.career.mapper.SysUserMapper;
import com.wxy.career.po.SysUser;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.SystemPromptProvider;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.UserProfileRespVO;
import com.wxy.career.tool.ReadResumeTool;
import com.wxy.career.tool.SubmitResumeDiagnosisTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
     * 记录模型收到的消息，用于校验子 Agent 实际使用的系统提示词。
     */
    private CapturingModel capturingModel;

    /**
     * 初始化工厂依赖。
     */
    @BeforeEach
    void setUp() {
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setProvider("dashscope");
        agentProperties.setApiKey("test-key");
        agentProperties.setMaxIters(6);

        SystemPromptProvider systemPromptProvider = agentId -> "测试系统提示词:" + agentId;
        SystemPromptMiddleware systemPromptMiddleware = new SystemPromptMiddleware();
        ReflectionTestUtils.setField(systemPromptMiddleware, "systemPromptProvider", systemPromptProvider);
        SysUserMapper sysUserMapper = mock(SysUserMapper.class);
        SysUser user = new SysUser();
        user.setId(1L);
        user.setNickname("Alice");
        when(sysUserMapper.selectById(1L)).thenReturn(user);
        UserProfileService userProfileService = mock(UserProfileService.class);
        UserProfileRespVO userProfile = new UserProfileRespVO();
        userProfile.setTargetPosition("后端开发");
        userProfile.setWorkYears(3);
        when(userProfileService.getUserProfileByUserId(1L)).thenReturn(userProfile);
        ReflectionTestUtils.setField(systemPromptMiddleware, "sysUserMapper", sysUserMapper);
        ReflectionTestUtils.setField(systemPromptMiddleware, "userProfileService", userProfileService);

        AgentSkillRepository agentSkillRepository = mock(AgentSkillRepository.class);
        when(agentSkillRepository.getSkill("resume-analysis"))
                .thenReturn(AgentSkill.builder()
                        .name("resume-analysis")
                        .description("简历分析规范")
                        .skillContent("简历分析规范")
                        .build());

        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.getHash(anyString(), anyString(), eq(Long.class))).thenReturn(null);
        MetricsMiddleware metricsMiddleware = new MetricsMiddleware();
        ReflectionTestUtils.setField(metricsMiddleware, "redisUtil", redisUtil);

        agentFactory = new AgentFactoryImpl();
        capturingModel = new CapturingModel();
        ReflectionTestUtils.setField(agentFactory, "agentModel", capturingModel);
        ReflectionTestUtils.setField(agentFactory, "agentStateStore", new InMemoryAgentStateStore());
        ReflectionTestUtils.setField(agentFactory, "agentProperties", agentProperties);
        ReflectionTestUtils.setField(agentFactory, "systemPromptProvider", systemPromptProvider);
        ReflectionTestUtils.setField(agentFactory, "systemPromptMiddleware", systemPromptMiddleware);
        ReflectionTestUtils.setField(agentFactory, "metricsMiddleware", metricsMiddleware);
        ReflectionTestUtils.setField(agentFactory, "agentSkillRepository", agentSkillRepository);
        ReflectionTestUtils.setField(agentFactory, "readResumeTool", new ReadResumeTool());
        ReflectionTestUtils.setField(agentFactory, "submitResumeDiagnosisTool", new SubmitResumeDiagnosisTool());
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
     * 验证助手 Agent 的工具白名单：只有读简历与框架的子 Agent 派发工具，框架默认工具
     * （文件、Shell、Web、异步等待等）与只属于子 Agent 的提交诊断结论工具都没有混入。
     */
    @Test
    void shouldExposeOnlyAssistantTools() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.MAIN_AGENT_NAME);

        Set<String> toolNames = agent.getToolkit().getToolNames();
        // 白名单是精确集合：只多一个框架默认工具或业务工具都说明 allow / deny 没配好。
        assertThat(toolNames).containsExactlyInAnyOrder(
                "read_resume", "agent_spawn", "agent_send", "agent_list");
        assertThat(toolNames).doesNotContain("submit_resume_diagnosis");
    }

    /**
     * 验证简历分析子 Agent 的声明：工具白名单只含读简历与提交诊断结论，技能为 resume-analysis。
     */
    @Test
    void shouldDeclareResumeAnalystSubagent() {
        SubagentDeclaration declaration = agentFactory.buildResumeAnalystDeclaration();

        assertThat(declaration.getName()).isEqualTo(AgentFactory.RESUME_ANALYST_AGENT_NAME);
        assertThat(declaration.getTools())
                .containsExactly("read_resume", "submit_resume_diagnosis");
        assertThat(declaration.getSkills()).containsExactly("resume-analysis");
        assertThat(declaration.getExposeToUser()).isFalse();
    }

    /**
     * 验证提交诊断结论工具的参数 schema 真的带上了结构化字段。
     *
     * <p>模型是照着 schema 填参数的：如果框架没能把嵌套 DTO 展开成字段，模型只会收到一个空对象要求，
     * 卡片就永远是空的。这里把字段名固化成断言；工具只出现在子 Agent 的工具集里（助手侧被 allow 过滤）。
     */
    @Test
    void shouldExposeDiagnosisFieldsInToolSchema() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.MAIN_AGENT_NAME);
        var runtimeContext = io.agentscope.core.agent.RuntimeContext.builder()
                .userId("1")
                .sessionId("1")
                .build();
        var subagent = agent.getSubagentAgentManager()
                .createAgentIfPresent(AgentFactory.RESUME_ANALYST_AGENT_NAME, runtimeContext);

        assertThat(subagent).isPresent();
        var submitTool = subagent.get().getToolkit().getTool("submit_resume_diagnosis");

        assertThat(submitTool).isNotNull();
        assertThat(submitTool.isReadOnly()).isTrue();
        assertThat(String.valueOf(submitTool.getParameters()))
                .contains("diagnosis", "resumeId", "overallScore", "dimensions", "optimizedResume",
                        "interviewFollowUps");
    }

    /**
     * 验证助手真的把「简历分析」子 Agent 派发出去，且子 Agent 只拿到两个只读工具。
     *
     * <p>这一条固定的是框架行为而不是实现细节：声明里的 tools 从父 Toolkit 继承，若框架改成先按
     * allow 过滤父 Toolkit，这里的断言会失败，提醒把提交工具补进允许集或改用自定义子 Agent 工厂。
     */
    @Test
    void shouldDispatchResumeAnalystWithNarrowedTools() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.MAIN_AGENT_NAME);
        var runtimeContext = io.agentscope.core.agent.RuntimeContext.builder()
                .userId("1")
                .sessionId("1")
                .build();

        var subagent = agent.getSubagentAgentManager()
                .createAgentIfPresent(AgentFactory.RESUME_ANALYST_AGENT_NAME, runtimeContext);

        assertThat(subagent).isPresent();
        Set<String> subagentTools = subagent.get().getToolkit().getToolNames();
        assertThat(subagentTools)
                .containsExactlyInAnyOrder("read_resume", "submit_resume_diagnosis", "load_skill_through_path");
        assertThat(subagentTools).doesNotContain("web_search", "web_fetch", "wait_async_results");
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
     * 验证子 Agent 跑的是自己的提示词，并且继承了用户背景注入。
     *
     * <p>子 Agent 的提示词按 Agent 标识取（不是助手的），求职目标由系统提示词中间件注入——
     * 简历诊断需要目标岗位，这两点都必须端到端成立，而不只是装配时看着对。
     */
    @Test
    void shouldUseOwnPromptAndInheritUserContextInSubagent() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.MAIN_AGENT_NAME);
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("1").sessionId("100").build();
        HarnessAgent subagent = (HarnessAgent) agent.getSubagentAgentManager()
                .createAgentIfPresent(AgentFactory.RESUME_ANALYST_AGENT_NAME, runtimeContext)
                .orElseThrow();

        capturingModel.reset();
        subagent.streamEvents(
                Msg.builder().role(MsgRole.USER).textContent("帮我诊断简历").build(),
                runtimeContext).blockLast();

        String systemPrompt = capturingModel.systemPrompt();
        assertThat(systemPrompt).contains("测试系统提示词:" + AgentFactory.RESUME_ANALYST_AGENT_NAME);
        assertThat(systemPrompt).contains("目标岗位「后端开发」");
        assertThat(systemPrompt).contains("当前对话用户昵称：Alice");
    }

    /**
     * 测试用桩模型：记录收到的消息并返回固定文本，不访问模型服务。
     *
     * @author wxy
     * @date 2026-09-28
     */
    private static final class CapturingModel implements Model {

        /**
         * 最近一次调用收到的上下文消息。
         */
        private final List<Msg> received = new ArrayList<>();

        /**
         * 清空记录。
         */
        void reset() {
            received.clear();
        }

        /**
         * 取出记录到的系统提示词。
         *
         * @return 系统提示词，未记录到系统消息时返回空串
         */
        String systemPrompt() {
            for (Msg message : received) {
                if (message.getRole() == MsgRole.SYSTEM && message.getTextContent() != null) {
                    return message.getTextContent();
                }
            }
            return "";
        }

        /**
         * 记录消息并返回固定文本。
         *
         * @param messages 上下文消息
         * @param tools 可用工具
         * @param options 生成参数
         * @return 固定文本响应流
         */
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            received.addAll(messages);
            return Flux.just(ChatResponse.builder()
                    .id("stub-text")
                    .content(List.of(TextBlock.builder().text("桩模型回复").build()))
                    .build());
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
