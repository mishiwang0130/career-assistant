package com.wxy.career.middleware;

import com.wxy.career.mapper.SysUserMapper;
import com.wxy.career.po.SysUser;
import com.wxy.career.service.SystemPromptProvider;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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
     * 昵称注入行前缀，模型据此知道对话对象是谁。
     */
    private static final String NICKNAME_LINE_PREFIX = "当前对话用户昵称：";

    /**
     * 系统提示词提供者。
     */
    @Resource
    private SystemPromptProvider systemPromptProvider;

    /**
     * 用户 Mapper，用于按运行时上下文中的用户 ID 取昵称。
     */
    @Resource
    private SysUserMapper sysUserMapper;

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
     * 用配置中的最新系统提示词覆盖本次调用的提示词，并追加当前用户昵称。
     *
     * <p>昵称按每次调用现查现拼，不按用户缓存整份提示词：用户改昵称后无需等待缓存过期，
     * 也不会因为缓存导致多用户串号。
     *
     * @param agent 当前 Agent
     * @param runtimeContext 运行时上下文
     * @param prompt Agent 装配时的系统提示词
     * @return 最终使用的系统提示词
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext runtimeContext, String prompt) {
        String basePrompt = prompt;
        try {
            String currentPrompt = systemPromptProvider.currentPrompt();
            if (currentPrompt != null && !currentPrompt.isBlank()) {
                basePrompt = currentPrompt;
            }
        } catch (Exception exception) {
            log.warn("读取系统提示词失败，沿用 Agent 装配时的提示词", exception);
        }
        return Mono.just(appendNickname(basePrompt, runtimeContext));
    }

    /**
     * 在系统提示词结尾追加当前用户昵称。
     *
     * <p>昵称属于补充信息而不是关键约束：取不到用户、昵称为空或查询异常时都只沿用原提示词，
     * 不能因为一次查询失败打断对话。
     *
     * @param prompt 原始系统提示词
     * @param runtimeContext 运行时上下文
     * @return 追加昵称后的系统提示词
     */
    private String appendNickname(String prompt, RuntimeContext runtimeContext) {
        String nickname = resolveNickname(runtimeContext);
        if (!StringUtils.hasText(nickname)) {
            return prompt;
        }
        return prompt + System.lineSeparator() + NICKNAME_LINE_PREFIX + nickname.trim();
    }

    /**
     * 按运行时上下文中的用户 ID 查询昵称。
     *
     * @param runtimeContext 运行时上下文
     * @return 用户昵称，取不到时返回 null
     */
    private String resolveNickname(RuntimeContext runtimeContext) {
        if (runtimeContext == null || !StringUtils.hasText(runtimeContext.getUserId())) {
            return null;
        }
        try {
            Long userId = Long.valueOf(runtimeContext.getUserId());
            SysUser user = sysUserMapper.selectById(userId);
            return user == null ? null : user.getNickname();
        } catch (Exception exception) {
            log.warn("查询当前用户昵称失败，系统提示词不注入昵称", exception);
            return null;
        }
    }
}
