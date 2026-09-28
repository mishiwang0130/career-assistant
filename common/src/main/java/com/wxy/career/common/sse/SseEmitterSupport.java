package com.wxy.career.common.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * SSE 推送封装。
 *
 * <p>负责三件事：按协议推送固定事件名的数据、定期发送心跳注释行保持连接、在超时或客户端断开时
 * 结束连接，避免 Servlet 异步请求长期挂死。推送方法返回 {@code false} 表示连接已不可用，调用方
 * 应停止后续推送并释放上游资源。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Slf4j
public class SseEmitterSupport {

    /**
     * 心跳间隔秒数，仅用于保活，不占用协议事件名。
     */
    private static final long HEARTBEAT_SECONDS = 15L;

    /**
     * 心跳注释内容，序列化后为 SSE 注释行，前端解析时直接忽略。
     */
    private static final String HEARTBEAT_COMMENT = "heartbeat";

    /**
     * 数据载荷类型。
     *
     * <p>与接口声明的 {@code text/event-stream} 保持一致，避免 data 片段把响应类型改成 text/plain；
     * 载荷使用字节数组承载 UTF-8 编码后的单行 JSON，保证中文不会被默认字符集破坏。
     */
    private static final MediaType PAYLOAD_MEDIA_TYPE =
            new MediaType("text", "event-stream", StandardCharsets.UTF_8);

    /**
     * 超时错误提示。
     */
    private static final String TIMEOUT_MESSAGE = "响应超时，请稍后重试";

    /**
     * Servlet 异步响应对象。
     */
    private final SseEmitter emitter;

    /**
     * JSON 序列化组件。
     */
    private final ObjectMapper objectMapper;

    /**
     * 心跳调度器。
     */
    private final TaskScheduler taskScheduler;

    /**
     * 心跳任务句柄，用于结束时取消。
     */
    private ScheduledFuture<?> heartbeatFuture;

    /**
     * 连接是否已结束，结束后不再推送。
     */
    private volatile boolean closed;

    /**
     * 创建 SSE 推送封装并注册超时、异常、完成回调。
     *
     * @param objectMapper JSON 序列化组件
     * @param taskScheduler 心跳调度器
     * @param timeoutSeconds 流超时时间，单位为秒
     */
    public SseEmitterSupport(ObjectMapper objectMapper, TaskScheduler taskScheduler, long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.taskScheduler = taskScheduler;
        this.emitter = new SseEmitter(timeoutSeconds * 1000L);
        this.emitter.onTimeout(this::handleTimeout);
        this.emitter.onError(this::handleError);
        this.emitter.onCompletion(this::close);
    }

    /**
     * 获取 Servlet 异步响应对象，供 Controller 直接返回。
     *
     * @return SSE 响应对象
     */
    public SseEmitter getEmitter() {
        return emitter;
    }

    /**
     * 推送流开始事件。
     *
     * @param scene 业务场景标识
     * @param sessionId 会话 ID
     * @param provider 模型提供方
     * @param messageId 消息 ID
     * @return true 表示推送成功
     */
    public boolean sendMeta(String scene, String sessionId, String provider, String messageId) {
        return send(SseEvent.meta(scene, sessionId, provider, messageId));
    }

    /**
     * 推送文本增量事件。
     *
     * @param content 文本增量
     * @return true 表示推送成功
     */
    public boolean sendDelta(String content) {
        return send(SseEvent.delta(content));
    }

    /**
     * 推送思考增量事件。
     *
     * @param content 思考增量
     * @return true 表示推送成功
     */
    public boolean sendThinking(String content) {
        return send(SseEvent.thinking(content));
    }

    /**
     * 推送工具调用事件。
     *
     * @param name 工具名
     * @param status 调用状态，START 或 END
     * @param detail 调用明细
     * @return true 表示推送成功
     */
    public boolean sendTool(String name, String status, String detail) {
        return send(SseEvent.tool(name, status, detail));
    }

