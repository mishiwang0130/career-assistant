package com.wxy.career.tool;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.service.TrainingPlanService;
import com.wxy.career.service.TrainingReminderService;
import com.wxy.career.vo.PlannedUsersResultVO;
import com.wxy.career.vo.TrainingReminderSubmitVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
     * 提交成功时返回可读说明，落库走服务层；正文与调整原因原样传给服务。
     */
    @Test
    void shouldSubmitPlanContentThroughService() {
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("7")
                .sessionId("training-plan-7")
                .build();

        String message = submitTrainingPlanTool.submitTrainingPlan(
                "第 1 天：Redis 分布式锁——能讲清加锁、续期、释放三步", "新增薄弱点", runtimeContext);

        assertThat(message).contains("已保存");
        verify(trainingPlanService).submitPlan(eq(7L), eq("training-plan-7"),
                eq("第 1 天：Redis 分布式锁——能讲清加锁、续期、释放三步"), eq("新增薄弱点"));
    }

    /**
     * 校验失败时把业务错误翻译成可读提示，让模型改正后重试。
     */
    @Test
    void shouldTranslateBizExceptionIntoReadableHint() {
        // submitPlan 是 void 方法：桩注入失败要用 doThrow。
        doThrow(new BizException(ErrorConstant.PARAM_ERROR))
                .when(trainingPlanService).submitPlan(any(), anyString(), any(), any());
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("7")
                .sessionId("training-plan-7")
                .build();

        String message = submitTrainingPlanTool.submitTrainingPlan(null, null, runtimeContext);

        assertThat(message).contains("提交失败").contains("参数错误").contains("一天一行");
    }

    /**
     * 提交工具的入参不能带 JSON Schema 类型约束（回归保护）。
     *
     * <p>声明成 {@code String} 时 schema 会带上 {@code type=string}，模型把正文写成数组/对象就会在**进入业务方法之前**
     * 被框架判成 {@code state=ERROR}，业务日志里什么都没有，模型只能反复重试同一条坏入参。
     */
    @Test
    void shouldKeepPlanContentParameterTypeFree() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(submitTrainingPlanTool);

        Map<String, Object> parameters = toolkit.getTool("submit_training_plan").getParameters();
        @SuppressWarnings("unchecked")
        Map<Object, Object> properties = (Map<Object, Object>) parameters.get("properties");
        assertThat(properties).containsKey("planContent");
        @SuppressWarnings("unchecked")
        Map<Object, Object> planContentParam = (Map<Object, Object>) properties.get("planContent");
        assertThat(planContentParam).doesNotContainKey("type");
    }

    /**
     * 模型把正文写成数组（一天一条）时也能归一化成正文。
     *
     * <p>入参声明成 Object 的目的就是让这些写法都进得来；声明成 String 时框架会在入参校验阶段直接判 ERROR，
     * 业务日志里什么都看不到，模型只能反复重试同一条坏入参。
     */
    @Test
    void shouldNormalizeContentWrittenAsArray() {
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("7")
                .sessionId("training-plan-7")
                .build();

        String message = submitTrainingPlanTool.submitTrainingPlan(
                List.of("第 1 天：Redis 分布式锁", "第 2 天：JVM 内存模型"), null, runtimeContext);

        assertThat(message).contains("已保存");
        verify(trainingPlanService).submitPlan(eq(7L), eq("training-plan-7"),
                eq("第 1 天：Redis 分布式锁" + System.lineSeparator() + "第 2 天：JVM 内存模型"), isNull());
    }

    /**
     * 模型把正文包在对象里、键名写成 snake_case 时也能取出来。
     */
    @Test
    void shouldNormalizeContentWrappedInSnakeCaseField() {
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("7")
                .sessionId("training-plan-7")
                .build();

        submitTrainingPlanTool.submitTrainingPlan(
                Map.of("plan_content", "第 1 天：Redis 分布式锁"), "新增薄弱点", runtimeContext);

        verify(trainingPlanService).submitPlan(eq(7L), eq("training-plan-7"),
                eq("第 1 天：Redis 分布式锁"), eq("新增薄弱点"));
    }

    /**
     * 模型按天给列表（每项是对象）时，拼成「第 N 天：……」的正文。
     */
    @Test
    void shouldNormalizeDayListIntoContent() {
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("7")
                .sessionId("training-plan-7")
                .build();

        submitTrainingPlanTool.submitTrainingPlan(
                Map.of("days", List.of(
                        Map.of("topic", "Redis 分布式锁"),
                        Map.of("topic", "JVM 内存模型"))),
                null, runtimeContext);

        verify(trainingPlanService).submitPlan(eq(7L), eq("training-plan-7"),
                eq("第 1 天：Redis 分布式锁" + System.lineSeparator() + "第 2 天：JVM 内存模型"), isNull());
    }

    /**
     * 模型把整份入参写成 JSON 字符串时，先解一层再取正文。
     */
    @Test
    void shouldNormalizeJsonStringPayload() {
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("7")
                .sessionId("training-plan-7")
                .build();

        submitTrainingPlanTool.submitTrainingPlan(
                "{\"planContent\":\"第 1 天：Redis 分布式锁\"}", null, runtimeContext);

        verify(trainingPlanService).submitPlan(eq(7L), eq("training-plan-7"),
                eq("第 1 天：Redis 分布式锁"), isNull());
    }

    /**
     * 非业务异常（数据库异常、空指针一类）也要转成可读提示，不让框架把这一轮直接标记成工具 ERROR。
     */
    @Test
    void shouldTranslateUnexpectedFailure() {
        doThrow(new IllegalStateException("DB down"))
                .when(trainingPlanService).submitPlan(any(), anyString(), any(), any());
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("7")
                .sessionId("training-plan-7")
                .build();

        String message = submitTrainingPlanTool.submitTrainingPlan("第 1 天：Redis 分布式锁——能讲清三步", null, runtimeContext);

        assertThat(message).contains("提交失败").contains("IllegalStateException");
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
        submit.setContent("今天第 2 天：Redis 分布式锁");

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
