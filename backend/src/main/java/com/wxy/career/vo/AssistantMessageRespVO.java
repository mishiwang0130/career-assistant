package com.wxy.career.vo;

import com.wxy.career.po.AssistantMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通用助手消息响应。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssistantMessageRespVO {

    /**
     * 消息 ID。
     */
    private Long id;

    /**
     * 会话 ID，会话实体的主键值，以十进制字符串返回与请求参数保持一致。
     */
    private String sessionId;

    /**
     * 消息角色，USER / ASSISTANT / SYSTEM。
     */
    private String role;

    /**
     * 消息内容。
     */
    private String content;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;

    /**
     * 从消息实体构建响应对象。
     *
     * @param message 消息实体
     * @return 消息响应
     */
    public static AssistantMessageRespVO from(AssistantMessage message) {
        return new AssistantMessageRespVO(
                message.getId(),
                message.getSessionId() == null ? null : String.valueOf(message.getSessionId()),
                message.getRole(),
                message.getContent(),
                message.getCreateTime());
    }
}
