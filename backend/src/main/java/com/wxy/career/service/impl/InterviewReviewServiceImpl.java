package com.wxy.career.service.impl;

import com.wxy.career.common.enums.InterviewOutcomeEnum;
import com.wxy.career.mapper.InterviewQaMapper;
import com.wxy.career.po.InterviewQa;
import com.wxy.career.service.InterviewReportService;
import com.wxy.career.service.InterviewReviewService;
import com.wxy.career.service.KnowledgeMasteryService;
import com.wxy.career.util.InterviewEvaluationParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.vo.AnswerEvaluationSubmitVO;
import com.wxy.career.vo.InterviewEvaluationRespVO;
import com.wxy.career.vo.InterviewReportRespVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 面试复盘服务实现。
 *
 * <p>逐题点评直接回放 {@code interview_qa} 最近一条记录的 {@code evaluation_json}（F5 的评分结论，F6 不重新评分）；
 * 掌握度与报告都通过各自的服务完成，本类只做编排与异常兜底。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
@Service
public class InterviewReviewServiceImpl implements InterviewReviewService {

    /**
     * 面试问答 Mapper。
     */
    @Resource
    private InterviewQaMapper interviewQaMapper;

    /**
     * 掌握度服务。
     */
    @Resource
    private KnowledgeMasteryService knowledgeMasteryService;

    /**
     * 面试报告服务。
     */
    @Resource
    private InterviewReportService interviewReportService;

    /**
     * JSON 组件。
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 取本会话最近一条已落库问答的逐题点评。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @return 逐题点评载荷，没有记录或没有评分结论时返回 null
     */
    @Override
    public InterviewEvaluationRespVO latestEvaluation(Long userId, String sessionId) {
        InterviewQa row = latestRow(userId, sessionId);
        if (row == null) {
            return null;
        }
        AnswerEvaluationSubmitVO evaluation =
                InterviewEvaluationParser.parse(objectMapper, row.getEvaluationJson());
        InterviewEvaluationRespVO vo = new InterviewEvaluationRespVO();
        vo.setSessionId(sessionId);
        vo.setQuestionIndex(row.getQuestionIndex());
        vo.setRoundNo(row.getRoundNo());
        vo.setOutcome(row.getOutcome());
        InterviewOutcomeEnum outcome = InterviewOutcomeEnum.find(row.getOutcome());
        vo.setOutcomeLabel(outcome == null ? null : outcome.getLabel());
        vo.setDifficulty(row.getDifficulty());
        vo.setScore(evaluation == null ? null : evaluation.getScore());
        // 评分不可用时也下发点评（只有判定与判定要点），界面据此提示「这道题没有拿到评分结论」。
        vo.setComment(evaluation == null ? row.getJudgement() : evaluation.getComment());
        vo.setCorrectPoints(listOrEmpty(evaluation == null ? null : evaluation.getCorrectPoints()));
        vo.setMissingPoints(listOrEmpty(evaluation == null ? null : evaluation.getMissingPoints()));
        vo.setWrongPoints(listOrEmpty(evaluation == null ? null : evaluation.getWrongPoints()));
        vo.setExpressionIssues(listOrEmpty(evaluation == null ? null : evaluation.getExpressionIssues()));
        vo.setSuggestions(listOrEmpty(evaluation == null ? null : evaluation.getSuggestions()));
        vo.setKnowledgePoints(InterviewEvaluationParser.knowledgePoints(evaluation));
        vo.setReferenceAnswer(evaluation == null ? null : evaluation.getReferenceAnswer());
        vo.setEvaluated(evaluation != null);
        return vo;
    }

    /**
     * 一回合落库之后：沉淀掌握度与薄弱点；面试结束时启动报告生成。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @param finished 本场面试是否已结束
     * @return 面试结束且已启动报告生成时返回报告状态，否则返回 null
     */
    @Override
    public InterviewReportRespVO afterTurnCommitted(Long userId, String sessionId, boolean finished) {
        InterviewQa row = latestRow(userId, sessionId);
        if (row == null) {
            return null;
        }
        try {
            // 掌握度必须在报告派发之前沉淀：报告的任务材料里带着本场知识点的掌握度快照。
            knowledgeMasteryService.refreshForTurn(userId, row.getSessionId(), row);
        } catch (Exception exception) {
            // 掌握度沉淀失败不能影响面试主流程，更不能让用户看不到点评与进度。
            log.error("掌握度沉淀失败，userId={}，sessionId={}", userId, sessionId, exception);
        }
        if (!finished) {
            return null;
        }
        try {
            return interviewReportService.startGeneration(userId, sessionId);
        } catch (Exception exception) {
            log.error("启动面试报告生成失败，userId={}，sessionId={}", userId, sessionId, exception);
            return null;
        }
    }

    /**
     * 取本会话最近一条已落库的问答记录。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @return 问答记录，没有时返回 null
     */
    private InterviewQa latestRow(Long userId, String sessionId) {
        if (userId == null || !StringUtils.hasText(sessionId)) {
            return null;
        }
        Long sessionIdValue;
        try {
            sessionIdValue = Long.valueOf(sessionId.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
        List<InterviewQa> rows = interviewQaMapper.selectBySession(userId, sessionIdValue);
        return rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }

    /**
     * 空清单归一化为空列表。
     *
     * @param source 原清单
     * @return 非空清单
     */
    private List<String> listOrEmpty(List<String> source) {
        return source == null ? List.of() : source;
    }
}
