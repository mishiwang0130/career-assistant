package com.wxy.career.service;

import com.wxy.career.vo.TrainingPlanRespVO;

/**
 * 训练计划服务。
 *
 * <p>一份计划 = 一条 {@code training_plan} 记录：起止日期与每天时长来自用户在计划页填写的表单
 * （「还有几天、每天多长时间」），**正文是一份按天的「今天练什么知识点」Markdown**，
 * 存在 {@code plan_content}。没有训练任务表——提醒 Agent 每天读这份正文推断今天要干什么。
 *
 * <p>计划正文只落 MySQL，不往服务器工作区写文件；薄弱点只从 {@code knowledge_mastery} 读
 * （复用 F9 的只读工具），F7 不写记忆库。
 *
 * @author wxy
 * @date 2026-10-01
 */
public interface TrainingPlanService {

    /**
     * 查询当前用户生效中的计划（含实时剩余天数、今日提醒与未读角标）。
     *
     * @param userId 用户 ID
     * @return 计划
     */
    TrainingPlanRespVO getCurrentPlan(Long userId);

    /**
     * 判断用户当前是否有生效中的计划。
     *
     * @param userId 用户 ID
     * @return true 表示有生效计划
     */
    boolean hasActivePlan(Long userId);

    /**
     * 记下本次生成请求的输入（天数与每日时长）。
     *
     * <p>起止日期与每天时长以**用户本次表单输入**为准，模型不需要、也不允许决定它们。
     *
     * @param userId 用户 ID
     * @param sessionId 计划 Agent 的运行标识
     * @param days 本次请求的天数
     * @param dailyMinutes 本次请求的每日时长（分钟）
     */
    void recordGenerationInput(Long userId, String sessionId, int days, int dailyMinutes);

    /**
     * 落库一份计划正文（计划 Agent 的提交工具调用，人工确认之后才执行）。
     *
     * <p>事务内完成：把该用户旧的生效计划标记为 ENDED（不物理删除）→ 写入新计划。
     *
     * @param userId 用户 ID
     * @param sessionId 计划 Agent 的运行标识
     * @param planContent 计划正文（Markdown，一天一行）
     * @param adjustmentReason 调整原因，重新规划时写清依据；首次生成可为空
     */
    void submitPlan(Long userId, String sessionId, String planContent, String adjustmentReason);

    /**
     * 取走本次生成落库的计划并清空缓冲。
     *
     * @param userId 用户 ID
     * @param sessionId 计划 Agent 的运行标识
     * @return 计划响应，没有提交过时返回 null
     */
    TrainingPlanRespVO consumeSubmittedPlan(Long userId, String sessionId);

    /**
     * 取走本次生成最近一次提交失败的原因。
     *
     * @param userId 用户 ID
     * @param sessionId 计划 Agent 的运行标识
     * @return 失败原因，没有失败过时返回 null
     */
    String consumeSubmitFailure(Long userId, String sessionId);
}
