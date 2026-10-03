package com.wxy.career.service.impl;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.redis.RedisUtil;
import com.wxy.career.config.AgentProperties;
import com.wxy.career.config.InterviewProperties;
import com.wxy.career.config.MemoryProperties;
import com.wxy.career.middleware.MetricsMiddleware;
import com.wxy.career.middleware.InMemoryTaskRepository;
import com.wxy.career.middleware.SystemPromptMiddleware;
import com.wxy.career.middleware.UserLongTermMemoryAdapter;
import com.wxy.career.mapper.SysUserMapper;
import com.wxy.career.po.SysUser;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.SystemPromptProvider;
import com.wxy.career.service.TrainingPlanService;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.UserProfileRespVO;
import com.wxy.career.tool.GetInterviewStateTool;
import com.wxy.career.tool.GetWeakPointsTool;
import com.wxy.career.tool.ListPlannedUsersTool;
import com.wxy.career.tool.ReadResumeTool;
import com.wxy.career.tool.RecordInterviewAnswerTool;
import com.wxy.career.tool.SaveTrainingReminderTool;
import com.wxy.career.tool.SubmitAnswerEvaluationTool;
import com.wxy.career.tool.SubmitInterviewReportTool;
import com.wxy.career.tool.SubmitResumeDiagnosisTool;
import com.wxy.career.tool.SubmitTrainingPlanTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
     * 测试用的计划正文（一天一行里的第 1 天），用于断言 HITL 确认后提交的正文没被改写。
     */
    private static final String PLAN_CONTENT = "第 1 天：Redis 分布式锁";

    /**
     * 与 {@link #PLAN_CONTENT} 对应的模型原始入参 JSON，模拟 DashScope 解析器回填的 ToolUseBlock.content。
     */
    private static final String PLAN_CONTENT_JSON = "{\"planContent\":\"" + PLAN_CONTENT + "\"}";

    /**
     * 被测 Agent 工厂。
     */
    private AgentFactoryImpl agentFactory;

    /**
     * 记录模型收到的消息，用于校验子 Agent 实际使用的系统提示词。
     */
    private CapturingModel capturingModel;

    /**
     * 技能仓库 stub，用于校验助手与子 Agent 各自能看到的技能集合。
     */
    private AgentSkillRepository agentSkillRepository;

    /**
     * 计划提交工具实例，测试里注入计划服务桩，用于断言 HITL 确认后工具真的执行了。
     */
    private SubmitTrainingPlanTool submitTrainingPlanTool;

    /**
     * 计划服务桩。
     */
    private TrainingPlanService trainingPlanService;

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

        agentSkillRepository = mock(AgentSkillRepository.class);
        when(agentSkillRepository.getSkill("resume-analysis"))
                .thenReturn(AgentSkill.builder()
                        .name("resume-analysis")
                        .description("简历分析规范")
                        .skillContent("简历分析规范")
                        .build());
        when(agentSkillRepository.getSkill("job-match"))
                .thenReturn(AgentSkill.builder()
                        .name("job-match")
                        .description("岗位匹配规范")
                        .skillContent("岗位匹配规范")
                        .build());
        // F9：讲解规范技能，与 sql/career_assistant.sql 里写入的 tutoring 同名。
        when(agentSkillRepository.getSkill("tutoring"))
                .thenReturn(AgentSkill.builder()
                        .name("tutoring")
                        .description("专项辅导讲解规范")
                        .skillContent("先定位、再类比、再举例、最后出一道练习")
                        .build());
        // F7：排期口径技能，与 sql/career_assistant.sql 里写入的 training-planning 同名。
        when(agentSkillRepository.getSkill("training-planning"))
                .thenReturn(AgentSkill.builder()
                        .name("training-planning")
                        .description("训练计划排期口径")
                        .skillContent("每天任务数按每日时长分配，薄弱点优先")
                        .build());
        // 技能清单由 getAllSkills 渲染进系统提示词，因此必须一起打桩，否则技能过滤在测试里看不出效果。
        when(agentSkillRepository.getAllSkills()).thenReturn(List.of(
                AgentSkill.builder().name("resume-analysis").description("简历分析规范")
                        .skillContent("简历分析规范").build(),
                AgentSkill.builder().name("job-match").description("岗位匹配规范")
                        .skillContent("岗位匹配规范").build(),
                AgentSkill.builder().name("tutoring").description("专项辅导讲解规范")
                        .skillContent("先定位、再类比、再举例、最后出一道练习").build(),
                AgentSkill.builder().name("training-planning").description("训练计划排期口径")
                        .skillContent("每天任务数按每日时长分配，薄弱点优先").build()));

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
        // F9：读薄弱点工具只给助手用，装配断言要能构建出它。
        ReflectionTestUtils.setField(agentFactory, "getWeakPointsTool", new GetWeakPointsTool());
        ReflectionTestUtils.setField(agentFactory, "getInterviewStateTool", new GetInterviewStateTool());
        ReflectionTestUtils.setField(
                agentFactory, "recordInterviewAnswerTool", new RecordInterviewAnswerTool());
        ReflectionTestUtils.setField(
                agentFactory, "submitAnswerEvaluationTool", new SubmitAnswerEvaluationTool());
        ReflectionTestUtils.setField(agentFactory, "interviewProperties", new InterviewProperties());
        // F6：长期记忆适配层与报告提交工具都要装配上，但测试里关掉记忆读写，避免依赖 Mem0 服务。
        MemoryProperties memoryProperties = new MemoryProperties();
        memoryProperties.setEnabled(false);
        UserLongTermMemoryAdapter userLongTermMemoryAdapter = new UserLongTermMemoryAdapter();
        ReflectionTestUtils.setField(userLongTermMemoryAdapter, "memoryProperties", memoryProperties);
        ReflectionTestUtils.setField(agentFactory, "userLongTermMemoryAdapter", userLongTermMemoryAdapter);
        ReflectionTestUtils.setField(agentFactory, "submitInterviewReportTool", new SubmitInterviewReportTool());
        // F7：计划 Agent 的提交工具与提醒 Agent 的两个工具都要装配上。
        // 提交工具注入计划服务桩：确认之后的续跑要能断言工具真的被执行，而不是被框架的入参校验挡回来。
        trainingPlanService = mock(TrainingPlanService.class);
        submitTrainingPlanTool = new SubmitTrainingPlanTool();
        ReflectionTestUtils.setField(submitTrainingPlanTool, "trainingPlanService", trainingPlanService);
        ReflectionTestUtils.setField(agentFactory, "submitTrainingPlanTool", submitTrainingPlanTool);
        ReflectionTestUtils.setField(agentFactory, "listPlannedUsersTool", new ListPlannedUsersTool());
        ReflectionTestUtils.setField(agentFactory, "saveTrainingReminderTool", new SaveTrainingReminderTool());
        // 计划 Agent 的任务仓储用进程内实现：测试链路同样不往工作区落文件。
        ReflectionTestUtils.setField(agentFactory, "planTaskRepository", new InMemoryTaskRepository());
    }

    /**
     * 验证计划 Agent 的工具白名单：读薄弱点（MySQL）+ 提交计划 + 技能加载 + Plan Mode 进出 + Task List，
     * 而写工作区文件的 plan_write 与平台工具、子 Agent 派发工具都被挡住（计划正文只落 MySQL）。
     */
    @Test
    void shouldExposeOnlyPlannerToolsAndDenyWorkspaceWrites() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME);

        assertThat(agent).isInstanceOf(HarnessAgent.class);
        assertThat(agent.getName()).isEqualTo(AgentFactory.PLANNER_AGENT_NAME);
        Set<String> toolNames = agent.getToolkit().getToolNames();
        assertThat(toolNames).contains(
                "get_weak_points", "submit_training_plan", "load_skill_through_path", "todo_write");
        assertThat(toolNames).doesNotContain(
                "plan_write", "read_resume", "submit_resume_diagnosis", "web_search", "web_fetch",
                "wait_async_results", "task_output", "task_list", "task_cancel",
                "agent_spawn", "agent_send", "agent_list");
    }

    /**
     * 验证写计划必须人工确认：允许规则只覆盖只读与规划类工具，提交计划被显式标记为 ASK。
     */
    @Test
    void shouldRequireConfirmBeforeSubmittingPlan() {
        var permissionContext = agentFactory.buildPlannerPermissionContext();

        assertThat(permissionContext.getMode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(permissionContext.getAllowRules().keySet()).contains(
                "get_weak_points", "load_skill_through_path", "plan_enter", "plan_exit", "todo_write");
        assertThat(permissionContext.getAllowRules().keySet()).doesNotContain("submit_training_plan");
        assertThat(permissionContext.getAskRules().keySet()).contains("submit_training_plan");
    }

    /**
     * 验证归档总结 Agent 没有任何工具：它是唯一产出记忆的模型，但写记忆必须由平台侧解析 JSON 后执行，
     * 所以模型不能读库、不能写库、不能联网。工具集一旦不为空，就说明框架默认工具漏了进来。
     */
    @Test
    void shouldBuildSessionArchiverWithoutAnyTool() {
        HarnessAgent archiver = agentFactory.getAgent(AgentFactory.SESSION_ARCHIVER_AGENT_NAME);

        assertThat(archiver.getName()).isEqualTo(AgentFactory.SESSION_ARCHIVER_AGENT_NAME);
        assertThat(archiver.getToolkit().getToolNames()).isEmpty();
    }

    /**
     * 验证框架真的会在写计划前停下来等确认：模型调用 submit_training_plan 时事件流里出现确认请求。
     *
     * <p>这是 HITL 的行为断言，不依赖提示词：只要确认事件出现就说明写工具被权限闸门挡住，未确认不会落库。
     */
    @Test
    void shouldEmitConfirmRequestWhenModelTriesToWritePlan() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME);
        capturingModel.withToolCall("submit_training_plan", Map.of("plan", Map.of("days", 3)));
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("1")
                .sessionId("training-plan-1")
                .build();

        List<AgentEvent> events = agent.streamEvents(
                        Msg.builder().role(MsgRole.USER).textContent("生成训练计划").build(),
                        runtimeContext)
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).anyMatch(event -> event instanceof RequireUserConfirmEvent);
        capturingModel.resetToolCall();
    }

    /**
     * 用户确认后工具真的被执行：回填的确认结论必须带上 {@code content}（模型原始入参 JSON）。
     *
     * <p>框架会用确认结论里的工具调用**替换**上下文里的原始调用，再拿 {@code content} 做一次入参 schema 校验。
     * 只带 {@code input} 时 {@code content} 为空，校验阶段就抛
     * {@code Schema validation error: argument "content" is null}，工具根本进不来——线上表现就是「用户点了确认，
     * 计划还是存不下去，模型反复重提同一份计划」。
     */
    @Test
    void shouldExecutePlanToolWhenConfirmCarriesRawArguments() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME);
        capturingModel.withToolCall("submit_training_plan", Map.of("planContent", PLAN_CONTENT));
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("1")
                .sessionId("training-plan-1")
                .build();
        ToolUseBlock pending = waitForPlanConfirm(agent, runtimeContext);
        // 续跑阶段模型只回一句收尾文本，避免它又发起一次工具调用。
        capturingModel.resetToolCall();

        agent.streamEvents(confirmMessage(resumedBlock(pending, PLAN_CONTENT_JSON)), runtimeContext)
                .collectList()
                .block();

        verify(trainingPlanService).submitPlan(1L, "training-plan-1", PLAN_CONTENT, null);
    }

    /**
     * 反向断言：回填的工具调用缺 {@code content} 时，框架在进工具之前就判 ERROR，业务一次都不会被调用。
     *
     * <p>这条固定住「为什么要带 content」这个约定，避免后续有人图省事只回填结构化入参。
     */
    @Test
    void shouldNotExecutePlanToolWhenConfirmLacksRawArguments() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME);
        capturingModel.withToolCall("submit_training_plan", Map.of("planContent", PLAN_CONTENT));
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("1")
                .sessionId("training-plan-2")
                .build();
        ToolUseBlock pending = waitForPlanConfirm(agent, runtimeContext);
        capturingModel.resetToolCall();

        List<AgentEvent> events = agent.streamEvents(confirmMessage(resumedBlock(pending, null)), runtimeContext)
                .collectList()
                .block();

        assertThat(events).isNotNull();
        String toolText = events.stream()
                .filter(ToolResultTextDeltaEvent.class::isInstance)
                .map(event -> ((ToolResultTextDeltaEvent) event).getDelta())
                .collect(Collectors.joining());
        assertThat(toolText).contains("Parameter validation failed");
        verify(trainingPlanService, never()).submitPlan(anyLong(), anyString(), anyString(), any());
    }

    /**
     * 跑第一轮生成，取出框架抛出的待确认工具调用。
     *
     * @param agent 计划 Agent
     * @param runtimeContext 运行时上下文
     * @return 待确认的工具调用
     */
    private ToolUseBlock waitForPlanConfirm(HarnessAgent agent, RuntimeContext runtimeContext) {
        List<AgentEvent> events = agent.streamEvents(
                        Msg.builder().name("user").role(MsgRole.USER).textContent("生成训练计划").build(),
                        runtimeContext)
                .collectList()
                .block();
        assertThat(events).isNotNull();
        return events.stream()
                .filter(RequireUserConfirmEvent.class::isInstance)
                .map(event -> ((RequireUserConfirmEvent) event).getToolCalls().get(0))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有等到写工具的确认请求"));
    }

    /**
     * 按服务端确认回填的口径重建工具调用。
     *
     * @param pending 待确认调用
     * @param content 要带回的原始入参 JSON，传 null 表示故意不带
     * @return 重建后的工具调用
     */
    private ToolUseBlock resumedBlock(ToolUseBlock pending, String content) {
        return ToolUseBlock.builder()
                .id(pending.getId())
                .name(pending.getName())
                .content(content)
                .input(pending.getInput())
                .build();
    }

    /**
     * 组装确认回填消息（与 TrainingPlanGenerationServiceImpl 的确认口径一致）。
     *
     * @param block 重建后的工具调用
     * @return 回填消息
     */
    private Msg confirmMessage(ToolUseBlock block) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("用户已确认，请保存这份计划")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(new ConfirmResult(true, block))))
                .build();
    }

    /**
     * 验证提醒 Agent 的调度配置：名字固定、提示词取提醒 Agent 自己的文件、工具只有两个。
     */
    @Test
    void shouldBuildReminderAgentConfigWithOnlyReminderTools() {
        RuntimeAgentConfig config = agentFactory.buildReminderAgentConfig();

        assertThat(config.getName()).isEqualTo(AgentFactory.REMINDER_AGENT_NAME);
        assertThat(config.getSysPrompt()).contains(AgentFactory.REMINDER_AGENT_NAME);
        assertThat(config.getToolkit().getToolNames())
                .containsExactlyInAnyOrder("list_planned_users", "save_training_reminder");
        assertThat(config.getModel()).isSameAs(capturingModel);
    }

    /**
     * 验证计划 Agent 与助手、面试官共用同一套底层装配：自动召回照常（多一个背景参考）。
     *
     * <p>F7 的约束是「不写记忆」与「薄弱点以 MySQL 为准」，不是「不召回」；这里断言框架的长期记忆钩子确实
     * 走到了适配层的 {@code retrieve}，避免以后有人把计划 Agent 的记忆装配单独摘掉而与其它 Agent 不一致。
     */
    @Test
    void shouldKeepAutomaticRecallForPlanner() {
        com.wxy.career.middleware.UserLongTermMemoryAdapter adapter =
                mock(com.wxy.career.middleware.UserLongTermMemoryAdapter.class);
        when(adapter.retrieve(any(Msg.class))).thenReturn(reactor.core.publisher.Mono.just("历史片段"));
        ReflectionTestUtils.setField(agentFactory, "userLongTermMemoryAdapter", adapter);

        HarnessAgent agent = agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME);
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("1")
                .sessionId("training-plan-memory")
                .build();
        capturingModel.reset();
        agent.streamEvents(Msg.builder().role(MsgRole.USER).textContent("生成训练计划").build(), runtimeContext)
                .blockLast();

        // 调用前框架按当前问题召回一次：计划 Agent 的召回链路与助手一致。
        verify(adapter, org.mockito.Mockito.timeout(3000).atLeastOnce()).retrieve(any(Msg.class));
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
     * 验证助手 Agent 的工具白名单：读简历 + F9 读薄弱点 + 技能加载 + 框架的子 Agent 派发工具；
     * 框架默认工具（文件、Shell、Web、异步等待等）与只属于子 Agent 的提交类工具都没有混入。
     */
    @Test
    void shouldExposeOnlyAssistantTools() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.MAIN_AGENT_NAME);

        Set<String> toolNames = agent.getToolkit().getToolNames();
        // 白名单是精确集合：只多一个框架默认工具或业务工具都说明 allow / deny 没配好。
        // F9 起助手多了 get_weak_points（读薄弱点）与技能加载工具，两者都是 F9 讲解链路必需。
        assertThat(toolNames).containsExactlyInAnyOrder(
                "read_resume", "get_weak_points", "load_skill_through_path",
                "agent_spawn", "agent_send", "agent_list");
        assertThat(toolNames).doesNotContain("submit_resume_diagnosis");
        // F9 对记忆只有读：助手不得持有任何写记忆或记忆检索类工具。
        assertThat(toolNames).doesNotContain(
                "memory_save", "memory_search", "memory_get", "remember_fact");
    }

    /**
     * 验证助手只加载讲解规范技能：技能清单里只出现 tutoring，其它模块的技能不会漏给助手。
     *
     * <p>助手的技能过滤在构建期收窄到 tutoring；声明式子 Agent 的技能由各自的声明单独决定，
     * 因此这里同时确认 F2/F3 的子 Agent 技能没有被上级过滤影响。
     */
    @Test
    void shouldExposeOnlyTutoringSkillToAssistant() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.MAIN_AGENT_NAME);
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("1").sessionId("100").build();

        capturingModel.reset();
        agent.streamEvents(Msg.builder().role(MsgRole.USER).textContent("Redis 分布式锁再讲一遍").build(),
                runtimeContext).blockLast();

        // 只断言技能清单块：同一份提示词里还有「可用子 Agent」清单，里面本来就会出现子 Agent 的名字。
        String systemPrompt = capturingModel.systemPrompt();
        String availableSkills = systemPrompt.substring(
                systemPrompt.indexOf("<available_skills>"), systemPrompt.indexOf("</available_skills>"));
        assertThat(availableSkills).contains("<name>tutoring</name>");
        assertThat(availableSkills).doesNotContain("resume-analysis", "job-match");

        // 子 Agent 的技能由声明决定，不受助手侧的收窄影响。
        assertThat(agentFactory.buildResumeAnalystDeclaration().getSkills())
                .containsExactly("resume-analysis");
        assertThat(agentFactory.buildJobMatchDeclaration().getSkills())
                .containsExactly("job-match");
    }

    /**
     * 验证 Mem0 不可用时助手的讲解链路照常可用：记忆关闭不影响工具集与一轮对话。
     *
     * <p>F9 的薄弱点是 MySQL 的精确查询，记忆只用于「接着上次讲」的召回；因此记忆降级时讲解、
     * 举例与出题必须照常，不能因为召回失败就答不出话。
     */
    @Test
    void shouldKeepTutoringAvailableWhenMemoryDisabled() {
        // 显式把记忆关掉：本次断言的就是「Mem0 不可用时讲解链路照常」。
        MemoryProperties memoryProperties = new MemoryProperties();
        memoryProperties.setEnabled(false);
        UserLongTermMemoryAdapter adapter = new UserLongTermMemoryAdapter();
        ReflectionTestUtils.setField(adapter, "memoryProperties", memoryProperties);
        ReflectionTestUtils.setField(agentFactory, "userLongTermMemoryAdapter", adapter);

        HarnessAgent agent = agentFactory.getAgent(AgentFactory.MAIN_AGENT_NAME);
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("1").sessionId("101").build();

        assertThat(agent.getToolkit().getToolNames()).contains("get_weak_points");

        capturingModel.reset();
        agent.streamEvents(Msg.builder().role(MsgRole.USER).textContent("讲讲我的薄弱点").build(),
                runtimeContext).blockLast();

        assertThat(capturingModel.systemPrompt()).contains("tutoring");
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
     * 验证面试 Agent 的工具白名单：读简历 + 面试状态 + 记录回合 + 技能加载 + 框架派发工具，
     * 助手侧的业务工具（提交诊断结论）与框架默认工具都没有混入。
     */
    @Test
    void shouldExposeOnlyInterviewerTools() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.INTERVIEWER_AGENT_NAME);

        assertThat(agent).isInstanceOf(HarnessAgent.class);
        assertThat(agent.getName()).isEqualTo(AgentFactory.INTERVIEWER_AGENT_NAME);
        assertThat(agent.getToolkit().getToolNames()).containsExactlyInAnyOrder(
                "read_resume",
                "get_interview_state",
                "record_interview_answer",
                "load_skill_through_path");
        // 面试不写库：提交简历诊断结论这类工具不能出现在面试 Agent 上。
        assertThat(agent.getToolkit().getToolNames()).doesNotContain("submit_resume_diagnosis");
        // 评分结论只能由评分子 Agent 提交，面试官自己看不到这个工具。
        assertThat(agent.getToolkit().getToolNames()).doesNotContain("submit_answer_evaluation");
        // 评分由平台编排：面试官连派发子 Agent 的工具都没有，不存在把子 Agent 输出抄进回答的可能。
        assertThat(agent.getToolkit().getToolNames())
                .doesNotContain("agent_spawn", "agent_send", "agent_list");
    }

    /**
     * 验证评分子 Agent 的声明：只声明 answer-evaluation 技能，不暴露给用户，不注册业务工具。
     */
    @Test
    void shouldDeclareAnswerEvaluatorSubagent() {
        SubagentDeclaration declaration = agentFactory.buildAnswerEvaluatorDeclaration();

        assertThat(declaration.getName()).isEqualTo(AgentFactory.ANSWER_EVALUATOR_AGENT_NAME);
        assertThat(declaration.getSkills()).containsExactly("answer-evaluation");
        assertThat(declaration.getExposeToUser()).isFalse();
        assertThat(declaration.getTools()).containsExactly("submit_answer_evaluation");
    }

    /**
     * 验证评分子 Agent 的工具集被收窄到只剩技能加载工具：评分只依据派发消息给全的输入。
     */
    @Test
    void shouldNarrowAnswerEvaluatorTools() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.INTERVIEWER_AGENT_NAME);
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("1").sessionId("12").build();

        var subagent = agent.getSubagentAgentManager()
                .createAgentIfPresent(AgentFactory.ANSWER_EVALUATOR_AGENT_NAME, runtimeContext);

        assertThat(subagent).isPresent();
        Set<String> subagentTools = subagent.get().getToolkit().getToolNames();
        assertThat(subagentTools)
                .containsExactlyInAnyOrder("load_skill_through_path", "submit_answer_evaluation");
        assertThat(subagentTools).doesNotContain("read_resume", "web_search", "web_fetch", "wait_async_results");
    }

    /**
     * 验证面试会话的上下文压缩阈值就是登记值：40 条触发、保留 16 条，且压缩前不落盘、不卸载。
     *
     * <p>阈值是第 3 批能力接入项的一部分，改了必须同步 docs/技术约定.md 的取值理由，因此用测试固定。
     */
    @Test
    void shouldConfigureInterviewCompaction() {
        CompactionConfig compactionConfig = agentFactory.buildInterviewCompactionConfig();

        assertThat(compactionConfig.getTriggerMessages()).isEqualTo(40);
        assertThat(compactionConfig.getKeepMessages()).isEqualTo(16);
        assertThat(compactionConfig.isOffloadBeforeCompact()).isFalse();
        assertThat(compactionConfig.isFlushBeforeCompact()).isFalse();

        HarnessAgent agent = agentFactory.getAgent(AgentFactory.INTERVIEWER_AGENT_NAME);
        assertThat(agent.getCompactionHook()).isNotNull();
    }

    /**
     * 验证助手同时声明了「简历分析」与「岗位匹配」两个子 Agent，两者互不取代。
     *
     * <p>用户说「简历和这个 JD 一起看看」时两项分析都要能派出去，所以两个声明必须在同一个
     * 助手上都注册成功，而不是只剩其中一个。
     */
    @Test
    void shouldDeclareBothAnalysisSubagents() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.MAIN_AGENT_NAME);
        var agentManager = agent.getSubagentAgentManager();

        assertThat(agentManager.getAgentFactories().keySet())
                .contains(AgentFactory.RESUME_ANALYST_AGENT_NAME, AgentFactory.JOB_MATCH_AGENT_NAME);
        assertThat(agentManager.getDeclaration(AgentFactory.RESUME_ANALYST_AGENT_NAME)).isPresent();
        assertThat(agentManager.getDeclaration(AgentFactory.JOB_MATCH_AGENT_NAME)).isPresent();
    }

    /**
     * 验证岗位匹配子 Agent 的声明：工具只有只读的读简历工具，技能为 job-match，不暴露给用户。
     *
     * <p>JD 由用户在对话里粘贴、内容随派发指令进入上下文，所以 F3 不需要读 JD 的工具；
     * F3 也不下发结构化卡片，因此没有 F2 那种提交工具——工具集必须收窄到只剩读简历。
     */
    @Test
    void shouldDeclareJobMatchSubagent() {
        SubagentDeclaration declaration = agentFactory.buildJobMatchDeclaration();

        assertThat(declaration.getName()).isEqualTo(AgentFactory.JOB_MATCH_AGENT_NAME);
        assertThat(declaration.getTools()).containsExactly("read_resume");
        assertThat(declaration.getSkills()).containsExactly("job-match");
        assertThat(declaration.getExposeToUser()).isFalse();
    }

    /**
     * 验证两个子 Agent 的工具白名单各自收紧、互不串号。
     *
     * <p>岗位匹配子 Agent 只保留读简历与技能加载工具；简历分析子 Agent 的三件套（含提交诊断结论）
     * 不受影响，说明 F3 追加收窄没有破坏 F2 已冻结的白名单。
     */
    @Test
    void shouldNarrowBothSubagentsSeparately() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.MAIN_AGENT_NAME);
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("1").sessionId("1").build();

        var jobMatch = agent.getSubagentAgentManager()
                .createAgentIfPresent(AgentFactory.JOB_MATCH_AGENT_NAME, runtimeContext);
        assertThat(jobMatch).isPresent();
        Set<String> jobMatchTools = jobMatch.get().getToolkit().getToolNames();
        assertThat(jobMatchTools)
                .containsExactlyInAnyOrder("read_resume", "load_skill_through_path");
        assertThat(jobMatchTools).doesNotContain(
                "submit_resume_diagnosis", "web_search", "web_fetch", "wait_async_results");

        var resumeAnalyst = agent.getSubagentAgentManager()
                .createAgentIfPresent(AgentFactory.RESUME_ANALYST_AGENT_NAME, runtimeContext);
        assertThat(resumeAnalyst).isPresent();
        assertThat(resumeAnalyst.get().getToolkit().getToolNames())
                .containsExactlyInAnyOrder("read_resume", "submit_resume_diagnosis", "load_skill_through_path");
    }

    /**
     * 验证岗位匹配子 Agent 跑的是自己的提示词，并且同样继承了用户背景注入。
     *
     * <p>匹配判断需要求职目标作参照，提示词也必须与简历分析区分开，否则子 Agent 会去写诊断报告。
     */
    @Test
    void shouldUseOwnPromptAndInheritUserContextInJobMatchSubagent() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.MAIN_AGENT_NAME);
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("1").sessionId("101").build();
        HarnessAgent subagent = (HarnessAgent) agent.getSubagentAgentManager()
                .createAgentIfPresent(AgentFactory.JOB_MATCH_AGENT_NAME, runtimeContext)
                .orElseThrow();

        capturingModel.reset();
        subagent.streamEvents(
                Msg.builder().role(MsgRole.USER).textContent("这段 JD 我匹配吗").build(),
                runtimeContext).blockLast();

        String systemPrompt = capturingModel.systemPrompt();
        assertThat(systemPrompt).contains("测试系统提示词:" + AgentFactory.JOB_MATCH_AGENT_NAME);
        assertThat(systemPrompt).contains("目标岗位「后端开发」");
        assertThat(systemPrompt).contains("当前对话用户昵称：Alice");
    }

    /**
     * 验证报告子 Agent 的声明：技能为掌握度口径与报告规范，工具只有提交报告结论，不暴露给用户。
     */
    @Test
    void shouldDeclareReportWriterSubagent() {
        SubagentDeclaration declaration = agentFactory.buildReportWriterDeclaration();

        assertThat(declaration.getName()).isEqualTo(AgentFactory.REPORT_WRITER_AGENT_NAME);
        assertThat(declaration.getTools()).containsExactly("submit_interview_report");
        assertThat(declaration.getSkills()).containsExactly("mastery-evaluation", "interview-report");
        assertThat(declaration.getExposeToUser()).isFalse();
    }

    /**
     * 验证报告子 Agent 的工具集被收窄到「技能加载 + 提交报告」，框架默认平台工具混不进来。
     */
    @Test
    void shouldNarrowReportWriterTools() {
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.INTERVIEWER_AGENT_NAME);
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("1").sessionId("12").build();

        var subagent = agent.getSubagentAgentManager()
                .createAgentIfPresent(AgentFactory.REPORT_WRITER_AGENT_NAME, runtimeContext);

        assertThat(subagent).isPresent();
        Set<String> subagentTools = subagent.get().getToolkit().getToolNames();
        assertThat(subagentTools)
                .containsExactlyInAnyOrder("load_skill_through_path", "submit_interview_report");
        // 报告由平台后台派发，子 Agent 不需要任务平台工具，也不需要读库工具
        assertThat(subagentTools).doesNotContain(
                "task_output", "task_list", "task_cancel", "agent_spawn", "read_resume",
                "web_search", "web_fetch", "wait_async_results");
    }

    /**
     * 验证任务平台工具本批仍然全员 deny：报告状态由 interview_report 表承载，没有 Agent 需要它们。
     *
     * <p>放开范围必须随代码固定下来：面试官看不到 task_output / task_list / task_cancel 与
     * agent_spawn 系工具，报告只由平台派发；助手侧同样看不到任务工具。
     */
    @Test
    void shouldKeepTaskPlatformToolsDenied() {
        Set<String> interviewerTools =
                agentFactory.getAgent(AgentFactory.INTERVIEWER_AGENT_NAME).getToolkit().getToolNames();
        Set<String> assistantTools =
                agentFactory.getAgent(AgentFactory.MAIN_AGENT_NAME).getToolkit().getToolNames();

        assertThat(interviewerTools).doesNotContain(
                "task_output", "task_list", "task_cancel", "agent_spawn", "agent_send", "agent_list");
        assertThat(assistantTools).doesNotContain("task_output", "task_list", "task_cancel");
        // 报告提交工具只属于报告子 Agent，面试官自己看不到
        assertThat(interviewerTools).doesNotContain("submit_interview_report");
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
         * 让桩模型下一次返回的工具调用名，为空时返回纯文本。
         */
        private String toolCallName;

        /**
         * 工具调用入参。
         */
        private Map<String, Object> toolCallInput = Map.of();

        /**
         * 设置桩模型返回的工具调用，用于验证 HITL 等需要模型发起工具调用的链路。
         *
         * @param name 工具名
         * @param input 工具入参
         */
        void withToolCall(String name, Map<String, Object> input) {
            this.toolCallName = name;
            this.toolCallInput = input;
        }

        /**
         * 清除工具调用设置，避免影响后续用例。
         */
        void resetToolCall() {
            this.toolCallName = null;
            this.toolCallInput = Map.of();
        }

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
            if (toolCallName != null) {
                return Flux.just(ChatResponse.builder()
                        .id("stub-tool-call")
                        .content(List.of(ToolUseBlock.builder()
                                .id("call-1")
                                .name(toolCallName)
                                .input(toolCallInput)
                                .build()))
                        .build());
            }
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
