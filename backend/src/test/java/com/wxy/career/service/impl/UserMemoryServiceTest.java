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
 * <p>用桩替换记忆实现，不依赖真实 Mem0：验证写入按类型落库、召回失败按无记忆处理、
 * 以及**所有写入都带 userId**（不存在无用户标识的调用路径）。
 * 薄弱点不写记忆库（落 MySQL 的 {@code knowledge_mastery}），因此这里也没有对应用例。
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
     * 没有用户名（未登录场景）时一律不写：宁可少写一条记忆，也不允许跨用户写。
     */
    @Test
    void shouldNotWriteWithoutUserId() {
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

        userMemoryService.rememberFact(1L, "12", "Redis 分布式锁：已讲实现与续期，卡点在锁误删");
    }

    /**
     * 归档总结（F9）产出的记忆按 FACT 类型写入，带 userId 与会话 ID，内容原样交给适配层。
     */
    @Test
    void shouldRememberFactThroughAdapter() {
        userMemoryService.rememberFact(1L, "12", "Redis 分布式锁：已讲实现与续期，卡点在锁误删");

        verify(adapter).recordForUser(eq(1L), eq("12"), eq(UserLongTermMemoryAdapter.TYPE_FACT),
                eq("Redis 分布式锁：已讲实现与续期，卡点在锁误删"));
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
