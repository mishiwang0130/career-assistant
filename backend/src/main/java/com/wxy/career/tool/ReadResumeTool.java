package com.wxy.career.tool;

import com.wxy.career.service.ResumeDiagnosisService;
import com.wxy.career.util.RuntimeContextUserUtil;
import com.wxy.career.vo.ResumeReadResultVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 读简历工具。
 *
 * <p>F2 简历诊断的只读工具：按简历标识、标题或默认简历取正文，按固定长度分段返回，避免长简历一次
 * 撑爆模型上下文（本项目不启用框架的大结果卸载）。工具只读：不写库、不改简历、不创建新简历。
 *
 * <p>用户身份只从 {@code RuntimeContext.userId} 取，模型传入的任何用户标识都不被信任。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
@Component
public class ReadResumeTool {

    /**
     * 简历诊断服务。
     */
    @Resource
    private ResumeDiagnosisService resumeDiagnosisService;

    /**
     * 读取当前用户的一份简历正文（分段）。
     *
     * @param resumeId 简历 ID，可为空
     * @param title 简历标题或标题中的关键字，可为空
     * @param segment 分段序号，从 1 开始，为空按第 1 段返回
     * @param runtimeContext 运行时上下文，由框架注入
     * @return 读简历结果：是否取到正文、当前段内容、是否还有下一段、可选简历候选
     */
    @Tool(name = "read_resume",
            description = "读取当前登录用户的一份简历正文，按分段返回。不传 resume_id 时读取默认简历；"
                    + "也可以传 title 按标题定位。简历较长时先读第 1 段，再用 segment=2、3……继续读完。"
                    + "返回的 candidates 是无法确定目标时的可选简历列表。",
            readOnly = true)
    public ResumeReadResultVO readResume(
            @ToolParam(name = "resume_id", required = false,
                    description = "简历 ID；已知具体简历时传入，为空时按 title 或默认简历定位")
            Long resumeId,
            @ToolParam(name = "title", required = false,
                    description = "简历标题或标题关键字；用于「用某一份简历诊断」这类说法")
            String title,
            @ToolParam(name = "segment", required = false,
                    description = "分段序号，从 1 开始；上一段返回 hasMore=true 时用下一段序号继续读")
            Integer segment,
            RuntimeContext runtimeContext) {
        Long userId = RuntimeContextUserUtil.requireUserId(runtimeContext);
        ResumeReadResultVO result = resumeDiagnosisService.readResume(userId, resumeId, title, segment);
        log.info("读取简历完成，userId={}，resumeId={}，found={}，segment={}/{}",
                userId, result.getResumeId(), result.isFound(), result.getSegment(), result.getTotalSegments());
        return result;
    }
}
