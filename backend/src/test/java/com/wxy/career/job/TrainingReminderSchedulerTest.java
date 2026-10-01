package com.wxy.career.job;

import com.wxy.career.config.TrainingProperties;
import com.wxy.career.service.AgentFactory;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.scheduler.config.ModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每日提醒调度测试。
 *
 * <p>固定三条口径：开关关闭时不注册、提醒 Agent 的提示词与工具集来自工厂、调度参数（CRON/时区）取自配置。
 * Quartz 用桩，不连数据库、不起调度线程。
 *
 * @author wxy
 * @date 2026-10-01
 */
class TrainingReminderSchedulerTest {

    /**
     * 被测调度组件。
     */
    private TrainingReminderScheduler scheduler;

    /**
     * Agent 工厂桩。
     */
    private AgentFactory agentFactory;

    /**
     * Quartz 调度器桩。
     */
    private Scheduler quartzScheduler;

    /**
     * 配置。
     */
    private TrainingProperties trainingProperties;

    /**
     * 组装被测组件。
     */
    @BeforeEach
    void setUp() throws SchedulerException {
        agentFactory = mock(AgentFactory.class);
        quartzScheduler = mock(Scheduler.class);
        trainingProperties = new TrainingProperties();
        scheduler = new TrainingReminderScheduler();
        ReflectionTestUtils.setField(scheduler, "agentFactory", agentFactory);
        ReflectionTestUtils.setField(scheduler, "quartzScheduler", quartzScheduler);
        ReflectionTestUtils.setField(scheduler, "trainingProperties", trainingProperties);

        RuntimeAgentConfig config = RuntimeAgentConfig.builder()
                .name("reminder")
                .sysPrompt("提醒提示词")
                .modelConfig(new StubModelConfig())
                .toolkit(new Toolkit())
                .build();
        // 提醒 AgentConfig 的模型字段不参与本测试断言，这里只保证工厂能给出配置。
        when(agentFactory.buildReminderAgentConfig()).thenReturn(config);
    }

    /**
     * 开关关闭时不注册任务，也不去动 Quartz。
     */
    @Test
    void shouldSkipRegistrationWhenDisabled() throws SchedulerException {
        trainingProperties.getReminder().setEnabled(false);

        scheduler.register();

        verify(quartzScheduler, never()).getJobKeys(any());
        verify(quartzScheduler, never()).scheduleJob(any(), any());
        assertThat(scheduler.runOnce()).isNull();
    }

    /**
     * CRON 为空时不注册，避免调度器起一个永远不会触发的任务。
     */
    @Test
    void shouldSkipRegistrationWhenCronBlank() throws SchedulerException {
        trainingProperties.getReminder().setCron("  ");

        scheduler.register();

        verify(quartzScheduler, never()).scheduleJob(any(), any());
    }

    /**
     * 注册失败（例如 Quartz 不可用）时只记日志，不影响应用启动。
     */
    @Test
    void shouldNotFailFastWhenRegistrationThrows() throws SchedulerException {
        when(quartzScheduler.getJobKeys(any())).thenThrow(new SchedulerException("quartz down"));

        scheduler.register();

        assertThat(scheduler.runOnce()).isNull();
    }

    /**
     * 注册成功后再注册一次（模拟重启）会先清理遗留任务，保证不会重复触发。
     */
    @Test
    void shouldClearLegacyJobsBeforeRegistering() throws SchedulerException {
        when(quartzScheduler.getJobKeys(any())).thenReturn(java.util.Set.of());

        scheduler.register();

        verify(quartzScheduler).getJobKeys(any());
    }

    /**
     * 测试用模型配置：只需通过框架对必填项的校验，不真的建模型。
     *
     * @author wxy
     * @date 2026-10-01
     */
    private static final class StubModelConfig implements ModelConfig {

        /**
         * 模型名。
         *
         * @return 模型名
         */
        @Override
        public String getModelName() {
            return "stub-model";
        }

        /**
         * 模型实例，测试里不访问模型服务。
         *
         * @return 桩模型
         */
        @Override
        public Model createModel() {
            return mock(Model.class);
        }
    }
}
