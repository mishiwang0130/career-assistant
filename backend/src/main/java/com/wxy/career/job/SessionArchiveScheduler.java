package com.wxy.career.job;

import com.wxy.career.config.MemoryProperties;
import com.wxy.career.service.SessionArchiveService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.TimeZone;

/**
 * 会话归档总结调度。
 *
 * <p>复用 F7 引入的 Quartz 装配（同一个 JDBC 调度器，任务与触发器与业务共库），按
 * {@code app.memory.archive.cron} 周期触发 {@link SessionArchiveJob}。归档开关关闭时不注册任务，
 * 记忆只读不写。
 *
 * <p>重启后按最新配置重新注册：任务与触发器持久化在 QRTZ_ 表里，直接注册会因「任务已存在」失败，
 * 因此启动时先删同键任务再注册（与训练提醒的处理一致）。
 *
 * @author wxy
 * @date 2026-10-03
 */
@Slf4j
@Component
public class SessionArchiveScheduler {

    /**
     * 归档任务的 JobKey 名称。
     */
    private static final String JOB_NAME = "session-archive";

    /**
     * 归档任务所在的分组；与框架托管任务（agent-scope 扩展的 JobGroup）分开，互不清理。
     */
    private static final String JOB_GROUP = "career-assistant-jobs";

    /**
     * 长期记忆配置（含归档开关、CRON 与时区）。
     */
    @Resource
    private MemoryProperties memoryProperties;

    /**
     * Quartz 调度器，由 {@code TrainingQuartzConfiguration} 手工装配。
     */
    @Resource
    private Scheduler quartzScheduler;

    /**
     * 归档服务，供手动触发复用同一段逻辑。
     */
    @Resource
    private SessionArchiveService sessionArchiveService;

    /**
     * 启动时注册归档任务。
     */
    @PostConstruct
    public void register() {
        MemoryProperties.Archive archive = memoryProperties.getArchive();
        if (!archive.isEnabled()) {
            log.info("会话归档调度已关闭，跳过注册");
            return;
        }
        if (!StringUtils.hasText(archive.getCron())) {
            log.warn("会话归档 CRON 为空，跳过注册");
            return;
        }
        try {
            JobKey jobKey = JobKey.jobKey(JOB_NAME, JOB_GROUP);
            // 先删后建：改 CRON 或重启后要按最新配置生效，旧触发器不清理会一直按老节奏触发。
            if (quartzScheduler.checkExists(jobKey)) {
                quartzScheduler.deleteJob(jobKey);
                log.info("清理遗留的会话归档任务，jobKey={}", jobKey);
            }
            JobDetail job = JobBuilder.newJob(SessionArchiveJob.class)
                    .withIdentity(jobKey)
                    .build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(TriggerKey.triggerKey(JOB_NAME, JOB_GROUP))
                    .withSchedule(CronScheduleBuilder.cronSchedule(archive.getCron())
                            .inTimeZone(TimeZone.getTimeZone(archive.getZoneId()))
                            // 错过触发时间（如停机）时不补跑：下一次扫描会自然带上这批会话。
                            .withMisfireHandlingInstructionDoNothing())
                    .build();
            quartzScheduler.scheduleJob(job, trigger);
            log.info("会话归档调度已注册，cron={}，zone={}", archive.getCron(), archive.getZoneId());
        } catch (Exception exception) {
            // 注册失败不影响应用启动：归档是辅助能力，其余链路照常可用，日志里给出明确原因。
            log.error("会话归档调度注册失败，cron={}，zone={}",
                    archive.getCron(), archive.getZoneId(), exception);
        }
    }

    /**
     * 手动触发一次归档扫描（联调与测试用，不走调度器）。
     *
     * @return 本次归档成功的会话数
     */
    public int runOnce() {
        int archived = sessionArchiveService.archiveQuietSessions();
        log.info("手动触发会话归档完成，成功归档 {} 场会话", archived);
        return archived;
    }
}
