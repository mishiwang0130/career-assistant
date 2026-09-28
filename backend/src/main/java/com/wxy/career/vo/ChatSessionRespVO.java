package com.wxy.career.vo;

import com.wxy.career.po.ChatSession;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话列表与新建/重命名响应。
 *
 * <p>会话 ID 对外统一是十进制字符串，与 {@code /api/assistant/chat} 的 sessionId 参数保持一致。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionRespVO {

    /**
     * 会话 ID，取自 chat_session.id 的十进制字符串。
     */
    private String sessionId;

    /**
     * 会话标题。
     */
    private String title;

    /**
     * 会话场景，取值见 {@code ChatSceneEnum}。
     */
    private String scene;

    /**
     * 最近一条用户消息时间，用于前端展示相对时间。
     */
    private LocalDateTime lastMessageAt;

    /**
     * 从会话实体构建响应对象。
     *
     * @param session 会话实体
     * @return 会话响应
     */
    public static ChatSessionRespVO from(ChatSession session) {
        return new ChatSessionRespVO(
                session.getId() == null ? null : String.valueOf(session.getId()),
                session.getTitle(),
                session.getScene(),
                session.getLastMessageAt());
    }
}
