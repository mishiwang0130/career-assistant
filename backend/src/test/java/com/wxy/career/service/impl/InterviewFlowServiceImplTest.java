package com.wxy.career.service.impl;

import com.wxy.career.common.enums.ChatSceneEnum;
import com.wxy.career.common.enums.InterviewActionEnum;
import com.wxy.career.common.enums.InterviewOutcomeEnum;
import com.wxy.career.common.enums.InterviewQuestionTypeEnum;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.config.InterviewProperties;
import com.wxy.career.mapper.ChatSessionMapper;
import com.wxy.career.mapper.InterviewQaMapper;
import com.wxy.career.po.ChatSession;
import com.wxy.career.po.InterviewQa;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.InterviewAnswerResultVO;
import com.wxy.career.vo.InterviewAnswerSubmitVO;
import com.wxy.career.vo.InterviewStateRespVO;
import com.wxy.career.vo.UserProfileRespVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面试流程服务单测。
 *
 * <p>覆盖三件事：状态由问答记录回放得出（离开再回来能接着答）、每轮记录落库并把判定结果与流程动作写全、
 * 以及准入校验（非面试会话、求职目标未填、已结束）与账号隔离（查询始终带 user_id）。
 * 不依赖 MySQL、Redis 与模型。
 *
 * @author wxy
 * @date 2026-09-29
 */
class InterviewFlowServiceImplTest {

    /**
     * 面试会话 ID。
     */
    private static final String SESSION_ID = "12";

    /**
     * 用户 ID。
     */
    private static final long USER_ID = 1L;

    /**
     * 被 mock 的问答 Mapper。
     */
    private InterviewQaMapper interviewQaMapper;

    /**
     * 被 mock 的会话 Mapper。
     */
    private ChatSessionMapper chatSessionMapper;

    /**
     * 被 mock 的求职目标服务。
     */
    private UserProfileService userProfileService;

    /**
     * 被测的流程服务。
     */
    private InterviewFlowServiceImpl interviewFlowService;

    /**
     * 初始化依赖：工作年限 5 年 → 起始难度 L3。
     */
    @BeforeEach
    void setUp() {
        interviewQaMapper = mock(InterviewQaMapper.class);
        chatSessionMapper = mock(ChatSessionMapper.class);
        userProfileService = mock(UserProfileService.class);

        InterviewProperties interviewProperties = new InterviewProperties();
        interviewProperties.setQuestionCount(8);

        interviewFlowService = new InterviewFlowServiceImpl();
        ReflectionTestUtils.setField(interviewFlowService, "interviewQaMapper", interviewQaMapper);
        ReflectionTestUtils.setField(interviewFlowService, "chatSessionMapper", chatSessionMapper);
        ReflectionTestUtils.setField(interviewFlowService, "userProfileService", userProfileService);
        ReflectionTestUtils.setField(interviewFlowService, "interviewProperties", interviewProperties);

        when(chatSessionMapper.selectByIdAndUserId(anyLong(), anyLong()))
                .thenReturn(interviewSession());
        UserProfileRespVO profile = new UserProfileRespVO();
        profile.setTargetPosition("Java 后端开发");
        profile.setWorkYears(5);
        when(userProfileService.getRequiredUserProfile(anyLong())).thenReturn(profile);
        when(interviewQaMapper.selectBySession(anyLong(), anyLong())).thenReturn(List.of());
    }

    /**
     * 开场所见状态：第 1 题、起始难度 L3、主问题轮次、建议八股题。
     */
    @Test
    void shouldStartAtFirstQuestionWithStartDifficulty() {
        InterviewStateRespVO state = interviewFlowService.getState(USER_ID, SESSION_ID);

        assertThat(state.getQuestionIndex()).isEqualTo(1);
        assertThat(state.getQuestionCount()).isEqualTo(8);
        assertThat(state.getDifficulty()).isEqualTo(3);
        assertThat(state.getStartDifficulty()).isEqualTo(3);
        assertThat(state.getRoundNo()).isEqualTo(InterviewQa.ROUND_MAIN);
        assertThat(state.getFinished()).isFalse();
        assertThat(state.getRecommendedQuestionType()).isEqualTo(InterviewQuestionTypeEnum.BASIC.getValue());
    }

