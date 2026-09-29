package com.wxy.career.controller;

import com.wxy.career.common.result.Result;
import com.wxy.career.service.InterviewFlowService;
import com.wxy.career.vo.AssistantChatReqVO;
import com.wxy.career.vo.InterviewStateRespVO;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