    /**
     * 推送子智能体触发事件。
     *
     * @param name 子智能体名
     * @return true 表示推送成功
     */
    public boolean sendNode(String name) {
        return send(SseEvent.node(name));
    }

    /**
     * 推送最终结构化结果事件。
     *
     * @param data 结构化结果
     * @return true 表示推送成功
     */
    public boolean sendResult(Object data) {
        return send(SseEvent.result(data));
    }

    /**
     * 推送正常结束事件并结束连接。
     *
     * @return true 表示推送成功
     */
    public boolean sendDone() {
        boolean sent = send(SseEvent.done());
        complete();
        return sent;
    }

    /**
     * 推送异常结束事件并结束连接。
     *
     * @param message 错误提示
     * @return true 表示推送成功
     */
    public boolean sendError(String message) {
        boolean sent = send(SseEvent.error(message));
        complete();
        return sent;
    }

    /**
     * 结束连接并取消心跳。
     */
    public void complete() {
        close();
        try {
            emitter.complete();
        } catch (IllegalStateException exception) {
            log.debug("SSE 连接已结束，忽略重复 complete", exception);
        }
    }

    /**
     * 判断连接是否已结束。
     *
     * @return true 表示已结束
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * 推送协议事件，并在首次推送时启动心跳。
     *
     * <p>结束类事件请使用 {@link #sendDone()} 与 {@link #sendError(String)}，它们会在推送后关闭连接。
     *
     * @param event SSE 事件
     * @return true 表示推送成功
     */
    public boolean send(SseEvent event) {
        if (closed) {
            return false;
        }
        startHeartbeat();
        try {
            // 事件名与 data 行交给 Spring 的 SseEmitter 按 SSE 规范落盘，避免自行拼接出现格式偏差。
            emitter.send(SseEmitter.event()
                    .name(event.getName())
                    .data(toPayload(event), PAYLOAD_MEDIA_TYPE));
            return true;
        } catch (Exception exception) {
            // 客户端断开、连接已结束等场景只记录日志，避免异常冒泡影响主流程。
            log.warn("SSE 推送失败，事件={}，会话可能已断开", event.getName(), exception);
            close();
            return false;
        }
    }

    /**
     * 启动心跳任务。
     *
     * <p>心跳在首个事件推送成功后启动，此时异步响应已初始化，可以安全写入。
     */
    private void startHeartbeat() {
        if (heartbeatFuture != null || closed) {
            return;
        }
        synchronized (this) {
            if (heartbeatFuture != null || closed) {
                return;
            }
            // 首次心跳延迟一个周期，避免刚建立连接就插入保活注释行。
            heartbeatFuture = taskScheduler.scheduleAtFixedRate(() -> {
                try {
                    if (!closed) {
                        emitter.send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
                    }
                } catch (Exception exception) {
                    log.debug("SSE 心跳发送失败，关闭连接", exception);
                    close();
                }
            }, Instant.now().plusSeconds(HEARTBEAT_SECONDS), Duration.ofSeconds(HEARTBEAT_SECONDS));
        }
    }

    /**
     * 处理异步请求超时：主动推送 error 事件后结束连接。
     */
    private void handleTimeout() {
        log.warn("SSE 响应超时，主动推送 error 事件后结束连接");
        sendError(TIMEOUT_MESSAGE);
    }

    /**
     * 处理异步请求异常。
     *
     * @param throwable 异常
     */
    private void handleError(Throwable throwable) {
        log.warn("SSE 连接异常", throwable);
        close();
    }

    /**
     * 结束连接状态：标记关闭并取消心跳。
     */
    private void close() {
        closed = true;
        ScheduledFuture<?> future = heartbeatFuture;
        if (future != null) {
            future.cancel(false);
            heartbeatFuture = null;
        }
    }

    /**
     * 将事件数据序列化为 UTF-8 字节载荷。
     *
     * @param event SSE 事件
     * @return 单行 JSON 的 UTF-8 字节
     */
    private byte[] toPayload(SseEvent event) {
        return event.toJson(objectMapper).getBytes(StandardCharsets.UTF_8);
    }
}
