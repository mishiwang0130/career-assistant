package com.wxy.career.vo;

import lombok.Data;

import java.util.List;

/**
 * 面试报告的结构化结论：亮点与下一步建议。
 *
 * <p>由报告子 Agent 产出并序列化进 {@code interview_report.report_json}；面试总结正文单独存
 * {@code interview_report.summary}，错题清单、薄弱点清单与掌握度读取时现算，不落在这里。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class InterviewReportContentVO {

    /**
     * 本场面试的亮点清单。
     */
    private List<String> highlights;

    /**
     * 下一步建议清单。
     */
    private List<String> suggestions;
}
