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
     * 会话 ID 允许的字符集合与长度：字母、数字、下划线与短横线，1-64 位。
     *
     * <p>会话 ID 会参与 Redis key 拼接与 SCAN 模式匹配，必须限制字符集，
     * 否则 {@code *}、{@code ?} 等通配符会扩大键扫描范围。
     */
    public static final String SESSION_ID_REGEXP = "^[A-Za-z0-9_-]{1,64}$";

    /**
     * 会话 ID 字符集校验失败提示。
     */
    public static final String SESSION_ID_PATTERN_MESSAGE =
            "会话 ID 只能包含字母、数字、下划线和短横线";

    /**
     * 会话 ID，由前端生成的 UUID，最长 64 位。
     */
    @NotBlank(message = "会话 ID 不能为空")
    @Size(max = 64, message = "会话 ID 长度不能超过64位")
    @Pattern(regexp = SESSION_ID_REGEXP, message = SESSION_ID_PATTERN_MESSAGE)
    private String sessionId;

    /**
     * 用户消息内容，最长 4000 字。
     */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 4000, message = "消息内容长度不能超过4000位")
    private String content;
}
