package com.wxy.career.util;

import com.wxy.career.config.AgentProperties;
import org.springframework.util.StringUtils;

/**
 * Agent 配置校验。
 *
 * <p>把配置错误暴露在启动阶段：provider 取值非法或缺少 API Key 时直接终止启动，
 * 避免运行期才出现空指针或认证失败。
 *
 * @author wxy
 * @date 2026-09-28
 */
public final class AgentSettingsValidator {

    /**
     * 当前唯一支持的模型提供方。
     */
    public static final String PROVIDER_DASHSCOPE = "dashscope";

    /**
     * 工具类禁止实例化。
     */
    private AgentSettingsValidator() {
    }

    /**
     * 校验 Agent 配置。
     *
     * @param properties Agent 配置
     * @throws IllegalStateException 配置不合法
     */
    public static void validate(AgentProperties properties) {
        if (properties == null) {
            throw new IllegalStateException("app.agent 配置不能为空");
        }
        String provider = properties.getProvider();
        if (!PROVIDER_DASHSCOPE.equals(provider)) {
            throw new IllegalStateException(
                    "app.agent.provider 只支持 " + PROVIDER_DASHSCOPE + "，当前值为：" + provider);
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException(
                    "app.agent.provider=" + PROVIDER_DASHSCOPE + " 时必须配置 DASHSCOPE_API_KEY 环境变量");
        }
        if (!StringUtils.hasText(properties.getModel())) {
            throw new IllegalStateException("app.agent.model 不能为空");
        }
        if (properties.getMaxIters() <= 0) {
            throw new IllegalStateException("app.agent.max-iters 必须大于 0");
        }
        if (properties.getStreamTimeoutSeconds() <= 0) {
            throw new IllegalStateException("app.agent.stream-timeout-seconds 必须大于 0");
        }
        if (properties.getSessionTtlHours() <= 0) {
            throw new IllegalStateException("app.agent.session-ttl-hours 必须大于 0");
        }
    }
}
