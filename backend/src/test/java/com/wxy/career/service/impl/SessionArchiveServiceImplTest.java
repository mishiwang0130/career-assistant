package com.wxy.career.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.enums.ChatSceneEnum;
import com.wxy.career.common.enums.MessageRoleEnum;
import com.wxy.career.config.MemoryProperties;
import com.wxy.career.mapper.AssistantMessageMapper;
import com.wxy.career.mapper.ChatSessionMapper;
import com.wxy.career.po.AssistantMessage;
import com.wxy.career.po.ChatSession;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.UserMemoryService;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.UserProfileRespVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话归档总结服务单测。
 *
 * <p>不连接 MySQL、Redis 与模型：会话扫描、消息读取、Mem0 写入全部用桩替换，只固定服务端编排契约——
 * 只归档助手场景的安静会话、模型只产出 JSON、记忆经 {@code UserMemoryService} 以 FACT 写入、
 * 失败按尝试次数重试且达到上限置 FAILED。
 *
 * @author wxy
 * @date 2026-10-03
 */
class SessionArchiveServiceImplTest {

    /**
     * 会话 ID。
     */
    private static final Long SESSION_ID = 11L;

    /**
     * 用户 ID。
     */
    private static final Long USER_ID = 1L;

    /**
     * 被测服务。
     */
    private SessionArchiveServiceImpl service;

    /**
     * 会话元数据桩。
     */
    private ChatSessionMapper chatSessionMapper;

    /**
     * 会话消息桩。
     */
    private AssistantMessageMapper assistantMessageMapper;

    /**
     * 求职目标桩。
     */
    private UserProfileService userProfileService;

    /**
     * 记忆写入桩。
     */
    private UserMemoryService userMemoryService;

    /**
     * 归档 Agent 桩。
     */
    private HarnessAgent archiver;

    /**
     * 归档配置。
     */
    private MemoryProperties memoryProperties;

    /**
     * 初始化被测服务与全部桩。
     */
    @BeforeEach
    void setUp() {
        chatSessionMapper = mock(ChatSessionMapper.class);
        assistantMessageMapper = mock(AssistantMessageMapper.class);
        userProfileService = mock(UserProfileService.class);
        userMemoryService = mock(UserMemoryService.class);
        archiver = mock(HarnessAgent.class);
        AgentFactory agentFactory = mock(AgentFactory.class);
        when(agentFactory.getAgent(AgentFactory.SESSION_ARCHIVER_AGENT_NAME)).thenReturn(archiver);

        memoryProperties = new MemoryProperties();
        service = new SessionArchiveServiceImpl();
        ReflectionTestUtils.setField(service, "memoryProperties", memoryProperties);
        ReflectionTestUtils.setField(service, "chatSessionMapper", chatSessionMapper);
        ReflectionTestUtils.setField(service, "assistantMessageMapper", assistantMessageMapper);
        ReflectionTestUtils.setField(service, "userProfileService", userProfileService);
        ReflectionTestUtils.setField(service, "userMemoryService", userMemoryService);
        ReflectionTestUtils.setField(service, "agentFactory", agentFactory);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        UserProfileRespVO profile = new UserProfileRespVO();
        profile.setTargetPosition("后端开发");
        profile.setWorkYears(3);
        when(userProfileService.getUserProfileByUserId(USER_ID)).thenReturn(profile);
    }

