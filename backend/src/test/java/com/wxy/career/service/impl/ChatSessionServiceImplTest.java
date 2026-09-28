package com.wxy.career.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wxy.career.common.auth.LoginUser;
import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.mapper.ChatSessionMapper;
import com.wxy.career.po.ChatSession;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.AssistantMessageService;
import com.wxy.career.vo.ChatSessionCreateReqVO;
import com.wxy.career.vo.ChatSessionRenameReqVO;
import com.wxy.career.vo.ChatSessionRespVO;
import com.wxy.career.vo.PageRespVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 会话中心服务测试。
 *
 * <p>全部依赖使用 Mockito 桩，不依赖 MySQL、Redis 与模型配置。
 *
 * @author wxy
 * @date 2026-09-28
 */
@ExtendWith(MockitoExtension.class)
class ChatSessionServiceImplTest {

    /**
     * 会话元数据 Mapper。
     */
    @Mock
    private ChatSessionMapper chatSessionMapper;

    /**
     * 消息服务。
     */
    @Mock
    private AssistantMessageService assistantMessageService;

    /**
     * Agent 工厂。
     */
    @Mock
    private AgentFactory agentFactory;

    /**
     * 被测会话中心服务。
     */
    @InjectMocks
    private ChatSessionServiceImpl chatSessionService;

    /**
     * 初始化登录用户上下文。
     */
    @BeforeEach
    void setUp() {
        LoginUserHolder.set(new LoginUser(1L, "alice", "jti-1"));
    }

    /**
     * 清理登录用户上下文。
     */
    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    /**
     * 验证新建会话使用后端生成的 ID，并写入默认标题、场景与活跃时间。
     */
    @Test
    void shouldCreateSessionWithGeneratedIdAndDefaultTitle() {
        ChatSessionCreateReqVO reqVO = new ChatSessionCreateReqVO();
        reqVO.setScene("ASSISTANT");
        when(chatSessionMapper.insert(any(ChatSession.class))).thenAnswer(invocation -> {
            ChatSession session = invocation.getArgument(0);
            session.setId(12L);
            return 1;
        });

        ChatSessionRespVO response = chatSessionService.create(reqVO);

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(chatSessionMapper).insert(captor.capture());
        ChatSession inserted = captor.getValue();
        assertThat(inserted.getUserId()).isEqualTo(1L);
        assertThat(inserted.getScene()).isEqualTo("ASSISTANT");
        assertThat(inserted.getTitle()).isEqualTo(ChatSession.DEFAULT_TITLE);
        assertThat(inserted.getStatus()).isEqualTo(ChatSession.STATUS_ACTIVE);
        assertThat(inserted.getLastMessageAt()).isNotNull();
        assertThat(response.getSessionId()).isEqualTo("12");
        assertThat(response.getScene()).isEqualTo("ASSISTANT");
        assertThat(response.getTitle()).isEqualTo(ChatSession.DEFAULT_TITLE);
    }

    /**
     * 验证未登记或尚未开放的场景被拒绝。
     */
    @Test
    void shouldRejectUnavailableScene() {
        ChatSessionCreateReqVO reqVO = new ChatSessionCreateReqVO();
        reqVO.setScene("INTERVIEW");

        assertThatThrownBy(() -> chatSessionService.create(reqVO))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(1052));

