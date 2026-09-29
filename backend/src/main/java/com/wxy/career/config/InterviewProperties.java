package com.wxy.career.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 模拟面试配置。
 *
 * <p>题量是可调参数（不写进提示词，也不写进 Skill），上下文压缩阈值同样在这里配置：面试是本项目
 * 最长的会话，按实际轮次调阈值并在 {@code docs/技术约定.md} 的「模拟面试（F5）」章节登记取值理由。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.interview")
public class InterviewProperties {

    /**
     * 一场面试的主问题数量，默认 8；追问不占题量。
     */
    @Min(value = 1, message = "面试题量至少为 1")
    @Max(value = 20, message = "面试题量最多为 20")
    private int questionCount = 8;

    /**
     * 上下文压缩配置。
     */
    @Valid
    private Compaction compaction = new Compaction();

    /**
     * 面试会话的上下文压缩阈值。
     *
     * @author wxy
     * @date 2026-09-29
     */
    @Data
    public static class Compaction {

        /**
         * 上下文消息数达到该值时触发压缩，即框架 CompactionConfig.triggerMessages。
         */
        @Min(value = 4, message = "压缩触发消息数至少为 4")
        private int triggerMessages = 40;

        /**
         * 压缩后保留的最近消息数，即框架 CompactionConfig.keepMessages；至少要覆盖当前这道题与上一轮问答。
         */
        @Min(value = 4, message = "压缩后保留消息数至少为 4")
        private int keepMessages = 16;
    }
}
