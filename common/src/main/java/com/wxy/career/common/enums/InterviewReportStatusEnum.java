package com.wxy.career.common.enums;

/**
 * 面试报告生成状态。
 *
 * <p>报告由后台子 Agent 生成，状态落在 {@code interview_report.status}：面板先显示「报告生成中」，
 * 生成完成后（或用户刷新页面时）再展示报告内容，失败态给出明确提示与重试入口。
 *
 * @author wxy
 * @date 2026-09-29
 */
public enum InterviewReportStatusEnum {

    /**
     * 生成中：后台任务已派发，尚未回写结论。
     */
    GENERATING("GENERATING", "报告生成中"),

    /**
     * 已完成：报告内容已回写，可直接展示。
     */
    SUCCEEDED("SUCCEEDED", "已完成"),

    /**
     * 生成失败：可重试。
     */
    FAILED("FAILED", "生成失败");

    /**
     * 落库与接口传输使用的字符串值。
     */
    private final String value;

    /**
     * 状态中文说明，用于前端提示与日志。
     */
    private final String label;

    /**
     * 构造报告状态枚举。
     *
     * @param value 字符串值
     * @param label 中文说明
     */
    InterviewReportStatusEnum(String value, String label) {
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
     * 按字符串值查找状态，取值非法时返回 null。
     *
     * @param value 字符串值
     * @return 报告状态，未登记时返回 null
     */
    public static InterviewReportStatusEnum find(String value) {
        for (InterviewReportStatusEnum status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        return null;
    }
}
