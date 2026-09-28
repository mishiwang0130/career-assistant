package com.wxy.career.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 与模型配置。
 *
 * <p>系统提示词的正文不写在本配置里：每个 Agent 一份 Markdown 放在 {@code resources/prompts/}，
 * 这里只记录它的位置。提示词随代码版本发布，可 diff、可 review；业务规则类内容走 Skill
 * （MySQL 技能仓库），不占配置项。
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
     * 系统提示词文件位置，支持 Spring 资源前缀；默认指向助手 Agent 的提示词。
     *
     * <p>多 Agent 接入后，每个 Agent 各有一份提示词文件，按 Agent 标识取不同的位置。
     */
    private String promptLocation = "classpath:prompts/assistant.md";

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
