package com.wxy.career.service;

import com.wxy.career.vo.TrainingPlanRespVO;
import com.wxy.career.vo.TrainingPlanSubmitVO;

/**
 * 训练计划服务。
 *
 * <p>计划正文只落 MySQL（{@code training_plan} + {@code training_task}），不往服务器工作区写文件；薄弱点只从
 * {@code knowledge_mastery} 读，不查记忆库。写入路径只有一条：计划 Agent 通过提交工具调用
 * {@link #submitPlan(Long, String, TrainingPlanSubmitVO)}，服务端校验后覆盖生成。
 *
 * @author wxy
 * @date 2026-10-01
 */
public interface TrainingPlanService {

    /**
     * 查询当前用户生效中的计划（含按天任务、今日提醒与未读角标）。
     *
     * <p>没有生效计划时返回 {@code hasPlan=false} 的空状态而不是抛异常：计划页据此展示生成入口。
     * 剩余天数由截止日期与今天实时算出，不落库。
     *
     * @param userId 用户 ID
     * @return 计划概览
     */
    TrainingPlanRespVO getCurrentPlan(Long userId);

    /**
     * 判断用户当前是否有生效中的计划。
     *
     * <p>生成链路用它决定是否需要人工确认：有生效计划时要先确认才能覆盖。
     *
     * @param userId 用户 ID
     * @return true 表示有生效计划
     */
    boolean hasActivePlan(Long userId);

    /**
     * 记下本次生成请求的输入（天数与每日时长）。
     *
     * <p>这两个量以**用户本次请求**为准：计划 Agent 填错或漏填都不该让计划落不了库，也不该改变用户输入的周期。
     * 生成开始时登记一次，提交计划时按它落库（模型提交的同名字段只作为对照）。
     *
     * @param userId 用户 ID
     * @param sessionId 计划 Agent 的运行标识
     * @param days 本次请求的天数
     * @param dailyMinutes 本次请求的每日时长（分钟）
     */
    void recordGenerationInput(Long userId, String sessionId, int days, int dailyMinutes);

    /**
     * 勾选或取消勾选一条训练任务。
     *
     * @param userId 用户 ID
     * @param taskId 任务 ID
     * @param finished 目标状态
     */
    void finishTask(Long userId, Long taskId, boolean finished);

    /**
     * 落库一份计划结论（计划 Agent 的提交工具调用）。
     *
     * <p>事务内完成三件事：把该用户旧的生效计划标记为 ENDED（不物理删除）、写入新计划、写入按天任务。
     * 校验不通过时抛业务异常，由工具转成可读提示让模型修正后重新提交。
     *
     * @param userId 用户 ID
     * @param sessionId 计划 Agent 的运行标识，用于暂存本次产物
     * @param submitVO 计划结论
     */
    void submitPlan(Long userId, String sessionId, TrainingPlanSubmitVO submitVO);

    /**
     * 取走本次生成落库的计划并清空缓冲。
     *
     * <p>生成流结束时用它把结构化的计划下发给前端；没有提交过（模型没调用提交工具）时返回 null。
     *
     * @param userId 用户 ID
     * @param sessionId 计划 Agent 的运行标识
     * @return 计划响应，没有提交过时返回 null
     */
    TrainingPlanRespVO consumeSubmittedPlan(Long userId, String sessionId);

    /**
     * 取走本次生成最近一次提交失败的原因。
     *
     * <p>模型提交的计划不满足结构要求时，工具只能返回一段提示给模型，生成流本身是「正常结束但没有产出」。
     * 生成流结束时会用本方法取走原因，拼进给用户的失败提示里，避免只看到一句「没有生成出可用的计划」。
     *
     * @param userId 用户 ID
     * @param sessionId 计划 Agent 的运行标识
     * @return 失败原因，没有失败过时返回 null
     */
    String consumeSubmitFailure(Long userId, String sessionId);
}
