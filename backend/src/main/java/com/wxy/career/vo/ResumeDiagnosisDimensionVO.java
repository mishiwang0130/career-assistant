package com.wxy.career.vo;

import lombok.Data;

/**
 * 简历诊断的单项维度评分。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class ResumeDiagnosisDimensionVO {

    /**
     * 维度名，例如「结构与排版」。
     */
    private String name;

    /**
     * 维度得分，取值范围 0-100。
     */
    private Integer score;

    /**
     * 一句话理由，说明该得分的依据。
     */
    private String comment;
}
