package com.wxy.career.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wxy.career.common.mybatis.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 训练计划。
 *
 * <p>一个用户同一时刻只有一份生效中的计划（{@code status = ACTIVE}）；重规划是覆盖生成：旧计划标记为
 * {@code ENDED} 但保留，新计划带 {@code adjustment_reason} 说明本次调整依据。计划正文（按天任务）落在
 * {@code training_task}，不往服务器工作区写任何文件。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("training_plan")
public class TrainingPlan extends BasePO {

    /**
     * 计划正文摘要的最大长度，与表结构一致。
     */
    public static final int PLAN_SUMMARY_MAX_LENGTH = 2000;

    /**
     * 调整原因的最大长度，与表结构一致。
     */
    public static final int ADJUSTMENT_REASON_MAX_LENGTH = 500;

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID，对应 training_plan.user_id；所有查询都必须带该条件。
     */
    private Long userId;

    /**
     * 计划状态，对应 training_plan.status：ACTIVE-生效中，ENDED-已结束（被重规划替换）。
     */
    private String status;

    /**
     * 生成时的目标岗位快照，对应 training_plan.target_position：页面概览展示这一份，不回读用户档案。
     */
    private String targetPosition;

    /**
     * 计划总天数，对应 training_plan.total_days：即用户生成时输入的「还有几天」。
     */
    private Integer totalDays;

    /**
     * 每天可练时长（分钟），对应 training_plan.daily_minutes。
     */
    private Integer dailyMinutes;

    /**
     * 计划开始日期，对应 training_plan.start_date：生成当天。
     */
    private LocalDate startDate;

    /**
     * 计划截止日期，对应 training_plan.end_date：由「还有几天」一次性算出，页面剩余天数由它实时计算。
     */
    private LocalDate endDate;

    /**
     * 计划概要正文，对应 training_plan.plan_summary：模型给出的总体思路与取舍说明。
     */
    private String planSummary;

    /**
     * 调整原因，对应 training_plan.adjustment_reason：重新规划时写清依据，首次生成为空。
     */
    private String adjustmentReason;

    /**
     * 生成时间，对应 training_plan.generated_at。
     */
    private LocalDateTime generatedAt;
}
