package com.wxy.career.vo;

import lombok.Data;

/**
 * 简历诊断的问题条目。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class ResumeDiagnosisProblemVO {

    /**
     * 问题描述。
     */
    private String problem;

    /**
     * 问题出现在简历的哪里，例如「项目经历第二段」。
     */
    private String location;

    /**
     * 为什么这是问题。
     */
    private String reason;

    /**
     * 怎么改。
     */
    private String suggestion;

    /**
     * 严重程度，取值为 HIGH、MEDIUM 或 LOW。
     */
    private String severity;
}
