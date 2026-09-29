package com.wxy.career.tool;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.service.InterviewFlowService;
import com.wxy.career.util.RuntimeContextUserUtil;
import com.wxy.career.vo.AnswerEvaluationSubmitVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 提交单题评分结论工具。
 *
 * <p>只给评分子 Agent 用：把结构化结论暂存到本次请求的运行态缓冲，面试流程据此决定追问还是换题。
 * 之所以不靠子 Agent 输出 JSON 文本：子 Agent 的正文会被上级 Agent 转述，之前就出现过评分 JSON
 * 直接贴进给用户的回答；走工具提交后子 Agent 只回一句「评分完成」，用户可见的正文里不会出现评分内容。
 *
 * <p>只读语义：不写库、不改面试进度，真正的落库发生在对话通道里本轮流正常结束时。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
@Component
public class SubmitAnswerEvaluationTool {

    /**
     * 校验失败时补充的字段口径提示，避免模型反复提交同一份非法结论。
     */
    private static final String FIELD_HINT =
            "请检查：outcome 取 CORRECT（答到要点）、PARTIAL（有遗漏）、WRONG（不会或答错）之一；"
                    + "score 为 0-100 的整数；comment 是一句话点评。";

    /**
     * 面试流程服务。
     */
    @Resource
    private InterviewFlowService interviewFlowService;

    /**
     * 提交本题的评分结论。
     *
     * @param evaluation 评分结论
     * @param runtimeContext 运行时上下文，由框架注入
     * @return 提交结果说明，失败时给出可读原因
     */
    @Tool(name = "submit_answer_evaluation",
            description = "提交本题的评分结论（结构化字段），面试流程用它判断追问还是换题。只提交一次；"
                    + "提交后正文只回一句「评分完成」，不要输出 JSON、分数或清单，也不要把结论讲给用户。",
            readOnly = true)
    public String submitAnswerEvaluation(
            @ToolParam(name = "evaluation", required = true,
                    description = "评分结论：outcome（CORRECT/PARTIAL/WRONG）、score（0-100）、correctPoints、"
                            + "missingPoints、wrongPoints、expressionIssues、suggestions、knowledgePoints、comment")
            AnswerEvaluationSubmitVO evaluation,
            RuntimeContext runtimeContext) {
        Long userId = RuntimeContextUserUtil.requireUserId(runtimeContext);
        String sessionId = runtimeContext.getSessionId();
        try {
            interviewFlowService.submitEvaluation(userId, sessionId, evaluation);
            return "评分结论已提交，请只回复一句「评分完成」，不要输出结论内容";
        } catch (BizException exception) {
            log.info("提交评分结论失败，userId={}，sessionId={}，code={}",
                    userId, sessionId, exception.getErrorCode().getCode());
            return "提交失败：" + exception.getErrorCode().getMsg() + "。" + FIELD_HINT;
        }
    }
}
