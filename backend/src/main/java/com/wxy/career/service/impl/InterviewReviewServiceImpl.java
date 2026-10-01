package com.wxy.career.service.impl;

import com.wxy.career.mapper.InterviewQaMapper;
import com.wxy.career.po.InterviewQa;
import com.wxy.career.service.InterviewReportService;
import com.wxy.career.service.InterviewReviewService;
import com.wxy.career.service.KnowledgeMasteryService;
import com.wxy.career.vo.InterviewReportRespVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 面试复盘服务实现。
 *
 * <p>掌握度与报告都通过各自的服务完成，本类只做编排与异常兜底；逐题点评由面试结束时的逐题结果承载，
 * 不在每回合下发。
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

}
