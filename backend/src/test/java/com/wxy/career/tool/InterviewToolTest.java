package com.wxy.career.tool;

import com.wxy.career.common.enums.InterviewActionEnum;
import com.wxy.career.common.enums.InterviewQuestionTypeEnum;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.service.InterviewFlowService;
import com.wxy.career.vo.InterviewAnswerResultVO;
import com.wxy.career.vo.InterviewAnswerSubmitVO;
import com.wxy.career.vo.InterviewStateRespVO;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 面试工具单测。
 *
 * <p>工具对模型只暴露「能直接照做的中文指令」：记录成功时给下一步怎么问，校验失败时给可读原因而不是抛异常，
 * 让模型能立刻改正后重试。用户身份只从 RuntimeContext 取，服务收到的也是这个身份。
 *
 * @author wxy
 * @date 2026-09-29
 */
class InterviewToolTest {

    /**
     * 被 mock 的流程服务。
     */
    private InterviewFlowService interviewFlowService;

    /**
     * 被测的记录工具。
     */
    private RecordInterviewAnswerTool recordTool;

    /**
     * 被测的读状态工具。
     */
    private GetInterviewStateTool stateTool;

    /**
     * 初始化工具与依赖。
     */
    @BeforeEach
    void setUp() {
        interviewFlowService = mock(InterviewFlowService.class);
        recordTool = new RecordInterviewAnswerTool();
        stateTool = new GetInterviewStateTool();
        ReflectionTestUtils.setField(recordTool, "interviewFlowService", interviewFlowService);
        ReflectionTestUtils.setField(stateTool, "interviewFlowService", interviewFlowService);
    }

    /**
     * 追问回合：指令里必须出现题序、难度与「追问一层」，模型照着问即可。
     */
    @Test
    void shouldReturnFollowUpInstruction() {
        when(interviewFlowService.recordAnswer(eq(1L), eq("12"), any()))
                .thenReturn(buildResult(InterviewActionEnum.FOLLOW_UP, 3, 8, 3,
                        InterviewQuestionTypeEnum.BASIC, false));

        String instruction = recordTool.recordInterviewAnswer(
                "讲讲 Redis 分布式锁", "BASIC", "CORRECT", "答到了 setnx 与过期时间", null, runtimeContext());

        assertThat(instruction).contains("追问一层").contains("第 3 题 / 共 8 题").contains("L3");
        // 用户回答不由模型回填：工具入参里没有 answer，服务从对话通道记下的内容里取。
        ArgumentCaptor<InterviewAnswerSubmitVO> captor = ArgumentCaptor.forClass(InterviewAnswerSubmitVO.class);
        org.mockito.Mockito.verify(interviewFlowService)
                .recordAnswer(eq(1L), eq("12"), captor.capture());
        assertThat(captor.getValue().getQuestionType()).isEqualTo("BASIC");
        assertThat(captor.getValue().getOutcome()).isEqualTo("CORRECT");
    }

    /**
     * 答错换题：指令要明确「不再围绕这道题追问」，并给出下一题的题序、难度与建议题型。
     */
    @Test
    void shouldReturnNextQuestionInstruction() {
        when(interviewFlowService.recordAnswer(eq(1L), eq("12"), any()))
                .thenReturn(buildResult(InterviewActionEnum.NEXT_QUESTION, 4, 8, 3,
                        InterviewQuestionTypeEnum.PROJECT, false));

        String instruction = recordTool.recordInterviewAnswer(
                "讲讲你的限流方案", "COMPREHENSIVE", "WRONG", "没做过", Boolean.FALSE, runtimeContext());

        assertThat(instruction).contains("换一道新题").contains("不要再围绕").contains("第 4 题 / 共 8 题");
        assertThat(instruction).contains("项目");
    }

    /**
     * 面试结束：指令要求收尾且不得出现分数、点评与报告。
     */
    @Test
    void shouldReturnFinishInstruction() {
        when(interviewFlowService.recordAnswer(eq(1L), eq("12"), any()))
                .thenReturn(buildResult(InterviewActionEnum.FINISHED, 8, 8, 4, null, true));

        String instruction = recordTool.recordInterviewAnswer(
                "最后一个问题", "BASIC", "CORRECT", null, Boolean.TRUE, runtimeContext());

        assertThat(instruction).contains("面试结束").contains("不要再出新题");
    }

    /**
     * 校验失败：工具返回可读原因与字段口径提示，不抛异常到模型侧。
     */
    @Test
    void shouldReturnReadableHintWhenSubmitRejected() {
        when(interviewFlowService.recordAnswer(eq(1L), eq("12"), any()))
                .thenThrow(new BizException(ErrorConstant.PARAM_ERROR));

        String instruction = recordTool.recordInterviewAnswer(
                "题目", "UNKNOWN_TYPE", "CORRECT", null, null, runtimeContext());

        assertThat(instruction).startsWith("提交失败").contains("参数错误").contains("BASIC");
    }

    /**
     * 读状态：把服务返回的快照原样交给模型，用户身份取自 RuntimeContext。
     */
    @Test
    void shouldReturnInterviewState() {
        InterviewStateRespVO state = new InterviewStateRespVO();
        state.setSessionId("12");
        state.setQuestionIndex(3);
        state.setQuestionCount(8);
        state.setDifficulty(2);
        state.setFinished(false);
        when(interviewFlowService.getState(1L, "12")).thenReturn(state);

        InterviewStateRespVO actual = stateTool.getInterviewState(runtimeContext());

        assertThat(actual).isSameAs(state);
    }

    /**
     * 构造下一步结果。
     *
     * @param action 流程动作
     * @param questionIndex 题序
     * @param questionCount 总题量
     * @param difficulty 难度
     * @param questionType 建议题型
     * @param finished 是否结束
     * @return 下一步结果
     */
    private InterviewAnswerResultVO buildResult(
            InterviewActionEnum action,
            int questionIndex,
            int questionCount,
            int difficulty,
            InterviewQuestionTypeEnum questionType,
            boolean finished) {
        InterviewAnswerResultVO result = new InterviewAnswerResultVO();
        result.setSessionId("12");
        result.setAction(action.getValue());
        result.setQuestionIndex(questionIndex);
        result.setQuestionCount(questionCount);
        result.setDifficulty(difficulty);
        result.setRoundNo(resolveRound(action));
        result.setQuestionType(questionType == null ? null : questionType.getValue());
        result.setFinished(finished);
        return result;
    }

    /**
     * 追问回合的轮次是 2，其余是 1。
     *
     * @param action 流程动作
     * @return 轮次
     */
    private int resolveRound(InterviewActionEnum action) {
        return action == InterviewActionEnum.FOLLOW_UP ? 2 : 1;
    }

    /**
     * 组装运行时上下文。
     *
     * @return 运行时上下文
     */
    private RuntimeContext runtimeContext() {
        return RuntimeContext.builder().userId("1").sessionId("12").build();
    }
}
