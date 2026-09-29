package com.wxy.career.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.enums.InterviewOutcomeEnum;
import com.wxy.career.mapper.InterviewQaMapper;
import com.wxy.career.mapper.KnowledgeMasteryMapper;
import com.wxy.career.po.InterviewQa;
import com.wxy.career.po.KnowledgeMastery;
import com.wxy.career.service.KnowledgeMasteryService;
import com.wxy.career.service.UserMemoryService;
import com.wxy.career.util.InterviewEvaluationParser;
import com.wxy.career.util.MasteryCalculator;
import com.wxy.career.vo.AnswerEvaluationSubmitVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识点掌握度服务实现。
 *
 * <p>证据来自 {@code interview_qa}：每条问答记录按它点评里的知识点展开，单条证据分优先取点评给的参考分，
 * 没有参考分时按判定折算。计算是纯函数（{@link MasteryCalculator}），本类只负责取证据、落库与同步薄弱点。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
@Service
public class KnowledgeMasteryServiceImpl implements KnowledgeMasteryService {

    /**
     * 单次重算最多回看的问答记录条数。
     *
     * <p>证据窗口只有 90 天、每个知识点最多取 8 条，这个上限只用于兜住异常数据，正常用户的面试记录远小于该值。
     */
    private static final int EVIDENCE_ROW_LIMIT = 1000;

    /**
     * 掌握度 Mapper。
     */
    @Resource
    private KnowledgeMasteryMapper knowledgeMasteryMapper;

    /**
     * 面试问答 Mapper，掌握度的证据来源。
     */
    @Resource
    private InterviewQaMapper interviewQaMapper;

    /**
     * 长期记忆入口：薄弱点同时写一份到 Mem0，供跨会话召回。
     */
    @Resource
    private UserMemoryService userMemoryService;

    /**
     * JSON 组件，用于回放问答记录里的点评结论。
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 按本回合的问答记录重算涉及的知识点，并把薄弱点同步到长期记忆。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param row 刚落库的问答记录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshForTurn(Long userId, Long sessionId, InterviewQa row) {
        if (userId == null || sessionId == null || row == null || !StringUtils.hasText(row.getEvaluationJson())) {
            // 评分不可用的回合没有知识点，跳过掌握度沉淀，不写半截数据。
            return;
        }
        AnswerEvaluationSubmitVO evaluation =
                InterviewEvaluationParser.parse(objectMapper, row.getEvaluationJson());
        List<String> knowledgePoints = InterviewEvaluationParser.knowledgePoints(evaluation);
        if (knowledgePoints.isEmpty()) {
            return;
        }
        List<InterviewQa> evidenceRows = loadEvidenceRows(userId);
        Map<Long, AnswerEvaluationSubmitVO> evaluationCache = new HashMap<>(evidenceRows.size());
        LocalDateTime now = LocalDateTime.now();
        for (String knowledgePoint : knowledgePoints) {
            MasteryCalculator.MasteryResult mastery = MasteryCalculator.calculate(
                    collectEvidences(knowledgePoint, evidenceRows, evaluationCache), now);
            upsert(userId, sessionId, knowledgePoint, mastery, now);
            if (mastery.weak()) {
                // outcome = WRONG 的知识点必然落在薄弱点里，这里统一按「是否薄弱」写长期记忆。
                userMemoryService.rememberWeakness(userId, String.valueOf(sessionId), knowledgePoint,
                        evaluation == null ? null : evaluation.getComment());
            }
        }
        log.info("掌握度沉淀完成，userId={}，sessionId={}，knowledgePoints={}", userId, sessionId, knowledgePoints);
    }

    /**
     * 查询指定用户的全部掌握度。
     *
     * @param userId 用户 ID
     * @return 掌握度列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeMastery> listByUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return knowledgeMasteryMapper.selectByUser(userId);
    }

    /**
     * 按知识点集合查询指定用户的掌握度。
     *
     * @param userId 用户 ID
     * @param knowledgePoints 知识点集合
     * @return 掌握度列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeMastery> listByUserAndPoints(Long userId, Collection<String> knowledgePoints) {
        if (userId == null || knowledgePoints == null || knowledgePoints.isEmpty()) {
            return List.of();
        }
        return knowledgeMasteryMapper.selectByUserAndPoints(userId, knowledgePoints);
    }

    /**
     * 取一批问答记录涉及的全部知识点。
     *
     * @param rows 问答记录
     * @return 知识点列表（去重、保持出现顺序）
     */
    @Override
    public List<String> knowledgePointsOf(List<InterviewQa> rows) {
        Set<String> points = new LinkedHashSet<>();
        if (rows == null) {
            return List.of();
        }
        for (InterviewQa row : rows) {
            AnswerEvaluationSubmitVO evaluation =
                    InterviewEvaluationParser.parse(objectMapper, row.getEvaluationJson());
            points.addAll(InterviewEvaluationParser.knowledgePoints(evaluation));
        }
        return List.copyOf(points);
    }

