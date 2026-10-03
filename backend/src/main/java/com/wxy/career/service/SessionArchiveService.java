package com.wxy.career.service;

/**
 * 会话归档总结（记忆写入）。
 *
 * <p>这是长期记忆的**唯一写入路径**：把已经安静下来的助手会话交给归档总结 Agent 提炼成 0~3 条结论式记忆，
 * 再经 {@link UserMemoryService} 以 {@code FACT} 类型写入 Mem0。模型没有写记忆的工具，写入动作完全由平台侧
 * 解析结论后执行。
 *
 * <p>只归档助手场景（{@code ASSISTANT}）：讲解进度与卡点在这里产生；面试会话的结论已经有
 * {@code interview_qa}、{@code knowledge_mastery}、{@code interview_report} 三张结构化表，不重复沉淀。
 *
 * @author wxy
 * @date 2026-10-03
 */
public interface SessionArchiveService {

    /**
     * 扫描一次待归档的安静会话并逐场归档。
     *
     * <p>没有候选会话、长期记忆关闭或归档开关关闭时都不做任何事；单场会话失败只影响它自己，
     * 不会中断整批扫描。
     *
     * @return 本次归档成功的会话数
     */
    int archiveQuietSessions();
}
