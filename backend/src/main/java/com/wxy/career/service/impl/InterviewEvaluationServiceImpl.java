package com.wxy.career.service.impl;

import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.AssistantMessageService;
import com.wxy.career.service.InterviewEvaluationService;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.common.enums.MessageRoleEnum;
import com.wxy.career.vo.AssistantMessageRespVO;
import com.wxy.career.vo.PageRespVO;
import com.wxy.career.vo.UserProfileRespVO;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 面试单题评分服务实现。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
@Service
public class InterviewEvaluationServiceImpl implements InterviewEvaluationService {

    /**
     * 最近一条助手消息：面试官上一轮问的题（可能是追问）。
     */
    private static final long LAST_QUESTION_PAGE_SIZE = 2L;

    /**
     * 评分子 Agent 的执行上限，超时按评分不可用处理，避免拖住整轮流。
     */
    private static final Duration EVALUATION_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Agent 工厂，用于取面试 Agent 与它的评分子 Agent。
     */
    @Resource
    private AgentFactory agentFactory;

    /**
     * 消息服务，用于取上一道题。
     */
    @Resource
    private AssistantMessageService assistantMessageService;

    /**
     * 求职目标服务，给评分子 Agent 提供岗位与工作年限。
     */
    @Resource
    private UserProfileService userProfileService;

    /**
     * 对用户本题的作答评分。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @param answer 用户本题的回答
     */
    @Override
    public void evaluate(Long userId, String sessionId, String answer) {
        String question = lastAssistantQuestion(userId, sessionId);
        if (!StringUtils.hasText(question)) {
            // 开场那一轮没有上一道题，直接跳过；用户发「开始面试」时也不该产生评分。
            return;
        }
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(String.valueOf(userId))
                .sessionId(sessionId)
                .build();
        Optional<Agent> evaluator;
        try {
            HarnessAgent interviewer = agentFactory.getAgent(AgentFactory.INTERVIEWER_AGENT_NAME);
            evaluator = interviewer.getSubagentAgentManager()
                    .createAgentIfPresent(AgentFactory.ANSWER_EVALUATOR_AGENT_NAME, runtimeContext);
        } catch (RuntimeException exception) {
            log.warn("取评分子 Agent 失败，本回合按有遗漏继续，userId={}，sessionId={}", userId, sessionId, exception);
            return;
        }
        if (evaluator.isEmpty() || !(evaluator.get() instanceof HarnessAgent evaluatorAgent)) {
            log.warn("评分子 Agent 不可用，本回合按有遗漏继续，userId={}，sessionId={}", userId, sessionId);
            return;
        }
        Msg message = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(buildEvaluationInput(userId, question, answer))
                .build();
        try {
            // 只关心副作用：结论由评分子 Agent 通过 submit_answer_evaluation 落进缓冲，正文一律丢弃。
            evaluatorAgent.streamEvents(message, runtimeContext).blockLast(EVALUATION_TIMEOUT);
        } catch (RuntimeException exception) {
            log.warn("评分子 Agent 执行失败，本回合按有遗漏继续，userId={}，sessionId={}", userId, sessionId, exception);
        }
    }

    /**
     * 取上一道题：面试官最近一条助手消息。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @return 上一道题的正文，没有时返回 null
     */
    private String lastAssistantQuestion(Long userId, String sessionId) {
        try {
            PageRespVO<AssistantMessageRespVO> page = assistantMessageService.listMessages(
                    userId, Long.valueOf(sessionId), 1L, LAST_QUESTION_PAGE_SIZE);
            List<AssistantMessageRespVO> records = page.getRecords();
            for (AssistantMessageRespVO record : records) {
                if (record != null && MessageRoleEnum.ASSISTANT.getValue().equals(record.getRole())) {
                    return record.getContent();
                }
            }
        } catch (RuntimeException exception) {
            log.warn("读取上一道题失败，跳过本回合评分，userId={}，sessionId={}", userId, sessionId, exception);
        }
        return null;
    }

    /**
     * 组装评分子 Agent 的输入。
     *
     * @param userId 用户 ID
     * @param question 上一道题
     * @param answer 用户本题的回答
     * @return 输入正文
     */
    private String buildEvaluationInput(Long userId, String question, String answer) {
        UserProfileRespVO profile = userProfileService.getUserProfileByUserId(userId);
        String targetPosition = profile == null ? "未填写" : profile.getTargetPosition();
        Integer workYears = profile == null || profile.getWorkYears() == null ? 0 : profile.getWorkYears();
        return """
                请对下面这道题的作答给出评分结论，并用 submit_answer_evaluation 提交一次。

                【目标岗位】%s（工作年限 %d 年）
                【题目】%s
                【用户回答】%s
                """.formatted(targetPosition, workYears, question, answer);
    }

}
