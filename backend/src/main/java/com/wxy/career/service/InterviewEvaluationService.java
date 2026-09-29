package com.wxy.career.service;

/**
 * 面试单题评分服务。
 *
 * <p>评分与提问必须是两个角色，但「让面试官派发评分」这条路已被实测证伪：子 Agent 的正文会被面试官
 * 抄进回答里（出现过评分 JSON 直接出现在用户可见正文与历史消息里的情况），而正文内容不受平台控制。
 * 因此改由平台编排：面试官开流之前，平台直接用评分子 Agent 完成本题评分，结论通过工具落进运行态缓冲，
 * 子 Agent 的正文一律丢弃。面试官只负责按 {@code record_interview_answer} 返回的指令问下一题。
 *
 * <p>评分失败时只记 warn 并让流程按「答得有遗漏」保守继续，用户不会因此卡住。
 *
 * @author wxy
 * @date 2026-09-29
 */
public interface InterviewEvaluationService {

    /**
     * 对用户本题的作答评分，结论落到本回合的运行态缓冲。
     *
     * <p>没有上一道题（面试开场那一轮）时直接跳过，不调用模型。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @param answer 用户本题的回答
     */
    void evaluate(Long userId, String sessionId, String answer);
}
