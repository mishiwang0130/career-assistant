package com.wxy.career.service;

import com.wxy.career.vo.InterviewReportRespVO;
import com.wxy.career.vo.InterviewReportSubmitVO;

/**
 * 面试报告服务。
 *
 * <p>报告由后台子 Agent 生成，状态只有生成中 / 已完成 / 生成失败三态：面试结束时创建报告记录并派发任务，
 * 生成完成后由子 Agent 经 {@code submit_interview_report} 回写；错题清单、薄弱点清单与掌握度读取时由
 * {@code interview_qa} + {@code knowledge_mastery} 现算。生成失败可重试，不会卡在「生成中」不动。
 *
 * @author wxy
 * @date 2026-09-29
 */
public interface InterviewReportService {

    /**
     * 面试结束后启动报告生成（幂等：已有进行中的生成不会重复派发）。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @return 报告当前状态
     */
    InterviewReportRespVO startGeneration(Long userId, String sessionId);

    /**
     * 读取报告：生成中 / 已完成 / 生成失败三态 + 错题清单、薄弱点清单、掌握度与面试总结。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @return 报告
     */
    InterviewReportRespVO getReport(Long userId, String sessionId);

    /**
     * 重试生成报告：已完成幂等返回，生成中返回 1602，未结束返回 1601。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @return 报告当前状态
     */
    InterviewReportRespVO retry(Long userId, String sessionId);

    /**
     * 由报告子 Agent 的工具回写报告结论。
     *
     * @param userId 用户 ID
     * @param submitVO 报告结论
     */
    void submitReport(Long userId, InterviewReportSubmitVO submitVO);
}
