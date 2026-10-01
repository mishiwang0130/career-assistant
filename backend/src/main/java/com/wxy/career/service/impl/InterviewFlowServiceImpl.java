package com.wxy.career.service.impl;

import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.enums.ChatSceneEnum;
import com.wxy.career.common.enums.InterviewActionEnum;
import com.wxy.career.common.enums.InterviewOutcomeEnum;
import com.wxy.career.common.enums.InterviewQuestionTypeEnum;
import com.wxy.career.common.enums.MessageRoleEnum;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.config.InterviewProperties;
import com.wxy.career.mapper.ChatSessionMapper;
import com.wxy.career.mapper.InterviewQaMapper;
import com.wxy.career.po.ChatSession;
import com.wxy.career.po.InterviewQa;
import com.wxy.career.service.AssistantMessageService;
import com.wxy.career.service.InterviewFlowService;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.AssistantMessageRespVO;
import com.wxy.career.vo.InterviewAnswerResultVO;
import com.wxy.career.vo.InterviewAnswerSubmitVO;
import com.wxy.career.vo.InterviewResultItemVO;
import com.wxy.career.vo.InterviewResultRespVO;
import com.wxy.career.vo.InterviewStateRespVO;
import com.wxy.career.vo.AnswerEvaluationSubmitVO;
import com.wxy.career.vo.PageRespVO;
import com.wxy.career.vo.UserProfileRespVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模拟面试流程服务实现。
 *
 * <p>规则全是确定性计算：难度阶梯、追问 / 换题判定、题型配比、结束条件都由纯函数给出，单测直接覆盖；
 * 状态由 {@code interview_qa} 的行回放得出，模型提交的两个判定（outcome、题型）只作为输入，不参与决策。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
@Service
public class InterviewFlowServiceImpl implements InterviewFlowService {

    /**
     * 难度等级下界。
     */
    private static final int DIFFICULTY_MIN = 1;

    /**
     * 难度等级上界。
     */
    private static final int DIFFICULTY_MAX = 5;

    /**
     * 八股题在参考配比里的占比。
     */
    private static final double BASIC_RATIO = 0.5D;

    /**
     * 项目题在参考配比里的占比。
     */
    private static final double PROJECT_RATIO = 0.25D;

    /**
     * 题型铺排模板：八股 / 项目 / 八股 / 综合，循环使用，保证同类题不连续堆在一起。
     */
    private static final List<InterviewQuestionTypeEnum> QUESTION_TYPE_PATTERN = List.of(
            InterviewQuestionTypeEnum.BASIC,
            InterviewQuestionTypeEnum.PROJECT,
            InterviewQuestionTypeEnum.BASIC,
            InterviewQuestionTypeEnum.COMPREHENSIVE);

    /**
     * 回合缓冲的兜底过期时间，单位毫秒。
     *
     * <p>正常路径下缓冲在流结束时就被取走；过期时间只用于兜住异常路径（接口报错、进程内异常未清理）。
     */
    private static final long TURN_BUFFER_TTL_MILLIS = 30L * 60L * 1000L;

    /**
     * 读取「本回合题目」时的分页大小：取最近两条消息，跳过用户消息拿上一条助手消息。
     */
    private static final long LAST_QUESTION_PAGE_SIZE = 2L;

    /**
     * 参考得分下限。
     */
    private static final int SCORE_MIN = 0;

    /**
     * 参考得分上限。
     */
    private static final int SCORE_MAX = 100;

    /**
     * 面试问答 Mapper。
     */
    @Resource
    private InterviewQaMapper interviewQaMapper;

    /**
     * 会话 Mapper，只用于读取会话场景与归属（面试状态与进度不落在会话表里）。
     */
    @Resource
    private ChatSessionMapper chatSessionMapper;

    /**
     * 求职目标服务，提供起始难度所需的当前工作年限，并复用 F4 的「未填写」校验。
     */
    @Resource
    private UserProfileService userProfileService;

    /**
     * 消息服务：回合开始时用它记下用户正在回答的那道题（上一条助手消息），
     * 供「模型漏调记录工具」时的收尾兜底补记使用。
     */
    @Resource
    private AssistantMessageService assistantMessageService;

    /**
     * 面试配置，提供题量与上下文压缩阈值。
     */
    @Resource
    private InterviewProperties interviewProperties;

    /**
     * JSON 序列化组件，用于把评分结论存成 interview_qa.evaluation_json 并回放。
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 本回合的运行态缓冲：键为 {@code userId/sessionId}，值为待落库的问答记录与写入时间。
     */
    private final Map<String, BufferedTurn> turnBuffer = new ConcurrentHashMap<>();

    /**
     * 本回合的评分结论缓冲：键与回合缓冲相同，值为评分子 Agent 提交的结构化结论。
     *
     * <p>回合开始时清空、记录回合时取走，保证上一道题的评分不会落到下一题上。
     */
    private final Map<String, BufferedEvaluation> evaluationBuffer = new ConcurrentHashMap<>();

    /**
     * 评分不可用时的判定要点：写明原因，便于 F6 复盘时识别这一题没有真实评分。
     */
    private static final String FALLBACK_JUDGEMENT = "评分不可用，本回合按答得有遗漏处理";

    /**
     * 兜底补记收尾回合时，取不到题目正文时的占位文案。
     */
    private static final String FALLBACK_QUESTION = "（本题题目未记录）";

