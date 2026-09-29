package com.wxy.career.vo;

import lombok.Data;

import java.util.List;

/**
 * 提交简历诊断结论的工具入参。
 *
 * <p>字段与 SSE {@code result} 事件里的诊断载荷一一对应：模型用它填一份机器可读的结论，
 * 供前端渲染评分卡。这里刻意不继承其它 VO，保证框架按本类生成 JSON Schema 时字段完整。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class ResumeDiagnosisSubmitVO {

    /**
     * 被诊断的简历 ID，必须属于当前登录用户。
     */
    private Long resumeId;

    /**
     * 综合得分，取值范围 0-100。
     */
    private Integer overallScore;

    /**
     * 综合得分的一句话说明，点出扣分主要来自哪里。
     */
    private String scoreSummary;

    /**
     * 维度评分，至少 4 项。
     */
    private List<ResumeDiagnosisDimensionVO> dimensions;

    /**
     * 问题清单，按严重程度排序。
     */
    private List<ResumeDiagnosisProblemVO> problems;

    /**
     * 亮点清单。
     */
    private List<ResumeDiagnosisHighlightVO> highlights;

    /**
     * 优化建议，按优先级排列。
     */
    private List<ResumeDiagnosisSuggestionVO> suggestions;

    /**
     * 优化后的简历正文：只重组改写已有内容，不得虚构新经历与新数字。
     */
    private String optimizedResume;

    /**
     * 可能被追问的项目点，3-5 条。
     */
    private List<String> interviewFollowUps;
}
