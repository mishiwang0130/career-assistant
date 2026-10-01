package com.wxy.career.common.enums;

/**
 * 训练计划状态。
 *
 * <p>F7 训练计划引入：一个用户同一时刻只有一份 {@code ACTIVE} 计划；重规划是覆盖生成，旧计划改成
 * {@code ENDED} 保留下来（不物理删除），便于回溯「这份计划为什么被调整」。
 *
 * @author wxy
 * @date 2026-10-01
 */
public enum TrainingPlanStatusEnum {

    /**
     * 生效中：页面展示、勾选与提醒都以这份计划为准。
     */
    ACTIVE("ACTIVE", "生效中"),

    /**
     * 已结束：被后续重规划替换，只做历史留痕，不再参与展示与提醒。
     */
    ENDED("ENDED", "已结束");

    /**
     * 入库值。
     */
    private final String value;

    /**
     * 中文说明。
     */
    private final String label;

    TrainingPlanStatusEnum(String value, String label) {
        this.value = value;
        this.label = label;
    }

    /**
     * 获取入库值。
     *
     * @return 入库值
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
}
