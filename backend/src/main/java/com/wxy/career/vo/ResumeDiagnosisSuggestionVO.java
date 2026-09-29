package com.wxy.career.vo;

import lombok.Data;

/**
 * 简历诊断的优化建议条目。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class ResumeDiagnosisSuggestionVO {

    /**
     * 优先级，1 表示最高。
     */
    private Integer priority;

    /**
     * 能直接照着改的建议内容。
     */
    private String content;
}