    /**
     * 判定「用户明确要求结束面试」的关键词。
     *
     * <p>只在模型漏调记录工具的兜底里用，判定刻意保守：整条消息去掉空白与标点后不超过
     * {@link #END_REQUEST_MAX_LENGTH} 个字，且包含这里的关键词（或整条消息就是「结束」）。
     * 这样「结束面试吧」「不面了」会被识别，而「结束语怎么写」这类正常提问不会误判成收尾。
     */
    private static final List<String> END_REQUEST_KEYWORDS = List.of(
            "结束面试", "结束这场", "结束吧", "不面了", "不想面", "不答了",
            "到此为止", "停止面试", "面完了", "就到这", "不来了");

    /**
     * 判定「用户要求结束」时的消息长度上限（去掉空白与标点后的字符数）。
     */
    private static final int END_REQUEST_MAX_LENGTH = 8;

    /**
     * 判断用户这条消息是不是「明确要求结束面试」。
     *
     * <p>给「模型漏调记录工具」的兜底用：题量走满或用户明确要求结束时，平台自己把收尾回合补记下来，
     * 保证面试一定能走到结束并给出逐题点评与报告。判定保守，宁可漏判也不误判（漏判只是保持原有行为：
     * 这一轮不推进、用户重新作答）。
     *
     * @param answer 用户本回合的消息内容
     * @return 是否明确要求结束
     */
    static boolean isEndRequest(String answer) {
        if (!StringUtils.hasText(answer)) {
            return false;
        }
        String normalized = answer.replaceAll("[\\s\\p{Punct}。，、？！；：“”‘’（）【】]", "");
        if (normalized.isEmpty() || normalized.length() > END_REQUEST_MAX_LENGTH) {
            return false;
        }
        if ("结束".equals(normalized)) {
            return true;
        }
        for (String keyword : END_REQUEST_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 面试回合准入校验并记下本回合的用户回答。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param answer 本回合用户发送的消息内容
     * @return 面试状态；非面试会话返回 null
     */
    @Override
    public InterviewStateRespVO prepareTurn(Long userId, String sessionId, String answer) {
        Long sessionIdValue = parseSessionId(sessionId);
        if (userId == null || sessionIdValue == null) {
            return null;
        }
        ChatSession session = chatSessionMapper.selectByIdAndUserId(sessionIdValue, userId);
        if (session == null || !ChatSceneEnum.INTERVIEW.getValue().equals(session.getScene())) {
            // 助手会话（以及会话元数据缺失的历史 ID）走原对话链路，面试规则不介入。
            return null;
        }
        // getState 内含求职目标必填校验：没有目标岗位与工作年限就开不了面试（1101）。
        InterviewStateRespVO state = getState(userId, sessionId);
        if (Boolean.TRUE.equals(state.getFinished())) {
            throw new BizException(ErrorConstant.INTERVIEW_FINISHED);
        }
        InterviewQa pending = new InterviewQa();
        pending.setUserId(userId);
        pending.setSessionId(sessionIdValue);
        pending.setAnswer(answer);
        long now = System.currentTimeMillis();
        purgeExpired(now);
        // 新回合开始：上一回合的评分结论作废，避免旧结论被用在这一题上。
        evaluationBuffer.remove(bufferKey(userId, sessionId));
        // 顺手记下用户正在回答的题目：模型漏调记录工具时，平台要靠它把收尾回合补记完整。
        turnBuffer.put(bufferKey(userId, sessionId),
                new BufferedTurn(pending, currentQuestion(userId, sessionIdValue), now));
        return state;
    }

    /**
     * 暂存评分子 Agent 提交的单题评分结论。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID；平台用面试会话的上下文调评分子 Agent，因此这里就是面试会话 ID
     * @param submitVO 评分结论
     */
    @Override
    public void submitEvaluation(Long userId, String sessionId, AnswerEvaluationSubmitVO submitVO) {
        requireInterviewSession(userId, sessionId);
        validateEvaluation(submitVO);
        long now = System.currentTimeMillis();
        purgeExpired(now);
        evaluationBuffer.put(bufferKey(userId, sessionId), new BufferedEvaluation(submitVO, now));
        log.info("评分结论已提交，userId={}，sessionId={}，outcome={}，score={}",
                userId, sessionId, submitVO.getOutcome(), submitVO.getScore());
    }

    /**
     * 读取当前面试状态（进度、当前难度、是否结束）。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 面试状态
     */
    @Override
    @Transactional(readOnly = true)
    public InterviewStateRespVO getState(Long userId, String sessionId) {
        Long sessionIdValue = requireInterviewSession(userId, sessionId);
        return loadState(userId, sessionId, sessionIdValue);
    }

    /**
     * 读取当前登录用户指定会话的面试状态。
     *
     * @param sessionId 会话 ID
     * @return 面试状态
     */
    @Override
    public InterviewStateRespVO getCurrentUserState(String sessionId) {
        Long userId = LoginUserHolder.getUserId();
        if (userId == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
        return getState(userId, sessionId);
    }

    /**
     * 记录本回合的判定结果并算出下一步指令。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param submitVO 模型提交的判定结果
     * @return 下一步指令
     */
    @Override
    public InterviewAnswerResultVO recordAnswer(
            Long userId, String sessionId, InterviewAnswerSubmitVO submitVO) {
        Long sessionIdValue = requireInterviewSession(userId, sessionId);
        validateSubmit(submitVO);
        InterviewQuestionTypeEnum questionType = InterviewQuestionTypeEnum.find(submitVO.getQuestionType());
        if (questionType == null) {
            // 题型取值非法时按参数错误抛出，由工具转成可读提示让模型改正后重试。
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        // 本回合已经记录过（模型重复调用同一个工具）：直接返回上一次的结论，既不改判定也不写第二条。
        BufferedTurn recorded = turnBuffer.get(bufferKey(userId, sessionId));
        if (recorded != null && recorded.row().getNextAction() != null) {
            log.info("面试回合已记录，返回上一次指令，userId={}，sessionId={}", userId, sessionId);
            int startDifficulty = resolveStartDifficulty(loadWorkYears(userId));
            InterviewStateRespVO recordedState = deriveState(
                    sessionId, interviewProperties.getQuestionCount(), startDifficulty, List.of(recorded.row()));
            return toResult(sessionId, InterviewActionEnum.find(recorded.row().getNextAction()), recordedState);
        }
        // 判定结果只认评分子 Agent 提交的结论：面试官不转述评分，用户可见的回答里就不会出现评分内容。
        // 评分不可用（子 Agent 没提交或执行失败）时按「答得有遗漏」保守继续，保证面试不被一次故障卡住。
        BufferedEvaluation evaluation = evaluationBuffer.get(bufferKey(userId, sessionId));
        InterviewOutcomeEnum outcome = evaluation == null
                ? InterviewOutcomeEnum.PARTIAL
                : InterviewOutcomeEnum.find(evaluation.payload().getOutcome());
        if (outcome == null) {
            outcome = InterviewOutcomeEnum.PARTIAL;
        }
        String judgement = evaluation == null ? FALLBACK_JUDGEMENT : evaluation.payload().getComment();
        if (evaluation == null) {
            log.warn("面试回合缺少评分结论，按有遗漏继续，userId={}，sessionId={}", userId, sessionId);
        }
        InterviewStateRespVO current = loadState(userId, sessionId, sessionIdValue);
        if (Boolean.TRUE.equals(current.getFinished())) {
            throw new BizException(ErrorConstant.INTERVIEW_FINISHED);
        }
        InterviewActionEnum action = decideAction(
                current.getQuestionIndex(),
                current.getRoundNo(),
                current.getQuestionCount(),
                outcome,
                Boolean.TRUE.equals(submitVO.getEndNow()));
        String evaluationJson = evaluation == null ? null : writeEvaluation(evaluation.payload());
        InterviewQa row = buildRow(userId, sessionId, sessionIdValue, current, submitVO.getQuestion(),
                questionType, outcome, judgement, evaluationJson, action);
        long now = System.currentTimeMillis();
        purgeExpired(now);
        turnBuffer.put(bufferKey(userId, sessionId),
                new BufferedTurn(row, recorded == null ? null : recorded.pendingQuestion(), now));
        // 结论只服务本回合：记录成功后立即取走，模型重复调用时不会再落一条重复记录。
        evaluationBuffer.remove(bufferKey(userId, sessionId));
        // 下一步状态与落库后的回放结果同源：同一份规则、同一条记录，实时与回放不会走偏。
        InterviewStateRespVO next = deriveState(
                sessionId, current.getQuestionCount(), current.getStartDifficulty(), List.of(row));
        return toResult(sessionId, action, next);
    }

    /**
     * 落库本回合记录并返回最新状态。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 最新面试状态；本回合没有待落库记录时返回 null
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public InterviewStateRespVO commitTurn(Long userId, String sessionId) {
        BufferedTurn buffered = removeBufferedTurn(userId, sessionId);
        if (buffered == null) {
            return null;
        }
        if (System.currentTimeMillis() - buffered.createdAt() > TURN_BUFFER_TTL_MILLIS) {
            log.info("面试回合缓冲已过期，丢弃不落库，userId={}，sessionId={}", userId, sessionId);
            return null;
        }
        InterviewQa row = buffered.row();
        if (row.getNextAction() == null || row.getOutcome() == null || row.getQuestion() == null) {
            // 只填了用户回答的占位记录说明模型没走完本回合（漏调 record_interview_answer 或评分结论没提交上来）。
            // 如果这一回合本该收尾（题量走满或用户明确要求结束），平台按兜底口径补记，保证面试一定能结束并出结果；
            // 其余情况仍然不推进、不写半截数据，用户重新作答即可。
            row = synthesizeFinishFallback(userId, sessionId, buffered);
            if (row == null) {
                log.warn("面试回合记录不完整，跳过落库，userId={}，sessionId={}", userId, sessionId);
                return null;
            }
        }
        interviewQaMapper.insert(row);
        // 以落库后的完整记录回放状态，界面拿到的进度就是下一次出题时的真实进度。
        List<InterviewQa> rows = interviewQaMapper.selectBySession(userId, row.getSessionId());
        int startDifficulty = resolveStartDifficulty(loadWorkYears(userId));
        return deriveState(sessionId, interviewProperties.getQuestionCount(), startDifficulty, rows);
    }

    /**
     * 模型漏调记录工具时的收尾兜底：只有在「本回合确实该收尾」时才补记一条完整记录。
     *
     * <p>触发条件（二者之一）：
     * <ol>
     *   <li>主问题已经答满（questionIndex ≥ questionCount）；</li>
     *   <li>用户本回合明确要求结束（{@link #isEndRequest(String)}）。</li>
     * </ol>
     * 其余情况返回 null，保持「进度停在原处、用户重新作答即可」的既有行为，绝不写半截数据。
     *
     * <p>补记所需的题目来自回合开始时记下的上一条助手消息；判定与点评优先取评分子 Agent 已经提交的结论
     * （评分在面试官开流前就完成了），拿不到就按既有的保守口径「答得有遗漏」处理。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param buffered 本回合的运行态缓冲
     * @return 补记完整的收尾记录；不需要或无法补记时返回 null
     */
    private InterviewQa synthesizeFinishFallback(Long userId, String sessionId, BufferedTurn buffered) {
        Long sessionIdValue = parseSessionId(sessionId);
        if (sessionIdValue == null) {
            return null;
        }
        InterviewStateRespVO current = loadState(userId, sessionId, sessionIdValue);
        if (Boolean.TRUE.equals(current.getFinished())) {
            return null;
        }
        BufferedEvaluation evaluation = evaluationBuffer.get(bufferKey(userId, sessionId));
        InterviewOutcomeEnum outcome = evaluation == null
                ? InterviewOutcomeEnum.PARTIAL : InterviewOutcomeEnum.find(evaluation.payload().getOutcome());
        if (outcome == null) {
            outcome = InterviewOutcomeEnum.PARTIAL;
        }
        String answer = buffered.row() == null ? null : buffered.row().getAnswer();
        boolean endNow = isEndRequest(answer);
        InterviewActionEnum action = decideAction(
                current.getQuestionIndex(), current.getRoundNo(), current.getQuestionCount(), outcome, endNow);
        if (action != InterviewActionEnum.FINISHED) {
            return null;
        }
        InterviewQuestionTypeEnum questionType = InterviewQuestionTypeEnum.find(current.getRecommendedQuestionType());
        if (questionType == null) {
            // 建议题型取不到时按八股题落库：这一列非空，且题型只影响统计展示。
            questionType = InterviewQuestionTypeEnum.BASIC;
        }
        String question = StringUtils.hasText(buffered.pendingQuestion())
                ? buffered.pendingQuestion() : FALLBACK_QUESTION;
        String judgement = evaluation == null ? FALLBACK_JUDGEMENT : evaluation.payload().getComment();
        InterviewQa row = new InterviewQa();
        row.setUserId(userId);
        row.setSessionId(sessionIdValue);
        row.setQuestionIndex(current.getQuestionIndex());
        row.setRoundNo(current.getRoundNo());
        row.setQuestionType(questionType.getValue());
        row.setDifficulty(current.getDifficulty());
        row.setQuestion(question.trim());
        row.setAnswer(answer);
        row.setOutcome(outcome.getValue());
        row.setJudgement(truncate(judgement, InterviewQa.JUDGEMENT_MAX_LENGTH));
        row.setEvaluationJson(evaluation == null ? null : writeEvaluation(evaluation.payload()));
        row.setNextAction(action.getValue());
        // 流式回落在异步线程执行，审计字段显式写入。
        row.setCreateBy(userId);
        row.setUpdateBy(userId);
        // 结论只服务本回合：补记成功后取走，避免重复提交时再落到下一题上。
        evaluationBuffer.remove(bufferKey(userId, sessionId));
        log.warn("模型未记录本回合，平台按收尾兜底补记，userId={}，sessionId={}，questionIndex={}，endNow={}",
                userId, sessionId, current.getQuestionIndex(), endNow);
        return row;
    }

    /**
     * 取本回合用户正在回答的题目：当前会话里最近一条助手消息。
     *
     * <p>回合开始时读取（此时用户消息还没落库，上一条助手消息就是刚问出去的那道题），
     * 读取失败只记日志、返回 null，不影响答题主流程。
     *
     * @param userId 用户 ID
     * @param sessionIdValue 会话 ID
     * @return 题目正文，取不到时返回 null
     */
    private String currentQuestion(Long userId, Long sessionIdValue) {
        try {
            PageRespVO<AssistantMessageRespVO> page =
                    assistantMessageService.listMessages(userId, sessionIdValue, 1L, LAST_QUESTION_PAGE_SIZE);
            for (AssistantMessageRespVO record : page.getRecords()) {
                if (record != null && MessageRoleEnum.ASSISTANT.getValue().equals(record.getRole())) {
                    return record.getContent();
                }
            }
        } catch (Exception exception) {
            log.warn("读取本回合题目失败，收尾兜底将使用占位题目，userId={}，sessionId={}",
                    userId, sessionIdValue, exception);
        }
        return null;
    }

    /**
     * 丢弃本回合的运行态缓冲。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     */
    @Override
    public void discardTurn(Long userId, String sessionId) {
        if (userId == null || !StringUtils.hasText(sessionId)) {
            return;
        }
        turnBuffer.remove(bufferKey(userId, sessionId));
    }

    /**
     * 组装面试结果：逐题明细（答得不好的地方、标准答案等）与整体统计。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 面试结果
     */
    @Override
    @Transactional(readOnly = true)
    public InterviewResultRespVO getResult(Long userId, String sessionId) {
        Long sessionIdValue = requireInterviewSession(userId, sessionId);
        InterviewStateRespVO state = loadState(userId, sessionId, sessionIdValue);
        List<InterviewQa> rows = interviewQaMapper.selectBySession(userId, sessionIdValue);
        List<InterviewResultItemVO> items = new ArrayList<>(rows.size());
        int correctCount = 0;
        int partialCount = 0;
        int wrongCount = 0;
        int scoreSum = 0;
        int scoreCount = 0;
        for (InterviewQa row : rows) {
            InterviewResultItemVO item = toResultItem(row);
            items.add(item);
            InterviewOutcomeEnum outcome = InterviewOutcomeEnum.find(row.getOutcome());
            if (outcome == InterviewOutcomeEnum.CORRECT) {
                correctCount++;
            } else if (outcome == InterviewOutcomeEnum.PARTIAL) {
                partialCount++;
            } else if (outcome == InterviewOutcomeEnum.WRONG) {
                wrongCount++;
            }
            if (item.getScore() != null) {
                scoreSum += item.getScore();
                scoreCount++;
            }
        }
        InterviewResultRespVO result = new InterviewResultRespVO();
        result.setSessionId(sessionId);
        result.setQuestionCount(state.getQuestionCount());
        result.setFinished(state.getFinished());
        result.setAnsweredCount(items.size());
        result.setCorrectCount(correctCount);
        result.setPartialCount(partialCount);
        result.setWrongCount(wrongCount);
        result.setAverageScore(scoreCount == 0 ? null : Math.round((float) scoreSum / scoreCount));
        result.setItems(items);
        return result;
    }

    /**
     * 读取当前登录用户指定会话的面试结果。
     *
     * @param sessionId 会话 ID
     * @return 面试结果
     */
    @Override
    public InterviewResultRespVO getCurrentUserResult(String sessionId) {
        Long userId = LoginUserHolder.getUserId();
        if (userId == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
        return getResult(userId, sessionId);
    }

    /**
     * 把一条问答记录转成结果明细：有评分结论时回放完整明细，评分不可用时只回放判定与判定要点。
     *
     * @param row 问答记录
     * @return 结果明细
     */
    private InterviewResultItemVO toResultItem(InterviewQa row) {
        InterviewOutcomeEnum outcome = InterviewOutcomeEnum.find(row.getOutcome());
        AnswerEvaluationSubmitVO evaluation = readEvaluation(row.getEvaluationJson());
        InterviewResultItemVO item = new InterviewResultItemVO();
        item.setQuestionIndex(row.getQuestionIndex());
        item.setRoundNo(row.getRoundNo());
        item.setQuestion(row.getQuestion());
        item.setAnswer(row.getAnswer());
        item.setOutcome(row.getOutcome());
        item.setOutcomeLabel(outcome == null ? null : outcome.getLabel());
        item.setDifficulty(row.getDifficulty());
        item.setEvaluated(evaluation != null);
        item.setComment(evaluation == null ? row.getJudgement() : evaluation.getComment());
        item.setCorrectPoints(nullToEmpty(evaluation == null ? null : evaluation.getCorrectPoints()));
        item.setMissingPoints(nullToEmpty(evaluation == null ? null : evaluation.getMissingPoints()));
        item.setWrongPoints(nullToEmpty(evaluation == null ? null : evaluation.getWrongPoints()));
        item.setExpressionIssues(nullToEmpty(evaluation == null ? null : evaluation.getExpressionIssues()));
        item.setSuggestions(nullToEmpty(evaluation == null ? null : evaluation.getSuggestions()));
        item.setKnowledgePoints(nullToEmpty(evaluation == null ? null : evaluation.getKnowledgePoints()));
        item.setScore(evaluation == null ? null : evaluation.getScore());
        item.setReferenceAnswer(evaluation == null ? null : evaluation.getReferenceAnswer());
        return item;
    }

    /**
     * 空清单归一化为空列表，避免前端为 null 做额外判空。
     *
     * @param source 原清单
     * @param <T> 元素类型
     * @return 非空清单
     */
    private <T> List<T> nullToEmpty(List<T> source) {
        return source == null ? List.of() : source;
    }

    /**
     * 把评分结论序列化进 interview_qa.evaluation_json。
     *
     * @param evaluation 评分结论
     * @return JSON 字符串，序列化失败时返回 null（不影响本回合流程与判定）
     */
    private String writeEvaluation(AnswerEvaluationSubmitVO evaluation) {
        try {
            return objectMapper.writeValueAsString(evaluation);
        } catch (JsonProcessingException exception) {
            log.warn("评分结论序列化失败，本回合只落判定要点", exception);
            return null;
        }
    }

    /**
     * 回放评分结论。
     *
     * @param evaluationJson 评分结论 JSON
     * @return 评分结论，缺失或解析失败时返回 null（按评分不可用回放）
     */
    private AnswerEvaluationSubmitVO readEvaluation(String evaluationJson) {
        if (!StringUtils.hasText(evaluationJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(evaluationJson, AnswerEvaluationSubmitVO.class);
        } catch (JsonProcessingException exception) {
            log.warn("评分结论解析失败，按评分不可用回放", exception);
            return null;
        }
    }

    /**
     * 按工作年限算起始难度：难度只升不降，因此它同时是全场下限。
     *
     * @param workYears 当前工作年限（年）
     * @return 起始难度等级 1-5
     */
    static int resolveStartDifficulty(int workYears) {
        if (workYears <= 1) {
            return 1;
        }
        if (workYears <= 3) {
            return 2;
        }
        if (workYears <= 5) {
            return 3;
        }
        if (workYears <= 8) {
            return 4;
        }
        return DIFFICULTY_MAX;
    }

    /**
     * 按本题表现算下一题难度：答到要点上调一级（上限 5 级），答得有遗漏与答错一律持平，
     * 且不因答错掉到起始难度以下。
     *
     * @param current 本题难度等级
     * @param startDifficulty 起始难度等级，即全场下限
     * @param outcome 本题判定结果
     * @return 下一题难度等级
     */
    static int nextDifficulty(int current, int startDifficulty, InterviewOutcomeEnum outcome) {
        int floor = clampDifficulty(startDifficulty);
        int base = Math.max(clampDifficulty(current), floor);
        if (outcome == InterviewOutcomeEnum.CORRECT) {
            return Math.min(base + 1, DIFFICULTY_MAX);
        }
        return base;
    }

    /**
     * 按本题表现决定流程动作：答到要点或答得有遗漏就追问一层，完全不会或答错就换新题，
     * 题量走满或用户主动结束时收尾。
     *
     * @param questionIndex 本题的主问题序号
     * @param roundNo 本题轮次，1-主问题，2-追问
     * @param questionCount 主问题总题量
     * @param outcome 本题判定结果
     * @param endNow 用户是否主动要求结束
     * @return 本回合之后的流程动作
     */
    static InterviewActionEnum decideAction(
            int questionIndex,
            int roundNo,
            int questionCount,
            InterviewOutcomeEnum outcome,
            boolean endNow) {
        if (endNow) {
            return InterviewActionEnum.FINISHED;
        }
        // 追问只问一层：主问题这一轮才追问，追问的回答直接进入下一题。
        if (roundNo == InterviewQa.ROUND_MAIN
                && outcome != null
                && outcome != InterviewOutcomeEnum.WRONG) {
            return InterviewActionEnum.FOLLOW_UP;
        }
        // 错题不纠缠：不再围绕同一知识点换角度提问，直接换题。
        return questionIndex >= questionCount
                ? InterviewActionEnum.FINISHED : InterviewActionEnum.NEXT_QUESTION;
    }

    /**
     * 按 4:2:2 的参考配比给出指定题序的建议题型。
     *
     * <p>先按比例算出三类题各出几道，再用固定模板铺到题序上：配比可复现、单测可断言，
     * 模型仍可按岗位需要微调（最终以模型上报的题型落库）。
     *
     * @param questionIndex 主问题序号，从 1 开始
     * @param questionCount 主问题总题量
     * @return 建议题型
     */
    static InterviewQuestionTypeEnum recommendQuestionType(int questionIndex, int questionCount) {
        int count = Math.max(questionCount, 1);
        int index = Math.min(Math.max(questionIndex, 1), count);
        Map<InterviewQuestionTypeEnum, Integer> quota = new EnumMap<>(InterviewQuestionTypeEnum.class);
        int basic = Math.min((int) Math.ceil(count * BASIC_RATIO), count);
        int project = Math.min((int) Math.round(count * PROJECT_RATIO), count - basic);
        quota.put(InterviewQuestionTypeEnum.BASIC, basic);
        quota.put(InterviewQuestionTypeEnum.PROJECT, project);
        quota.put(InterviewQuestionTypeEnum.COMPREHENSIVE, Math.max(count - basic - project, 0));

        InterviewQuestionTypeEnum picked = InterviewQuestionTypeEnum.BASIC;
        for (int position = 1; position <= index; position++) {
            InterviewQuestionTypeEnum candidate =
                    QUESTION_TYPE_PATTERN.get((position - 1) % QUESTION_TYPE_PATTERN.size());
            if (quota.getOrDefault(candidate, 0) > 0) {
                picked = candidate;
            } else {
                picked = firstWithQuota(quota);
            }
            quota.put(picked, quota.getOrDefault(picked, 0) - 1);
        }
        return picked;
    }

    /**
     * 取还有剩余名额的第一个题型，保证题型铺设不会因为配额提前用完而中断。
     *
     * @param quota 各题型剩余名额
     * @return 还有剩余名额的题型，全部用尽时兜底为八股题
     */
    private static InterviewQuestionTypeEnum firstWithQuota(Map<InterviewQuestionTypeEnum, Integer> quota) {
        for (InterviewQuestionTypeEnum type : InterviewQuestionTypeEnum.values()) {
            if (quota.getOrDefault(type, 0) > 0) {
                return type;
            }
        }
        return InterviewQuestionTypeEnum.BASIC;
    }

    /**
     * 把难度等级钳制到 1-5。
     *
     * @param difficulty 难度等级
     * @return 钳制后的难度等级
     */
    private static int clampDifficulty(int difficulty) {
        return Math.min(Math.max(difficulty, DIFFICULTY_MIN), DIFFICULTY_MAX);
    }

    /**
     * 由问答记录回放整场面试的进度与当前难度。
     *
     * <p>记录为空时是第 1 题、起始难度；最后一条记录的 next_action 决定下一步是追问、换题还是结束。
     * 状态是记录的纯函数，所以刷新页面、离开再回来都能恢复到同一步。
     *
     * @param sessionId 会话 ID 字符串
     * @param questionCount 主问题总题量
     * @param startDifficulty 起始难度
     * @param rows 问答记录，按提问顺序
     * @return 面试状态
     */
    private InterviewStateRespVO deriveState(
            String sessionId, int questionCount, int startDifficulty, List<InterviewQa> rows) {
        int normalizedCount = Math.max(questionCount, 1);
        InterviewStateRespVO state = new InterviewStateRespVO();
        state.setSessionId(sessionId);
        state.setQuestionCount(normalizedCount);
        state.setStartDifficulty(clampDifficulty(startDifficulty));
        if (rows.isEmpty()) {
            state.setQuestionIndex(1);
            state.setRoundNo(InterviewQa.ROUND_MAIN);
            state.setDifficulty(clampDifficulty(startDifficulty));
            state.setFinished(false);
            state.setRecommendedQuestionType(
                    recommendQuestionType(1, normalizedCount).getValue());
            return state;
        }
        InterviewQa last = rows.get(rows.size() - 1);
        InterviewActionEnum action = InterviewActionEnum.find(last.getNextAction());
        InterviewOutcomeEnum outcome = InterviewOutcomeEnum.find(last.getOutcome());
        int lastIndex = last.getQuestionIndex() == null ? 1 : last.getQuestionIndex();
        int lastRound = last.getRoundNo() == null ? InterviewQa.ROUND_MAIN : last.getRoundNo();
        int lastDifficulty = clampDifficulty(
                last.getDifficulty() == null ? startDifficulty : last.getDifficulty());
        if (action == InterviewActionEnum.FINISHED) {
            // 已结束：停在最后一道题上，难度与轮次保持结束时的值，供界面显示。
            state.setQuestionIndex(lastIndex);
            state.setRoundNo(lastRound);
            state.setDifficulty(lastDifficulty);
            state.setFinished(true);
            return state;
        }
        boolean followUp = action == InterviewActionEnum.FOLLOW_UP;
        state.setQuestionIndex(followUp ? lastIndex : Math.min(lastIndex + 1, normalizedCount));
        state.setRoundNo(followUp ? InterviewQa.ROUND_FOLLOW_UP : InterviewQa.ROUND_MAIN);
        state.setDifficulty(nextDifficulty(
                lastDifficulty,
                startDifficulty,
                outcome == null ? InterviewOutcomeEnum.PARTIAL : outcome));
        state.setFinished(false);
        state.setRecommendedQuestionType(followUp && StringUtils.hasText(last.getQuestionType())
                ? last.getQuestionType()
                : recommendQuestionType(state.getQuestionIndex(), normalizedCount).getValue());
        return state;
    }

    /**
     * 组装待落库的问答记录，回答取自对话通道在进流前记下的内容。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID 字符串，与运行态缓冲的键保持一致
     * @param sessionIdValue 会话 ID
     * @param current 答题时的面试状态
     * @param question 本次提问的题目正文
     * @param questionType 题型枚举
     * @param outcome 判定结果枚举
     * @param judgement 判定要点，来自评分子 Agent 的结论
     * @param evaluationJson 评分子 Agent 的结构化结论 JSON，评分不可用时为 null
     * @param action 本回合之后的流程动作
     * @return 待落库的问答记录
     */
    private InterviewQa buildRow(
            Long userId,
            String sessionId,
            Long sessionIdValue,
            InterviewStateRespVO current,
            String question,
            InterviewQuestionTypeEnum questionType,
            InterviewOutcomeEnum outcome,
            String judgement,
            String evaluationJson,
            InterviewActionEnum action) {
        BufferedTurn buffered = turnBuffer.get(bufferKey(userId, sessionId));
        String answer = buffered == null || buffered.row() == null ? null : buffered.row().getAnswer();
        if (!StringUtils.hasText(answer)) {
            // 没有本回合回答说明这次调用不在答题回合上（或缓冲已被取走），不落库、让模型重新判断。
            log.info("面试回合缺少用户回答，拒绝记录，userId={}，sessionId={}", userId, sessionIdValue);
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        InterviewQa row = new InterviewQa();
        row.setUserId(userId);
        row.setSessionId(sessionIdValue);
        row.setQuestionIndex(current.getQuestionIndex());
        row.setRoundNo(current.getRoundNo());
        row.setQuestionType(questionType.getValue());
        row.setDifficulty(current.getDifficulty());
        row.setQuestion(question.trim());
        row.setAnswer(answer);
        row.setOutcome(outcome.getValue());
        row.setJudgement(truncate(judgement, InterviewQa.JUDGEMENT_MAX_LENGTH));
        row.setEvaluationJson(evaluationJson);
        row.setNextAction(action.getValue());
        // 流式回落在异步线程执行，审计字段显式写入。
        row.setCreateBy(userId);
        row.setUpdateBy(userId);
        return row;
    }

    /**
     * 把下一步状态翻译成工具返回值。
     *
     * @param sessionId 会话 ID 字符串
     * @param action 本回合之后的流程动作
     * @param next 下一步状态
     * @return 下一步指令
     */
    private InterviewAnswerResultVO toResult(String sessionId, InterviewActionEnum action, InterviewStateRespVO next) {
        InterviewAnswerResultVO result = new InterviewAnswerResultVO();
        result.setSessionId(sessionId);
        result.setAction(action.getValue());
        result.setQuestionIndex(next.getQuestionIndex());
        result.setQuestionCount(next.getQuestionCount());
        result.setDifficulty(next.getDifficulty());
        result.setRoundNo(next.getRoundNo());
        result.setQuestionType(next.getRecommendedQuestionType());
        result.setFinished(next.getFinished());
        return result;
    }

    /**
     * 校验模型提交的判定结果结构。
     *
     * @param submitVO 判定结果
     */
    private void validateSubmit(InterviewAnswerSubmitVO submitVO) {
        if (submitVO == null || !StringUtils.hasText(submitVO.getQuestion())) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        if (!StringUtils.hasText(submitVO.getQuestionType())) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
    }

    /**
     * 校验评分结论：三档判定之一、参考得分 0-100、一句话点评非空。
     *
     * @param submitVO 评分结论
     */
    private void validateEvaluation(AnswerEvaluationSubmitVO submitVO) {
        if (submitVO == null || InterviewOutcomeEnum.find(submitVO.getOutcome()) == null) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        if (submitVO.getScore() == null
                || submitVO.getScore() < SCORE_MIN
                || submitVO.getScore() > SCORE_MAX
                || !StringUtils.hasText(submitVO.getComment())
                || !StringUtils.hasText(submitVO.getReferenceAnswer())) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
    }

    /**
     * 读取面试状态：起始难度来自求职目标，进度与难度来自问答记录。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID 字符串
     * @param sessionIdValue 会话 ID
     * @return 面试状态
     */
    private InterviewStateRespVO loadState(Long userId, String sessionId, Long sessionIdValue) {
        int startDifficulty = resolveStartDifficulty(loadWorkYears(userId));
        List<InterviewQa> rows = interviewQaMapper.selectBySession(userId, sessionIdValue);
        return deriveState(sessionId, interviewProperties.getQuestionCount(), startDifficulty, rows);
    }

    /**
     * 取当前用户的工作年限，档案未填写时抛 1101（面试与训练计划的准入校验复用同一条）。
     *
     * @param userId 用户 ID
     * @return 当前工作年限
     */
    private int loadWorkYears(Long userId) {
        UserProfileRespVO profile = userProfileService.getRequiredUserProfile(userId);
        return profile.getWorkYears() == null ? 0 : profile.getWorkYears();
    }

    /**
     * 校验会话属于当前用户且是模拟面试场景。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID 字符串
     * @return 会话 ID
     */
    private Long requireInterviewSession(Long userId, String sessionId) {
        if (userId == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
        Long sessionIdValue = parseSessionId(sessionId);
        if (sessionIdValue == null) {
            throw new BizException(ErrorConstant.CHAT_SESSION_NOT_FOUND);
        }
        ChatSession session = chatSessionMapper.selectByIdAndUserId(sessionIdValue, userId);
        if (session == null) {
            // 跨账号访问与已删除会话一律表现为不存在，不暴露资源是否存在。
            throw new BizException(ErrorConstant.CHAT_SESSION_NOT_FOUND);
        }
        if (!ChatSceneEnum.INTERVIEW.getValue().equals(session.getScene())) {
            throw new BizException(ErrorConstant.INTERVIEW_SCENE_MISMATCH);
        }
        return sessionIdValue;
    }

    /**
     * 解析会话 ID，非法取值返回 null。
     *
     * @param sessionId 会话 ID 字符串
     * @return 会话 ID，非法时返回 null
     */
    private Long parseSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        try {
            return Long.valueOf(sessionId.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * 超长文本截断，避免模型把整段点评写进落库字段。
     *
     * @param text 原文本
     * @param maxLength 最大长度
     * @return 截断后的文本，入参为空时返回 null
     */
    private String truncate(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    /**
     * 取出并移除本回合的缓冲。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 缓冲内容，不存在时返回 null
     */
    private BufferedTurn removeBufferedTurn(Long userId, String sessionId) {
        if (userId == null || !StringUtils.hasText(sessionId)) {
            return null;
        }
        return turnBuffer.remove(bufferKey(userId, sessionId));
    }

    /**
     * 清理过期缓冲，异常路径下不残留内存。
     *
     * @param now 当前时间戳
     */
    private void purgeExpired(long now) {
        Collection<String> keys = turnBuffer.keySet();
        Iterator<String> iterator = keys.iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            BufferedTurn buffered = turnBuffer.get(key);
            if (buffered != null && now - buffered.createdAt() > TURN_BUFFER_TTL_MILLIS) {
                turnBuffer.remove(key, buffered);
            }
        }
        Collection<String> evaluationKeys = evaluationBuffer.keySet();
        Iterator<String> evaluationIterator = evaluationKeys.iterator();
        while (evaluationIterator.hasNext()) {
            String key = evaluationIterator.next();
            BufferedEvaluation buffered = evaluationBuffer.get(key);
            if (buffered != null && now - buffered.createdAt() > TURN_BUFFER_TTL_MILLIS) {
                evaluationBuffer.remove(key, buffered);
            }
        }
    }

    /**
     * 构造缓冲键：用户与会话共同决定，避免同用户不同会话互相覆盖。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 缓冲键
     */
    private String bufferKey(Long userId, String sessionId) {
        return userId + "/" + sessionId;
    }

    /**
     * 暂存的本回合记录。
     *
     * @param row 待落库的问答记录，答题回合里先只填回答
     * @param pendingQuestion 用户正在回答的题目（回合开始时的上一条助手消息），供收尾兜底补记使用
     * @param createdAt 写入时间戳，用于过期兜底清理
     * @author wxy
     * @date 2026-09-29
     */
    private record BufferedTurn(InterviewQa row, String pendingQuestion, long createdAt) {
    }

    /**
     * 暂存的评分结论。
     *
     * @param payload 评分子 Agent 提交的结构化结论
     * @param createdAt 写入时间戳，用于过期兜底清理
     * @author wxy
     * @date 2026-09-29
     */
    private record BufferedEvaluation(AnswerEvaluationSubmitVO payload, long createdAt) {
    }
}
