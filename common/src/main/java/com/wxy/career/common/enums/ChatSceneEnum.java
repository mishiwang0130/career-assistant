package com.wxy.career.common.enums;

/**
 * 会话场景。
 *
 * <p>本枚举由 M16 建立，登记所有会话型功能的场景取值；枚举值与数据库字段 {@code chat_session.scene}
 * 以及前端的面板映射保持一致。新增场景时在此追加枚举项并把 {@code available} 置为 {@code true}，
 * 未开放的场景不允许建会话，避免前端拿到还没有渲染面板的场景。
 *
 * @author wxy
 * @date 2026-09-28
 */
public enum ChatSceneEnum {

    /**
     * 通用助手咨询，M16 开放。
     */
    ASSISTANT("ASSISTANT", true),

    /**
     * 模拟面试，由 M8 开放。
     */
    INTERVIEW("INTERVIEW", false),

    /**
     * 简历诊断，由 M7 开放。
     */
    DIAGNOSIS("DIAGNOSIS", false),

    /**
     * 岗位匹配，由 M7 开放。
     */
    MATCH("MATCH", false),

    /**
     * Tutor 答疑，由 M14 开放。
     */
    TUTOR("TUTOR", false);

    /**
     * 场景字符串值，落库与接口传输均使用该值。
     */
    private final String value;

    /**
     * 当前是否已经开放建会话。
     */
    private final boolean available;

    /**
     * 构造场景枚举。
     *
     * @param value 场景字符串值
     * @param available 是否已经开放
     */
    ChatSceneEnum(String value, boolean available) {
        this.value = value;
        this.available = available;
    }

    /**
     * 获取场景字符串值。
     *
     * @return 场景字符串值
     */
    public String getValue() {
        return value;
    }

    /**
     * 判断场景是否已经开放。
     *
     * @return 已开放返回 true
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * 按字符串值查找场景，未登记的取值返回 null。
     *
     * <p>调用方需要把 null 与「未开放」区分处理，因此这里不抛异常。
     *
     * @param value 场景字符串值
     * @return 场景枚举，未登记时返回 null
     */
    public static ChatSceneEnum find(String value) {
        for (ChatSceneEnum scene : values()) {
            if (scene.value.equals(value)) {
                return scene;
            }
        }
        return null;
    }
}
