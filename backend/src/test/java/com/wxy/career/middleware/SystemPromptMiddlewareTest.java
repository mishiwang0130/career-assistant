package com.wxy.career.middleware;

import com.wxy.career.mapper.SysUserMapper;
import com.wxy.career.po.SysUser;
import com.wxy.career.service.SystemPromptProvider;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 系统提示词中间件测试。
 *
 * <p>覆盖昵称注入的三条路径：查到昵称时追加一行、查不到或查询异常时沿用原提示词，
 * 保证昵称只是补充信息，不会因为一次查询失败打断对话。
 *
 * @author wxy
 * @date 2026-09-28
 */
class SystemPromptMiddlewareTest {

    /**
     * 基础提示词，模拟提示词文件内容。
     */
    private static final String BASE_PROMPT = "基础系统提示词";

    /**
     * 用户 Mapper。
     */
    private SysUserMapper sysUserMapper;

    /**
     * 被测中间件。
     */
    private SystemPromptMiddleware systemPromptMiddleware;

    /**
     * 初始化中间件依赖。
     */
    @BeforeEach
    void setUp() {
        sysUserMapper = mock(SysUserMapper.class);
        systemPromptMiddleware = new SystemPromptMiddleware();
        SystemPromptProvider systemPromptProvider = () -> BASE_PROMPT;
        ReflectionTestUtils.setField(systemPromptMiddleware, "systemPromptProvider", systemPromptProvider);
        ReflectionTestUtils.setField(systemPromptMiddleware, "sysUserMapper", sysUserMapper);
    }

    /**
     * 验证按运行时上下文的用户 ID 查到昵称时追加到提示词结尾。
     */
    @Test
    void shouldAppendNicknameToPrompt() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setNickname("Alice");
        when(sysUserMapper.selectById(1L)).thenReturn(user);

        String prompt = systemPromptMiddleware
                .onSystemPrompt(null, buildContext("1"), "装配时的提示词")
                .block();

        assertThat(prompt).isEqualTo(
                BASE_PROMPT + System.lineSeparator() + "当前对话用户昵称：Alice");
    }

    /**
     * 验证用户不存在时沿用原提示词。
     */
    @Test
    void shouldKeepPromptWhenUserMissing() {
        when(sysUserMapper.selectById(1L)).thenReturn(null);

        String prompt = systemPromptMiddleware
                .onSystemPrompt(null, buildContext("1"), "装配时的提示词")
                .block();

        assertThat(prompt).isEqualTo(BASE_PROMPT);
    }

    /**
     * 验证查询昵称异常时沿用原提示词，不抛给调用方。
     */
    @Test
    void shouldKeepPromptWhenQueryFails() {
        when(sysUserMapper.selectById(1L)).thenThrow(new RuntimeException("db down"));

        String prompt = systemPromptMiddleware
                .onSystemPrompt(null, buildContext("1"), "装配时的提示词")
                .block();

        assertThat(prompt).isEqualTo(BASE_PROMPT);
    }

    /**
     * 验证缺少运行时上下文时沿用原提示词。
     */
    @Test
    void shouldKeepPromptWithoutRuntimeContext() {
        String prompt = systemPromptMiddleware
                .onSystemPrompt(null, null, "装配时的提示词")
                .block();

        assertThat(prompt).isEqualTo(BASE_PROMPT);
    }

    /**
     * 构造运行时上下文。
     *
     * @param userId 用户 ID
     * @return 运行时上下文
     */
    private RuntimeContext buildContext(String userId) {
        return RuntimeContext.builder().userId(userId).sessionId("1").build();
    }
}
