package com.wxy.career.config;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * 训练计划的 Quartz 调度装配。
 *
 * <p>本批首次引入定时任务：调度器用 {@code JobStoreTX} 把任务与触发器持久化到 **业务同一个 MySQL 库**
 * （表前缀 {@code QRTZ_}，建表脚本见 {@code sql/career_assistant.sql} 的 F7 段），应用重启后任务不丢。
 *
 * <p>不使用 {@code spring-boot-starter-quartz}：新增依赖只允许
 * {@code agentscope-extensions-scheduler-quartz}（它传递 quartz 2.5.2），因此这里用 {@link StdSchedulerFactory}
 * 手工装配。数据源用 Quartz 自带的 Hikari 连接提供者，连接参数取自 Spring 的 {@link DataSourceProperties}，
 * 保证与业务同库同账号，不新增依赖也不自建连接池。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Slf4j
@Configuration
public class TrainingQuartzConfiguration {

    /**
     * Quartz JobStore 使用的数据源名（仅在本配置内部使用）。
     */
    private static final String QUARTZ_DATASOURCE_NAME = "careerDs";

    /**
     * Quartz 连接提供者：使用其自带的 Hikari 实现，连接参数来自 Spring 数据源配置。
     */
    private static final String QUARTZ_CONNECTION_PROVIDER = "hikaricp";

    /**
     * Quartz 连接池大小，提醒任务每天只有一次，取小值即可。
     */
    private static final String QUARTZ_MAX_CONNECTIONS = "5";

    /**
     * Quartz 失火阈值（毫秒）：错过触发时间超过该值就按失火策略处理。
     */
    private static final String QUARTZ_MISFIRE_THRESHOLD_MILLIS = "60000";

    /**
     * 训练计划配置（含 Quartz 参数）。
     */
    @Resource
    private TrainingProperties trainingProperties;

    /**
     * 装配 Quartz 调度器。
     *
     * <p>调度器懒启动由本类控制：配置开启自动启动时在这里启动，应用关闭时由 Spring 调用 {@code shutdown}。
     *
     * @param dataSourceProperties Spring 数据源配置，用于让 Quartz 共用业务库
     * @return Quartz 调度器
     * @throws SchedulerException 调度器初始化失败
     */
    @Bean(destroyMethod = "shutdown")
    public Scheduler quartzScheduler(DataSourceProperties dataSourceProperties) throws SchedulerException {
        TrainingProperties.Quartz quartz = trainingProperties.getQuartz();
        Properties properties = new Properties();
        properties.setProperty("org.quartz.scheduler.instanceName", quartz.getInstanceName());
        // 单实例部署：instanceId 用 AUTO 即可，集群时再统一改为固定值并打开 isClustered。
        properties.setProperty("org.quartz.scheduler.instanceId", "AUTO");
        properties.setProperty("org.quartz.threadPool.threadCount", String.valueOf(quartz.getThreadCount()));
        properties.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
        properties.setProperty("org.quartz.jobStore.class", "org.quartz.impl.jdbcjobstore.JobStoreTX");
        properties.setProperty("org.quartz.jobStore.driverDelegateClass",
                "org.quartz.impl.jdbcjobstore.StdJDBCDelegate");
        properties.setProperty("org.quartz.jobStore.tablePrefix", quartz.getTablePrefix());
        properties.setProperty("org.quartz.jobStore.isClustered", String.valueOf(quartz.isClustered()));
        // JobDataMap 里只放字符串（调度扩展自己保证），因此可以用 useProperties 降低序列化风险。
        properties.setProperty("org.quartz.jobStore.useProperties", "true");
        properties.setProperty("org.quartz.jobStore.misfireThreshold", QUARTZ_MISFIRE_THRESHOLD_MILLIS);
        properties.setProperty("org.quartz.jobStore.dataSource", QUARTZ_DATASOURCE_NAME);
        String prefix = "org.quartz.dataSource." + QUARTZ_DATASOURCE_NAME + ".";
        properties.setProperty(prefix + "provider", QUARTZ_CONNECTION_PROVIDER);
        properties.setProperty(prefix + "driver", dataSourceProperties.getDriverClassName());
        properties.setProperty(prefix + "URL", dataSourceProperties.getUrl());
        properties.setProperty(prefix + "user", dataSourceProperties.getUsername());
        properties.setProperty(prefix + "password", dataSourceProperties.getPassword());
        properties.setProperty(prefix + "maxConnections", QUARTZ_MAX_CONNECTIONS);
        Scheduler scheduler = new StdSchedulerFactory(properties).getScheduler();
        if (quartz.isAutoStart() && !scheduler.isStarted()) {
            scheduler.start();
        }
        log.info("Quartz 调度器已装配，instanceName={}，jobStore=jdbc，tablePrefix={}，clustered={}",
                quartz.getInstanceName(), quartz.getTablePrefix(), quartz.isClustered());
        return scheduler;
    }
}
