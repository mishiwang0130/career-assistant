package com.wxy.career.controller;

import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.result.Result;
import com.wxy.career.service.TrainingPlanGenerationService;
import com.wxy.career.service.TrainingPlanService;
import com.wxy.career.service.TrainingReminderService;
import com.wxy.career.vo.TrainingPlanConfirmReqVO;
import com.wxy.career.vo.TrainingPlanGenerateReqVO;
import com.wxy.career.vo.TrainingPlanRespVO;
import com.wxy.career.vo.TrainingReminderUnreadRespVO;
import com.wxy.career.vo.AssistantChatReqVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 训练计划接口。
 *
 * <p>计划页的数据入口：读取当前计划、生成 / 重新规划（含覆盖确认）、提醒未读与已读。用户身份一律取登录态，
 * 所有查询与写入都带 {@code user_id}。没有「勾选任务」接口——计划就是一份按天正文，没有任务表。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Validated
@RestController
@RequestMapping("/api/plans")
public class TrainingPlanController {

    /**
     * 训练计划服务。
     */
    @Resource
    private TrainingPlanService trainingPlanService;

    /**
     * 训练计划生成服务（两阶段 SSE）。
     */
    @Resource
    private TrainingPlanGenerationService trainingPlanGenerationService;

    /**
     * 训练提醒服务。
     */
    @Resource
    private TrainingReminderService trainingReminderService;

    /**
     * 查询当前用户的训练计划（概览 + 正文 + 今日提醒 + 未读角标）。
     *
     * @return 计划
     */
    @GetMapping("/current")
    public Result<TrainingPlanRespVO> current() {
        return Result.success(trainingPlanService.getCurrentPlan(currentUserId()));
    }

    /**
     * 生成（或重新规划）训练计划。
     *
     * @param reqVO 生成参数
     * @return SSE 响应对象
     */
    @PostMapping(value = "/generation", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generate(@Valid @RequestBody TrainingPlanGenerateReqVO reqVO) {
        return trainingPlanGenerationService.generate(reqVO);
    }

    /**
     * 回填「是否保存这份计划」的确认结论并继续生成。
     *
     * @param reqVO 确认结果
     * @return SSE 响应对象
     */
    @PostMapping(value = "/generation/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter confirmGeneration(@Valid @RequestBody TrainingPlanConfirmReqVO reqVO) {
        return trainingPlanGenerationService.confirm(reqVO);
    }

    /**
     * 查询未读提醒数，用于侧栏与计划页角标。
     *
     * @return 未读条数
     */
    @GetMapping("/reminders/unread-count")
    public Result<TrainingReminderUnreadRespVO> unreadCount() {
        TrainingReminderUnreadRespVO response = new TrainingReminderUnreadRespVO();
        response.setCount(trainingReminderService.unreadCount(currentUserId()));
        return Result.success(response);
    }

    /**
     * 把一条提醒标记为已读。
     *
     * @param reminderId 提醒 ID
     * @return 操作结果
     */
    @PostMapping("/reminders/{reminderId}/read")
    public Result<Void> readReminder(
            @Pattern(regexp = AssistantChatReqVO.SESSION_ID_REGEXP,
                    message = "提醒 ID 只能为数字") @PathVariable String reminderId) {
        trainingReminderService.markRead(currentUserId(), Long.valueOf(reminderId));
        return Result.success();
    }

    /**
     * 把全部未读提醒标记为已读（进入计划页即清零角标）。
     *
     * @return 操作结果
     */
    @PostMapping("/reminders/read-all")
    public Result<Void> readAllReminders() {
        trainingReminderService.markAllRead(currentUserId());
        return Result.success();
    }

    /**
     * 取当前登录用户 ID。
     *
     * @return 用户 ID
     */
    private Long currentUserId() {
        return LoginUserHolder.getUserId();
    }
}
