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
 * 训练任务。
 *
 * <p>按天分组的每日任务，是用户在计划页逐条勾选的对象。任务归属某一份计划（{@code plan_id}）与某个
 * 用户（{@code user_id}），重规划时旧计划的任务随旧计划一起失效（旧计划标记 ENDED），不做物理删除。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("training_task")
public class TrainingTask extends BasePO {

    /**
     * 训练主题最大长度，与表结构一致。
     */
    public static final int TOPIC_MAX_LENGTH = 200;

    /**
     * 题型最大长度，与表结构一致。
     */
    public static final int QUESTION_TYPE_MAX_LENGTH = 32;

    /**
     * 知识点名称最大长度，与表结构一致。
     */
    public static final int KNOWLEDGE_POINT_MAX_LENGTH = 200;

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID，对应 training_task.user_id，所有查询都必须带该条件。
     */
    private Long userId;

    /**
     * 所属计划 ID，对应 training_task.plan_id，关联 training_plan.id（逻辑关联，不建物理外键）。
     */
    private Long planId;

    /**
     * 第几天，对应 training_task.day_index，取值 1..total_days。
     */
    private Integer dayIndex;

    /**
     * 任务日期，对应 training_task.task_date：由计划开始日期加 day_index 算出，供按天展示与今日提醒使用。
     */
    private LocalDate taskDate;

    /**
     * 训练主题，对应 training_task.topic：薄弱点补齐、项目题演练这类主题名。
     */
    private String topic;

    /**
     * 题型，对应 training_task.question_type：八股/项目/综合/算法等。
     */
    private String questionType;

    /**
     * 难度，对应 training_task.difficulty，取值 1-5，难度按天递进。
     */
    private Integer difficulty;

    /**
     * 预计时长（分钟），对应 training_task.duration_minutes，当天合计不超过用户输入的每日时长。
     */
    private Integer durationMinutes;

    /**
     * 对应知识点名称，对应 training_task.knowledge_point：用于核对主题是否对上薄弱点，可为空。
     */
    private String knowledgePoint;

    /**
     * 同一天内的排序号，对应 training_task.sort_order，从 1 开始。
     */
    private Integer sortOrder;

    /**
     * 是否已完成，对应 training_task.finished：0-未完成，1-已完成。
     */
    private Integer finished;

    /**
     * 完成时间，对应 training_task.finish_time，未完成时为空。
     */
    private LocalDateTime finishTime;
}
