package com.wxy.career.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.enums.ChatSceneEnum;
import com.wxy.career.common.enums.InterviewOutcomeEnum;
import com.wxy.career.common.enums.InterviewReportStatusEnum;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.mapper.ChatSessionMapper;
import com.wxy.career.mapper.InterviewQaMapper;
import com.wxy.career.mapper.InterviewReportMapper;
import com.wxy.career.middleware.ReportTaskDispatcher;
import com.wxy.career.po.ChatSession;
import com.wxy.career.po.InterviewQa;
import com.wxy.career.po.InterviewReport;
import com.wxy.career.po.KnowledgeMastery;
import com.wxy.career.service.InterviewFlowService;
import com.wxy.career.service.InterviewReportService;
import com.wxy.career.service.KnowledgeMasteryService;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.util.InterviewEvaluationParser;
import com.wxy.career.vo.AnswerEvaluationSubmitVO;
import com.wxy.career.vo.InterviewReportContentVO;
import com.wxy.career.vo.InterviewReportRespVO;
import com.wxy.career.vo.InterviewReportSubmitVO;
import com.wxy.career.vo.InterviewStateRespVO;
import com.wxy.career.vo.InterviewWeaknessVO;
import com.wxy.career.vo.InterviewWrongItemVO;
import com.wxy.career.vo.KnowledgeMasteryVO;
import com.wxy.career.vo.UserProfileRespVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 面试报告服务实现。
 *
 * <p>报告只由后台子 Agent 写结论（面试总结、亮点、下一步建议），错题清单、薄弱点清单与掌握度由
 * {@code interview_qa} + {@code knowledge_mastery} 确定性派生；状态机与失败口径见
 * {@code docs/技术约定.md} 的「面试点评与报告（F6）」章节。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
@Service
public class InterviewReportServiceImpl implements InterviewReportService {

    /**
     * 「生成中」超过该时长仍未回写即视为失败，保证用户不会卡在「报告生成中」不动。
     */
    private static final Duration GENERATING_STALE_TIMEOUT = Duration.ofMinutes(5);

    /**
     * 失败原因的兜底文案。
     */
    private static final String FAILED_MESSAGE_DEFAULT = "报告生成失败，请重试";

    /**
     * 生成超时的失败原因。
     */
    private static final String FAILED_MESSAGE_TIMEOUT = "报告生成超时，请重试";

    /**
     * 报告生成时间格式。
     */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 报告 Mapper。
     */
    @Resource
    private InterviewReportMapper interviewReportMapper;

    /**
     * 会话 Mapper，只用于校验会话归属与场景。
     */
    @Resource
    private ChatSessionMapper chatSessionMapper;

    /**
     * 面试问答 Mapper，报告里的错题与知识点由它派生。
     */
    @Resource
    private InterviewQaMapper interviewQaMapper;

    /**
     * 面试流程服务，用于判断面试是否已结束。
     */
    @Resource
    private InterviewFlowService interviewFlowService;

    /**
     * 掌握度服务，报告里的薄弱点与掌握度由它读取。
     */
    @Resource
    private KnowledgeMasteryService knowledgeMasteryService;

    /**
     * 求职目标服务，给报告子 Agent 提供目标岗位与工作年限。
     */
    @Resource
    private UserProfileService userProfileService;

    /**
     * 后台任务派发器：用框架的后台子 Agent 模式生成报告。
     */
    @Resource
    private ReportTaskDispatcher reportTaskDispatcher;

    /**
     * JSON 组件。
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 面试结束后启动报告生成。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @return 报告当前状态
     */
    @Override
    public InterviewReportRespVO startGeneration(Long userId, String sessionId) {
        Long sessionIdValue = requireFinishedInterview(userId, sessionId);
        interviewReportMapper.upsertGenerating(userId, sessionIdValue);
        dispatchGeneration(userId, sessionId, sessionIdValue);
        return getReport(userId, sessionId);
    }

