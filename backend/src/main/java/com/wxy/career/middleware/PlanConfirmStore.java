package com.wxy.career.middleware;

import com.wxy.career.common.redis.RedisKeyConstants;
import com.wxy.career.common.redis.RedisUtil;
import com.wxy.career.vo.PendingPlanConfirmVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 「覆盖已有计划」待确认状态存储。
 *
 * <p>HITL 的确认请求与恢复是两个请求（计划页不是会话），框架把待确认的回复 ID 持久化在 Agent 会话状态里，
 * 本类把确认所需的工具调用快照放在 Redis：确认请求到达时按快照重建工具调用，交给框架继续执行。放在 Redis
 * 而不是进程内内存，是为了不把链路绑在单个进程的存活上；同时留出 30 分钟有效期，超时后要求重新生成。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Slf4j
@Component
public class PlanConfirmStore {

    /**
     * 待确认状态有效期（分钟）。
     */
    private static final long CONFIRM_TTL_MINUTES = 30L;

    /**
     * 「生成中」标记有效期（分钟），用于避免同一用户并发跑两次生成。
     */
    private static final long GENERATING_TTL_MINUTES = 5L;

    /**
     * Redis 操作封装。
     */
    @Resource
    private RedisUtil redisUtil;

    /**
     * 暂存待确认的计划快照。
     *
     * @param userId 用户 ID
     * @param confirm 待确认快照
     */
    public void savePending(Long userId, PendingPlanConfirmVO confirm) {
        redisUtil.set(pendingKey(userId), confirm, CONFIRM_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 取走待确认快照并清除。
     *
     * @param userId 用户 ID
     * @return 待确认快照，不存在或已过期时返回 null
     */
    public PendingPlanConfirmVO takePending(Long userId) {
        PendingPlanConfirmVO confirm = redisUtil.get(pendingKey(userId), PendingPlanConfirmVO.class);
        if (confirm != null) {
            redisUtil.delete(pendingKey(userId));
        }
        return confirm;
    }

    /**
     * 清除待确认快照。
     *
     * @param userId 用户 ID
     */
    public void clearPending(Long userId) {
        redisUtil.delete(pendingKey(userId));
    }

    /**
     * 尝试标记「该用户正在生成计划」。
     *
     * @param userId 用户 ID
     * @return true 表示占位成功，false 表示已有一次生成在跑
     */
    public boolean markGenerating(Long userId) {
        Boolean marked = redisUtil.setIfAbsent(
                generatingKey(userId), String.valueOf(System.currentTimeMillis()),
                GENERATING_TTL_MINUTES, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(marked);
    }

    /**
     * 释放「生成中」标记。
     *
     * @param userId 用户 ID
     */
    public void releaseGenerating(Long userId) {
        redisUtil.delete(generatingKey(userId));
    }

    /**
     * 待确认快照的 Redis key。
     *
     * @param userId 用户 ID
     * @return Redis key
     */
    private String pendingKey(Long userId) {
        return RedisKeyConstants.PLAN + "confirm:" + userId;
    }

    /**
     * 「生成中」标记的 Redis key。
     *
     * @param userId 用户 ID
     * @return Redis key
     */
    private String generatingKey(Long userId) {
        return RedisKeyConstants.PLAN + "generating:" + userId;
    }
}
