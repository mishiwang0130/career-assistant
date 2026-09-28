package com.wxy.career.util;

import com.wxy.career.common.sse.SseEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentScope 事件到 SSE 事件的映射契约测试。
 *
 * <p>覆盖协议中约定的 delta / thinking / tool / node / result / error，保证事件名与 data 字段不被随意改动。
 *
 * @author wxy
 * @date 2026-09-28
 */
class AgentEventMapperTest {

    /**
     * 验证文本增量映射为 delta 事件。
     */
    @Test
    void shouldMapTextDelta() {
        SseEvent event = AgentEventMapper.map(new TextBlockDeltaEvent("reply-1", "block-1", "你好"));

        assertThat(event).isNotNull();
        assertThat(event.getName()).isEqualTo(SseEvent.NAME_DELTA);
        assertThat(contentOf(event)).isEqualTo("你好");
    }

    /**
     * 验证思考增量映射为 thinking 事件。
     */
    @Test
    void shouldMapThinkingDelta() {
        SseEvent event = AgentEventMapper.map(new ThinkingBlockDeltaEvent("reply-1", "block-2", "思考中"));

        assertThat(event).isNotNull();
        assertThat(event.getName()).isEqualTo(SseEvent.NAME_THINKING);
        assertThat(contentOf(event)).isEqualTo("思考中");
    }

    /**
     * 验证工具调用开始与结束映射为 tool 事件且状态正确。
     */
    @Test
    void shouldMapToolCallStartAndEnd() {
        SseEvent start = AgentEventMapper.map(new ToolCallStartEvent("reply-1", "call-1", "get_current_user"));
        SseEvent end = AgentEventMapper.map(new ToolCallEndEvent("reply-1", "call-1", "get_current_user"));

        assertThat(start).isNotNull();
        assertThat(start.getName()).isEqualTo(SseEvent.NAME_TOOL);
        assertThat(fieldOf(start, "name")).isEqualTo("get_current_user");
        assertThat(fieldOf(start, "status")).isEqualTo(SseEvent.TOOL_STATUS_START);
        assertThat(fieldOf(start, "detail")).isEqualTo("call-1");

        assertThat(end).isNotNull();
        assertThat(fieldOf(end, "status")).isEqualTo(SseEvent.TOOL_STATUS_END);
    }

    /**
     * 验证子智能体触发映射为 node 事件，label 为空时回退到 agentId。
     */
    @Test
    void shouldMapSubagentExposed() {
        SseEvent withLabel = AgentEventMapper.map(
                new SubagentExposedEvent("sub-1", "agent-1", "session-1", "简历分析"));
        SseEvent withoutLabel = AgentEventMapper.map(
                new SubagentExposedEvent("sub-1", "agent-1", "session-1", ""));

        assertThat(withLabel).isNotNull();
        assertThat(withLabel.getName()).isEqualTo(SseEvent.NAME_NODE);
        assertThat(contentOf(withLabel)).isEqualTo("简历分析");
        assertThat(contentOf(withoutLabel)).isEqualTo("agent-1");
    }

    /**
     * 验证最终结果映射为 result 事件并携带文本内容。
     */
    @Test
    void shouldMapAgentResult() {
        Msg result = Msg.builder().role(MsgRole.ASSISTANT).textContent("最终结果").build();

        SseEvent event = AgentEventMapper.map(new AgentResultEvent(result));

        assertThat(event).isNotNull();
        assertThat(event.getName()).isEqualTo(SseEvent.NAME_RESULT);
        assertThat(fieldOf(event, "data")).isEqualTo("最终结果");
    }

    /**
     * 验证超出最大步数映射为 error 事件，且提示包含步数信息。
     */
    @Test
    void shouldMapExceedMaxItersToError() {
        SseEvent event = AgentEventMapper.map(new ExceedMaxItersEvent("reply-1", 12, 13));

        assertThat(event).isNotNull();
        assertThat(event.getName()).isEqualTo(SseEvent.NAME_ERROR);
        assertThat(AgentEventMapper.isError(event)).isTrue();
        assertThat(fieldOf(event, "message")).asString().contains("12").contains("13");
    }

    /**
     * 验证协议外的事件被忽略（不推送）。
     */
    @Test
    void shouldIgnoreEventsOutsideProtocol() {
        AgentEvent modelCallStart = new ModelCallStartEvent("reply-1");

        assertThat(AgentEventMapper.map(modelCallStart)).isNull();
        assertThat(AgentEventMapper.map(null)).isNull();
    }

    /**
     * 取出事件 data 中的 content 字段。
     *
     * @param event SSE 事件
     * @return content 字段值
     */
    private String contentOf(SseEvent event) {
        return String.valueOf(fieldOf(event, "content"));
    }

    /**
     * 取出事件 data 中的指定字段。
     *
     * @param event SSE 事件
     * @param field 字段名
     * @return 字段值
     */
    private Object fieldOf(SseEvent event, String field) {
        assertThat(event.getData()).isInstanceOf(Map.class);
        return ((Map<?, ?>) event.getData()).get(field);
    }
}
