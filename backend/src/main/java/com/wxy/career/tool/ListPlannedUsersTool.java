package com.wxy.career.tool;

import com.wxy.career.service.TrainingReminderService;
import com.wxy.career.vo.PlannedUsersResultVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import io.agentscope.core.tool.Tool;

/**
 * 列出今天需要提醒的用户（提醒 Agent 的只读工具）。
 *
 * <p>返回的是提醒文案所需的训练简报：用户 ID、目标岗位、剩余天数、今天的任务与昨天未完成数。不返回昵称、账号、
 * 简历等与文案无关的个人信息，避免提醒链路接触到多余的用户数据。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Slf4j
@Component
public class ListPlannedUsersTool {

    /**
     * 训练提醒服务。
     */
    @Resource
    private TrainingReminderService trainingReminderService;

    /**
     * 取出今天需要提醒的用户简报。
     *
     * @return 用户简报列表，没有需要提醒的用户时返回空状态
     */
    @Tool(name = "list_planned_users",
            description = "列出今天需要提醒的用户及其训练简报（用户 ID、目标岗位、剩余天数、今天的任务、"
                    + "昨天未完成数）。没有用户需要提醒时返回空列表，此时不要生成任何提醒。",
            readOnly = true)
    public PlannedUsersResultVO listPlannedUsers() {
        PlannedUsersResultVO result = trainingReminderService.listPlannedUsers();
        log.info("提醒任务读取用户简报完成，count={}，hasData={}", result.getCount(), result.isHasData());
        return result;
    }
}
