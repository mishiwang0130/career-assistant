package com.wxy.career.tool;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.service.TrainingPlanService;
import com.wxy.career.util.RuntimeContextUserUtil;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 提交训练计划工具（计划 Agent 唯一的写工具）。
 *
 * <p>计划正文不落工作区文件：模型把整份按天任务清单通过本工具一次提交，服务端校验后写 MySQL。工具标注为非只读，
 * 因此受框架权限管控：调用前会触发人工确认（HITL），**没有确认就不会写库，已有计划也不会被覆盖**。
 *
 * <p>校验失败时返回可读提示而不是抛异常，让模型能立刻修正后重新提交。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Slf4j
@Component
public class SubmitTrainingPlanTool {

    /**
     * 校验失败时补充的口径提示，避免模型反复提交同一份不完整结论。
     */
    private static final String FIELD_HINT =
            "请检查：每一天（第 1 天到第 N 天）都用 add_training_task 登记过任务；"
                    + "同一天各条时长合计不超过每天可练时长。"
                    + "修正后请**立即重新调用 submit_training_plan** 提交一次。";

    /**
     * 训练计划服务。
     */
    @Resource
    private TrainingPlanService trainingPlanService;

    /**
     * 提交训练计划结论。
     *
     * <p>参数只有两个短文本：任务已经用 {@code add_training_task} 逐条登记过，这里只提交概要与调整原因，
     * 避免让模型一次性吐一整份嵌套 JSON。
     *
     * @param summary 计划概要，一句话说明总体思路
     * @param adjustmentReason 调整原因，重新规划时写清依据；首次生成留空
     * @param runtimeContext 运行时上下文，由框架注入
     * @return 提交结果说明，失败时给出可读原因
     */
    @Tool(name = "submit_training_plan",
            description = "提交本次生成的训练计划（用 add_training_task 登记好的任务会一起落库）。"
                    + "只提交一次；提交成功后只简要说明取舍，不要重复整份计划。",
            readOnly = false)
    public String submitTrainingPlan(
            @ToolParam(name = "summary", required = false,
                    description = "计划概要：一两句话说明总体思路与取舍")
            String summary,
            @ToolParam(name = "adjustmentReason", required = false,
                    description = "调整原因：重新规划时写清依据（新增薄弱点/进度落后/时间变化），首次生成留空")
            String adjustmentReason,
            RuntimeContext runtimeContext) {
        Long userId = RuntimeContextUserUtil.requireUserId(runtimeContext);
        log.info("收到训练计划提交，userId={}，summaryLength={}，hasAdjustmentReason={}",
                userId, summary == null ? 0 : summary.length(), adjustmentReason != null);
        try {
            trainingPlanService.submitStagedPlan(userId, runtimeContext.getSessionId(), summary, adjustmentReason);
            return "训练计划已保存，请用一两句话说明本次的取舍或调整依据。";
        } catch (BizException exception) {
            log.info("提交训练计划失败，userId={}，code={}", userId, exception.getErrorCode().getCode());
            return "提交失败：" + exception.getErrorCode().getMsg() + "。" + FIELD_HINT;
        } catch (Exception exception) {
            log.error("提交训练计划出现非业务异常，userId={}", userId, exception);
            return "提交失败：服务端处理计划时出错（" + exception.getClass().getSimpleName()
                    + "）。请稍后重试。" + FIELD_HINT;
        }
    }
}
