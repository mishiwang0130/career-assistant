package com.wxy.career.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 与模型配置。
 *
 * <p>系统提示词写在本配置中，每次调用 Agent 时实时读取，修改配置后无需重建 Agent。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {

    /**
     * 模型提供方，当前只支持 dashscope。
     */
    private String provider = "dashscope";

    /**
     * 模型 API Key，只允许从环境变量注入。
     */
    private String apiKey;

    /**
     * 常规对话使用的主模型名。
     */
    private String model = "qwen-plus";

    /**
     * 长上下文模型名，供后续压缩、长文本场景使用。
     */
    private String longContextModel = "qwen-max";

    /**
     * 系统提示词，每次调用 Agent 时实时读取。
     */
    private String systemPrompt;

    /**
     * 单次请求允许的最大推理步数，超出后以 error 事件结束。
     */
    private int maxIters = 12;

    /**
     * SSE 流超时时间，单位为秒。
     */
    private long streamTimeoutSeconds = 300L;

    /**
     * Redis 会话状态过期时间，单位为小时。
     */
    private long sessionTtlHours = 168L;
}
