package com.wxy.career.service;

/**
 * 长期记忆业务入口。
 *
 * <p>Mem0 实例的创建与装配封装在 {@code AgentFactoryImpl} 与 {@code UserLongTermMemoryAdapter}，
 * 业务模块**只能**通过本接口读写长期记忆。任何模块都不得直接 new 记忆实例或直接调 Mem0。
 *
 * <p>写入的唯一来源是**会话归档总结**（F9）：一场会话安静下来后由归档总结 Agent 产出结论式记忆，
 * 再经本接口写入。不写薄弱点（它落 MySQL 的 {@code knowledge_mastery}，是精确查询、不需要记忆库副本）；
 * 模型也没有写记忆的权限。
 *
 * <p>所有方法都遵守「Mem0 不可用时降级」的硬约束：召回失败返回空串，写入失败只记日志、不抛异常，
 * 由调用方按返回值决定是否留待补偿——会话归档总结据此决定标记已归档还是下轮重试。
 *
 * @author wxy
 * @date 2026-09-29
 */
public interface UserMemoryService {

    /**
     * 记录一条用户画像记忆（记忆类型 {@code PROFILE}）。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param content 画像内容，例如「目标岗位是后端开发，偏好 Java 生态」
     * @return 写入成功返回 true；缺用户标识、内容为空或 Mem0 不可用时返回 false（不抛异常）
     */
    boolean rememberProfile(Long userId, String sessionId, String content);

    /**
     * 记录一条事实记忆（记忆类型 {@code FACT}）。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param content 事实内容，例如「正在准备 2026 届秋招，投递方向是中间件」
     * @return 写入成功返回 true；缺用户标识、内容为空或 Mem0 不可用时返回 false（不抛异常）
     */
    boolean rememberFact(Long userId, String sessionId, String content);

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
