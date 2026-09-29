package com.wxy.career.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wxy.career.common.mybatis.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 面试问答记录。
 *
 * <p>一轮问答一条：主问题与它的追问都记（用 {@code roundNo} 区分），题目、回答、题型、难度、
 * 判定结果与随后的流程动作一并留存。整场面试的进度与当前难度由这些行回放得出，因此不需要额外的
 * 面试级表；F6 的点评、错题清单与掌握度也以本表为数据来源。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("interview_qa")
public class InterviewQa extends BasePO {

    /**
     * 主问题轮次：面试官提出的主问题。
     */
    public static final int ROUND_MAIN = 1;

    /**
     * 追问轮次：就同一道主问题的追问，题序与主问题一致。
     */
    public static final int ROUND_FOLLOW_UP = 2;

    /**
     * 判定要点最大长度，超长截断后落库，避免模型回填整段点评。
     */
    public static final int JUDGEMENT_MAX_LENGTH = 500;

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID，对应 interview_qa.user_id，所有查询都必须带该条件。
     */
    private Long userId;

    /**
     * 面试会话 ID，对应 interview_qa.session_id，取值是 chat_session.id（逻辑关联，不建物理外键）。
     */
    private Long sessionId;

    /**
     * 主问题序号，对应 interview_qa.question_index，从 1 开始且不超过配置题量。
     */
    private Integer questionIndex;

    /**
     * 同一道主问题下的轮次，对应 interview_qa.round_no：1-主问题，2-追问。
     */
    private Integer roundNo;

    /**
     * 题型，对应 interview_qa.question_type，取值见 InterviewQuestionTypeEnum。
     */
    private String questionType;

    /**
     * 本题难度等级，对应 interview_qa.difficulty，取值范围 1-5，起始难度由工作年限决定。
     */
    private Integer difficulty;

    /**
     * 题目正文，对应 interview_qa.question。
     */
    private String question;

    /**
     * 用户本题的回答，对应 interview_qa.answer，取用户实际发送的消息内容。
     */
    private String answer;

    /**
     * 本题判定结果，对应 interview_qa.outcome，取值见 InterviewOutcomeEnum。
     */
    private String outcome;

    /**
     * 判定要点，对应 interview_qa.judgement，来自评分子 Agent 的结论摘要，最长 500 字符。
     */
    private String judgement;

    /**
     * 本回合之后的流程动作，对应 interview_qa.next_action，取值见 InterviewActionEnum。
     */
    private String nextAction;

    /**
     * 评分子 Agent 的结构化结论 JSON，对应 interview_qa.evaluation_json。
     *
     * <p>字段与 {@code AnswerEvaluationSubmitVO} 一一对应（评分、答对的点、缺失点、错误点、表达问题、建议、
     * 知识点、一句话点评、标准答案）。面试结束时的逐题结果直接读这一列回放，F6 的逐题点评也复用它；
     * 评分不可用时为 null。
     */
    private String evaluationJson;
}
