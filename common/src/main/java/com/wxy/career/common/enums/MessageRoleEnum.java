package com.wxy.career.common.enums;

/**
 * 会话消息角色。
 *
 * <p>本枚举由 M2 冻结，后续所有涉及对话消息的模块直接复用，不再自定义角色取值。
 * 枚举值与前端、数据库字段 {@code assistant_message.role} 保持一致。
 *
 * @author wxy
 * @date 2026-09-28
 */
public enum MessageRoleEnum {

    /**
     * 用户消息。
     */
    USER("USER"),

    /**
     * 智能体回复。
     */
    ASSISTANT("ASSISTANT"),

    /**
     * 系统消息。
     */
    SYSTEM("SYSTEM");

    /**
     * 角色字符串值，落库与接口传输均使用该值。
     */
    private final String value;

    /**
     * 构造角色枚举。
     *
     * @param value 角色字符串值
     */
    MessageRoleEnum(String value) {
        this.value = value;
    }

    /**
     * 获取角色字符串值。
     *
     * @return 角色字符串值
     */
    public String getValue() {
        return value;
    }

    /**
     * 按字符串值解析角色。
     *
     * @param value 角色字符串值
     * @return 角色枚举
     * @throws IllegalArgumentException 角色取值不受支持
     */
    public static MessageRoleEnum of(String value) {
        for (MessageRoleEnum role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unsupported message role: " + value);
    }
}
