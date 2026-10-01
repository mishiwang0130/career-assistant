package com.wxy.career.service.impl;

import com.wxy.career.common.enums.ChatSceneEnum;
import com.wxy.career.common.enums.InterviewActionEnum;
import com.wxy.career.common.enums.InterviewOutcomeEnum;
import com.wxy.career.common.enums.InterviewQuestionTypeEnum;
import com.wxy.career.common.enums.MessageRoleEnum;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.config.InterviewProperties;
import com.wxy.career.mapper.ChatSessionMapper;
import com.wxy.career.mapper.InterviewQaMapper;
import com.wxy.career.po.ChatSession;
import com.wxy.career.po.InterviewQa;
import com.wxy.career.service.AssistantMessageService;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.AssistantMessageRespVO;
import com.wxy.career.vo.AnswerEvaluationSubmitVO;
import com.wxy.career.vo.InterviewAnswerResultVO;
import com.wxy.career.vo.InterviewAnswerSubmitVO;
import com.wxy.career.vo.InterviewResultItemVO;
import com.wxy.career.vo.InterviewResultRespVO;
import com.wxy.career.vo.InterviewStateRespVO;
import com.wxy.career.vo.PageRespVO;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.never;
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
     * 被 mock 的消息服务：回合开始时用它记下正在回答的题目。
     */
    private AssistantMessageService assistantMessageService;

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
        assistantMessageService = mock(AssistantMessageService.class);

        InterviewProperties interviewProperties = new InterviewProperties();
        interviewProperties.setQuestionCount(8);

        interviewFlowService = new InterviewFlowServiceImpl();
        ReflectionTestUtils.setField(interviewFlowService, "interviewQaMapper", interviewQaMapper);
        ReflectionTestUtils.setField(interviewFlowService, "chatSessionMapper", chatSessionMapper);
        ReflectionTestUtils.setField(interviewFlowService, "userProfileService", userProfileService);
        ReflectionTestUtils.setField(interviewFlowService, "assistantMessageService", assistantMessageService);
        ReflectionTestUtils.setField(interviewFlowService, "interviewProperties", interviewProperties);
        ReflectionTestUtils.setField(interviewFlowService, "objectMapper", new ObjectMapper());

        when(chatSessionMapper.selectByIdAndUserId(anyLong(), anyLong()))
                .thenReturn(interviewSession());
        UserProfileRespVO profile = new UserProfileRespVO();
        profile.setTargetPosition("Java 后端开发");
        profile.setWorkYears(5);
        when(userProfileService.getRequiredUserProfile(anyLong())).thenReturn(profile);
        when(interviewQaMapper.selectBySession(anyLong(), anyLong())).thenReturn(List.of());
        when(assistantMessageService.listMessages(anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(messagePage("讲讲 JVM 内存结构"));
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
     * 模型没走完本回合（只记了用户回答、没有评分与判定）时不落库：进度停在原处，也不写半截数据。
     *
     * <p>真实环境出现过模型把工具调用写成 JSON 文本、导致评分结论没提交、最后把一个只有 answer 的占位
     * 记录写库并撞上非空约束的情况，这里把它固定成「不落库、不推进」。
     */
    @Test
    void shouldSkipCommitWhenTurnIncomplete() {
        interviewFlowService.prepareTurn(USER_ID, SESSION_ID, "我的回答");

        assertThat(interviewFlowService.commitTurn(USER_ID, SESSION_ID)).isNull();
        verify(interviewQaMapper, never()).insert(any(InterviewQa.class));
    }

    /**
     * 模型漏调记录工具、但最后一题的追问已经答完时，平台补记收尾回合：面试正常结束、结果可用。
     *
     * <p>真实环境出现过「面试官回了『本场面试到此结束』但没调用记录工具」的故障：这一轮被丢弃，
     * 面试永远结束不了，逐题点评与报告都出不来。兜底必须把这种情况接住。
     */
    @Test
    void shouldSynthesizeFinishTurnWhenLastQuestionAnswered() {
        InterviewQa lastMainQuestion = new InterviewQa();
        lastMainQuestion.setQuestionIndex(8);
        lastMainQuestion.setRoundNo(InterviewQa.ROUND_MAIN);
        lastMainQuestion.setQuestionType(InterviewQuestionTypeEnum.BASIC.getValue());
        lastMainQuestion.setDifficulty(4);
        lastMainQuestion.setOutcome(InterviewOutcomeEnum.PARTIAL.getValue());
        lastMainQuestion.setNextAction(InterviewActionEnum.FOLLOW_UP.getValue());
        when(interviewQaMapper.selectBySession(USER_ID, 12L)).thenReturn(List.of(lastMainQuestion));
        when(assistantMessageService.listMessages(USER_ID, 12L, 1L, 2L))
                .thenReturn(messagePage("最后一题的追问：那拒绝策略呢？"));

        interviewFlowService.prepareTurn(USER_ID, SESSION_ID, "拒绝策略是直接抛异常");
        submitEvaluation("CORRECT", "答到要点");
        // 模型只给了收尾话术，没有调用 record_interview_answer
        interviewFlowService.commitTurn(USER_ID, SESSION_ID);

        ArgumentCaptor<InterviewQa> captor = ArgumentCaptor.forClass(InterviewQa.class);
        verify(interviewQaMapper).insert(captor.capture());
        InterviewQa row = captor.getValue();
        // 题目取回合开始时记下的那条助手消息，判定取评分子 Agent 的结论，动作固定为结束
        assertThat(row.getQuestion()).isEqualTo("最后一题的追问：那拒绝策略呢？");
        assertThat(row.getRoundNo()).isEqualTo(InterviewQa.ROUND_FOLLOW_UP);
        assertThat(row.getOutcome()).isEqualTo(InterviewOutcomeEnum.CORRECT.getValue());
        assertThat(row.getNextAction()).isEqualTo(InterviewActionEnum.FINISHED.getValue());
        assertThat(row.getEvaluationJson()).contains("答到要点");
        // 补记落库后，面试状态就是「已结束」：逐题结果与报告据此下发
        when(interviewQaMapper.selectBySession(USER_ID, 12L)).thenReturn(List.of(row));
        assertThat(interviewFlowService.getState(USER_ID, SESSION_ID).getFinished()).isTrue();
    }

    /**
     * 用户明确要求结束、模型却没调用记录工具时，平台同样补记收尾回合。
     */
    @Test
    void shouldSynthesizeFinishTurnWhenUserAsksToEnd() {
        interviewFlowService.prepareTurn(USER_ID, SESSION_ID, "结束面试吧");
        submitEvaluation("PARTIAL", "答得有遗漏");

        interviewFlowService.commitTurn(USER_ID, SESSION_ID);

        ArgumentCaptor<InterviewQa> captor = ArgumentCaptor.forClass(InterviewQa.class);
        verify(interviewQaMapper).insert(captor.capture());
        InterviewQa row = captor.getValue();
        assertThat(row.getQuestion()).isEqualTo("讲讲 JVM 内存结构");
        assertThat(row.getOutcome()).isEqualTo(InterviewOutcomeEnum.PARTIAL.getValue());
        assertThat(row.getNextAction()).isEqualTo(InterviewActionEnum.FINISHED.getValue());
        when(interviewQaMapper.selectBySession(USER_ID, 12L)).thenReturn(List.of(row));
        assertThat(interviewFlowService.getState(USER_ID, SESSION_ID).getFinished()).isTrue();
    }

    /**
     * 「用户要求结束」的判定刻意保守：短消息命中结束类关键词才算，正常提问里出现「结束」不算。
     */
    @Test
    void shouldJudgeEndRequestConservatively() {
        assertThat(InterviewFlowServiceImpl.isEndRequest("结束面试吧")).isTrue();
        assertThat(InterviewFlowServiceImpl.isEndRequest(" 不面了 ")).isTrue();
        assertThat(InterviewFlowServiceImpl.isEndRequest("结束")).isTrue();
        assertThat(InterviewFlowServiceImpl.isEndRequest("就到这吧")).isTrue();

        // 正常提问里出现「结束」不算：不是结束类关键词
        assertThat(InterviewFlowServiceImpl.isEndRequest("结束语怎么写")).isFalse();
        // 长句不算：避免把「这题我不会，结束前再问一个简单的吧」误判成要求结束
        assertThat(InterviewFlowServiceImpl.isEndRequest("这题我不会，结束前再问一个简单的吧")).isFalse();
        assertThat(InterviewFlowServiceImpl.isEndRequest("")).isFalse();
        assertThat(InterviewFlowServiceImpl.isEndRequest(null)).isFalse();
    }

    /**
     * 提交一条评分结论（评分子 Agent 在面试官开流前完成的那一步）。
     *
     * @param outcome 判定结果
     * @param comment 一句话点评
     */
    private void submitEvaluation(String outcome, String comment) {
        AnswerEvaluationSubmitVO evaluation = new AnswerEvaluationSubmitVO();
        evaluation.setOutcome(outcome);
        evaluation.setScore(80);
        evaluation.setComment(comment);
        evaluation.setReferenceAnswer("标准答案：要点一、要点二");
        interviewFlowService.submitEvaluation(USER_ID, SESSION_ID, evaluation);
    }

    /**
     * 构造一页消息，只放一条助手消息（本回合的题目）。
     *
     * @param content 助手消息正文
     * @return 分页消息
     */
    private PageRespVO<AssistantMessageRespVO> messagePage(String content) {
        AssistantMessageRespVO message = new AssistantMessageRespVO();
        message.setId(1L);
        message.setSessionId(SESSION_ID);
        message.setRole(MessageRoleEnum.ASSISTANT.getValue());
        message.setContent(content);
        PageRespVO<AssistantMessageRespVO> page = new PageRespVO<>();
        page.setTotal(1L);
        page.setRecords(List.of(message));
        return page;
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
        // 判定结果由评分子 Agent 先用提交工具交上来：面试官不再转述评分内容。
        AnswerEvaluationSubmitVO evaluation = new AnswerEvaluationSubmitVO();
        evaluation.setOutcome(outcome);
        evaluation.setScore(80);
        evaluation.setComment("判定要点");
        evaluation.setMissingPoints(List.of("漏掉的点"));
        evaluation.setReferenceAnswer("标准答案：要点一、要点二");
        interviewFlowService.submitEvaluation(USER_ID, SESSION_ID, evaluation);
        InterviewAnswerSubmitVO submitVO = new InterviewAnswerSubmitVO();
        submitVO.setQuestion(question);
        submitVO.setQuestionType(questionType);
        submitVO.setEndNow(endNow);
        return interviewFlowService.recordAnswer(USER_ID, SESSION_ID, submitVO);
    }

    /**
     * 评分不可用时按「答得有遗漏」保守继续：面试不会因为一次评分故障卡住，判定要点写明原因。
     */
    @Test
    void shouldFallBackWhenEvaluationMissing() {
        interviewFlowService.prepareTurn(USER_ID, SESSION_ID, "我的回答");
        InterviewAnswerSubmitVO submitVO = new InterviewAnswerSubmitVO();
        submitVO.setQuestion("讲讲 JVM 内存结构");
        submitVO.setQuestionType("BASIC");

        InterviewAnswerResultVO result = interviewFlowService.recordAnswer(USER_ID, SESSION_ID, submitVO);

        assertThat(result.getAction()).isEqualTo(InterviewActionEnum.FOLLOW_UP.getValue());
        InterviewQa row = commitAndCaptureRow();
        assertThat(row.getOutcome()).isEqualTo(InterviewOutcomeEnum.PARTIAL.getValue());
        assertThat(row.getJudgement()).contains("评分不可用");
    }

    /**
     * 评分结论不合法时不接受；本回合没有可用结论时按保守口径继续。
     */
    @Test
    void shouldRejectInvalidEvaluation() {
        interviewFlowService.prepareTurn(USER_ID, SESSION_ID, "我的回答");
        AnswerEvaluationSubmitVO invalid = new AnswerEvaluationSubmitVO();
        invalid.setOutcome("UNKNOWN");
        invalid.setScore(120);
        assertThatThrownBy(() -> interviewFlowService.submitEvaluation(USER_ID, SESSION_ID, invalid))
                .isInstanceOf(BizException.class);

        InterviewAnswerSubmitVO submitVO = new InterviewAnswerSubmitVO();
        submitVO.setQuestion("讲讲 JVM 内存结构");
        submitVO.setQuestionType("BASIC");
        assertThat(interviewFlowService.recordAnswer(USER_ID, SESSION_ID, submitVO).getAction())
                .isEqualTo(InterviewActionEnum.FOLLOW_UP.getValue());
    }

    /**
     * 判定结果取自评分子 Agent 提交的结论：outcome 与判定要点按它落库，面试官不参与判定。
     */
    @Test
    void shouldRecordUsingSubmittedEvaluation() {
        interviewFlowService.prepareTurn(USER_ID, SESSION_ID, "我的回答");
        AnswerEvaluationSubmitVO evaluation = new AnswerEvaluationSubmitVO();
        evaluation.setOutcome("CORRECT");
        evaluation.setScore(90);
        evaluation.setComment("答到要点");
        evaluation.setReferenceAnswer("标准答案：先讲结构，再讲扩容与树化条件");
        interviewFlowService.submitEvaluation(USER_ID, SESSION_ID, evaluation);

        InterviewAnswerSubmitVO submitVO = new InterviewAnswerSubmitVO();
        submitVO.setQuestion("讲讲 JVM 内存结构");
        submitVO.setQuestionType("BASIC");
        InterviewAnswerResultVO result = interviewFlowService.recordAnswer(USER_ID, SESSION_ID, submitVO);

        assertThat(result.getAction()).isEqualTo(InterviewActionEnum.FOLLOW_UP.getValue());
        InterviewQa row = commitAndCaptureRow();
        assertThat(row.getOutcome()).isEqualTo(InterviewOutcomeEnum.CORRECT.getValue());
        assertThat(row.getJudgement()).isEqualTo("答到要点");
    }

    /**
     * 面试结果：逐题明细带上「哪里答得不好」与标准答案，整体统计按判定分档。
     */
    @Test
    void shouldAssembleInterviewResult() {
        recordTurn("讲讲 HashMap", "BASIC", "PARTIAL", false);
        InterviewQa row = commitAndCaptureRow();
        when(interviewQaMapper.selectBySession(USER_ID, 12L)).thenReturn(List.of(row));

        InterviewResultRespVO result = interviewFlowService.getResult(USER_ID, SESSION_ID);

        assertThat(result.getType()).isEqualTo(InterviewResultRespVO.TYPE_INTERVIEW_RESULT);
        assertThat(result.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(result.getQuestionCount()).isEqualTo(8);
        assertThat(result.getAnsweredCount()).isEqualTo(1);
        assertThat(result.getPartialCount()).isEqualTo(1);
        assertThat(result.getCorrectCount()).isZero();
        assertThat(result.getAverageScore()).isEqualTo(80);
        assertThat(result.getItems()).hasSize(1);

        InterviewResultItemVO item = result.getItems().get(0);
        assertThat(item.getQuestion()).isEqualTo("讲讲 HashMap");
        assertThat(item.getAnswer()).isEqualTo("我的回答");
        assertThat(item.getOutcomeLabel()).isEqualTo("答得有遗漏");
        assertThat(item.getEvaluated()).isTrue();
        assertThat(item.getScore()).isEqualTo(80);
        assertThat(item.getMissingPoints()).containsExactly("漏掉的点");
        assertThat(item.getReferenceAnswer()).contains("标准答案");
    }

    /**
     * 评分不可用的回合也能进结果：只回放判定与判定要点，并标记没有评分结论。
     */
    @Test
    void shouldAssembleResultWithoutEvaluation() {
        interviewFlowService.prepareTurn(USER_ID, SESSION_ID, "我的回答");
        InterviewAnswerSubmitVO submitVO = new InterviewAnswerSubmitVO();
        submitVO.setQuestion("讲讲 HashMap");
        submitVO.setQuestionType("BASIC");
        interviewFlowService.recordAnswer(USER_ID, SESSION_ID, submitVO);
        InterviewQa row = commitAndCaptureRow();
        when(interviewQaMapper.selectBySession(USER_ID, 12L)).thenReturn(List.of(row));

        InterviewResultItemVO item = interviewFlowService.getResult(USER_ID, SESSION_ID).getItems().get(0);

        assertThat(item.getEvaluated()).isFalse();
        assertThat(item.getScore()).isNull();
        assertThat(item.getReferenceAnswer()).isNull();
        assertThat(item.getComment()).contains("评分不可用");
    }

    /**
     * 新回合开始会作废上一回合的结论：本轮没有新结论时按评分不可用保守处理，避免评分串题。
     */
    @Test
    void shouldResetEvaluationBetweenTurns() {
        recordTurn("讲讲 JVM 内存结构", "BASIC", "CORRECT", false);
        InterviewQa firstRow = commitAndCaptureRow();

        // 第二回合是追问轮：上一回合的结论已作废，本轮没有新结论 → 按有遗漏处理，换下一题。
        when(interviewQaMapper.selectBySession(USER_ID, 12L)).thenReturn(List.of(firstRow));
        interviewFlowService.prepareTurn(USER_ID, SESSION_ID, "第二题的回答");
        InterviewAnswerSubmitVO submitVO = new InterviewAnswerSubmitVO();
        submitVO.setQuestion("讲讲你负责的模块");
        submitVO.setQuestionType("PROJECT");
        assertThat(interviewFlowService.recordAnswer(USER_ID, SESSION_ID, submitVO).getAction())
                .isEqualTo(InterviewActionEnum.NEXT_QUESTION.getValue());
        assertThat(commitAndCaptureRow().getJudgement()).contains("评分不可用");
    }

    /**
     * 模型重复调用记录工具时是幂等的：返回上一次的指令，既不会被回退判定覆盖，也不会写第二条记录。
     */
    @Test
    void shouldBeIdempotentWhenRecordedTwice() {
        interviewFlowService.prepareTurn(USER_ID, SESSION_ID, "我的回答");
        AnswerEvaluationSubmitVO evaluation = new AnswerEvaluationSubmitVO();
        evaluation.setOutcome("CORRECT");
        evaluation.setScore(90);
        evaluation.setComment("答到要点");
        evaluation.setReferenceAnswer("标准答案：先讲结构，再讲扩容与树化条件");
        interviewFlowService.submitEvaluation(USER_ID, SESSION_ID, evaluation);
        InterviewAnswerSubmitVO submitVO = new InterviewAnswerSubmitVO();
        submitVO.setQuestion("讲讲 JVM 内存结构");
        submitVO.setQuestionType("BASIC");

        InterviewAnswerResultVO first = interviewFlowService.recordAnswer(USER_ID, SESSION_ID, submitVO);
        // 同一回合内重复调用：评分结论已被取走，若不幂等就会退化成「评分不可用」。
        InterviewAnswerResultVO second = interviewFlowService.recordAnswer(USER_ID, SESSION_ID, submitVO);

        assertThat(first.getAction()).isEqualTo(InterviewActionEnum.FOLLOW_UP.getValue());
        assertThat(second.getAction()).isEqualTo(InterviewActionEnum.FOLLOW_UP.getValue());
        assertThat(second.getDifficulty()).isEqualTo(first.getDifficulty());
        // 只落一条记录，判定仍是真实的评分结论，没有被回退值覆盖。
        InterviewQa row = commitAndCaptureRow();
        assertThat(row.getOutcome()).isEqualTo(InterviewOutcomeEnum.CORRECT.getValue());
        assertThat(row.getJudgement()).isEqualTo("答到要点");
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