    /**
     * 读取报告三态与内容。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @return 报告
     */
    @Override
    public InterviewReportRespVO getReport(Long userId, String sessionId) {
        Long sessionIdValue = requireFinishedInterview(userId, sessionId);
        InterviewReport report = interviewReportMapper.selectByUserAndSession(userId, sessionIdValue);
        if (report == null) {
            // 面试已结束但记录缺失（例如派发前进程重启）：返回失败态让用户能点重试，读取路径不写库。
            log.warn("面试报告记录缺失，返回失败态，userId={}，sessionId={}", userId, sessionIdValue);
        }
        report = expireStaleGenerating(userId, sessionIdValue, report);
        return buildResponse(userId, sessionId, sessionIdValue, report);
    }

    /**
     * 重试生成报告。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @return 报告当前状态
     */
    @Override
    public InterviewReportRespVO retry(Long userId, String sessionId) {
        Long sessionIdValue = requireFinishedInterview(userId, sessionId);
        InterviewReport report = interviewReportMapper.selectByUserAndSession(userId, sessionIdValue);
        if (report != null && InterviewReportStatusEnum.SUCCEEDED.getValue().equals(report.getStatus())) {
            // 已完成：幂等返回现有报告，不重复生成。
            return getReport(userId, sessionId);
        }
        report = expireStaleGenerating(userId, sessionIdValue, report);
        if (report != null && InterviewReportStatusEnum.GENERATING.getValue().equals(report.getStatus())) {
            throw new BizException(ErrorConstant.INTERVIEW_REPORT_GENERATING);
        }
        return startGeneration(userId, sessionId);
    }

    /**
     * 由报告子 Agent 的工具回写报告结论。
     *
     * @param userId 用户 ID
     * @param submitVO 报告结论
     */
    @Override
    public void submitReport(Long userId, InterviewReportSubmitVO submitVO) {
        if (submitVO == null || !StringUtils.hasText(submitVO.getSessionId())) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        Long sessionIdValue = requireInterviewSession(userId, submitVO.getSessionId());
        InterviewReport report = interviewReportMapper.selectByUserAndSession(userId, sessionIdValue);
        if (report == null) {
            // 没有待生成的报告：后台任务的会话 ID 与用户对不上（跨账号或已重置），按「会话不存在」拒绝。
            throw new BizException(ErrorConstant.CHAT_SESSION_NOT_FOUND);
        }
        if (InterviewReportStatusEnum.SUCCEEDED.getValue().equals(report.getStatus())) {
            // 重复提交（模型重试工具）：幂等忽略，不再覆盖已完成的报告，也不报错让模型反复重试。
            log.info("报告已生成，忽略重复提交，userId={}，sessionId={}", userId, sessionIdValue);
            return;
        }
        String reportJson = writeContent(submitVO);
        interviewReportMapper.markSucceeded(userId, sessionIdValue, submitVO.getSummary(), reportJson);
        log.info("面试报告已生成，userId={}，sessionId={}，summaryLength={}",
                userId, sessionIdValue, submitVO.getSummary() == null ? 0 : submitVO.getSummary().length());
    }

    /**
     * 派发后台生成任务；派发失败只把报告置为失败，不抛给调用方（面试主流程不受影响）。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID 字符串
     * @param sessionIdValue 面试会话 ID
     */
    private void dispatchGeneration(Long userId, String sessionId, Long sessionIdValue) {
        try {
            List<InterviewQa> rows = interviewQaMapper.selectBySession(userId, sessionIdValue);
            // 后台派发：框架的 timeout_seconds=0 会立刻返回受理结果、任务在框架内部跑，
            // 因此这里同步订阅即可（不阻塞对话线程）；派发失败或子 Agent 执行失败都把报告置为失败态，
            // 让用户拿到明确提示与重试入口，而不是卡在「报告生成中」。
            reportTaskDispatcher.dispatch(userId, sessionId, buildTaskText(userId, sessionId, rows))
                    .subscribe(
                            result -> log.info("面试报告后台任务已受理，userId={}，sessionId={}，result={}",
                                    userId, sessionId, result),
                            error -> {
                                log.error("面试报告后台任务派发或执行失败，userId={}，sessionId={}",
                                        userId, sessionId, error);
                                interviewReportMapper.markFailed(userId, sessionIdValue, FAILED_MESSAGE_DEFAULT);
                            });
        } catch (Exception exception) {
            log.error("面试报告后台任务派发失败，userId={}，sessionId={}", userId, sessionId, exception);
            interviewReportMapper.markFailed(userId, sessionIdValue, FAILED_MESSAGE_DEFAULT);
        }
    }

