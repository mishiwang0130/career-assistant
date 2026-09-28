package com.wxy.career.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
     * 会话 ID 允许的字符集合与长度：M16 起会话 ID 就是 chat_session.id，只允许 1-19 位数字。
     *
     * <p>会话 ID 会参与 Redis key 拼接与 SCAN 模式匹配，必须限制字符集，
     * 否则 {@code *}、{@code ?} 等通配符会扩大键扫描范围。
     */
    public static final String SESSION_ID_REGEXP = "^[0-9]{1,19}$";

    /**
     * 会话 ID 字符集校验失败提示。
     */
    public static final String SESSION_ID_PATTERN_MESSAGE =
            "会话 ID 只能为数字";

    /**
     * 会话 ID，由后端生成的 chat_session.id 十进制字符串，最长 19 位。
     */
    @NotBlank(message = "会话 ID 不能为空")
    @Pattern(regexp = SESSION_ID_REGEXP, message = SESSION_ID_PATTERN_MESSAGE)
    private String sessionId;

    /**
     * 用户消息内容，最长 4000 字。
     */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 4000, message = "消息内容长度不能超过4000位")
    private String content;
}