    /**
     * 归档主链路：扫到安静会话 → 模型给出 2 条结论 → 以 FACT 写入 → 标记 DONE。
     */
    @Test
    @DisplayName("安静会话归档成功并写入 FACT 记忆")
    void shouldArchiveQuietSessionAndWriteFacts() {
        when(chatSessionMapper.selectQuietSessionsForArchive(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(session(0)));
        when(assistantMessageMapper.selectList(any(Wrapper.class))).thenReturn(
                List.of(message(MessageRoleEnum.USER, "Redis 分布式锁怎么实现"),
                        message(MessageRoleEnum.ASSISTANT, "用 SETNX 加过期时间……")));
        replyWith("""
                {"memories":["Redis 分布式锁：已讲基本实现与续期，卡点在锁误删","用户在订单系统重构里只拆过服务"]}
                """);
        when(userMemoryService.rememberFact(anyLong(), anyString(), anyString())).thenReturn(true);

        int archived = service.archiveQuietSessions();

        assertThat(archived).isEqualTo(1);
        // 只扫助手场景的待归档会话，条数上限与重试上限来自配置。
        verify(chatSessionMapper).selectQuietSessionsForArchive(
                eq(ChatSceneEnum.ASSISTANT.getValue()), any(LocalDateTime.class), eq(5), eq(20));
        ArgumentCaptor<String> memoryCaptor = ArgumentCaptor.forClass(String.class);
        verify(userMemoryService, times(2))
                .rememberFact(eq(USER_ID), eq(String.valueOf(SESSION_ID)), memoryCaptor.capture());
        assertThat(memoryCaptor.getAllValues()).containsExactly(
                "Redis 分布式锁：已讲基本实现与续期，卡点在锁误删",
                "用户在订单系统重构里只拆过服务");
        verify(chatSessionMapper).updateArchiveState(
                eq(SESSION_ID), eq(ChatSession.ARCHIVE_STATUS_DONE), any(LocalDateTime.class), eq(1));
    }

    /**
     * 模型认为这场对话没有值得留存的信息时，0 条记忆是正常结果：不写记忆，同样标记 DONE。
     */
    @Test
    @DisplayName("提炼出 0 条记忆也标记已归档")
    void shouldMarkDoneWhenNoMemoryProduced() {
        when(chatSessionMapper.selectQuietSessionsForArchive(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(session(0)));
        when(assistantMessageMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(message(MessageRoleEnum.USER, "你好")));
        replyWith("{\"memories\":[]}");

        assertThat(service.archiveQuietSessions()).isEqualTo(1);
        verify(userMemoryService, never()).rememberFact(anyLong(), anyString(), anyString());
        verify(chatSessionMapper).updateArchiveState(
                eq(SESSION_ID), eq(ChatSession.ARCHIVE_STATUS_DONE), any(LocalDateTime.class), eq(1));
    }

    /**
     * 记忆写入失败时不算归档成功：保持待归档并累加尝试次数，下轮重试（这就是「留待补偿」的落地方式）。
     */
    @Test
    @DisplayName("记忆写入失败时保持待归档以便重试")
    void shouldKeepPendingWhenMemoryWriteFails() {
        when(chatSessionMapper.selectQuietSessionsForArchive(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(session(0)));
        when(assistantMessageMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(message(MessageRoleEnum.USER, "Redis 分布式锁怎么实现")));
        replyWith("{\"memories\":[\"Redis 分布式锁：卡点在锁误删\"]}");
        when(userMemoryService.rememberFact(anyLong(), anyString(), anyString())).thenReturn(false);

        assertThat(service.archiveQuietSessions()).isZero();
        verify(chatSessionMapper).updateArchiveState(
                eq(SESSION_ID), eq(ChatSession.ARCHIVE_STATUS_PENDING), isNull(), eq(1));
    }

    /**
     * 模型调用一直失败时，达到重试上限置 FAILED 并停止自动重试，避免无休止消耗模型调用。
     */
    @Test
    @DisplayName("达到重试上限后置 FAILED")
    void shouldFailAfterRetryLimit() {
        memoryProperties.getArchive().setMaxAttempts(2);
        when(chatSessionMapper.selectQuietSessionsForArchive(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(session(1)));
        when(assistantMessageMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(message(MessageRoleEnum.USER, "Redis 分布式锁怎么实现")));
        when(archiver.call(any(Msg.class), any(RuntimeContext.class)))
                .thenThrow(new IllegalStateException("模型不可用"));

        assertThat(service.archiveQuietSessions()).isZero();
        verify(chatSessionMapper).updateArchiveState(
                eq(SESSION_ID), eq(ChatSession.ARCHIVE_STATUS_FAILED), isNull(), eq(2));
    }

    /**
     * 未达上限的失败保持待归档：偶发的模型抖动下一轮会自然重试。
     */
    @Test
    @DisplayName("首次失败保持待归档")
    void shouldKeepPendingAfterFirstFailure() {
        when(chatSessionMapper.selectQuietSessionsForArchive(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(session(0)));
        when(assistantMessageMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(message(MessageRoleEnum.USER, "Redis 分布式锁怎么实现")));
        when(archiver.call(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Mono.error(new IllegalStateException("模型超时")));

        assertThat(service.archiveQuietSessions()).isZero();
        verify(chatSessionMapper).updateArchiveState(
                eq(SESSION_ID), eq(ChatSession.ARCHIVE_STATUS_PENDING), isNull(), eq(1));
    }

    /**
     * 长期记忆总开关关闭时不扫库：记忆只读不写，归档任务等于不存在。
     */
    @Test
    @DisplayName("长期记忆关闭时整段跳过")
    void shouldSkipWhenMemoryDisabled() {
        memoryProperties.setEnabled(false);

        assertThat(service.archiveQuietSessions()).isZero();
        verify(chatSessionMapper, never()).selectQuietSessionsForArchive(anyString(), any(), anyInt(), anyInt());
    }

    /**
     * 归档开关关闭时不扫库：便于本地只验证对话链路、不让后台任务调用模型。
     */
    @Test
    @DisplayName("归档开关关闭时不扫库")
    void shouldSkipWhenArchiveDisabled() {
        memoryProperties.getArchive().setEnabled(false);

        assertThat(service.archiveQuietSessions()).isZero();
        verify(chatSessionMapper, never()).selectQuietSessionsForArchive(anyString(), any(), anyInt(), anyInt());
    }

    /**
     * 对话记录超出长度上限时保留最近的对话：归档判断「讲到哪里、卡在哪里」靠的是最新几轮。
     */
    @Test
    @DisplayName("超长对话记录只保留最近内容")
    void shouldKeepLatestTranscriptWhenTooLong() {
        ChatSession session = session(0);
        List<AssistantMessage> messages = List.of(
                message(MessageRoleEnum.USER, "最早的问题" + "旧".repeat(200)),
                message(MessageRoleEnum.USER, "最近的问题：锁误删怎么处理"));

        String input = service.buildArchiverInput(session, messages, 40);

        assertThat(input).contains("（更早的消息已省略）");
        assertThat(input).contains("最近的问题：锁误删怎么处理");
        assertThat(input).doesNotContain("最早的问题");
        assertThat(input).contains("后端开发").contains("3 年");
    }

    /**
     * 模型输出带 Markdown 代码块时也要能解析，并去重、按上限截断。
     */
    @Test
    @DisplayName("解析带围栏的 JSON 并去重截断")
    void shouldParseMemoriesFromFencedJson() {
        List<String> memories = service.parseMemories(
                "```json\n{\"memories\":[\"A\",\"A\",\"B\",\"C\",\"D\"]}\n```", 3);

        assertThat(memories).containsExactly("A", "B", "C");
        assertThat(service.parseMemories("{\"memories\":[]}", 3)).isEmpty();
    }

    /**
     * 输出不是合法 JSON 时按失败处理：不能静默当成 0 条记忆，否则一次格式抖动就会永久丢掉这场会话。
     */
    @Test
    @DisplayName("非法 JSON 按失败处理")
    void shouldFailOnMalformedJson() {
        assertThatThrownBy(() -> service.parseMemories("这场对话没有值得记住的内容", 3))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.parseMemories("{\"memory\":[]}", 3))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 构造一场待归档会话。
     *
     * @param attempts 已尝试次数
     * @return 会话实体
     */
    private ChatSession session(int attempts) {
        ChatSession session = new ChatSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setScene(ChatSceneEnum.ASSISTANT.getValue());
        session.setArchiveStatus(ChatSession.ARCHIVE_STATUS_PENDING);
        session.setArchiveAttempts(attempts);
        session.setLastMessageAt(LocalDateTime.now().minusHours(1));
        return session;
    }

    /**
     * 构造一条会话消息。
     *
     * @param role 消息角色
     * @param content 消息内容
     * @return 消息实体
     */
    private AssistantMessage message(MessageRoleEnum role, String content) {
        AssistantMessage message = new AssistantMessage();
        message.setId((long) content.length());
        message.setUserId(USER_ID);
        message.setSessionId(SESSION_ID);
        message.setRole(role.getValue());
        message.setContent(content);
        return message;
    }

    /**
     * 让归档 Agent 返回指定正文。
     *
     * @param text 模型输出
     */
    private void replyWith(String text) {
        Msg reply = Msg.builder().name("assistant").role(MsgRole.ASSISTANT).textContent(text).build();
        when(archiver.call(any(Msg.class), any(RuntimeContext.class))).thenReturn(Mono.just(reply));
    }
}
