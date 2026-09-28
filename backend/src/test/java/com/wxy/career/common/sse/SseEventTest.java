package com.wxy.career.common.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 报文格式契约测试。
 *
 * <p>约定事件名与 data 行必须严格按 {@code event:} / {@code data:} 加空行的形式输出，
 * 前端解析逻辑依赖该格式。
 *
 * @author wxy
 * @date 2026-09-28
 */
class SseEventTest {

    /**
     * JSON 序列化组件。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 验证文本增量事件的报文格式。
     */
    @Test
    void shouldSerializeDeltaEvent() {
        String message = SseEvent.delta("你好").toSseMessage(objectMapper);

        assertThat(message).isEqualTo("event:delta\ndata:{\"content\":\"你好\"}\n\n");
    }

    /**
     * 验证工具事件的字段顺序与内容。
     */
    @Test
    void shouldSerializeToolEvent() {
        String message = SseEvent.tool("get_user_profile", SseEvent.TOOL_STATUS_START, "call-1")
                .toSseMessage(objectMapper);

        assertThat(message).isEqualTo(
                "event:tool\ndata:{\"name\":\"get_user_profile\",\"status\":\"START\",\"detail\":\"call-1\"}\n\n");
    }

    /**
     * 验证异常结束事件使用 message 字段。
     */
    @Test
    void shouldSerializeErrorEventWithMessageField() {
        SseEvent event = SseEvent.error("服务不可用");

        assertThat(event.getData()).isEqualTo(Map.of("message", "服务不可用"));
        assertThat(event.toSseMessage(objectMapper))
                .isEqualTo("event:error\ndata:{\"message\":\"服务不可用\"}\n\n");
    }

    /**
     * 验证正常结束事件没有 data 字段内容之外的额外负载。
     */
    @Test
    void shouldSerializeDoneEvent() {
        assertThat(SseEvent.done().toSseMessage(objectMapper)).isEqualTo("event:done\ndata:{}\n\n");
    }
}
