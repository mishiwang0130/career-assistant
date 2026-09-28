package com.wxy.career.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 通用助手对话请求。
 *
 * <p>注意：用户身份只从登录态获取，请求中不接受 userId。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
public class AssistantChatReqVO {

    /**
     * 会话 ID，由前端生成的 UUID，最长 64 位。
     */
    @NotBlank(message = "会话 ID 不能为空")
    @Size(max = 64, message = "会话 ID 长度不能超过64位")
    private String sessionId;

    /**
     * 用户消息内容，最长 4000 字。
     */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 4000, message = "消息内容长度不能超过4000位")
    private String content;
}
