package com.wxy.career.service;

import com.wxy.career.po.InterviewQa;
import com.wxy.career.po.KnowledgeMastery;
import com.wxy.career.vo.WeakPointsResultVO;

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
     * <p>只重算本回合涉及的知识点（用该用户 90 天窗口内的历史证据），不做全量重算。
     * 掌握度与薄弱点只落 MySQL 的 {@code knowledge_mastery}，不写记忆库（F9 的会话归档总结才写记忆）。
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

    /**
     * 读取指定用户用于专项辅导的薄弱点。
     *
     * <p>F9 专项辅导的读取入口：数据源就是本服务背后的 {@code knowledge_mastery}（掌握度与薄弱点是权威数据，
     * 不写记忆库）。不传关键词时只返回标记为薄弱的知识点；传关键词时按知识点名称模糊匹配该用户的全部掌握度
     * 记录，命中但不是薄弱点的知识点也会带上它当前的分数与等级，避免用户问到的知识点「查不到」。
     * 返回条数按配置项上限截断，空状态（没有面试记录、没有薄弱点、关键词没匹配到）写在返回结构的
     * {@code message} 里，由模型照实转述，本方法不抛业务异常。
     *
     * @param userId 用户 ID
     * @param keyword 知识点关键词，可为空
     * @return 薄弱点查询结果
     */
    WeakPointsResultVO listWeakPoints(Long userId, String keyword);
}
