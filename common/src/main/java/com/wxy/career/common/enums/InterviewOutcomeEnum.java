package com.wxy.career.common.enums;

/**
 * 面试单题的作答判定结果。
 *
 * <p>由评分子 Agent 判定、面试 Agent 转述后落库（{@code interview_qa.outcome}）。F5 用它决定追问还是换题，
 * F6 用它生成错题清单与薄弱点，因此取值一经冻结只追加、不改含义。
 *
 * @author wxy
 * @date 2026-09-29
 */
public enum InterviewOutcomeEnum {

    /**
     * 答到要点：就回答里的关键点追问一层，难度上调一级。
     */
    CORRECT("CORRECT", "答到要点"),

    /**
     * 答得有遗漏：只追问遗漏的那一点，难度持平。
     */
    PARTIAL("PARTIAL", "答得有遗漏"),

    /**
     * 完全不会或答错：记为错题，不再纠缠该知识点，直接换一道新题，难度持平。
     */
    WRONG("WRONG", "完全不会或答错");

    /**
     * 落库与接口传输使用的字符串值。
     */
    private final String value;

    /**
     * 判定结果的中文说明，用于提示词与日志。
     */
    private final String label;

    /**
     * 构造判定结果枚举。
     *
     * @param value 字符串值
     * @param label 中文说明
     */
    InterviewOutcomeEnum(String value, String label) {
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
     * 按字符串值查找判定结果，取值非法时返回 null，由调用方决定如何提示。
     *
     * @param value 字符串值
     * @return 判定结果枚举，未登记时返回 null
     */
    public static InterviewOutcomeEnum find(String value) {
        for (InterviewOutcomeEnum outcome : values()) {
            if (outcome.value.equals(value)) {
                return outcome;
            }
        }
        return null;
    }
}
