package com.wxy.career.tool;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.service.TrainingPlanService;
import com.wxy.career.service.TrainingReminderService;
import com.wxy.career.vo.PlannedUsersResultVO;
import com.wxy.career.vo.TrainingPlanSubmitVO;
import com.wxy.career.vo.TrainingReminderSubmitVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 训练计划链路三个工具的行为测试。
 *
 * <p>工具的作用是把「业务异常」翻译成模型能读懂的可读提示：校验失败不能让整轮生成崩掉，模型要能按提示改正后
 * 重新提交；提醒工具对不该提醒的用户要明确说「已跳过」，避免模型反复重试。
 *
 * @author wxy
 * @date 2026-10-01
 */
class TrainingPlanToolTest {

    /**
     * 提交计划工具。
     */
    private SubmitTrainingPlanTool submitTrainingPlanTool;

    /**
     * 列出待提醒用户工具。
     */
    private ListPlannedUsersTool listPlannedUsersTool;

    /**
     * 写入提醒工具。
     */
    private SaveTrainingReminderTool saveTrainingReminderTool;

    /**
     * 计划服务桩。
     */
    private TrainingPlanService trainingPlanService;

    /**
     * 提醒服务桩。
     */
    private TrainingReminderService trainingReminderService;

    /**
     * 组装工具与依赖。
     */
    @BeforeEach
    void setUp() {
        trainingPlanService = mock(TrainingPlanService.class);
        trainingReminderService = mock(TrainingReminderService.class);
        submitTrainingPlanTool = new SubmitTrainingPlanTool();
        listPlannedUsersTool = new ListPlannedUsersTool();
        saveTrainingReminderTool = new SaveTrainingReminderTool();
        ReflectionTestUtils.setField(submitTrainingPlanTool, "trainingPlanService", trainingPlanService);
        ReflectionTestUtils.setField(listPlannedUsersTool, "trainingReminderService", trainingReminderService);
        ReflectionTestUtils.setField(saveTrainingReminderTool, "trainingReminderService", trainingReminderService);
    }

    /**
     * 提交成功时返回可读说明，落库走服务层。
     */
    @Test
    void shouldSubmitPlanThroughService() {
        var runtimeContext = io.agentscope.core.agent.RuntimeContext.builder()
                .userId("7")
                .sessionId("training-plan-7")
                .build();
        TrainingPlanSubmitVO submit = new TrainingPlanSubmitVO();
        submit.setDays(3);
        submit.setDailyMinutes(60);
        submit.setTasks(List.of());

        String message = submitTrainingPlanTool.submitTrainingPlan(submit, runtimeContext);

        assertThat(message).contains("已保存");
        verify(trainingPlanService).submitPlan(eq(7L), eq("training-plan-7"), any(TrainingPlanSubmitVO.class));
    }

    /**
     * 校验失败时把业务错误翻译成可读提示，让模型改正后重试。
     */
    @Test
    void shouldTranslateBizExceptionIntoReadableHint() {
        // submitPlan 是 void 方法：桩注入失败要用 doThrow。
        doThrow(new BizException(ErrorConstant.PARAM_ERROR))
                .when(trainingPlanService).submitPlan(any(), anyString(), any());
        var runtimeContext = io.agentscope.core.agent.RuntimeContext.builder()
                .userId("7")
                .sessionId("training-plan-7")
                .build();

        String message = submitTrainingPlanTool.submitTrainingPlan(new TrainingPlanSubmitVO(), runtimeContext);

        assertThat(message).contains("提交失败").contains("参数错误");
    }

    /**
     * 只读工具直接返回服务层给出的简报（含空状态）。
     */
    @Test
    void shouldReturnPlannedUsersBriefing() {
        PlannedUsersResultVO result = new PlannedUsersResultVO();
        result.setHasData(false);
        result.setMessage("今天没有需要提醒的用户。");
        when(trainingReminderService.listPlannedUsers()).thenReturn(result);

        assertThat(listPlannedUsersTool.listPlannedUsers()).isSameAs(result);
    }

    /**
     * 写入提醒时把「跳过」与「已保存」区分开，模型据此决定是否继续。
     */
    @Test
    void shouldDistinguishSavedAndSkipped() {
        when(trainingReminderService.saveReminder(any())).thenReturn(true, false);
        TrainingReminderSubmitVO submit = new TrainingReminderSubmitVO();
        submit.setUserId("7");
        submit.setContent("今天练 Redis 分布式锁");

        assertThat(saveTrainingReminderTool.saveTrainingReminder(submit)).contains("已保存");
        assertThat(saveTrainingReminderTool.saveTrainingReminder(submit)).contains("已跳过");
    }

    /**
     * 写入失败（例如 user_id 非法）返回可读提示而不是抛异常。
     */
    @Test
    void shouldTranslateReminderFailure() {
        when(trainingReminderService.saveReminder(any()))
                .thenThrow(new BizException(ErrorConstant.PARAM_ERROR));
        TrainingReminderSubmitVO submit = new TrainingReminderSubmitVO();
        submit.setUserId("abc");
        submit.setContent("内容");

        assertThat(saveTrainingReminderTool.saveTrainingReminder(submit))
                .contains("写入失败")
                .contains("参数错误");
    }
}
