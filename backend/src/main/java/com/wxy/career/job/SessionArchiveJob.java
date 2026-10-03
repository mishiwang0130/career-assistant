package com.wxy.career.job;

import com.wxy.career.service.SessionArchiveService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

/**
 * 会话归档总结定时任务。
 *
 * <p>由 {@code SessionArchiveScheduler} 注册到与训练提醒同一个 Quartz 调度器（JDBC 存储、共业务库），
 * 每次触发只做一件事：调用 {@link SessionArchiveService#archiveQuietSessions()} 扫一批安静会话归档。
 *
 * <p>任务实例由 {@code TrainingQuartzConfiguration} 的 Spring 感知 JobFactory 创建，因此
 * {@code @Resource} 注入可用；框架托管的 Agent 任务不走这条路径，仍由 Quartz 默认方式实例化。
 * **必须登记为 Spring Bean**（{@code @Component}）：JobFactory 判断「这个 Job 类是不是容器里的 Bean」时
 * 按类型查找，漏登记会退回 Quartz 默认的无参构造路径，注入的字段全是 null。
 *
 * <p>{@code DisallowConcurrentExecution}：一场扫描可能包含多次模型调用，禁止同一任务并发触发，
 * 避免上一批还没跑完就又扫一遍同一批会话。
 *
 * @author wxy
 * @date 2026-10-03
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class SessionArchiveJob implements Job {

    /**
     * 会话归档服务。
     */
    @Resource
    private SessionArchiveService sessionArchiveService;

    /**
     * 执行一次归档扫描。
     *
     * <p>异常在这里兜住：Quartz 会把未捕获异常记成任务失败并可能反复重试触发器，
     * 而单场会话的失败重试已经由 {@code archive_status} + {@code archive_attempts} 表达。
     *
     * @param context Quartz 执行上下文
     */
    @Override
    public void execute(JobExecutionContext context) {
        try {
            int archived = sessionArchiveService.archiveQuietSessions();
            log.info("会话归档任务执行完成，成功归档 {} 场会话", archived);
        } catch (Exception exception) {
            log.error("会话归档任务执行失败", exception);
        }
    }
}
