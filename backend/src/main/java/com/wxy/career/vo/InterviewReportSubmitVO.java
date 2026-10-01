package com.wxy.career.vo;

import lombok.Data;

import java.util.List;

/**
 * 报告子 Agent 提交的面试报告结论。
 *
 * <p>字段与 {@code prompts/sub-report-writer.md} 的结构一一对应。报告走工具提交而不是正文输出：
 * 子 Agent 的正文由框架丢弃，报告内容只从这条结构化通道落库，用户可见的回答里不会出现过程与 JSON。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class InterviewReportSubmitVO {

    /**
     * 面试会话 ID；由派发文本原样填回，服务端按 (user_id, session_id, status) 校验归属。
     */
    private String sessionId;

    /**
     * 面试总结正文：整体表现、主要差距与整体判断。
     */
    private String summary;

    /**
     * 亮点清单，2-3 条。
     */
    private List<String> highlights;

    /**
     * 下一步建议清单，3-5 条，按优先级排列。
     */
    private List<String> suggestions;
}
