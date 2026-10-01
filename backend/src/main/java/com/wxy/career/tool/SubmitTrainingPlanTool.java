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
 * 提交训练计划正文（计划 Agent 唯一的写工具）。
 *
 * <p>**参数只有一个正文**（+ 可选的调整原因）：起止日期与每天时长都来自用户在计划页填写的表单，模型只需要写
 * 「第 1 天：今天练什么知识点」这种一天一行的短正文。工具调用的参数越少越稳定——之前让模型吐整份嵌套 JSON、
 * 或逐条报任务，都因为参数太多/太长而反复失败。
 *
 * <p>工具标注为非只读，因此受框架权限管控：调用前会触发人工确认（HITL），**用户确认之后才会落库**，
 * 已有计划也不会被未确认地覆盖。
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
     * 校验失败时补充的口径提示。
     */
    private static final String FIELD_HINT =
            "计划正文请一天一行，写成「第 N 天：今天练什么知识点」，一句话概括即可，不要写题型、难度或时长。"
                    + "修正后请**立即重新调用 submit_training_plan** 提交一次。";

    /**
     * 训练计划服务。
     */
    @Resource
    private TrainingPlanService trainingPlanService;

    /**
     * 提交计划正文。
     *
     * @param planContent 计划正文：一天一行「第 N 天：今天练什么知识点」
     * @param adjustmentReason 调整原因，重新规划时写清依据；首次生成留空
     * @param runtimeContext 运行时上下文，由框架注入
     * @return 提交结果说明，失败时给出可读原因
     */
    @Tool(name = "submit_training_plan",
            description = "提交训练计划正文：一天一行「第 N 天：今天练什么知识点」，一句话概括，正文尽量短。"
                    + "起止日期与每天时长由用户在计划页填写，不需要你填。只提交一次，提交成功后只简要说明取舍。",
            readOnly = false)
    public String submitTrainingPlan(
            @ToolParam(name = "planContent", required = true,
                    description = "计划正文：Markdown，一天一行，例如「第 1 天：Redis 分布式锁——能讲清加锁、"
                            + "续期、释放三步」；不要写题型、难度分档与时长分钟数")
            String planContent,
            @ToolParam(name = "adjustmentReason", required = false,
                    description = "调整原因：重新规划时写清依据（新增薄弱点/进度落后/时间变化），首次生成留空")
            String adjustmentReason,
            RuntimeContext runtimeContext) {
        Long userId = RuntimeContextUserUtil.requireUserId(runtimeContext);
        log.info("收到训练计划提交，userId={}，contentLength={}，hasAdjustmentReason={}",
                userId, planContent == null ? 0 : planContent.length(), adjustmentReason != null);
        try {
            trainingPlanService.submitPlan(
                    userId, runtimeContext.getSessionId(), planContent, adjustmentReason);
            return "训练计划已保存，请用一两句话说明本次的取舍。";
        } catch (BizException exception) {
            log.info("提交训练计划失败，userId={}，code={}", userId, exception.getErrorCode().getCode());
            return "提交失败：" + exception.getErrorCode().getMsg() + "。" + FIELD_HINT;
        } catch (Exception exception) {
            // 兜底：数据库异常、空指针一类非业务异常不能让整轮直接崩（框架会把工具异常标记成 ERROR 并中断这一轮），
            // 这里转成可读提示让模型改正或重提，同时把堆栈打进日志便于定位。
            log.error("提交训练计划出现非业务异常，userId={}", userId, exception);
            return "提交失败：服务端处理计划时出错（" + exception.getClass().getSimpleName()
                    + "）。请稍后重试。" + FIELD_HINT;
        }
    }
}
