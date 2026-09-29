package com.wxy.career.common.enums;

/**
 * 知识点掌握度等级。
 *
 * <p>口径写死在代码与 {@code docs/技术约定.md} 的「面试点评与报告（F6）」章节，并同步到
 * {@code mastery-evaluation} Skill：掌握度由近期多次证据按时间衰减加权得出，单题答错不会直接打到最低。
 * 等级用于面试报告与训练计划的展示，取值一经冻结只追加、不改含义。
 *
 * @author wxy
 * @date 2026-09-29
 */
public enum KnowledgeMasteryLevelEnum {

    /**
     * 薄弱：掌握度低于 30，或近期证据明显不足。
     */
    WEAK("WEAK", "薄弱"),

    /**
     * 待补强：掌握度 30-59，方向大致对但关键点覆盖不稳。
     */
    NEEDS_WORK("NEEDS_WORK", "待补强"),

    /**
     * 基本掌握：掌握度 60-74，能答出主干但仍需打磨。
     */
    BASIC("BASIC", "基本掌握"),

    /**
     * 熟练：掌握度 75-89，答得完整且能说到机制与取舍。
     */
    PROFICIENT("PROFICIENT", "熟练"),

    /**
     * 精通：掌握度 90 及以上，稳定答到要点。
     */
    MASTERED("MASTERED", "精通");

    /**
     * 薄弱等级下界（不含）。
     */
    public static final int BASIC_THRESHOLD = 30;

    /**
     * 基本掌握等级下界（含）。
     */
    public static final int FAIR_THRESHOLD = 60;

    /**
     * 熟练等级下界（含）。
     */
    public static final int PROFICIENT_THRESHOLD = 75;

    /**
     * 精通等级下界（含）。
     */
    public static final int MASTERED_THRESHOLD = 90;

    /**
     * 落库与接口传输使用的字符串值。
     */
    private final String value;

    /**
     * 等级中文说明，用于报告与日志。
     */
    private final String label;

    /**
     * 构造掌握度等级枚举。
     *
     * @param value 字符串值
     * @param label 中文说明
     */
    KnowledgeMasteryLevelEnum(String value, String label) {
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
     * 按掌握度分数换算等级。
     *
     * @param score 掌握度分数，0-100，越界按边界收敛
     * @return 掌握度等级
     */
    public static KnowledgeMasteryLevelEnum fromScore(int score) {
        if (score >= MASTERED_THRESHOLD) {
            return MASTERED;
        }
        if (score >= PROFICIENT_THRESHOLD) {
            return PROFICIENT;
        }
        if (score >= FAIR_THRESHOLD) {
            return BASIC;
        }
        if (score >= BASIC_THRESHOLD) {
            return NEEDS_WORK;
        }
        return WEAK;
    }

    /**
     * 按字符串值查找等级，取值非法时返回 null。
     *
     * @param value 字符串值
     * @return 掌握度等级，未登记时返回 null
     */
    public static KnowledgeMasteryLevelEnum find(String value) {
        for (KnowledgeMasteryLevelEnum level : values()) {
            if (level.value.equals(value)) {
                return level;
            }
        }
        return null;
    }
}
