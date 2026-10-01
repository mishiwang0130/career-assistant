package com.wxy.career.middleware;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内子 Agent 任务仓储（计划 Agent 专用）。
 *
 * <p>框架默认的任务仓储是「工作区文件」实现（把任务写成 {@code agents/{agentId}/tasks/{sessionId}.json}），本项目是多用户
 * 网页应用、共用一个工作区，落文件会互相覆盖，也不符合「计划正文只落 MySQL」的约定。计划 Agent 本身不派发子 Agent，
 * 因此这里用一个进程内实现替掉默认实现：**不落任何文件**，也不引入新的存储表。
 *
 * <p>它只承载「框架后台任务」这一技术概念；训练计划的业务事实是 MySQL 里的一条正文记录（{@code training_plan}），
 * 规划态与待确认状态随 AgentState 存 Redis。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Component
public class InMemoryTaskRepository implements TaskRepository {

    /**
     * 任务缓存：键为 {@code userId/taskId}，保证不同用户互不可见。
     */
    private final Map<String, BackgroundTask> tasks = new ConcurrentHashMap<>();

    /**
     * 查询单个任务。
     *
     * @param runtimeContext 运行上下文
     * @param taskId 任务 ID
     * @param subSessionId 子会话 ID，本实现不使用
     * @return 任务，不存在时返回 null
     */
    @Override
    public BackgroundTask getTask(RuntimeContext runtimeContext, String taskId, String subSessionId) {
        return tasks.get(key(runtimeContext, taskId));
    }

    /**
     * 登记一个任务（本项目计划 Agent 不会真的派发子 Agent，这里只保证接口可用且不落盘）。
     *
     * @param runtimeContext 运行上下文
     * @param taskId 任务 ID
     * @param agentId 子 Agent 名
     * @param query 任务文本
     * @param taskRunSpec 运行方式
     * @return 任务句柄
     */
    @Override
    public BackgroundTask putTask(RuntimeContext runtimeContext, String taskId, String agentId,
            String query, TaskRunSpec taskRunSpec) {
        BackgroundTask task = new BackgroundTask(taskId, agentId, new CompletableFuture<>());
        tasks.put(key(runtimeContext, taskId), task);
        return task;
    }

    /**
     * 列出某个子 Agent 的任务。
     *
     * @param runtimeContext 运行上下文
     * @param agentId 子 Agent 名，为空表示不限
     * @param status 状态过滤，可为空
     * @return 任务列表
     */
    @Override
    public Collection<BackgroundTask> listTasks(RuntimeContext runtimeContext, String agentId, TaskStatus status) {
        String prefix = userId(runtimeContext) + "/";
        List<BackgroundTask> matched = new ArrayList<>();
        for (Map.Entry<String, BackgroundTask> entry : tasks.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            BackgroundTask task = entry.getValue();
            if (agentId != null && !agentId.equals(task.getAgentId())) {
                continue;
            }
            if (status != null && status != task.getTaskStatus()) {
                continue;
            }
            matched.add(task);
        }
        return matched;
    }

    /**
     * 取消任务。
     *
     * @param runtimeContext 运行上下文
     * @param taskId 任务 ID
     * @param subSessionId 子会话 ID，本实现不使用
     * @return true 表示命中并已取消
     */
    @Override
    public boolean cancelTask(RuntimeContext runtimeContext, String taskId, String subSessionId) {
        BackgroundTask task = tasks.remove(key(runtimeContext, taskId));
        return task != null && task.cancel(true);
    }

    /**
     * 构造缓存键。
     *
     * @param runtimeContext 运行上下文
     * @param taskId 任务 ID
     * @return 缓存键
     */
    private String key(RuntimeContext runtimeContext, String taskId) {
        return userId(runtimeContext) + "/" + taskId;
    }

    /**
     * 取用户标识，缺失时用空串占位（框架不会在缺用户时调度任务）。
     *
     * @param runtimeContext 运行上下文
     * @return 用户标识
     */
    private String userId(RuntimeContext runtimeContext) {
        return runtimeContext == null || runtimeContext.getUserId() == null
                ? "" : runtimeContext.getUserId();
    }
}
