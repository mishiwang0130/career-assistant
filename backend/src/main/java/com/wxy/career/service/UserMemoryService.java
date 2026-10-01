package com.wxy.career.service;

/**
 * 长期记忆业务入口。
 *
 * <p>Mem0 实例的创建与装配封装在 {@code AgentFactoryImpl} 与 {@code UserLongTermMemoryAdapter}，
 * 业务模块**只能**通过本接口读写长期记忆：F6 用它在答错时沉淀薄弱点，F7 的计划生成与 F9 的专项辅导
 * 后续也会走同一套入口。任何模块都不得直接 new 记忆实例或直接调 Mem0。
 *
 * <p>所有方法都遵守「Mem0 不可用时降级」的硬约束：召回失败返回空串，写入失败只记日志并留待补偿，
 * 不抛异常、不阻断业务主流程。
 *
 * @author wxy
 * @date 2026-09-29
 */
public interface UserMemoryService {

    /**
     * 记录一个薄弱知识点（记忆类型 {@code WEAKNESS}）。
     *
     * <p>面试里判定为「完全不会或答错」的知识点，既进报告与 {@code knowledge_mastery}，也写一份到长期记忆，
     * 供后续会话召回与训练计划补强。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param knowledgePoint 知识点名称
     * @param summary 一句话说明（为什么薄弱、下次注意什么），超过 200 字会被截断
     */
    void rememberWeakness(Long userId, String sessionId, String knowledgePoint, String summary);

    /**
     * 记录一条用户画像记忆（记忆类型 {@code PROFILE}）。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param content 画像内容，例如「目标岗位是后端开发，偏好 Java 生态」
     */
    void rememberProfile(Long userId, String sessionId, String content);

    /**
     * 记录一条事实记忆（记忆类型 {@code FACT}）。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param content 事实内容，例如「正在准备 2026 届秋招，投递方向是中间件」
     */
    void rememberFact(Long userId, String sessionId, String content);

    /**
     * 按当前问题召回该用户的长期记忆。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param query 召回依据的问题文本
     * @return 注入文本；没有命中或 Mem0 不可用时返回空串
     */
    String recall(Long userId, String sessionId, String query);
}
