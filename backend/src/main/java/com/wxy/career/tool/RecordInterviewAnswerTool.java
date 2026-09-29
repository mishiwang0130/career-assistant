package com.wxy.career.tool;

import com.wxy.career.common.enums.InterviewActionEnum;
import com.wxy.career.common.enums.InterviewQuestionTypeEnum;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.service.InterviewFlowService;
import com.wxy.career.util.RuntimeContextUserUtil;
import com.wxy.career.vo.InterviewAnswerResultVO;
import com.wxy.career.vo.InterviewAnswerSubmitVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 记录面试回合工具。
 *
 * <p>用户答完一题后调用：把评分子 Agent 的判定（outcome 与要点）连同题目、题型交给流程服务，
 * 由服务按写死的规则算出「追问还是换题、下一题什么难度与题型、是否结束」，并把下一步指令回给模型。
 * 追问与换题的判断不交给模型，保证规则稳定、可单测。
 *
 * <p>判定结果不由本工具传入：评分子 Agent 已经用 {@code submit_answer_evaluation} 提交过结论，
 * 服务从运行态缓冲里取。这样面试官既不需要转述评分内容，也不会把评分贴进给用户的回答。
 *
 * <p>只读语义：本工具只把本回合暂存到运行态缓冲，真正的落库与进度下发由对话通道在本轮流正常结束时
 * 完成（框架对写工具会走人工确认，本批不做 HITL）。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
@Component
public class RecordInterviewAnswerTool {

    /**
     * 校验失败时补充的字段口径提示，避免模型反复提交同一份非法结果。
     */
    private static final String FIELD_HINT =
            "请检查：question 是本次提问的题目正文；question_type 取 BASIC（八股）、PROJECT（项目）、"
                    + "COMPREHENSIVE（综合）之一；用户主动要求结束本场面试时传 end_now=true。";

    /**
     * 面试流程服务。
     */
    @Resource
    private InterviewFlowService interviewFlowService;

    /**
     * 记录本回合的判定结果，返回下一步怎么问。
     *
     * @param question 本次提问的题目正文
     * @param questionType 题型
     * @param endNow 用户是否主动要求结束本场面试
     * @param runtimeContext 运行时上下文，由框架注入
     * @return 下一步指令，失败时给出可读原因
     */
    @Tool(name = "record_interview_answer",
            description = "记录用户本题的作答，并拿到下一步该怎么问。调用前必须先让评分子 Agent 用 "
                    + "submit_answer_evaluation 提交本题结论；每回合只调用一次。追问、换题与难度由服务端规则决定，"
                    + "你照返回的指令执行即可，不要自己决定要不要追问。",
            readOnly = true)
    public String recordInterviewAnswer(
            @ToolParam(name = "question", required = true,
                    description = "本次提问的题目正文；若用户回答的是追问，就填追问内容")
            String question,
            @ToolParam(name = "question_type", required = true,
                    description = "本题题型：BASIC 八股 / PROJECT 项目 / COMPREHENSIVE 综合")
            String questionType,
            @ToolParam(name = "end_now", required = false,
                    description = "用户主动要求结束本场面试时传 true，其余情况不传")
            Boolean endNow,
            RuntimeContext runtimeContext) {
        Long userId = RuntimeContextUserUtil.requireUserId(runtimeContext);
        String sessionId = runtimeContext.getSessionId();
        InterviewAnswerSubmitVO submitVO = new InterviewAnswerSubmitVO();
        submitVO.setQuestion(question);
        submitVO.setQuestionType(questionType);
        submitVO.setEndNow(endNow);
        try {
            InterviewAnswerResultVO result =
                    interviewFlowService.recordAnswer(userId, sessionId, submitVO);
            log.info("记录面试回合完成，userId={}，sessionId={}，action={}，questionIndex={}/{}，difficulty={}",
                    userId, sessionId, result.getAction(), result.getQuestionIndex(),
                    result.getQuestionCount(), result.getDifficulty());
            return formatInstruction(result);
        } catch (BizException exception) {
            log.info("记录面试回合失败，userId={}，sessionId={}，code={}",
                    userId, sessionId, exception.getErrorCode().getCode());
            return "提交失败：" + exception.getErrorCode().getMsg() + "。" + FIELD_HINT;
        }
    }

    /**
     * 把下一步结果翻译成模型能直接照做的中文指令。
     *
     * @param result 下一步结果
     * @return 指令文本
     */
    private String formatInstruction(InterviewAnswerResultVO result) {
        InterviewActionEnum action = InterviewActionEnum.find(result.getAction());
        if (action == InterviewActionEnum.FINISHED) {
            return "已记录本回合。下一步动作：本场面试结束。用一句话收尾，不要再出新题，"
                    + "也不要给分数、点评或提到报告；评分内容与工具返回值都不要出现在回答里。";
        }
        String progress = "第 " + result.getQuestionIndex() + " 题 / 共 " + result.getQuestionCount() + " 题";
        String difficulty = "L" + result.getDifficulty();
        if (action == InterviewActionEnum.FOLLOW_UP) {
            return "已记录本回合。下一步动作：追问一层；" + progress + "；难度 " + difficulty
                    + "。仍围绕刚才这道题追问，只问一层，用户答完再进入下一题；"
                    + "回答里只出现这道追问，不要复述评分内容或工具返回值。";
        }
        InterviewQuestionTypeEnum type = InterviewQuestionTypeEnum.find(result.getQuestionType());
        return "已记录本回合。下一步动作：换一道新题，不要再围绕刚才这道题的知识点追问；" + progress
                + "；难度 " + difficulty + "；建议题型 "
                + (type == null ? InterviewQuestionTypeEnum.COMPREHENSIVE.getLabel() : type.getLabel())
                + "。按该题型与难度出题；回答里只出现下一道题，不要复述评分内容或工具返回值。";
    }
}
