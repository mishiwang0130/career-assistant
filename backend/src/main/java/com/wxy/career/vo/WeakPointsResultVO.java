package com.wxy.career.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 读薄弱点工具（F9）返回结构。
 *
 * <p>工具只读 MySQL 的 {@code knowledge_mastery}（权威数据）：不传关键词时返回标记为薄弱的知识点，
 * 传关键词时按名称模糊匹配。空状态用 {@code hasData} 与 {@code message} 表达而不是抛异常，
 * 让模型能如实转述「这个人还没有面试记录」，而不是编造薄弱点。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class WeakPointsResultVO {

    /**
     * 该用户是否存在掌握度记录：false 表示还没有面试沉淀，message 里是明确空状态。
     */
    private boolean hasData;

    /**
     * 本次实际返回的条数。
     */
    private int count;

    /**
     * 本次生效的条数上限（配置项 {@code app.tutoring.max-weak-points}）。
     */
    private int limit;

    /**
     * 结果说明：空状态、关键词无匹配或结果被截断时给出可转述的中文说明；正常返回时为空串。
     */
    private String message = "";

    /**
     * 知识点条目，薄弱在前、掌握度低的在前。
     */
    private List<WeakPointVO> points = new ArrayList<>();
}
