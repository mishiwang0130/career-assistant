package com.wxy.career.common.enums;

/**
 * 会话场景。
 *
 * <p>只登记「有状态的长流程」型功能：枚举值与数据库字段 {@code chat_session.scene}、前端的面板映射
 * 保持一致。按《功能模块清单》的规则，一次性任务（简历诊断、岗位匹配、专项辅导）由助手 Agent 派发
 * 子 Agent 或加载 Skill 完成，不单独占用会话场景。
 *
 * <p>新增场景时在此追加枚举项并把 {@code available} 置为 {@code true}；未开放的场景不允许建会话，
 * 避免前端拿到还没有渲染面板的场景。
 *
 * @author wxy
 * @date 2026-09-28
 */
public enum ChatSceneEnum {

    /**
     * 通用助手咨询，已开放，也是应用默认入口。
     */
    ASSISTANT("ASSISTANT", true),

    /**
     * 模拟面试，F5 落地时开放：专属 Agent、按回答追问或换题、难度自适应，历史会话可回访。
     */
    INTERVIEW("INTERVIEW", true);

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
