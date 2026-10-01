package com.wxy.career.service;

import com.wxy.career.vo.InterviewReportRespVO;

/**
 * 面试复盘服务：一回合落库之后的 F6 动作。
 *
 * <p>负责两件事：按本回合知识点沉淀掌握度与薄弱点、面试结束的那一轮启动报告生成。
 * 对话通道只调用本服务，业务细节（掌握度口径、报告状态机）都在 F6 内部；
 * 逐题点评不在这里下发——它随面试结束时的逐题结果一起给用户。
 *
 * @author wxy
 * @date 2026-09-29
 */
public interface InterviewReviewService {

    /**
     * 一回合落库之后：沉淀掌握度与薄弱点；面试结束时启动报告生成。
     *
     * <p>掌握度与长期记忆失败都不影响面试主流程；报告派发失败会把报告置为失败态，由前端给重试入口。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @param finished 本场面试是否已结束
     * @return 面试结束且已启动报告生成时返回报告状态，否则返回 null
     */
    InterviewReportRespVO afterTurnCommitted(Long userId, String sessionId, boolean finished);
}
