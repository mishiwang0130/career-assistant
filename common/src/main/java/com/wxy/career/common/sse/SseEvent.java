package com.wxy.career.common.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 事件。
 *
 * <p>一个事件固定由事件名与数据两部分组成，序列化后形如：
 *
 * <pre>
 * event:delta
 * data:{"content":"你"}
 * </pre>
 *
 * <p>事件名由本类常量统一维护，后端与前端不得自行拼写字符串，避免协议漂移。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Getter
public final class SseEvent {

    /**
     * 流开始事件名，必须是流中的第一个事件。
     */
    public static final String NAME_META = "meta";

    /**
     * 文本增量事件名。
     */
    public static final String NAME_DELTA = "delta";

    /**
     * 思考增量事件名，模型不支持思考时不发送。
     */
    public static final String NAME_THINKING = "thinking";

    /**
     * 工具调用事件名，status 取 {@link #TOOL_STATUS_START} 或 {@link #TOOL_STATUS_END}。
     */
    public static final String NAME_TOOL = "tool";

    /**
     * 子智能体触发事件名。
     */
    public static final String NAME_NODE = "node";

    /**
     * 最终结构化结果事件名，纯文本场景不发送。
     */
    public static final String NAME_RESULT = "result";

    /**
     * 正常结束事件名，必须是流中的最后一个事件。
     */
    public static final String NAME_DONE = "done";

    /**
     * 异常结束事件名，与 done 互斥。
     */
    public static final String NAME_ERROR = "error";

    /**
     * 工具调用开始状态。
     */
    public static final String TOOL_STATUS_START = "START";

    /**
     * 工具调用结束状态。
     */
    public static final String TOOL_STATUS_END = "END";

    /**
     * 事件名。
     */
    private final String name;

    /**
     * 事件数据，序列化为 JSON 后写入 data 行。
     */
    private final Object data;

    /**
     * 构造 SSE 事件。
     *
     * @param name 事件名
     * @param data 事件数据
     */
    private SseEvent(String name, Object data) {
        this.name = name;
        this.data = data;
    }

    /**
     * 创建 SSE 事件。
     *
     * @param name 事件名
     * @param data 事件数据
     * @return SSE 事件
     */
    public static SseEvent of(String name, Object data) {
        return new SseEvent(name, data);
    }

    /**
     * 创建流开始事件。
     *
     * @param scene 业务场景标识
     * @param sessionId 会话 ID
     * @param provider 模型提供方
     * @param messageId 消息 ID
     * @return 流开始事件
     */
    public static SseEvent meta(String scene, String sessionId, String provider, String messageId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scene", scene);
        data.put("sessionId", sessionId);
        data.put("provider", provider);
        data.put("messageId", messageId);
        return of(NAME_META, data);
    }

    /**
     * 创建文本增量事件。
     *
     * @param content 文本增量
     * @return 文本增量事件
     */
    public static SseEvent delta(String content) {
        return contentEvent(NAME_DELTA, content);
    }

    /**
     * 创建思考增量事件。
     *
     * @param content 思考增量
     * @return 思考增量事件
     */
    public static SseEvent thinking(String content) {
        return contentEvent(NAME_THINKING, content);
    }

    /**
     * 创建工具调用事件。
     *
     * @param name 工具名
     * @param status 调用状态，START 或 END
     * @param detail 调用明细
     * @return 工具调用事件
     */
    public static SseEvent tool(String name, String status, String detail) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("status", status);
        data.put("detail", detail);
        return of(NAME_TOOL, data);
    }

    /**
     * 创建子智能体触发事件。
     *
     * @param name 子智能体名
     * @return 子智能体触发事件
     */
    public static SseEvent node(String name) {
        return contentEvent(NAME_NODE, name);
    }

    /**
     * 创建最终结构化结果事件。
     *
     * @param data 结构化结果
     * @return 结构化结果事件
     */
    public static SseEvent result(Object data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data", data);
        return of(NAME_RESULT, payload);
    }

    /**
     * 创建正常结束事件。
     *
     * @return 正常结束事件
     */
    public static SseEvent done() {
        return of(NAME_DONE, new LinkedHashMap<>());
    }

    /**
     * 创建异常结束事件。
     *
     * @param message 错误提示
     * @return 异常结束事件
     */
    public static SseEvent error(String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", message);
        return of(NAME_ERROR, data);
    }

    /**
     * 创建只带 content 字段的事件。
     *
     * @param name 事件名
     * @param content 内容
     * @return SSE 事件
     */
    private static SseEvent contentEvent(String name, String content) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", content);
        return of(name, data);
    }

    /**
     * 序列化为 SSE 报文。
     *
     * <p>data 使用单行 JSON，避免换行破坏 SSE 的按行解析规则。
     *
     * @param objectMapper JSON 序列化组件
     * @return SSE 报文文本
     */
    public String toSseMessage(ObjectMapper objectMapper) {
        StringBuilder message = new StringBuilder();
        message.append("event:").append(name).append('\n');
        if (data != null) {
            message.append("data:").append(toJson(objectMapper)).append('\n');
        }
        return message.append('\n').toString();
    }

    /**
     * 将事件数据序列化为单行 JSON。
     *
     * <p>运行时由 Spring 的 SseEmitter 负责拼接 {@code event:} / {@code data:} 行，
     * 这里只提供数据载荷；{@link #toSseMessage(ObjectMapper)} 给出同一格式的完整报文，
     * 用于协议契约测试。
     *
     * @param objectMapper JSON 序列化组件
     * @return JSON 字符串
     */
    public String toJson(ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("SSE event serialization failed: " + name, exception);
        }
    }
}
