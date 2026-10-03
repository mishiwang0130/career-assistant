package com.wxy.career.job;

import com.wxy.career.config.MemoryProperties;
import com.wxy.career.service.SessionArchiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.springframework.stereotype.Component;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话归档调度装配测试。
 *
 * <p>不启动 Quartz：调度器用桩替换，只固定「开关打开时注册归档任务、关闭时不注册」这条装配约定，
 * 任务本身的行为由 {@code SessionArchiveServiceImplTest} 覆盖。
 *
 * @author wxy
 * @date 2026-10-03
 */
class SessionArchiveSchedulerTest {

    /**
     * 被测调度组件。
     */
    private SessionArchiveScheduler scheduler;

    /**
     * Quartz 调度器桩。
     */
    private Scheduler quartzScheduler;

    /**
     * 归档服务桩。
     */
    private SessionArchiveService sessionArchiveService;

    /**
     * 归档配置。
     */
    private MemoryProperties memoryProperties;

    /**
     * 初始化被测组件与桩。
     *
     * @throws Exception 桩方法签名声明
     */
    @BeforeEach
    void setUp() throws Exception {
        quartzScheduler = mock(Scheduler.class);
        when(quartzScheduler.checkExists(any(org.quartz.JobKey.class))).thenReturn(false);
        sessionArchiveService = mock(SessionArchiveService.class);
        memoryProperties = new MemoryProperties();

        scheduler = new SessionArchiveScheduler();
        ReflectionTestUtils.setField(scheduler, "memoryProperties", memoryProperties);
        ReflectionTestUtils.setField(scheduler, "quartzScheduler", quartzScheduler);
        ReflectionTestUtils.setField(scheduler, "sessionArchiveService", sessionArchiveService);
    }

    /**
     * 开关打开时注册一个 CRON 任务；重启后同键任务会被先清理再注册。
     *
     * @throws Exception 注册任务失败
     */
    @Test
    @DisplayName("归档开关打开时注册 CRON 任务")
    void shouldRegisterCronJob() throws Exception {
        scheduler.register();

        verify(quartzScheduler).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    /**
     * 开关关闭时不注册任务：本地只验证对话链路时后台不调用模型。
     *
     * @throws Exception 注册任务失败
     */
    @Test
    @DisplayName("归档开关关闭时不注册任务")
    void shouldSkipRegistrationWhenDisabled() throws Exception {
        memoryProperties.getArchive().setEnabled(false);

        scheduler.register();

        verify(quartzScheduler, never()).scheduleJob(any(JobDetail.class), any(Trigger.class));
    }

    /**
     * 手动触发直接调用归档服务，不依赖 Quartz 触发。
     */
    @Test
    @DisplayName("手动触发走归档服务")
    void shouldRunOnceThroughService() {
        when(sessionArchiveService.archiveQuietSessions()).thenReturn(2);

        assertThat(scheduler.runOnce()).isEqualTo(2);
    }

    /**
     * 归档 Job 必须是 Spring Bean：调度器只对「容器里存在的 Job 类」走 Spring 创建路径，
     * 漏了 {@code @Component} 就会退回 Quartz 无参构造，注入的归档服务为 null。
     */
    @Test
    @DisplayName("归档 Job 登记为 Spring Bean")
    void shouldRegisterArchiveJobAsSpringBean() {
        assertThat(SessionArchiveJob.class.isAnnotationPresent(Component.class)).isTrue();
    }
}
