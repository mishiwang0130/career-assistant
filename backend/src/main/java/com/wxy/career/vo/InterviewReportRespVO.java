package com.wxy.career.vo;

import lombok.Data;

import java.util.List;

/**
 * 面试报告响应：SSE {@code interview_report} 状态事件与报告查询接口共用。
 *
 * <p>三态由 {@code status} 表达（GENERATING / SUCCEEDED / FAILED）：报告由后台子 Agent 生成，
 * 面试结束后面板先显示「报告生成中」，生成完成后（或用户刷新页面时）再展示内容；失败态给出原因与重试入口。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class InterviewReportRespVO {

    /**
     * 结构化结果类型标识，面试报告为 interview_report。
     */
    public static final String TYPE_INTERVIEW_REPORT = "interview_report";

    /**
     * 结果类型标识，前端据此分流。
     */
    private String type = TYPE_INTERVIEW_REPORT;

    /**
     * 面试会话 ID。
     */
    private String sessionId;

    /**
     * 生成状态：GENERATING / SUCCEEDED / FAILED。
     */
    private String status;

    /**
     * 生成状态中文说明。
     */
    private String statusLabel;

    /**
     * 生成完成时间，未完成时为 null。
     */
    private String generatedAt;

    /**
     * 面试总结正文，未完成时为 null。
     */
    private String summary;

    /**
     * 亮点清单，未完成时为空列表。
     */
    private List<String> highlights;

    /**
     * 下一步建议清单，未完成时为空列表。
     */
    private List<String> suggestions;

    /**
     * 错题清单：判定为「完全不会或答错」的题。
     */
    private List<InterviewWrongItemVO> wrongItems;

    /**
     * 薄弱点清单：本场面试涉及且被判定为薄弱的知识点。
     */
    private List<InterviewWeaknessVO> weaknesses;

    /**
     * 本场面试涉及知识点的掌握度。
     */
    private List<KnowledgeMasteryVO> mastery;

    /**
     * 失败原因，成功或生成中时为 null。
     */
    private String errorMessage;

    /**
     * 是否允许重试（生成失败时为 true）。
     */
    private Boolean canRetry;
}
