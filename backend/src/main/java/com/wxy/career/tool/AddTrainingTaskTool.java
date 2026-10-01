package com.wxy.career.tool;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.service.TrainingPlanService;
import com.wxy.career.util.RuntimeContextUserUtil;
import com.wxy.career.vo.TrainingTaskSubmitVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 逐条登记训练任务（计划 Agent 的轻量写工具）。
 *
 * <p>为什么拆成逐条：`submit_training_plan` 原本要求模型一次性吐出一整份嵌套 JSON（7 天 × 2 条任务 ≈ 2~4KB），
 * 工具调用的参数越大越容易出问题（截断、转义、类型漂移），线上就出现过模型反复提交失败、只能不停重试的情况。
 * 现在改成**每次只报一条任务、参数全是扁平标量**，最后再调用提交工具统一校验落库。
 *
 * <p>只读语义：本工具只把任务暂存到本次运行的缓冲里（按 {@code userId + sessionId} 隔离），**不写库**，
 * 因此不需要人工确认；真正落库的是 `submit_training_plan`，它受权限确认管控。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Slf4j
@Component
public class AddTrainingTaskTool {

    /**
     * 训练计划服务。
     */
    @Resource
    private TrainingPlanService trainingPlanService;

    /**
     * 登记一条当天的训练任务。
     *
     * @param dayIndex 第几天，从 1 开始
     * @param topic 训练主题
     * @param questionType 题型
     * @param difficulty 难度，1-5
     * @param durationMinutes 预计时长（分钟）
     * @param knowledgePoint 对应知识点，可为空
     * @param runtimeContext 运行时上下文，由框架注入
     * @return 登记结果说明
     */
    @Tool(name = "add_training_task",
            description = "逐条登记训练任务：每次只报一条，参数都是简单值。把当天所有任务都登记完，"
                    + "再调用 submit_training_plan 一次性提交落库。",
            readOnly = true)
    public String addTrainingTask(
            @ToolParam(name = "dayIndex", required = true, description = "第几天，从 1 开始，不超过总天数")
            Integer dayIndex,
            @ToolParam(name = "topic", required = true, description = "训练主题，例如「Redis 分布式锁补齐」")
            String topic,
            @ToolParam(name = "questionType", required = true, description = "题型：八股/项目/综合")
            String questionType,
            @ToolParam(name = "difficulty", required = true, description = "难度，1-5，按天递进")
            Integer difficulty,
            @ToolParam(name = "durationMinutes", required = true, description = "预计时长（分钟），整数")
            Integer durationMinutes,
            @ToolParam(name = "knowledgePoint", required = false, description = "对应的知识点名称，可为空")
            String knowledgePoint,
            RuntimeContext runtimeContext) {
        Long userId = RuntimeContextUserUtil.requireUserId(runtimeContext);
        TrainingTaskSubmitVO task = new TrainingTaskSubmitVO();
        task.setDayIndex(dayIndex);
        task.setTopic(topic);
        task.setQuestionType(questionType);
        task.setDifficulty(difficulty);
        task.setDurationMinutes(durationMinutes);
        task.setKnowledgePoint(knowledgePoint);
        try {
            trainingPlanService.stageTrainingTask(userId, runtimeContext.getSessionId(), task);
            return "已登记第 " + dayIndex + " 天的任务：" + topic;
        } catch (BizException exception) {
            return "登记失败：" + exception.getErrorCode().getMsg()
                    + "。请按「第几天 + 主题 + 题型 + 难度 + 时长」重新登记这一条。";
        } catch (Exception exception) {
            log.error("登记训练任务出现非业务异常，userId={}，dayIndex={}", userId, dayIndex, exception);
            return "登记失败：服务端处理时出错，请重新登记这一条。";
        }
    }
}
