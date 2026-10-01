package com.wxy.career.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 训练计划配置。
 *
 * <p>三块内容：计划生成的可调边界（天数、每日时长、任务数上限）、每日提醒的调度参数（CRON、时区、开关、
 * 单次处理用户上限）、Quartz 的调度器参数（实例名、线程数、表前缀、是否集群）。全部为非敏感配置，按环境
 * 隔离规范在 {@code application.yml} 与三份 Profile 同步补齐。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.training")
public class TrainingProperties {

    /**
     * 计划生成相关配置。
     */
    @Valid
    private Plan plan = new Plan();

    /**
     * 每日提醒相关配置。
     */
    @Valid
    private Reminder reminder = new Reminder();

    /**
     * Quartz 调度器相关配置。
     */
    @Valid
    private Quartz quartz = new Quartz();

    /**
     * 计划生成的输入边界与任务规模上限。
     *
     * @author wxy
     * @date 2026-10-01
     */
    @Data
    public static class Plan {

        /**
         * 「还有几天」的最大值，默认 365；超过该值请求按参数校验失败拒绝。
         */
        @Min(value = 1, message = "计划天数至少为 1")
        @Max(value = 3650, message = "计划天数最多为 3650")
        private int maxDays = 365;

        /**
         * 每天可练时长的最小值（分钟），默认 10；低于该值无法切出有意义的任务。
         */
        @Min(value = 1, message = "每日时长至少为 1 分钟")
        @Max(value = 600, message = "每日时长最多为 600 分钟")
        private int minDailyMinutes = 10;

        /**
         * 每天可练时长的最大值（分钟），默认 600；超过该值请求按参数校验失败拒绝。
         */
        @Min(value = 1, message = "每日时长上限至少为 1 分钟")
        @Max(value = 1440, message = "每日时长最多为 1440 分钟")
        private int maxDailyMinutes = 600;

        /**
         * 一份计划的任务条数上限，默认 200；超出时分配器按天压缩任务数。
         *
         * <p>本项目不启用大结果卸载，计划内容要一次性喂给模型并落库，因此必须有明确上限。
         */
        @Min(value = 1, message = "任务数上限至少为 1")
        @Max(value = 2000, message = "任务数上限最多为 2000")
        private int maxTasks = 200;
    }

    /**
     * 每日提醒的调度与批量处理参数。
     *
     * @author wxy
     * @date 2026-10-01
     */
    @Data
    public static class Reminder {

        /**
         * 每日提醒的 CRON 表达式，默认每天 08:00 触发；部署环境可用 TRAINING_REMINDER_CRON 覆盖。
         */
        @NotBlank(message = "提醒 CRON 不能为空")
        private String cron = "0 0 8 * * ?";

        /**
         * 调度时区，默认 Asia/Shanghai；CRON 的「每天 08:00」按该时区解释。
         */
        @NotBlank(message = "提醒时区不能为空")
        private String zoneId = "Asia/Shanghai";

        /**
         * 是否启用每日提醒调度，默认开启；本地联调时可用 TRAINING_REMINDER_ENABLED 关掉。
         */
        private boolean enabled = true;

        /**
         * 单次提醒任务最多处理的用户数，默认 200；按 userId 升序取前 N 个，防止一次运行撑爆上下文。
         */
        @Min(value = 1, message = "单次提醒用户上限至少为 1")
        @Max(value = 10000, message = "单次提醒用户上限最多为 10000")
        private int maxUsersPerRun = 200;
    }

    /**
     * Quartz 调度器参数。
     *
     * @author wxy
     * @date 2026-10-01
     */
    @Data
    public static class Quartz {

        /**
         * 调度器实例名，默认 career-assistant-quartz；JDBC 存储下与 QRTZ_SCHEDULER_STATE 的主键相关。
         */
        @NotBlank(message = "Quartz 实例名不能为空")
        private String instanceName = "career-assistant-quartz";

        /**
         * 调度线程池大小，默认 5；本项目每天只有一个提醒任务，留出余量即可。
         */
        @Min(value = 1, message = "Quartz 线程数至少为 1")
        @Max(value = 50, message = "Quartz 线程数最多为 50")
        private int threadCount = 5;

        /**
         * 表前缀，默认 QRTZ_；与建表脚本中的表名保持一致。
         */
        @NotBlank(message = "Quartz 表前缀不能为空")
        private String tablePrefix = "QRTZ_";

        /**
         * 是否启用集群模式，默认关闭；本项目单实例部署，开启后需要 Quartz 集群校验与时钟同步。
         */
        private boolean clustered = false;

        /**
         * 是否在启动时自动启动调度器，默认开启；测试环境可关掉，避免真起调度线程。
         */
        private boolean autoStart = true;
    }
}
