package com.wxy.career.config;

import jakarta.validation.Valid;
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
}
