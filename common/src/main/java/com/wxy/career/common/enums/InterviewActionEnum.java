package com.wxy.career.common.enums;

/**
 * 一轮问答结束后的面试流程动作。
 *
 * <p>由流程规则算出并落库到 {@code interview_qa.next_action}：它既能回放整场面试的进度与当前难度，
 * 也是 F6 复盘「这场面试怎么走的」的依据。取值只追加、不改含义。
 *
 * @author wxy
 * @date 2026-09-29
 */
public enum InterviewActionEnum {

    /**
     * 就本题回答里的关键点或遗漏点追问一层，题序不变。
     */
    FOLLOW_UP("FOLLOW_UP", "追问一层"),

    /**
     * 换一道新的主问题，题序加一。
     */
    NEXT_QUESTION("NEXT_QUESTION", "换新题"),

    /**
     * 本场面试结束：题量走满，或用户主动要求结束。
     */
    FINISHED("FINISHED", "面试结束");

    /**
     * 落库与接口传输使用的字符串值。
     */
    private final String value;

    /**
     * 动作的中文说明。
     */
    private final String label;

    /**
     * 构造流程动作枚举。
     *
     * @param value 字符串值
     * @param label 中文说明
     */
    InterviewActionEnum(String value, String label) {
        this.value = value;
        this.label = label;
    }

    /**
     * 获取字符串值。
     *
     * @return 字符串值
     */
    public String getValue() {
        return value;
    }

    /**
     * 获取中文说明。
     *
     * @return 中文说明
     */
    public String getLabel() {
        return label;
    }

    /**
     * 按字符串值查找流程动作，取值非法时返回 null。
     *
     * @param value 字符串值
     * @return 流程动作枚举，未登记时返回 null
     */
    public static InterviewActionEnum find(String value) {
        for (InterviewActionEnum action : values()) {
            if (action.value.equals(value)) {
                return action;
            }
        }
        return null;
    }
}
