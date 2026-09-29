package com.wxy.career.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wxy.career.common.mybatis.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 面试报告。
 *
 * <p>一个面试会话一行（user_id + session_id 唯一）。报告由后台子 Agent（report-writer）生成后经
 * {@code submit_interview_report} 回写，只存状态与子 Agent 的产出；错题清单、薄弱点清单与知识点掌握度
 * 由 {@code interview_qa} 与 {@code knowledge_mastery} 在读取时确定性派生，不落本表，避免派生数据过期。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("interview_report")
public class InterviewReport extends BasePO {

    /**
     * 失败原因最大长度，与表结构一致。
     */
    public static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID，对应 interview_report.user_id，所有查询都必须带该条件。
     */
    private Long userId;

    /**
     * 面试会话 ID，对应 interview_report.session_id，取值是 chat_session.id。
     */
    private Long sessionId;

    /**
     * 生成状态，对应 interview_report.status，取值见 InterviewReportStatusEnum。
     */
    private String status;

    /**
     * 生成尝试次数，对应 interview_report.attempt，重试时递增。
     */
    private Integer attempt;

    /**
     * 面试总结正文，对应 interview_report.summary，由报告子 Agent 产出。
     */
    private String summary;

    /**
     * 报告结构化结论 JSON，对应 interview_report.report_json（亮点与下一步建议等）。
     */
    private String reportJson;

    /**
     * 失败原因，对应 interview_report.error_message，成功时置空。
     */
    private String errorMessage;

    /**
     * 生成完成时间，对应 interview_report.finish_time。
     */
    private LocalDateTime finishTime;
}