    /**
     * 断点续答：轮次落库后，重新读取的状态就是「同题追问、难度已上调」，再答一题则进入下一题。
     */
    @Test
    void shouldReplayProgressFromPersistedRows() {
        // 第 1 题答到要点：追问一层，难度 L3 → L4。
        InterviewAnswerResultVO first = recordTurn(
                "讲讲 JVM 内存结构", "BASIC", "CORRECT", false);
        assertThat(first.getAction()).isEqualTo(InterviewActionEnum.FOLLOW_UP.getValue());
        assertThat(first.getRoundNo()).isEqualTo(InterviewQa.ROUND_FOLLOW_UP);
        assertThat(first.getDifficulty()).isEqualTo(4);
        assertThat(first.getQuestionIndex()).isEqualTo(1);

        InterviewQa firstRow = commitAndCaptureRow();
        assertThat(firstRow.getQuestionIndex()).isEqualTo(1);
        assertThat(firstRow.getRoundNo()).isEqualTo(InterviewQa.ROUND_MAIN);
        assertThat(firstRow.getOutcome()).isEqualTo(InterviewOutcomeEnum.CORRECT.getValue());
        assertThat(firstRow.getNextAction()).isEqualTo(InterviewActionEnum.FOLLOW_UP.getValue());
        // 回答来自对话通道记下的用户消息，不由模型回填。
        assertThat(firstRow.getAnswer()).isEqualTo("我的回答");

        // 用户离开再回来：状态从已落库的记录回放出来。
        when(interviewQaMapper.selectBySession(USER_ID, 12L)).thenReturn(List.of(firstRow));
        InterviewStateRespVO resumed = interviewFlowService.getState(USER_ID, SESSION_ID);
        assertThat(resumed.getQuestionIndex()).isEqualTo(1);
        assertThat(resumed.getRoundNo()).isEqualTo(InterviewQa.ROUND_FOLLOW_UP);
        assertThat(resumed.getDifficulty()).isEqualTo(4);

        // 追问答得有遗漏：难度持平，换到第 2 题。
        InterviewAnswerResultVO second = recordTurn("补充一句", "BASIC", "PARTIAL", false);
        assertThat(second.getAction()).isEqualTo(InterviewActionEnum.NEXT_QUESTION.getValue());
        assertThat(second.getQuestionIndex()).isEqualTo(2);
        assertThat(second.getRoundNo()).isEqualTo(InterviewQa.ROUND_MAIN);
        assertThat(second.getDifficulty()).isEqualTo(4);

        InterviewQa secondRow = commitAndCaptureRow();
        when(interviewQaMapper.selectBySession(USER_ID, 12L)).thenReturn(List.of(firstRow, secondRow));
        InterviewStateRespVO afterSecond = interviewFlowService.getState(USER_ID, SESSION_ID);
        assertThat(afterSecond.getQuestionIndex()).isEqualTo(2);
        assertThat(afterSecond.getDifficulty()).isEqualTo(4);
        assertThat(afterSecond.getRecommendedQuestionType())
                .isEqualTo(InterviewQuestionTypeEnum.PROJECT.getValue());
    }

    /**
     * 答错：记为错题、直接换题，难度停在起始难度上。
     */
    @Test
    void shouldKeepDifficultyWhenAnswerIsWrong() {
        InterviewAnswerResultVO result = recordTurn("讲不清", "COMPREHENSIVE", "WRONG", false);

        assertThat(result.getAction()).isEqualTo(InterviewActionEnum.NEXT_QUESTION.getValue());
        assertThat(result.getDifficulty()).isEqualTo(3);

        InterviewQa row = commitAndCaptureRow();
        assertThat(row.getOutcome()).isEqualTo(InterviewOutcomeEnum.WRONG.getValue());
        assertThat(row.getNextAction()).isEqualTo(InterviewActionEnum.NEXT_QUESTION.getValue());
        assertThat(row.getJudgement()).isEqualTo("判定要点");
    }

