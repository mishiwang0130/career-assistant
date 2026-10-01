package com.wxy.career.tool;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.service.TrainingPlanService;
import com.wxy.career.util.RuntimeContextUserUtil;
import com.wxy.career.vo.TrainingPlanSubmitVO;
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
     * 校验失败时补充的字段口径提示，避免模型反复提交同一份不完整结论。
     */
    private static final String FIELD_HINT =
            "字段名用 schema 里的 camelCase：plan.tasks[].dayIndex、topic、questionType、difficulty、"
                    + "durationMinutes、knowledgePoint。请检查：每天至少一条任务；同一天时长合计不超过每天时长；"
                    + "dayIndex 从 1 开始且不超过总天数；topic 与 questionType 非空。"
                    + "修正后请**立即重新调用 submit_training_plan** 提交一次。";

    /**
     * 训练计划服务。
     */
    @Resource
    private TrainingPlanService trainingPlanService;

    /**
     * 提交训练计划结论。
     *
     * @param plan 计划结论
     * @param runtimeContext 运行时上下文，由框架注入
     * @return 提交结果说明，失败时给出可读原因
     */
    @Tool(name = "submit_training_plan",
            description = "提交本次生成的训练计划（按天任务清单）。只提交一次；提交成功后只简要说明取舍，"
                    + "不要重复整份计划。",
            readOnly = false)
    public String submitTrainingPlan(
            @ToolParam(name = "plan", required = true,
                    description = "计划结论：days、dailyMinutes、summary、adjustmentReason（重新规划时写清依据）、"
                            + "tasks（每条含 dayIndex、topic、questionType、difficulty、durationMinutes、"
                            + "knowledgePoint）。字段名与 schema 一致，不要写 snake_case")
            TrainingPlanSubmitVO plan,
            RuntimeContext runtimeContext) {
        Long userId = RuntimeContextUserUtil.requireUserId(runtimeContext);
        // 记录模型实际提交的内容概要：工具调用失败时（尤其是框架在入参转换阶段就报错）日志里要能看出模型填了什么。
        log.info("收到训练计划提交，userId={}，days={}，dailyMinutes={}，taskCount={}",
                userId, plan == null ? null : plan.getDays(),
                plan == null ? null : plan.getDailyMinutes(),
                plan == null || plan.getTasks() == null ? null : plan.getTasks().size());
        try {
            trainingPlanService.submitPlan(userId, runtimeContext.getSessionId(), plan);
            return "训练计划已保存，请用一两句话说明本次的取舍或调整依据。";
        } catch (BizException exception) {
            log.info("提交训练计划失败，userId={}，code={}", userId, exception.getErrorCode().getCode());
            return "提交失败：" + exception.getErrorCode().getMsg() + "。" + FIELD_HINT;
        } catch (Exception exception) {
            // 兜底：数据库异常、空指针一类非业务异常不能让整轮直接崩（框架会把工具异常标记成 ERROR 并中断这一轮），
            // 这里转成可读提示让模型改正或重提，同时把堆栈打进日志便于定位。
            log.error("提交训练计划出现非业务异常，userId={}，days={}，taskCount={}",
                    userId, plan == null ? null : plan.getDays(),
                    plan == null || plan.getTasks() == null ? null : plan.getTasks().size(), exception);
            return "提交失败：服务端处理计划时出错（" + exception.getClass().getSimpleName()
                    + "）。请检查字段与本次输入是否一致后重新提交一次。" + FIELD_HINT;
        }
    }
}
