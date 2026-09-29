package com.wxy.career.tool;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.service.ResumeDiagnosisService;
import com.wxy.career.util.RuntimeContextUserUtil;
import com.wxy.career.vo.ResumeDiagnosisSubmitVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 提交简历诊断结论工具。
 *
 * <p>只读语义：只把模型填好的结构化结论暂存到本次请求的运行态缓冲（按 userId + sessionId 隔离），
 * 不写库、不改简历、不创建新简历。结构化结论用于前端渲染诊断卡片；用户看到的完整诊断正文，
 * 仍由助手按自己的语言给出。
 *
 * <p>字段校验失败时返回可读提示而不是抛异常，让模型能立刻修正后重新提交。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
@Component
public class SubmitResumeDiagnosisTool {

    /**
     * 校验失败时补充的字段口径提示，避免模型反复提交同一份不完整结论。
     */
    private static final String FIELD_HINT =
            "请检查：综合得分与各维度得分为 0-100 的整数、维度至少 4 项、scoreSummary 与 optimizedResume 非空、"
                    + "可能被追问的项目点 3-5 条、resumeId 是本次读到的简历。";

    /**
     * 简历诊断服务。
     */
    @Resource
    private ResumeDiagnosisService resumeDiagnosisService;

    /**
     * 提交结构化诊断结论。
     *
     * @param diagnosis 结构化诊断结论
     * @param runtimeContext 运行时上下文，由框架注入
     * @return 提交结果说明，失败时给出可读原因
     */
    @Tool(name = "submit_resume_diagnosis",
            description = "提交本次简历诊断的结构化结论，供界面渲染诊断卡片。只提交一次；"
                    + "提交后仍需把完整诊断结论（含优化后的简历正文）讲给用户。",
            readOnly = true)
    public String submitResumeDiagnosis(
            @ToolParam(name = "diagnosis", required = true,
                    description = "诊断结论：resumeId、overallScore、scoreSummary、dimensions(至少4项)、"
                            + "problems、highlights、suggestions、optimizedResume、interviewFollowUps(3-5条)")
            ResumeDiagnosisSubmitVO diagnosis,
            RuntimeContext runtimeContext) {
        Long userId = RuntimeContextUserUtil.requireUserId(runtimeContext);
        try {
            resumeDiagnosisService.submitDiagnosis(userId, runtimeContext.getSessionId(), diagnosis);
            return "诊断结论已提交，请继续把完整诊断结论讲给用户";
        } catch (BizException exception) {
            log.info("提交诊断结论失败，userId={}，code={}", userId, exception.getErrorCode().getCode());
            return "提交失败：" + exception.getErrorCode().getMsg() + "。" + FIELD_HINT;
        }
    }
}
