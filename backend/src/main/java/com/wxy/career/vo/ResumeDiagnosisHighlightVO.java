package com.wxy.career.vo;

import lombok.Data;

/**
 * 简历诊断的亮点条目。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class ResumeDiagnosisHighlightVO {

    /**
     * 值得保留的写法。
     */
    private String point;

    /**
     * 为什么好。
     */
    private String reason;
}
