package com.wxy.career.vo;

import lombok.Data;

import java.util.List;

/**
 * 简历诊断结论，SSE {@code result} 事件的 data 载荷。
 *
 * <p>用 {@code type} 做判别联合：F6 的点评卡片、报告卡片后续复用同一套路，只新增 type 取值，
 * 不改这里的字段含义与事件形态。字段与前端简历诊断卡片一一对应。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class ResumeDiagnosisResultVO {

    /**
     * 结构化结果类型标识，简历诊断为 resume_diagnosis。
     */
    public static final String TYPE_RESUME_DIAGNOSIS = "resume_diagnosis";

    /**
     * 结果类型标识，前端据此选择卡片组件。
     */
    private String type = TYPE_RESUME_DIAGNOSIS;

    /**
     * 被诊断的简历 ID。
     */
    private Long resumeId;

    /**
     * 被诊断的简历标题，「另存为新简历」的默认标题从这里派生。
     */
    private String resumeTitle;

    /**
     * 综合得分，取值范围 0-100。
     */
    private Integer overallScore;

    /**
     * 综合得分的一句话说明。
     */
    private String scoreSummary;

    /**
     * 维度评分，至少 4 项。
     */
    private List<ResumeDiagnosisDimensionVO> dimensions;

    /**
     * 问题清单。
     */
    private List<ResumeDiagnosisProblemVO> problems;

    /**
     * 亮点清单。
     */
    private List<ResumeDiagnosisHighlightVO> highlights;

    /**
     * 优化建议。
     */
    private List<ResumeDiagnosisSuggestionVO> suggestions;

    /**
     * 优化后的简历正文，前端「另存为新简历」用它创建一条 MANUAL 简历。
     */
    private String optimizedResume;

    /**
     * 可能被追问的项目点。
     */
    private List<String> interviewFollowUps;
}
