package com.wxy.career.util;

import com.wxy.career.common.sse.SseEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * AgentScope 事件到 SSE 事件的映射。
 *
 * <p>只映射协议中约定的八类事件，其余过程事件（模型调用、文本块起止等）直接忽略。
 * done 事件由流正常结束时统一发送，因此这里不处理 {@code AGENT_END}，避免重复结束事件。
 *
 * <p>{@code result} 事件承载的是**结构化产物**（F2 的简历诊断结论等），由业务侧在流结束时下发；
 * 框架的 {@code AGENT_RESULT} 里只有助手最终文本，纯文本场景按协议不发 result，因此这里不映射它。
 *
 * @author wxy
 * @date 2026-09-28
 */
public final class AgentEventMapper {

    /**
     * 工具类禁止实例化。
     */
    private AgentEventMapper() {
    }

    /**
     * 将 AgentScope 事件映射为 SSE 事件。
     *
     * @param event AgentScope 事件
     * @return SSE 事件，无需推送时返回 null
     */
    public static SseEvent map(AgentEvent event) {
        if (event == null || event.getType() == null) {
            return null;
        }
        AgentEventType type = event.getType();
        switch (type) {
            case TEXT_BLOCK_DELTA:
                return SseEvent.delta(((TextBlockDeltaEvent) event).getDelta());
            case THINKING_BLOCK_DELTA:
                return SseEvent.thinking(((ThinkingBlockDeltaEvent) event).getDelta());
            case TOOL_CALL_START:
                return mapToolStart((ToolCallStartEvent) event);
            case TOOL_CALL_END:
                return mapToolEnd((ToolCallEndEvent) event);
            case SUBAGENT_EXPOSED:
                return mapSubagent((SubagentExposedEvent) event);
            case EXCEED_MAX_ITERS:
                return mapExceedMaxIters((ExceedMaxItersEvent) event);
            default:
                return null;
        }
    }

    /**
     * 判断事件是否由子 Agent 转发而来。
     *
     * <p>框架把子 Agent 的事件转发给父 Agent 时，会给事件打上来源（实测形如 {@code "<父会话Id>/<子Agent名>"}，
     * 例如 {@code 40/job-match}），父 Agent 自己产生的事件来源为空；早期版本只在远端/网关路径写入
     * {@link io.agentscope.core.event.AgentEvent#METADATA_PARENT_SESSION_ID} 元数据，本地同步派发时元数据为空，
     * 因此两个判据都要看。这些事件属于内部过程：子 Agent 的正文里常有结构化结论（例如面试评分的 JSON），
     * 而且它的正文会和助手自己写的结论叠在一起，用户就会在自己的回答里看到同一份分析出现两遍。
     * 因此这类事件一律只留在服务端。
     *
     * @param event AgentScope 事件
     * @return 子 Agent 转发的事件返回 true
     */
    public static boolean isSubagentEvent(AgentEvent event) {
        if (event == null) {
            return false;
        }
        if (StringUtils.hasText(event.getSource())) {
            return true;
        }
        Map<String, Object> metadata = event.getMetadata();
        return metadata != null && metadata.containsKey(AgentEvent.METADATA_PARENT_SESSION_ID);
    }

    /**
     * 判断事件是否为异常结束事件。
     *
     * @param event SSE 事件
     * @return true 表示异常结束事件
     */
    public static boolean isError(SseEvent event) {
        return event != null && SseEvent.NAME_ERROR.equals(event.getName());
    }

    /**
     * 映射工具调用开始事件。
     *
     * @param event 工具调用开始事件
     * @return SSE 事件
     */
    private static SseEvent mapToolStart(ToolCallStartEvent event) {
        return SseEvent.tool(event.getToolCallName(), SseEvent.TOOL_STATUS_START, event.getToolCallId());
    }

    /**
     * 映射工具调用结束事件。
     *
     * @param event 工具调用结束事件
     * @return SSE 事件
     */
    private static SseEvent mapToolEnd(ToolCallEndEvent event) {
        return SseEvent.tool(event.getToolCallName(), SseEvent.TOOL_STATUS_END, event.getToolCallId());
    }

    /**
     * 映射子智能体触发事件。
     *
     * @param event 子智能体触发事件
     * @return SSE 事件
     */
    private static SseEvent mapSubagent(SubagentExposedEvent event) {
        String label = StringUtils.hasText(event.getLabel()) ? event.getLabel() : event.getAgentId();
        return SseEvent.node(label);
    }

    /**
     * 映射超出最大步数事件，按异常结束处理。
     *
     * @param event 超出最大步数事件
     * @return SSE 事件
     */
    private static SseEvent mapExceedMaxIters(ExceedMaxItersEvent event) {
        return SseEvent.error("本次回复超过最大步数限制（maxIters="
                + event.getMaxIters() + "，当前步数=" + event.getCurrentIter() + "）");
    }
}
