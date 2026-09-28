package com.wxy.career.middleware;

import com.wxy.career.service.SystemPromptProvider;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 系统提示词中间件。
 *
 * <p>每次调用 Agent 时都重新读取配置中的系统提示词，保证提示词调整后无需重建 Agent 即可生效；
 * 读取失败时沿用 Agent 自身已装配的提示词，不影响主流程。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Slf4j
@Component
public class SystemPromptMiddleware implements MiddlewareBase {

    /**
     * 中间件顺序，需先于埋点中间件执行以便埋点记录最终提示词。
     */
    private static final int MIDDLEWARE_ORDER = 10;

    /**
     * 系统提示词提供者。
     */
    @Resource
    private SystemPromptProvider systemPromptProvider;

    /**
     * 中间件顺序。
     *
     * @return 顺序值，越小越先执行
     */
    @Override
    public int order() {
        return MIDDLEWARE_ORDER;
    }

    /**
     * 用配置中的最新系统提示词覆盖本次调用的提示词。
     *
     * @param agent 当前 Agent
     * @param runtimeContext 运行时上下文
     * @param prompt Agent 装配时的系统提示词
     * @return 最终使用的系统提示词
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext runtimeContext, String prompt) {
        try {
            String currentPrompt = systemPromptProvider.currentPrompt();
            if (currentPrompt != null && !currentPrompt.isBlank()) {
                return Mono.just(currentPrompt);
            }
        } catch (Exception exception) {
            log.warn("读取系统提示词失败，沿用 Agent 装配时的提示词", exception);
        }
        return Mono.just(prompt);
    }
}
