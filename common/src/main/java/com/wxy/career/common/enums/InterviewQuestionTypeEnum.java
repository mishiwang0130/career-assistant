package com.wxy.career.common.enums;

/**
 * 面试题型。
 *
 * <p>三类题型按 4:2:2 的参考配比混合出题（见 Skill {@code interview-questioning}），落库字段为
 * {@code interview_qa.question_type}，F6 的报告与掌握度统计也会按本枚举聚合。
 *
 * @author wxy
 * @date 2026-09-29
 */
public enum InterviewQuestionTypeEnum {

    /**
     * 八股题：岗位相关的基础原理与常见考点。
     */
    BASIC("BASIC", "八股"),

    /**
     * 项目题：就候选人简历里的项目经历提问与追问。
     */
    PROJECT("PROJECT", "项目"),

    /**
     * 综合题：场景设计、权衡取舍、沟通协作一类没有唯一答案的问题。
     */
    COMPREHENSIVE("COMPREHENSIVE", "综合");

    /**
     * 落库与接口传输使用的字符串值。
     */
    private final String value;

    /**
     * 题型的中文说明。
     */
    private final String label;

    /**
     * 构造题型枚举。
     *
     * @param value 字符串值
     * @param label 中文说明
     */
    InterviewQuestionTypeEnum(String value, String label) {
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
     * 按字符串值查找题型，取值非法时返回 null。
     *
     * @param value 字符串值
     * @return 题型枚举，未登记时返回 null
     */
    public static InterviewQuestionTypeEnum find(String value) {
        for (InterviewQuestionTypeEnum type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
