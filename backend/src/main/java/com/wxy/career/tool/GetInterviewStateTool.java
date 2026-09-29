package com.wxy.career.tool;

import com.wxy.career.service.InterviewFlowService;
import com.wxy.career.util.RuntimeContextUserUtil;
import com.wxy.career.vo.InterviewStateRespVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 读面试状态工具。
 *
 * <p>面试 Agent 每回合开工前调用：拿到当前第几题、总题数、当前难度、是主问题还是追问、下一题的
 * 建议题型，以及本场是否已结束。状态由问答记录在服务端算好，模型不需要自己记进度。
 *
 * <p>只读工具：不写库、不改面试进度。用户身份只从 {@code RuntimeContext.userId} 取。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
@Component
public class GetInterviewStateTool {

    /**
     * 面试流程服务。
     */
    @Resource
    private InterviewFlowService interviewFlowService;

    /**
     * 读取当前这场模拟面试的进度、难度与是否结束。
     *
     * @param runtimeContext 运行时上下文，由框架注入
     * @return 面试状态快照
     */
    @Tool(name = "get_interview_state",
            description = "读取当前这场模拟面试的状态：questionIndex 当前题序、questionCount 总题量、"
                    + "difficulty 当前难度（1-5）、roundNo 轮次（1 主问题 / 2 追问）、"
                    + "recommendedQuestionType 本轮题型的建议、finished 是否已结束、"
                    + "startDifficulty 起始难度。开始提问或判断下一步之前先调用它。",
            readOnly = true)
    public InterviewStateRespVO getInterviewState(RuntimeContext runtimeContext) {
        Long userId = RuntimeContextUserUtil.requireUserId(runtimeContext);
        InterviewStateRespVO state =
                interviewFlowService.getState(userId, runtimeContext.getSessionId());
        log.info("读取面试状态，userId={}，sessionId={}，questionIndex={}/{}，difficulty={}，finished={}",
                userId, runtimeContext.getSessionId(), state.getQuestionIndex(), state.getQuestionCount(),
                state.getDifficulty(), state.getFinished());
        return state;
    }
}
