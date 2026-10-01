package com.wxy.career.tool;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.service.InterviewReportService;
import com.wxy.career.util.RuntimeContextUserUtil;
import com.wxy.career.vo.InterviewReportSubmitVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 提交面试报告结论工具。
 *
 * <p>只给报告子 Agent（{@code report-writer}）用：把结构化报告落进 {@code interview_report}。
 * 报告不靠子 Agent 的正文输出——它的正文会被框架当作子 Agent 事件丢弃，也不会出现在用户可见的回答里；
 * 走工具提交后，报告内容只从这条结构化通道落库。
 *
 * <p>会话 ID 由派发文本给出、模型原样填回，服务端按 {@code (user_id, session_id, status)} 校验归属与状态，
 * 跨账号或没有待生成的报告一律按「会话不存在」拒绝。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
@Component
public class SubmitInterviewReportTool {

    /**
     * 校验失败时补充的字段口径提示，避免模型反复提交同一份非法结论。
     */
    private static final String FIELD_HINT =
            "请检查：sessionId 填材料里给出的会话 ID；summary 是面试总结正文（3-5 句）；"
                    + "highlights 是 2-3 条亮点；suggestions 是 3-5 条下一步建议。";

    /**
     * 面试报告服务。
     */
    @Resource
    private InterviewReportService interviewReportService;

    /**
     * 提交面试报告结论。
     *
     * @param report 报告结论
     * @param runtimeContext 运行时上下文，由框架注入
     * @return 提交结果说明，失败时给出可读原因
     */
    @Tool(name = "submit_interview_report",
            description = "提交本场面试的报告结论（结构化字段）。只提交一次；提交后正文只回一句「报告完成」，"
                    + "不要复述报告内容、不要输出 JSON 或清单。",
            readOnly = true)
    public String submitInterviewReport(
            @ToolParam(name = "report", required = true,
                    description = "报告结论：sessionId（材料里给出的会话 ID）、summary（面试总结正文）、"
                            + "highlights（亮点清单）、suggestions（下一步建议清单）")
            InterviewReportSubmitVO report,
            RuntimeContext runtimeContext) {
        Long userId = RuntimeContextUserUtil.requireUserId(runtimeContext);
        try {
            interviewReportService.submitReport(userId, report);
            return "报告已提交，请只回复一句「报告完成」，不要输出报告内容";
        } catch (BizException exception) {
            log.info("提交面试报告失败，userId={}，code={}", userId, exception.getErrorCode().getCode());
            return "提交失败：" + exception.getErrorCode().getMsg() + "。" + FIELD_HINT;
        }
    }
}
