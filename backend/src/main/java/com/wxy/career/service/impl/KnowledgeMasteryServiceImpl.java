package com.wxy.career.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.config.TutoringProperties;
import com.wxy.career.common.enums.InterviewOutcomeEnum;
import com.wxy.career.mapper.InterviewQaMapper;
import com.wxy.career.mapper.KnowledgeMasteryMapper;
import com.wxy.career.po.InterviewQa;
import com.wxy.career.po.KnowledgeMastery;
import com.wxy.career.service.KnowledgeMasteryService;
import com.wxy.career.util.InterviewEvaluationParser;
import com.wxy.career.util.MasteryCalculator;
import com.wxy.career.vo.AnswerEvaluationSubmitVO;
import com.wxy.career.vo.WeakPointVO;
import com.wxy.career.vo.WeakPointsResultVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识点掌握度服务实现。
 *
 * <p>证据来自 {@code interview_qa}：每条问答记录按它点评里的知识点展开，单条证据分优先取点评给的参考分，
 * 没有参考分时按判定折算。计算是纯函数（{@link MasteryCalculator}），本类只负责取证据与落库。
 *
 * <p><b>掌握度与薄弱点只落 MySQL</b>：它们是精确查询（按知识点取分值、按分值排序），
 * {@code knowledge_mastery} 就是权威数据，F7 从这里读；不再往记忆库写副本。
 * 记忆库的写入链路是会话归档总结（F9），只承接「读得到、算不出、也列不全」的讲解进度与背景。
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

    // ==================== F9 专项辅导 ====================

    /**
     * 没有任何掌握度记录时的空状态说明，让模型如实告诉用户先去练一场。
     */
    private static final String NO_DATA_MESSAGE =
            "还没有面试记录，暂时没有薄弱点数据，建议先完成一场模拟面试再来复盘。";

    /**
     * 有掌握度记录、但没有一条被标记为薄弱时的说明。
     */
    private static final String NO_WEAK_MESSAGE = "目前没有标记为薄弱的知识点。";

    /**
     * 关键词没有匹配到任何知识点时的说明，占位符是用户问到的关键词。
     */
    private static final String KEYWORD_MISS_MESSAGE_FORMAT = "没有找到与「%s」相关的知识点记录。";

    /**
     * 结果被上限截断时的说明，两个占位符分别是匹配总数与本次生效的上限。
     */
    private static final String TRUNCATED_MESSAGE_FORMAT = "共匹配到 %d 个知识点，这里只返回最需要补的前 %d 个。";

    /**
     * 关键词的最大长度，与知识点名称字段的粒度对齐，避免异常输入撑大返回内容。
     */
    private static final int KEYWORD_MAX_LENGTH = 50;

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
     * JSON 组件，用于回放问答记录里的点评结论。
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 专项辅导配置，提供读薄弱点工具的返回条数上限。
     */
    @Resource
    private TutoringProperties tutoringProperties;

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

    /**
     * 读取指定用户用于专项辅导的薄弱点。
     *
     * <p>数据源就是本服务背后的 {@code knowledge_mastery}（权威数据，不写记忆库）。过滤与排序都在这里定死，
     * 不依赖上层查询的排序：薄弱在前、掌握度低的在前、同分按主键稳定；没给关键词时只留薄弱点，
     * 给了关键词时连命中但不是薄弱点的知识点也一并返回（用户问到的知识点不该「查不到」）。
     *
     * @param userId 用户 ID
     * @param keyword 知识点关键词，可为空
     * @return 薄弱点查询结果
     */
    @Override
    @Transactional(readOnly = true)
    public WeakPointsResultVO listWeakPoints(Long userId, String keyword) {
        WeakPointsResultVO result = new WeakPointsResultVO();
        // 上限兜住 1：配置校验之外再保一层，避免异常配置导致「返回 0 条」这种自相矛盾的行为。
        int limit = Math.max(1, tutoringProperties.getMaxWeakPoints());
        result.setLimit(limit);
        if (userId == null) {
            // 未登录由运行时上下文工具兜住并抛 401，这里只防御性地按空状态返回。
            result.setMessage(NO_DATA_MESSAGE);
            return result;
        }
        List<KnowledgeMastery> rows = knowledgeMasteryMapper.selectByUser(userId);
        if (rows == null || rows.isEmpty()) {
            result.setMessage(NO_DATA_MESSAGE);
            return result;
        }
        result.setHasData(true);
        String normalizedKeyword = normalizeKeyword(keyword);
        List<KnowledgeMastery> matched = new ArrayList<>();
        for (KnowledgeMastery row : rows) {
            if (StringUtils.hasText(normalizedKeyword)) {
                // 关键词匹配忽略大小写：用户常写 redis，表里存的是 Redis。
                String point = row.getKnowledgePoint();
                if (point == null || !point.toLowerCase().contains(normalizedKeyword.toLowerCase())) {
                    continue;
                }
            } else if (!Integer.valueOf(1).equals(row.getWeak())) {
                // 没给关键词时只讲薄弱点：已掌握的知识点铺给模型只会稀释注意力。
                continue;
            }
            matched.add(row);
        }
        matched.sort(Comparator
                .comparing((KnowledgeMastery row) -> Integer.valueOf(1).equals(row.getWeak()) ? 0 : 1)
                .thenComparing(row -> row.getMasteryScore() == null ? Integer.MAX_VALUE : row.getMasteryScore())
                .thenComparing(row -> row.getId() == null ? Long.MAX_VALUE : row.getId()));
        List<WeakPointVO> points = new ArrayList<>();
        for (KnowledgeMastery row : matched) {
            if (points.size() >= limit) {
                break;
            }
            points.add(WeakPointVO.from(row));
        }
        result.setPoints(points);
        result.setCount(points.size());
        if (points.isEmpty()) {
            result.setMessage(StringUtils.hasText(normalizedKeyword)
                    ? String.format(KEYWORD_MISS_MESSAGE_FORMAT, normalizedKeyword)
                    : NO_WEAK_MESSAGE);
        } else if (matched.size() > limit) {
            result.setMessage(String.format(TRUNCATED_MESSAGE_FORMAT, matched.size(), limit));
        }
        return result;
    }

    /**
     * 归一化关键词：折叠连续空白并截断长度。
     *
     * <p>关键词会被拼进返回给模型的说明文本，因此先折叠换行与连续空白、再截断长度，避免异常输入
     * 伪造出新的指令段落或撑大返回内容。
     *
     * @param keyword 原始关键词
     * @return 归一化后的关键词，为空时返回 null
     */
    private String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String normalized = keyword.replaceAll("\\s+", " ").trim();
        return normalized.length() > KEYWORD_MAX_LENGTH
                ? normalized.substring(0, KEYWORD_MAX_LENGTH) : normalized;
    }
}
