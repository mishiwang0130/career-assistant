package com.wxy.career.service.impl;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.AssistantMessageService;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.AssistantMessageRespVO;
import com.wxy.career.vo.PageRespVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 面试评分编排单测。
 *
 * <p>评分由平台编排：面试官开流前直接调评分子 Agent，它的正文一律丢弃、结论只进流程缓冲。
 * 这里固定两条底线：开场那一轮没有上一道题时不调用模型；评分链路出问题时不让整轮对话失败。
 *
 * @author wxy
 * @date 2026-09-29
 */
class InterviewEvaluationServiceImplTest {

    /**
     * 面试会话 ID。
     */
    private static final String SESSION_ID = "12";

    /**
     * Agent 工厂 mock。
     */
    private AgentFactory agentFactory;

    /**
     * 消息服务 mock。
     */
    private AssistantMessageService assistantMessageService;

    /**
     * 被测的评分编排服务。
     */
    private InterviewEvaluationServiceImpl interviewEvaluationService;

    /**
     * 初始化被测服务与依赖桩。
     */
    @BeforeEach
    void setUp() {
        agentFactory = mock(AgentFactory.class);
        assistantMessageService = mock(AssistantMessageService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        interviewEvaluationService = new InterviewEvaluationServiceImpl();
        ReflectionTestUtils.setField(interviewEvaluationService, "agentFactory", agentFactory);
        ReflectionTestUtils.setField(
                interviewEvaluationService, "assistantMessageService", assistantMessageService);
        ReflectionTestUtils.setField(interviewEvaluationService, "userProfileService", userProfileService);
    }

    /**
     * 开场那一轮没有上一道题（用户刚发「开始面试」）：不调用模型，也不产生评分。
     */
    @Test
    void shouldSkipWhenNoPreviousQuestion() {
        when(assistantMessageService.listMessages(1L, 12L, 1L, 2L))
                .thenReturn(PageRespVO.of(1L, 1L, 2L, List.of(message("USER", "开始面试"))));

        interviewEvaluationService.evaluate(1L, SESSION_ID, "开始面试");

        verifyNoInteractions(agentFactory);
    }

    /**
     * 评分链路故障（取 Agent 就失败）不让整轮对话失败：只记 warn，流程按有遗漏继续。
     */
    @Test
    void shouldSwallowEvaluationFailure() {
        when(assistantMessageService.listMessages(anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(PageRespVO.of(1L, 1L, 2L, List.of(message("ASSISTANT", "第 1 题：讲讲 HashMap"))));
        when(agentFactory.getAgent(AgentFactory.INTERVIEWER_AGENT_NAME))
                .thenThrow(new BizException(ErrorConstant.SYSTEM_ERROR));

        interviewEvaluationService.evaluate(1L, SESSION_ID, "我的回答");

        verify(agentFactory).getAgent(AgentFactory.INTERVIEWER_AGENT_NAME);
    }

    /**
     * 构造消息。
     *
     * @param role 角色
     * @param content 内容
     * @return 消息
     */
    private AssistantMessageRespVO message(String role, String content) {
        AssistantMessageRespVO message = new AssistantMessageRespVO();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
