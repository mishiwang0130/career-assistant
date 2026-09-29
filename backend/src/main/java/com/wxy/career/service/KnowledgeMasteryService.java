package com.wxy.career.service;

import com.wxy.career.po.InterviewQa;
import com.wxy.career.po.KnowledgeMastery;

import java.util.Collection;
import java.util.List;

/**
 * 知识点掌握度服务。
 *
 * <p>掌握度的权威数据在 MySQL 的 {@code knowledge_mastery} 表，口径写死在 {@code MasteryCalculator}
 * 与 {@code docs/技术约定.md} 的「面试点评与报告（F6）」章节：按近期多次证据与时间衰减加权，
 * 单题答错不会把知识点直接打到最低。F6 每回合落库后调用本服务沉淀，F7 通过本服务读表。
 *
 * @author wxy
 * @date 2026-09-29
 */
public interface KnowledgeMasteryService {

    /**
     * 按本回合的问答记录重算它涉及的知识点，并同步薄弱点到长期记忆。
     *
     * <p>只重算本回合涉及的知识点（用该用户 90 天窗口内的历史证据），不做全量重算；
     * 判定为薄弱的知识点会写一份 {@code WEAKNESS} 记忆，供后续会话召回。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param row 刚落库的问答记录
     */
    void refreshForTurn(Long userId, Long sessionId, InterviewQa row);

    /**
     * 查询指定用户的全部掌握度，薄弱在前、分数低的在前。
     *
     * @param userId 用户 ID
     * @return 掌握度列表
     */
    List<KnowledgeMastery> listByUser(Long userId);

    /**
     * 按知识点集合查询指定用户的掌握度，用于报告只取本场涉及的知识点。
     *
     * @param userId 用户 ID
     * @param knowledgePoints 知识点集合
     * @return 掌握度列表
     */
    List<KnowledgeMastery> listByUserAndPoints(Long userId, Collection<String> knowledgePoints);

    /**
     * 取一批问答记录涉及的全部知识点（去重、保持出现顺序）。
     *
     * @param rows 问答记录
     * @return 知识点列表
     */
    List<String> knowledgePointsOf(List<InterviewQa> rows);
}
