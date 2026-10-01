package com.wxy.career.tool;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.service.TrainingReminderService;
import com.wxy.career.vo.TrainingReminderSubmitVO;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 写入当天训练提醒（提醒 Agent 的写出工具）。
 *
 * <p>只服务定时任务：为某一位用户写一条站内提醒。写入是幂等的（同一天同一用户只保留一条），并且服务端会复核
 * 目标用户当天确实有生效计划与任务——没有就直接跳过，不报错、不写库。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Slf4j
@Component
public class SaveTrainingReminderTool {

    /**
     * 训练提醒服务。
     */
    @Resource
    private TrainingReminderService trainingReminderService;

    /**
     * 写入一条站内提醒。
     *
     * @param reminder 提醒内容（目标用户 ID + 正文）
     * @return 处理结果说明
     */
    @Tool(name = "save_training_reminder",
            description = "为一位用户写入今天的站内训练提醒。用户 ID 必须来自 list_planned_users 的结果；"
                    + "正文一句话讲清今天练什么，整条不超过 60 个字。",
            readOnly = false)
    public String saveTrainingReminder(
            @ToolParam(name = "reminder", required = true,
                    description = "提醒：user_id（来自 list_planned_users）+ content（不超过 60 字）")
            TrainingReminderSubmitVO reminder) {
        try {
            boolean saved = trainingReminderService.saveReminder(reminder);
            return saved ? "提醒已保存。" : "该用户今天没有需要提醒的训练任务，已跳过。";
        } catch (BizException exception) {
            log.info("写入训练提醒失败，code={}", exception.getErrorCode().getCode());
            return "写入失败：" + exception.getErrorCode().getMsg()
                    + "。请检查 user_id 是否来自 list_planned_users、内容是否非空。";
        }
    }
}