    /**
     * 取用于掌握度计算的证据行：该用户最近的问答记录，按主键倒序。
     *
     * <p>必须带 user_id：掌握度是用户私有数据，不能读到别人的面试记录。
     *
     * @param userId 用户 ID
     * @return 问答记录，最多 {@link #EVIDENCE_ROW_LIMIT} 条
     */
    private List<InterviewQa> loadEvidenceRows(Long userId) {
        List<InterviewQa> rows = interviewQaMapper.selectList(new LambdaQueryWrapper<InterviewQa>()
                .eq(InterviewQa::getUserId, userId)
                .orderByDesc(InterviewQa::getId));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        // 证据窗口只有 90 天，按主键倒序截断只影响窗口之外的旧记录，不会丢窗口内的证据。
        return rows.size() > EVIDENCE_ROW_LIMIT ? rows.subList(0, EVIDENCE_ROW_LIMIT) : rows;
    }

    /**
     * 收集某个知识点在该用户历史记录里的全部证据。
     *
     * @param knowledgePoint 知识点
     * @param rows 问答记录
     * @param evaluationCache 点评结论解析缓存，避免同一行重复解析
     * @return 证据列表
     */
    private List<MasteryCalculator.Evidence> collectEvidences(
            String knowledgePoint, List<InterviewQa> rows, Map<Long, AnswerEvaluationSubmitVO> evaluationCache) {
        List<MasteryCalculator.Evidence> evidences = new ArrayList<>();
        for (InterviewQa row : rows) {
            AnswerEvaluationSubmitVO evaluation = evaluationCache.computeIfAbsent(row.getId(),
                    id -> InterviewEvaluationParser.parse(objectMapper, row.getEvaluationJson()));
            if (!InterviewEvaluationParser.knowledgePoints(evaluation).contains(knowledgePoint)) {
                continue;
            }
            evidences.add(new MasteryCalculator.Evidence(
                    knowledgePoint,
                    evaluation == null ? null : evaluation.getScore(),
                    row.getOutcome(),
                    row.getCreateTime()));
        }
        return evidences;
    }

    /**
     * 写入或更新一个知识点的掌握度。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param knowledgePoint 知识点
     * @param mastery 掌握度计算结果
     * @param now 计算时间
     */
    private void upsert(
            Long userId, Long sessionId, String knowledgePoint,
            MasteryCalculator.MasteryResult mastery, LocalDateTime now) {
        String point = knowledgePoint.length() > KnowledgeMastery.KNOWLEDGE_POINT_MAX_LENGTH
                ? knowledgePoint.substring(0, KnowledgeMastery.KNOWLEDGE_POINT_MAX_LENGTH) : knowledgePoint;
        KnowledgeMastery existing = knowledgeMasteryMapper.selectByUserAndPoint(userId, point);
        KnowledgeMastery record = existing == null ? new KnowledgeMastery() : existing;
        record.setUserId(userId);
        record.setKnowledgePoint(point);
        record.setMasteryScore(mastery.score());
        record.setMasteryLevel(mastery.level().getValue());
        record.setWeak(mastery.weak() ? 1 : 0);
        record.setEvidenceCount(mastery.evidenceCount());
        record.setLastSessionId(sessionId);
        record.setLastOutcome(StringUtils.hasText(mastery.lastOutcome())
                ? mastery.lastOutcome() : InterviewOutcomeEnum.WRONG.getValue());
        record.setLastEvidenceTime(mastery.lastEvidenceTime() == null ? now : mastery.lastEvidenceTime());
        if (existing == null) {
            knowledgeMasteryMapper.insert(record);
        } else {
            knowledgeMasteryMapper.updateById(record);
        }
    }
}
