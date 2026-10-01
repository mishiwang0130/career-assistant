package com.wxy.career.service.impl;

import com.wxy.career.middleware.UserLongTermMemoryAdapter;
import com.wxy.career.service.UserMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长期记忆业务入口单测。
 *
 * <p>用桩替换记忆实现，不依赖真实 Mem0：验证薄弱点写入带知识点前缀、召回失败按无记忆处理、
 * 以及**所有写入都带 userId**（不存在无用户标识的调用路径）。
 *
 * @author wxy
 * @date 2026-09-29
 */
class UserMemoryServiceTest {

    /**
     * 记忆实现桩：适配层被 mock 掉，测试只看业务侧调用契约。
     */
    private UserLongTermMemoryAdapter adapter;

    /**
     * 被测服务。
     */
    private UserMemoryService userMemoryService;

    /**
     * 初始化被测服务与桩实现。
     */
    @BeforeEach
    void setUp() {
        adapter = mock(UserLongTermMemoryAdapter.class);
        UserMemoryServiceImpl service = new UserMemoryServiceImpl();
        ReflectionTestUtils.setField(service, "userLongTermMemoryAdapter", adapter);
        userMemoryService = service;
    }

    /**
     * 薄弱点写入：正文带知识点前缀与说明，类型固定为 WEAKNESS，且带 userId 与会话 ID。
     */
    @Test
    void shouldRememberWeaknessWithKnowledgePointPrefix() {
        userMemoryService.rememberWeakness(1L, "12", "Redis 分布式锁", "关键结论说错");

        verify(adapter).recordForUser(eq(1L), eq("12"), eq(UserLongTermMemoryAdapter.TYPE_WEAKNESS),
                eq("薄弱知识点「Redis 分布式锁」：关键结论说错"));
    }

    /**
     * 没有用户名（未登录场景）时一律不写：宁可少写一条记忆，也不允许跨用户写。
     */
    @Test
    void shouldNotWriteWithoutUserId() {
        userMemoryService.rememberWeakness(null, "12", "Redis 分布式锁", "关键结论说错");
        userMemoryService.rememberFact(null, "12", "正在准备秋招");
        userMemoryService.rememberProfile(null, "12", "目标岗位是后端开发");

        verify(adapter, never()).recordForUser(any(), any(), anyString(), anyString());
    }

    /**
     * 空内容不写：画像与事实记忆没有内容时直接跳过，不产生垃圾记忆。
     */
    @Test
    void shouldSkipEmptyContent() {
        userMemoryService.rememberFact(1L, "12", "   ");
        userMemoryService.rememberProfile(1L, "12", null);

        verify(adapter, never()).recordForUser(any(), any(), anyString(), anyString());
    }

    /**
     * 写入抛异常时只记日志、不向调用方抛出：写入失败留待补偿，不影响对话主流程。
     */
    @Test
    void shouldSwallowWriteFailure() {
        doThrow(new IllegalStateException("Mem0 不可用"))
                .when(adapter).recordForUser(anyLong(), anyString(), anyString(), anyString());

        userMemoryService.rememberWeakness(1L, "12", "Redis 分布式锁", "关键结论说错");
    }

    /**
     * 召回不可用时返回空串；召回抛异常时同样按无长期记忆处理。
     */
    @Test
    void shouldDegradeWhenRecallUnavailable() {
        when(adapter.recallForUser(anyLong(), anyString(), anyString())).thenReturn("");
        assertThat(userMemoryService.recall(1L, "12", "Redis 分布式锁")).isEmpty();

        doThrow(new IllegalStateException("Mem0 超时"))
                .when(adapter).recallForUser(anyLong(), anyString(), anyString());
        assertThat(userMemoryService.recall(1L, "12", "Redis 分布式锁")).isEmpty();
    }

    /**
     * 没有用户标识或没有问题时根本不调用记忆实现。
     */
    @Test
    void shouldNotRecallWithoutUserIdOrQuery() {
        assertThat(userMemoryService.recall(null, "12", "Redis 分布式锁")).isEmpty();
        assertThat(userMemoryService.recall(1L, "12", "  ")).isEmpty();

        verify(adapter, never()).recallForUser(any(), any(), any());
    }
}
