package com.wxy.career.controller;

import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.common.result.Result;
import com.wxy.career.service.InterviewFlowService;
import com.wxy.career.service.InterviewReportService;
import com.wxy.career.vo.AssistantChatReqVO;
import com.wxy.career.vo.InterviewReportRespVO;
import com.wxy.career.vo.InterviewStateRespVO;
import com.wxy.career.vo.InterviewResultRespVO;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟面试接口。
 *
 * <p>面试的对话本身仍走 {@code /api/assistant/chat}（按会话场景自动路由到面试 Agent），这里只提供
 * 进度读取：刷新页面、离开再回来时用它恢复「第 n 题 / 共 N 题、当前难度」。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Validated
@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    /**
     * 面试流程服务。
     */
    @Resource
    private InterviewFlowService interviewFlowService;

    /**
     * 面试报告服务（F6）：报告状态查询与失败重试。
     */
    @Resource
    private InterviewReportService interviewReportService;

    /**
     * 读取当前用户的面试进度与当前难度。
     *
     * @param sessionId 面试会话 ID
     * @return 面试状态
     */
    @GetMapping("/{sessionId}")
    public Result<InterviewStateRespVO> state(
            @PathVariable
            @Pattern(regexp = AssistantChatReqVO.SESSION_ID_REGEXP,
                    message = AssistantChatReqVO.SESSION_ID_PATTERN_MESSAGE)
            String sessionId) {
        return Result.success(interviewFlowService.getCurrentUserState(sessionId));
    }

    /**
     * 读取面试结果：逐题明细（哪里答得不好、标准答案）与整体统计。
     *
     * <p>面试结束时也会用 SSE 的 {@code result} 事件下发同一份数据，这条接口用于刷新页面与回看历史会话。
     *
     * @param sessionId 面试会话 ID
     * @return 面试结果
     */
    @GetMapping("/{sessionId}/result")
    public Result<InterviewResultRespVO> result(
            @PathVariable
            @Pattern(regexp = AssistantChatReqVO.SESSION_ID_REGEXP,
                    message = AssistantChatReqVO.SESSION_ID_PATTERN_MESSAGE)
            String sessionId) {
        return Result.success(interviewFlowService.getCurrentUserResult(sessionId));
    }

    /**
     * 读取面试报告：状态（生成中 / 已完成 / 生成失败）+ 错题清单 + 薄弱点清单 + 掌握度 + 面试总结。
     *
     * <p>报告由后台子 Agent 生成，因此这条接口既要能表达生成中，也要在失败时给出原因与重试入口；
     * 刷新页面同样走这条接口拿最新状态。
     *
     * @param sessionId 面试会话 ID
     * @return 面试报告
     */
    @GetMapping("/{sessionId}/report")
    public Result<InterviewReportRespVO> report(
            @PathVariable
            @Pattern(regexp = AssistantChatReqVO.SESSION_ID_REGEXP,
                    message = AssistantChatReqVO.SESSION_ID_PATTERN_MESSAGE)
            String sessionId) {
        return Result.success(interviewReportService.getReport(currentUserId(), sessionId));
    }

    /**
     * 报告生成失败后重试。
     *
     * <p>生成中重试返回 1602，已完成时幂等返回现有报告，面试未结束返回 1601。
     *
     * @param sessionId 面试会话 ID
     * @return 重试后的报告状态
     */
    @PostMapping("/{sessionId}/report/retry")
    public Result<InterviewReportRespVO> retryReport(
            @PathVariable
            @Pattern(regexp = AssistantChatReqVO.SESSION_ID_REGEXP,
                    message = AssistantChatReqVO.SESSION_ID_PATTERN_MESSAGE)
            String sessionId) {
        return Result.success(interviewReportService.retry(currentUserId(), sessionId));
    }

    /**
     * 取当前登录用户 ID。
     *
     * @return 用户 ID
     */
    private Long currentUserId() {
        Long userId = LoginUserHolder.getUserId();
        if (userId == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
        return userId;
    }
}
