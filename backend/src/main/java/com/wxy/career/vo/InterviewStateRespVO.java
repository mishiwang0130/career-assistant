package com.wxy.career.vo;

import lombok.Data;

/**
 * 面试进度与难度快照。
 *
 * <p>两个入口共用：面试状态接口（界面刷新、断点续答时恢复进度）与面试 Agent 的读状态工具
 * （模型据此知道当前第几题、该用哪个难度）。字段含义固定，前端与提示词都按同一口径理解。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class InterviewStateRespVO {

    /**
     * 面试会话 ID，取值是 chat_session.id 的十进制字符串。
     */
    private String sessionId;

    /**
     * 当前（或下一道）主问题序号，从 1 开始；面试已结束时停在最后一道题。
     */
    private Integer questionIndex;

    /**
     * 本场面试的主问题总题量，来自配置项 app.interview.question-count。
     */
    private Integer questionCount;

    /**
     * 当前题目难度等级，取值范围 1-5，只升不降。
     */
    private Integer difficulty;

    /**
     * 当前轮次：1-主问题，2-追问。用于界面提示与提示词判断当前在问什么。
     */
    private Integer roundNo;

    /**
     * 本场面试是否已结束；结束后该会话只读，需要新开一场。
     */
    private Boolean finished;

    /**
     * 起始难度，由求职目标里的工作年限决定，同时是全场难度下限。
     */
    private Integer startDifficulty;

    /**
     * 下一道主问题的建议题型，取值见 InterviewQuestionTypeEnum；面试已结束时为 null。
     */
    private String recommendedQuestionType;
}