        reqVO.setScene("UNKNOWN");
        assertThatThrownBy(() -> chatSessionService.create(reqVO))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(1052));

        verify(chatSessionMapper, never()).insert(any(ChatSession.class));
    }

    /**
     * 验证会话列表按当前用户分页查询，页码与每页条数会被归一化。
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldListCurrentUserSessionsWithNormalizedPaging() {
        when(chatSessionMapper.selectPageByUserId(any(), eq(1L))).thenAnswer(invocation -> {
            Page<ChatSession> page = invocation.getArgument(0);
            page.setRecords(List.of(existingSession(5L, "Java 并发问题"), existingSession(6L, "简历怎么改")));
            page.setTotal(9L);
            return page;
        });

        PageRespVO<ChatSessionRespVO> result = chatSessionService.list(0, 500);

        ArgumentCaptor<Page<ChatSession>> captor = ArgumentCaptor.forClass(Page.class);
        verify(chatSessionMapper).selectPageByUserId(captor.capture(), eq(1L));
        assertThat(captor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(captor.getValue().getSize()).isEqualTo(100L);
        assertThat(result.getTotal()).isEqualTo(9L);
        assertThat(result.getPageNum()).isEqualTo(1L);
        assertThat(result.getPageSize()).isEqualTo(100L);
        assertThat(result.getRecords()).extracting(ChatSessionRespVO::getSessionId)
                .containsExactly("5", "6");
    }

    /**
     * 验证重命名会归一化标题，且跨账号或已删除会话表现为不存在。
     */
    @Test
    void shouldNormalizeTitleOnRename() {
        when(chatSessionMapper.selectByIdAndUserId(9L, 1L)).thenReturn(existingSession(9L, "新会话"));
        ChatSessionRenameReqVO reqVO = new ChatSessionRenameReqVO();
        reqVO.setTitle("  Java   并发\n问题  ");

        ChatSessionRespVO response = chatSessionService.rename("9", reqVO);

        verify(chatSessionMapper).updateTitle(9L, 1L, "Java 并发 问题");
        assertThat(response.getTitle()).isEqualTo("Java 并发 问题");
    }

    /**
     * 验证重命名不存在的会话返回会话不存在，且不产生任何写操作。
     */
    @Test
    void shouldTreatMissingSessionAsNotFoundOnRename() {
        when(chatSessionMapper.selectByIdAndUserId(9L, 1L)).thenReturn(null);
        ChatSessionRenameReqVO reqVO = new ChatSessionRenameReqVO();
        reqVO.setTitle("新标题");

        assertThatThrownBy(() -> chatSessionService.rename("9", reqVO))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(1051));
        verify(chatSessionMapper, never()).updateTitle(any(), any(), any());
    }

    /**
     * 验证删除会话同时逻辑删除会话、清空消息并清理 Agent 会话状态。
     */
    @Test
    void shouldDeleteSessionWithMessagesAndAgentState() {
        when(chatSessionMapper.selectByIdAndUserId(9L, 1L)).thenReturn(existingSession(9L, "新会话"));

        chatSessionService.delete("9");

        verify(chatSessionMapper).deleteById(9L);
        verify(assistantMessageService).clearSession(1L, 9L);
        verify(agentFactory).clearSession(1L, "9");
    }

    /**
     * 验证删除跨账号或已删除的会话时直接返回会话不存在。
     */
    @Test
    void shouldTreatCrossAccountSessionAsNotFoundOnDelete() {
        when(chatSessionMapper.selectByIdAndUserId(9L, 1L)).thenReturn(null);

        assertThatThrownBy(() -> chatSessionService.delete("9"))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(1051));
        verify(chatSessionMapper, never()).deleteById(any(Long.class));
        verifyNoInteractions(assistantMessageService, agentFactory);
    }

    /**
     * 验证旧的字符串会话 ID 不能再访问，统一表现为会话不存在。
     */
    @Test
    void shouldTreatLegacySessionIdAsNotFound() {
        assertThatThrownBy(() -> chatSessionService.requireOwnedSession(1L, "session-1"))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(1051));
    }

    /**
     * 验证默认标题会被首条用户消息改写，并折叠换行与连续空白后截取前 20 个字符。
     */
    @Test
    void shouldRewriteDefaultTitleWithFirstUserMessage() {
        when(chatSessionMapper.selectByIdAndUserId(9L, 1L)).thenReturn(existingSession(9L, "新会话"));

        chatSessionService.recordUserMessage(1L, "9", "  Java\n并发   问题  一二三四五六七八九十 ");

        verify(chatSessionMapper).updateTitleAndLastMessageAt(
                eq(9L), eq(1L), eq("Java 并发 问题 一二三四五六七八九"), any(LocalDateTime.class));
    }

    /**
     * 验证自动标题按 Unicode 码点截断，不会把增补字符切成半个。
     */
    @Test
    void shouldTruncateAutoTitleByCodePoint() {
        when(chatSessionMapper.selectByIdAndUserId(9L, 1L)).thenReturn(existingSession(9L, "新会话"));

        chatSessionService.recordUserMessage(1L, "9", "🙂".repeat(25));

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatSessionMapper).updateTitleAndLastMessageAt(
                eq(9L), eq(1L), titleCaptor.capture(), any(LocalDateTime.class));
        String title = titleCaptor.getValue();
        assertThat(title).isEqualTo("🙂".repeat(20));
        assertThat(title.codePointCount(0, title.length())).isEqualTo(20);
    }

    /**
     * 验证用户自定义标题不会被自动标题覆盖，但仍刷新活跃时间。
     */
    @Test
    void shouldKeepCustomTitleAndRefreshActiveTime() {
        when(chatSessionMapper.selectByIdAndUserId(9L, 1L)).thenReturn(existingSession(9L, "我的简历分析"));

        chatSessionService.recordUserMessage(1L, "9", "帮我看看这段经历怎么写");

        verify(chatSessionMapper).updateTitleAndLastMessageAt(
                eq(9L), eq(1L), eq("我的简历分析"), any(LocalDateTime.class));
    }

    /**
     * 验证会话元数据缺失时只跳过更新，不抛异常（对话主流程不受影响）。
     */
    @Test
    void shouldSkipRecordWhenSessionMissing() {
        when(chatSessionMapper.selectByIdAndUserId(9L, 1L)).thenReturn(null);

        chatSessionService.recordUserMessage(1L, "9", "你好");

        verify(chatSessionMapper, never()).updateTitleAndLastMessageAt(any(), any(), any(), any());
    }

    /**
     * 验证没有用户身份或非法会话 ID 时不做任何数据库访问。
     */
    @Test
    void shouldSkipRecordWithoutUserIdOrValidSessionId() {
        chatSessionService.recordUserMessage(null, "9", "你好");
        chatSessionService.recordUserMessage(1L, "session-1", "你好");

        verifyNoInteractions(chatSessionMapper);
    }

    /**
     * 构建已有会话。
     *
     * @param id 会话 ID
     * @param title 会话标题
     * @return 会话实体
     */
    private ChatSession existingSession(Long id, String title) {
        ChatSession session = new ChatSession();
        session.setId(id);
        session.setUserId(1L);
        session.setScene("ASSISTANT");
        session.setTitle(title);
        session.setStatus(ChatSession.STATUS_ACTIVE);
        session.setLastMessageAt(LocalDateTime.now().minusMinutes(5));
        return session;
    }
}