    /**
     * 「生成中」超时兜底：读取时判定，不引入调度器。
     *
     * @param userId 用户 ID
     * @param sessionIdValue 面试会话 ID
     * @param report 当前报告记录，可为 null
     * @return 处理后的报告记录
     */
    private InterviewReport expireStaleGenerating(Long userId, Long sessionIdValue, InterviewReport report) {
        if (report == null || !InterviewReportStatusEnum.GENERATING.getValue().equals(report.getStatus())
                || report.getCreateTime() == null) {
            return report;
        }
        if (Duration.between(report.getCreateTime(), LocalDateTime.now()).compareTo(GENERATING_STALE_TIMEOUT) < 0) {
            return report;
        }
        log.warn("面试报告生成超时，转为失败态，userId={}，sessionId={}", userId, sessionIdValue);
        interviewReportMapper.markFailed(userId, sessionIdValue, FAILED_MESSAGE_TIMEOUT);
        return interviewReportMapper.selectByUserAndSession(userId, sessionIdValue);
    }

    /**
     * 组装报告响应：状态 + 子 Agent 产出 + 确定性派生的错题、薄弱点与掌握度。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID 字符串
     * @param sessionIdValue 面试会话 ID
     * @param report 报告记录，可为 null
     * @return 报告响应
     */
    private InterviewReportRespVO buildResponse(
            Long userId, String sessionId, Long sessionIdValue, InterviewReport report) {
        InterviewReportRespVO response = new InterviewReportRespVO();
        response.setSessionId(sessionId);
        InterviewReportStatusEnum status = report == null
                ? InterviewReportStatusEnum.FAILED : InterviewReportStatusEnum.find(report.getStatus());
        if (status == null) {
            status = InterviewReportStatusEnum.FAILED;
        }
        response.setStatus(status.getValue());
        response.setStatusLabel(status.getLabel());
        response.setCanRetry(status == InterviewReportStatusEnum.FAILED);
        response.setErrorMessage(report == null ? FAILED_MESSAGE_DEFAULT : report.getErrorMessage());
        if (report != null && report.getFinishTime() != null) {
            response.setGeneratedAt(report.getFinishTime().format(TIME_FORMATTER));
        }
        if (status == InterviewReportStatusEnum.SUCCEEDED && report != null) {
            response.setSummary(report.getSummary());
            InterviewReportContentVO content = readContent(report.getReportJson());
            response.setHighlights(nullToEmpty(content == null ? null : content.getHighlights()));
            response.setSuggestions(nullToEmpty(content == null ? null : content.getSuggestions()));
        } else {
            response.setHighlights(List.of());
            response.setSuggestions(List.of());
        }
        List<InterviewQa> rows = interviewQaMapper.selectBySession(userId, sessionIdValue);
        response.setWrongItems(buildWrongItems(rows));
        response.setMastery(buildMastery(userId, rows));
        response.setWeaknesses(buildWeaknesses(response.getMastery(), rows));
        return response;
    }

    /**
     * 组装错题清单：判定为「完全不会或答错」的回合。
     *
     * @param rows 问答记录
     * @return 错题清单
     */
    private List<InterviewWrongItemVO> buildWrongItems(List<InterviewQa> rows) {
        List<InterviewWrongItemVO> items = new ArrayList<>();
        for (InterviewQa row : rows) {
            if (!InterviewOutcomeEnum.WRONG.getValue().equals(row.getOutcome())) {
                continue;
            }
            AnswerEvaluationSubmitVO evaluation =
                    InterviewEvaluationParser.parse(objectMapper, row.getEvaluationJson());
            InterviewWrongItemVO item = new InterviewWrongItemVO();
            item.setQuestionIndex(row.getQuestionIndex());
            item.setRoundNo(row.getRoundNo());
            item.setQuestion(row.getQuestion());
            item.setOutcome(row.getOutcome());
            item.setOutcomeLabel(InterviewOutcomeEnum.WRONG.getLabel());
            item.setComment(evaluation == null ? row.getJudgement() : evaluation.getComment());
            item.setKnowledgePoints(InterviewEvaluationParser.knowledgePoints(evaluation));
            items.add(item);
        }
        return items;
    }

