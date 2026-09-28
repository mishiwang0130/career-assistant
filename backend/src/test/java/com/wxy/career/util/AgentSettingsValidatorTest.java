package com.wxy.career.util;

import com.wxy.career.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent 配置校验测试。
 *
 * <p>覆盖 provider 非法、缺少 API Key 等启动期配置错误。
 *
 * @author wxy
 * @date 2026-09-28
 */
class AgentSettingsValidatorTest {

    /**
     * 验证 DashScope 缺少 API Key 时给出明确错误。
     */
    @Test
    void shouldRejectBlankApiKey() {
        AgentProperties properties = validProperties();
        properties.setApiKey(" ");

        assertThatThrownBy(() -> AgentSettingsValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    /**
     * 验证非法 provider 时给出明确错误。
     */
    @Test
    void shouldRejectUnsupportedProvider() {
        AgentProperties properties = validProperties();
        properties.setProvider("mock");

        assertThatThrownBy(() -> AgentSettingsValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.agent.provider");
    }

    /**
     * 验证合法配置可以通过校验。
     */
    @Test
    void shouldPassWithDashScopeApiKey() {
        AgentProperties properties = validProperties();

        AgentSettingsValidator.validate(properties);
    }

    /**
     * 验证缺少 API Key 时应用上下文启动失败。
     */
    @Test
    void shouldFailContextStartupWhenApiKeyMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentModelValidationConfiguration.class)
                .withPropertyValues("app.agent.provider=dashscope", "app.agent.api-key=")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * 验证配置完整时应用上下文启动成功。
     */
    @Test
    void shouldStartContextWithDashScopeApiKey() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentModelValidationConfiguration.class)
                .withPropertyValues("app.agent.provider=dashscope", "app.agent.api-key=test-key")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * 构建合法配置。
     *
     * @return Agent 配置
     */
    private AgentProperties validProperties() {
        AgentProperties properties = new AgentProperties();
        properties.setProvider(AgentSettingsValidator.PROVIDER_DASHSCOPE);
        properties.setApiKey("test-key");
        return properties;
    }

    /**
     * 只包含模型校验逻辑的测试配置，等价于 {@code AgentScopeConfiguration#agentModel} 的启动期校验入口。
     *
     * @author wxy
     * @date 2026-09-28
     */
    @Configuration
    @EnableConfigurationProperties(AgentProperties.class)
    static class AgentModelValidationConfiguration {

        /**
         * 模拟模型装配前的配置校验。
         *
         * @param properties Agent 配置
         * @return 校验通过后的占位对象
         */
        @Bean
        Object agentModelValidator(AgentProperties properties) {
            AgentSettingsValidator.validate(properties);
            return new Object();
        }
    }
}