    /**
     * 非面试会话读不到面试状态：返回 1502，而不是把助手会话当成面试。
     */
    @Test
    void shouldRejectNonInterviewSession() {
        ChatSession assistantSession = new ChatSession();
        assistantSession.setId(12L);
        assistantSession.setUserId(USER_ID);
        assistantSession.setScene(ChatSceneEnum.ASSISTANT.getValue());
        when(chatSessionMapper.selectByIdAndUserId(anyLong(), anyLong())).thenReturn(assistantSession);

        assertThatThrownBy(() -> interviewFlowService.getState(USER_ID, SESSION_ID))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(1502));
        // 助手会话不受面试规则影响：进流前只返回 null，按原链路继续。
        assertThat(interviewFlowService.prepareTurn(USER_ID, SESSION_ID, "你好")).isNull();
    }

    /**
     * 求职目标未填写：面试开不了，返回 F4 的 1101。
     */
    @Test
    void shouldRejectWhenProfileMissing() {
        when(userProfileService.getRequiredUserProfile(USER_ID))
                .thenThrow(new BizException(com.wxy.career.common.result.ErrorConstant.USER_PROFILE_REQUIRED));

        assertThatThrownBy(() -> interviewFlowService.getState(USER_ID, SESSION_ID))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(1101));
    }

    /**
     * 面试已结束：再作答返回 1501。
     */
    @Test
    void shouldRejectFinishedInterview() {
        InterviewQa lastRow = new InterviewQa();
        lastRow.setQuestionIndex(8);
        lastRow.setRoundNo(InterviewQa.ROUND_MAIN);
        lastRow.setQuestionType(InterviewQuestionTypeEnum.BASIC.getValue());
        lastRow.setDifficulty(4);
        lastRow.setOutcome(InterviewOutcomeEnum.WRONG.getValue());
        lastRow.setNextAction(InterviewActionEnum.FINISHED.getValue());
        when(interviewQaMapper.selectBySession(USER_ID, 12L)).thenReturn(List.of(lastRow));

        InterviewStateRespVO state = interviewFlowService.getState(USER_ID, SESSION_ID);
        assertThat(state.getFinished()).isTrue();
        assertThat(state.getQuestionIndex()).isEqualTo(8);

        assertThatThrownBy(() -> interviewFlowService.prepareTurn(USER_ID, SESSION_ID, "还想答"))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(1501));
    }

    /**
     * 会话隔离：状态与落库查询始终用当前用户 + 当前会话，两个账号的面试互不可见。
     */
    @Test
    void shouldScopeQueriesByUserAndSession() {
        interviewFlowService.getState(2L, SESSION_ID);

        verify(interviewQaMapper).selectBySession(2L, 12L);
    }

    /**
     * 没有待落库的回合时（例如开场那一轮只提问）不写库、也不下发进度。
     */
    @Test
    void shouldNotCommitWithoutBufferedTurn() {
        assertThat(interviewFlowService.commitTurn(USER_ID, SESSION_ID)).isNull();
    }

    /**
     * 异常结束会丢弃回合缓冲：之后不再落库，进度停在出错前那一步。
     */
    @Test
    void shouldDiscardBufferedTurn() {
        interviewFlowService.prepareTurn(USER_ID, SESSION_ID, "我的回答");
        interviewFlowService.discardTurn(USER_ID, SESSION_ID);

        assertThat(interviewFlowService.commitTurn(USER_ID, SESSION_ID)).isNull();
    }

    /**
     * 走一次完整的「记回答 → 提交判定」。
     *
     * @param question 题目正文
     * @param questionType 题型
     * @param outcome 判定结果
     * @param endNow 是否主动结束
     * @return 下一步指令
     */
    private InterviewAnswerResultVO recordTurn(
            String question, String questionType, String outcome, boolean endNow) {
        interviewFlowService.prepareTurn(USER_ID, SESSION_ID, "我的回答");
        InterviewAnswerSubmitVO submitVO = new InterviewAnswerSubmitVO();
        submitVO.setQuestion(question);
        submitVO.setQuestionType(questionType);
        submitVO.setOutcome(outcome);
        submitVO.setJudgement("判定要点");
        submitVO.setEndNow(endNow);
        return interviewFlowService.recordAnswer(USER_ID, SESSION_ID, submitVO);
    }

    /**
     * 落库本回合并取出写入的那条记录。
     *
     * @return 落库的问答记录
     */
    private InterviewQa commitAndCaptureRow() {
        interviewFlowService.commitTurn(USER_ID, SESSION_ID);
        ArgumentCaptor<InterviewQa> captor = ArgumentCaptor.forClass(InterviewQa.class);
        verify(interviewQaMapper, atLeastOnce()).insert(captor.capture());
        // 一条用例里可能连续落库多次，取最后一次写入的记录。
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    /**
     * 构造一个面试会话。
     *
     * @return 会话实体
     */
    private ChatSession interviewSession() {
        ChatSession session = new ChatSession();
        session.setId(12L);
        session.setUserId(USER_ID);
        session.setScene(ChatSceneEnum.INTERVIEW.getValue());
        return session;
    }
}