    /**
     * 组装本场面试涉及知识点的掌握度。
     *
     * @param userId 用户 ID
     * @param rows 问答记录
     * @return 掌握度列表
     */
    private List<KnowledgeMasteryVO> buildMastery(Long userId, List<InterviewQa> rows) {
        List<String> points = knowledgeMasteryService.knowledgePointsOf(rows);
        List<KnowledgeMastery> records = knowledgeMasteryService.listByUserAndPoints(userId, points);
        return records.stream()
                .map(KnowledgeMasteryVO::from)
                .sorted(Comparator.comparing(KnowledgeMasteryVO::getMasteryScore,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * 组装薄弱点清单：本场掌握度里被判定为薄弱的知识点。
     *
     * @param mastery 本场掌握度
     * @param rows 问答记录，用于取最近一次点评作为薄弱原因
     * @return 薄弱点清单
     */
    private List<InterviewWeaknessVO> buildWeaknesses(List<KnowledgeMasteryVO> mastery, List<InterviewQa> rows) {
        List<InterviewWeaknessVO> weaknesses = new ArrayList<>();
        for (KnowledgeMasteryVO point : mastery) {
            if (!Boolean.TRUE.equals(point.getWeak())) {
                continue;
            }
            InterviewWeaknessVO weakness = new InterviewWeaknessVO();
            weakness.setKnowledgePoint(point.getKnowledgePoint());
            weakness.setMasteryScore(point.getMasteryScore());
            weakness.setMasteryLevel(point.getMasteryLevel());
            weakness.setMasteryLevelLabel(point.getMasteryLevelLabel());
            weakness.setLastOutcome(point.getLastOutcome());
            weakness.setComment(latestComment(point.getKnowledgePoint(), rows));
            weaknesses.add(weakness);
        }
        return weaknesses;
    }

    /**
     * 取某个知识点最近一次点评里的一句话点评。
     *
     * @param knowledgePoint 知识点
     * @param rows 问答记录（按作答顺序）
     * @return 一句话点评，找不到时返回 null
     */
    private String latestComment(String knowledgePoint, List<InterviewQa> rows) {
        String comment = null;
        for (InterviewQa row : rows) {
            AnswerEvaluationSubmitVO evaluation =
                    InterviewEvaluationParser.parse(objectMapper, row.getEvaluationJson());
            if (InterviewEvaluationParser.knowledgePoints(evaluation).contains(knowledgePoint)) {
                comment = evaluation == null ? row.getJudgement() : evaluation.getComment();
            }
        }
        return comment;
    }

    /**
     * 组装派发给报告子 Agent 的任务文本。
     *
     * <p>材料只给结论层：逐题判定与一句话点评、错题清单、本场掌握度；报告子 Agent 只依据这些证据写作，
     * 不需要自己查库，也就不需要额外的业务工具。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID 字符串
     * @param rows 问答记录
     * @return 任务文本
     */
    private String buildTaskText(Long userId, String sessionId, List<InterviewQa> rows) {
        StringBuilder text = new StringBuilder();
        text.append("请为下面这场模拟面试写一份报告，并用 submit_interview_report 提交一次。")
                .append(System.lineSeparator())
                .append("【会话 ID】").append(sessionId).append("（提交时 sessionId 原样填这个值）")
                .append(System.lineSeparator());
        UserProfileRespVO profile = userProfileService.getUserProfileByUserId(userId);
        if (profile != null) {
            text.append("【目标岗位】").append(profile.getTargetPosition())
                    .append("（工作年限 ")
                    .append(profile.getWorkYears() == null ? 0 : profile.getWorkYears())
                    .append(" 年）").append(System.lineSeparator());
        }
        text.append("【逐题判定】").append(System.lineSeparator());
        for (InterviewQa row : rows) {
            AnswerEvaluationSubmitVO evaluation =
                    InterviewEvaluationParser.parse(objectMapper, row.getEvaluationJson());
            text.append("- 第 ").append(row.getQuestionIndex()).append(" 题")
                    .append(row.getRoundNo() != null && row.getRoundNo() > 1 ? "（追问）" : "")
                    .append("，难度 L").append(row.getDifficulty())
                    .append("，判定 ").append(outcomeLabel(row.getOutcome()))
                    .append("，知识点 ").append(String.join("、",
                            InterviewEvaluationParser.knowledgePoints(evaluation)))
                    .append(System.lineSeparator());
            String comment = evaluation == null ? row.getJudgement() : evaluation.getComment();
            if (StringUtils.hasText(comment)) {
                text.append("  一句话点评：").append(comment).append(System.lineSeparator());
            }
            if (evaluation != null && evaluation.getMissingPoints() != null && !evaluation.getMissingPoints().isEmpty()) {
                text.append("  遗漏点：").append(String.join("；", evaluation.getMissingPoints()))
                        .append(System.lineSeparator());
            }
        }
        List<InterviewWrongItemVO> wrongItems = buildWrongItems(rows);
        if (!wrongItems.isEmpty()) {
            text.append("【错题清单】").append(System.lineSeparator());
            for (InterviewWrongItemVO item : wrongItems) {
                text.append("- 第 ").append(item.getQuestionIndex()).append(" 题：")
                        .append(item.getQuestion()).append("（知识点：")
                        .append(String.join("、", nullToEmpty(item.getKnowledgePoints()))).append("）")
                        .append(System.lineSeparator());
            }
        }
        List<KnowledgeMasteryVO> mastery = buildMastery(userId, rows);
        if (!mastery.isEmpty()) {
            text.append("【本场知识点掌握度】").append(System.lineSeparator());
            Set<String> weakPoints = new LinkedHashSet<>();
            for (KnowledgeMasteryVO point : mastery) {
                text.append("- ").append(point.getKnowledgePoint()).append("：")
                        .append(point.getMasteryScore()).append(" 分（")
                        .append(point.getMasteryLevelLabel()).append("）")
                        .append(Boolean.TRUE.equals(point.getWeak()) ? "，薄弱" : "")
                        .append(System.lineSeparator());
                if (Boolean.TRUE.equals(point.getWeak())) {
                    weakPoints.add(point.getKnowledgePoint());
                }
            }
            if (!weakPoints.isEmpty()) {
                text.append("【薄弱点清单】").append(String.join("、", weakPoints)).append(System.lineSeparator());
            }
        }
        return text.toString();
    }

    /**
     * 判定结果的中文说明。
     *
     * @param outcome 判定结果值
     * @return 中文说明，未登记时返回原值
     */
    private String outcomeLabel(String outcome) {
        InterviewOutcomeEnum outcomeEnum = InterviewOutcomeEnum.find(outcome);
        return outcomeEnum == null ? String.valueOf(outcome) : outcomeEnum.getLabel();
    }

    /**
     * 序列化报告结构化结论。
     *
     * @param submitVO 报告结论
     * @return JSON 字符串，序列化失败时返回 null
     */
    private String writeContent(InterviewReportSubmitVO submitVO) {
        InterviewReportContentVO content = new InterviewReportContentVO();
        content.setHighlights(submitVO.getHighlights());
        content.setSuggestions(submitVO.getSuggestions());
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception exception) {
            log.warn("报告结构化结论序列化失败，只保留面试总结，userId 略", exception);
            return null;
        }
    }

    /**
     * 回放报告结构化结论。
     *
     * @param reportJson 报告 JSON
     * @return 结论，解析失败时返回 null
     */
    private InterviewReportContentVO readContent(String reportJson) {
        if (!StringUtils.hasText(reportJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(reportJson, InterviewReportContentVO.class);
        } catch (Exception exception) {
            log.warn("报告结构化结论解析失败，报告只展示面试总结", exception);
            return null;
        }
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
     * 校验会话属于当前用户、是面试场景且已经结束。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID 字符串
     * @return 会话 ID
     */
    private Long requireFinishedInterview(Long userId, String sessionId) {
        Long sessionIdValue = requireInterviewSession(userId, sessionId);
        InterviewStateRespVO state = interviewFlowService.getState(userId, sessionId);
        if (!Boolean.TRUE.equals(state.getFinished())) {
            throw new BizException(ErrorConstant.INTERVIEW_REPORT_NOT_READY);
        }
        return sessionIdValue;
    }

    /**
     * 校验会话属于当前用户且是模拟面试场景。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID 字符串
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
     * 解析会话 ID，非纯数字返回 null。
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
}
