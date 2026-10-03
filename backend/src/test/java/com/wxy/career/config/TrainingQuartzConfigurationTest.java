package com.wxy.career.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SchedulerContext;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Quartz JobFactory 装配测试。
 *
 * <p>固定两条契约：业务自建的 Job（会话归档）必须由 Spring 容器创建并完成 {@code @Resource} 注入；
 * 框架托管的 Agent 任务不是 Spring Bean，必须原样交给 Quartz 默认工厂，避免影响训练提醒与调度扩展。
 *
 * @author wxy
 * @date 2026-10-03
 */
class TrainingQuartzConfigurationTest {

    /**
     * Spring 管理的 Job 由容器创建，返回的就是容器实例。
     *
     * @throws Exception 构造触发信息失败
     */
    @Test
    @DisplayName("Spring 管理的 Job 由容器创建")
    void shouldCreateSpringManagedJobFromContainer() throws Exception {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        AutowireCapableBeanFactory beanFactory = mock(AutowireCapableBeanFactory.class);
        SpringManagedJob springJob = new SpringManagedJob();
        when(applicationContext.getBeanNamesForType(SpringManagedJob.class, true, false))
                .thenReturn(new String[]{"springManagedJob"});
        when(applicationContext.getAutowireCapableBeanFactory()).thenReturn(beanFactory);
        when(beanFactory.createBean(SpringManagedJob.class)).thenReturn(springJob);

        Job created = new TrainingQuartzConfiguration.SpringAwareJobFactory(applicationContext)
                .newJob(bundle(SpringManagedJob.class), schedulerStub());

        assertThat(created).isSameAs(springJob);
    }

    /**
     * 非 Spring Bean 的 Job 走 Quartz 默认工厂，行为与引入本工厂之前完全一致。
     *
     * @throws Exception 构造触发信息失败
     */
    @Test
    @DisplayName("非 Spring 管理的 Job 交给 Quartz 默认工厂")
    void shouldDelegateFrameworkJobToQuartzDefaultFactory() throws Exception {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeanNamesForType(PlainJob.class, true, false)).thenReturn(new String[0]);

        Job created = new TrainingQuartzConfiguration.SpringAwareJobFactory(applicationContext)
                .newJob(bundle(PlainJob.class), schedulerStub());

        assertThat(created).isInstanceOf(PlainJob.class);
    }

    /**
     * 构造一次触发的 Job 与触发器信息。
     *
     * @param jobClass Job 类
     * @return 触发信息
     */
    private TriggerFiredBundle bundle(Class<? extends Job> jobClass) {
        JobDetail jobDetail = JobBuilder.newJob(jobClass).withIdentity("test", "test").build();
        // Quartz 默认工厂会回填 JobDataMap，这里给触发器带上一个键，模拟框架托管任务的真实形态。
        OperableTrigger trigger = (OperableTrigger) TriggerBuilder.newTrigger()
                .withIdentity("test", "test")
                .usingJobData("probe", "true")
                .build();
        Date now = new Date();
        return new TriggerFiredBundle(jobDetail, trigger, null, false, now, now, null, now);
    }

    /**
     * 构造调度器桩：Quartz 默认工厂会读 {@code scheduler.getContext()}，必须给一个真实的空上下文。
     *
     * @return 调度器桩
     * @throws SchedulerException 桩方法签名声明
     */
    private Scheduler schedulerStub() throws SchedulerException {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getContext()).thenReturn(new SchedulerContext());
        return scheduler;
    }

    /**
     * 模拟 Spring 容器管理的 Job。
     */
    public static class SpringManagedJob implements Job {

        /**
         * 空实现，测试只关心实例来源。
         *
         * @param context Quartz 执行上下文
         */
        @Override
        public void execute(JobExecutionContext context) {
            // 测试桩：不做事。
        }
    }

    /**
     * 模拟框架托管、不由 Spring 容器管理的 Job。
     */
    public static class PlainJob implements Job {

        /**
         * 空实现，测试只关心实例来源。
         *
         * @param context Quartz 执行上下文
         */
        @Override
        public void execute(JobExecutionContext context) {
            // 测试桩：不做事。
        }
    }
}
