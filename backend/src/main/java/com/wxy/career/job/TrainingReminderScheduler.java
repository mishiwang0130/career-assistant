package com.wxy.career.job;

import com.wxy.career.config.TrainingProperties;
import com.wxy.career.service.AgentFactory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.quartz.QuartzAgentScheduler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 每日训练提醒调度。
 *
 * <p>用 {@code agentscope-extensions-scheduler-quartz} 注册一个 CRON 任务：默认每天 08:00（时区
 * {@code app.training.reminder.zone-id}）触发提醒 Agent 一次，由它遍历「今天有活跃计划且有任务」的用户，
 * 逐个生成站内提醒并幂等落库。提醒 Agent 不是会话，运行结果不回任何对话。
 *
 * <p>启动时会先清掉本扩展在同一 JobGroup 下遗留的任务再重新注册：任务与触发器存在 Quartz 的 JDBC 存储里，
 * 重启后旧记录仍在，直接注册会因「任务已存在」失败；而进程内的任务登记表是空的，旧触发器即使触发也找不到
 * 对应的 Agent。清理后重新注册，保证重启后既能正常触发，也不会出现重复触发。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Slf4j
@Component
public class TrainingReminderScheduler {

    /**
     * 调度扩展使用的 Quartz JobGroup 名，清理遗留任务时按它筛选。
     */
    private static final String AGENT_QUARTZ_JOB_GROUP = "agentscope-quartz";

    /**
     * 触发提醒时送给 Agent 的任务文本。
     */
    private static final String REMINDER_TASK_TEXT =
            "为今天需要训练的用户生成站内提醒（先读取待提醒用户简报，再逐个写入提醒）。";

    /**
     * 训练计划配置。
     */
    @Resource
    private TrainingProperties trainingProperties;

    /**
     * Agent 工厂：提供提醒 Agent 的调度配置。
     */
    @Resource
    private AgentFactory agentFactory;

    /**
     * Quartz 调度器。
     */
    @Resource
    private Scheduler quartzScheduler;

    /**
     * AgentScope 的 Quartz 调度封装。
     */
    private QuartzAgentScheduler agentScheduler;

    /**
     * 已注册的提醒任务，供手动触发与测试使用。
     */
    private ScheduleAgentTask<?> reminderTask;

    /**
     * 启动时注册每日提醒任务。
     */
    @PostConstruct
    public void register() {
        TrainingProperties.Reminder reminder = trainingProperties.getReminder();
        if (!reminder.isEnabled()) {
            log.info("每日训练提醒调度已关闭，跳过注册");
            return;
        }
        if (!StringUtils.hasText(reminder.getCron())) {
            log.warn("每日训练提醒 CRON 为空，跳过注册");
            return;
        }
        try {
            clearLegacyJobs();
            agentScheduler = QuartzAgentScheduler.builder()
                    .schedulerId(trainingProperties.getQuartz().getInstanceName())
                    .scheduler(quartzScheduler)
                    .autoStart(true)
                    .build();
            RuntimeAgentConfig agentConfig = agentFactory.buildReminderAgentConfig();
            reminderTask = agentScheduler.schedule(
                    agentConfig,
                    ScheduleConfig.builder()
                            .cron(reminder.getCron())
                            .zoneId(reminder.getZoneId())
                            .build(),
                    Msg.builder().role(MsgRole.USER).textContent(REMINDER_TASK_TEXT).build());
            log.info("每日训练提醒已注册，cron={}，zone={}，taskId={}",
                    reminder.getCron(), reminder.getZoneId(), reminderTask.getId());
        } catch (Exception exception) {
            // 注册失败不影响应用启动：提醒是辅助功能，其余链路照常可用，日志里给出明确原因。
            log.error("每日训练提醒注册失败，cron={}，zone={}",
                    reminder.getCron(), reminder.getZoneId(), exception);
        }
    }

    /**
     * 手动触发一次提醒任务（联调与测试用）。
     *
     * @return 任务执行结果文本，未注册时返回 null
     */
    public String runOnce() {
        if (reminderTask == null) {
            log.warn("提醒任务未注册，忽略本次手动触发");
            return null;
        }
        try {
            Object result = reminderTask.run().block();
            log.info("手动触发提醒任务完成，result={}", result);
            return result == null ? null : result.toString();
        } catch (Exception exception) {
            log.error("手动触发提醒任务失败", exception);
            return null;
        }
    }

    /**
     * 应用关闭时释放调度资源。
     */
    @PreDestroy
    public void shutdown() {
        if (agentScheduler != null) {
            try {
                agentScheduler.shutdown();
            } catch (Exception exception) {
                log.warn("关闭提醒调度器失败", exception);
            }
        }
    }

    /**
     * 清理扩展遗留的调度任务，保证重启后重新注册不冲突。
     *
     * @throws SchedulerException 访问调度器失败
     */
    private void clearLegacyJobs() throws SchedulerException {
        Set<JobKey> jobKeys = quartzScheduler.getJobKeys(GroupMatcher.jobGroupEquals(AGENT_QUARTZ_JOB_GROUP));
        for (JobKey jobKey : jobKeys) {
            quartzScheduler.deleteJob(jobKey);
            log.info("清理遗留的调度任务，jobKey={}", jobKey);
        }
    }
}
