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
 * 长期记忆（Mem0）配置。
 *
 * <p>Mem0 服务由项目方自行部署与运维，本窗口只负责把连接配置写好：地址类取值统一用 {@code localhost}
 * 占位（自部署 Mem0 官方默认端口 8000），密钥只从环境变量读，部署后改环境变量即可、不改代码。
 * {@code enabled} 为 true 不代表启动时会连 Mem0：实例是懒建的，服务没起时应用照常启动、对话照常可用，
 * 召回按「无长期记忆」处理、写入只记 warn。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.memory")
public class MemoryProperties {

    /**
     * 是否启用长期记忆读写；false 时所有记忆操作直接跳过，主流程不受影响。
     */
    private boolean enabled = true;

    /**
     * 召回（检索 + 注入前处理）的整体时间预算，单位毫秒。
     *
     * <p>取值 3000：自部署 Mem0 的 search 是「查询 embedding → 向量检索 →（服务侧启用时）LLM 处理」，
     * 服务侧 LLM 开思考时单次常见 1-3 秒，300 毫秒会几乎必然超时、等于把长期记忆功能关掉。超时按
     * 「无长期记忆」降级并记 warn，真实 P95 用环境变量回调即可，不必改代码。
     */
    @Min(value = 1, message = "召回时间预算至少为 1 毫秒")
    private long recallTimeoutMs = 3000L;

    /**
     * Mem0 连接配置。
     */
    @Valid
    private Mem0 mem0 = new Mem0();

    /**
     * 会话归档总结（记忆写入）配置。
     */
    @Valid
    private Archive archive = new Archive();

    /**
     * Mem0 服务连接配置。
     *
     * @author wxy
     * @date 2026-09-29
     */
    @Data
    public static class Mem0 {

        /**
         * Mem0 服务地址；自部署官方默认端口 8000，部署后由环境变量覆盖。
         */
        @NotBlank(message = "Mem0 服务地址不能为空")
        private String baseUrl = "http://localhost:8000";

        /**
         * Mem0 API 密钥；只从环境变量读取，任何 YAML 都不写真实密钥。
         */
        private String apiKey = "";

        /**
         * Mem0 API 类型：自部署用 SELF_HOSTED，官方平台用 PLATFORM。
         */
        @NotBlank(message = "Mem0 API 类型不能为空")
        private String apiType = "SELF_HOSTED";

        /**
         * HTTP 客户端超时，单位毫秒；用于写入路径与单次请求上限。
         */
        @Min(value = 1, message = "Mem0 请求超时至少为 1 毫秒")
        private long timeoutMs = 60000L;
    }

    /**
     * 会话归档总结配置。
     *
     * <p>这是记忆写入的唯一路径：定时任务扫描「已经安静下来」的助手会话，交给归档总结 Agent 提炼
     * 0~3 条结论式记忆，再经 {@code UserMemoryService} 以 {@code FACT} 类型写入 Mem0。
     * 归档只做助手会话（讲解进度与卡点），面试会话的结论已经有结构化表，不重复沉淀。
     *
     * @author wxy
     * @date 2026-10-03
     */
    @Data
    public static class Archive {

        /**
         * 是否启用会话归档扫描；false 时不注册定时任务，记忆只读不写。
         */
        private boolean enabled = true;

        /**
         * 归档扫描的 CRON 表达式，默认每 30 分钟一次；部署环境可用 {@code MEMORY_ARCHIVE_CRON} 覆盖。
         */
        @NotBlank(message = "会话归档 CRON 不能为空")
        private String cron = "0 0/30 * * * ?";

        /**
         * 调度时区，默认 Asia/Shanghai；CRON 按该时区解释。
         */
        @NotBlank(message = "会话归档时区不能为空")
        private String zoneId = "Asia/Shanghai";

        /**
         * 「安静」阈值（分钟）：最后一条用户消息距今超过该值才允许归档，避免归档正在进行的会话。
         */
        @Min(value = 1, message = "会话安静阈值至少为 1 分钟")
        private int quietMinutes = 30;

        /**
         * 单次扫描最多处理的会话数，防止一次运行撑爆模型调用预算与上下文。
         */
        @Min(value = 1, message = "单次归档会话上限至少为 1")
        @Max(value = 200, message = "单次归档会话上限最多为 200")
        private int batchSize = 20;

        /**
         * 单场会话的最大归档尝试次数；模型或 Mem0 持续失败时达到该值即置 FAILED，不再反复重试。
         */
        @Min(value = 1, message = "归档重试上限至少为 1")
        @Max(value = 20, message = "归档重试上限最多为 20")
        private int maxAttempts = 5;

        /**
         * 单场会话最多写入的记忆条数；归档提示词要求 0~3 条，这里是平台侧兜底。
         */
        @Min(value = 1, message = "单场会话记忆条数上限至少为 1")
        @Max(value = 10, message = "单场会话记忆条数上限最多为 10")
        private int maxMemories = 3;

        /**
         * 送给归档 Agent 的对话记录长度上限（字符）；超出只保留最近的对话，避免超出模型上下文。
         */
        @Min(value = 500, message = "归档对话记录长度上限至少为 500 字符")
        private int maxTranscriptChars = 12000;

        /**
         * 单场会话归档（含模型调用与记忆写入）的等待上限，单位秒；超时按失败处理并留待下轮重试。
         */
        @Min(value = 10, message = "归档超时至少为 10 秒")
        private int timeoutSeconds = 120;
    }
}
