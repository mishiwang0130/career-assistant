package com.wxy.career.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计划结论宽松反序列化测试。
 *
 * <p>固定模型可能写出的几种形态都不再让工具调用失败：正常对象、整份写成 JSON 字符串、数字带单位、
 * snake_case 字段名、字段类型完全不对（留空交给服务端校验并给出可读原因）。
 *
 * @author wxy
 * @date 2026-10-01
 */
class TrainingPlanSubmitVODeserializerTest {

    /**
     * 工具入参用的 JSON 组件（与框架一致：未知字段不报错）。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 正常对象形态。
     */
    @Test
    void shouldReadCamelCaseObject() throws Exception {
        String json = "{\"days\":7,\"dailyMinutes\":60,\"summary\":\"概要\","
                + "\"adjustmentReason\":\"新增薄弱点\",\"tasks\":[{\"dayIndex\":1,\"topic\":\"Redis 分布式锁\","
                + "\"questionType\":\"八股\",\"difficulty\":2,\"durationMinutes\":30,"
                + "\"knowledgePoint\":\"Redis 分布式锁\"}]}";

        TrainingPlanSubmitVO plan = objectMapper.readValue(json, TrainingPlanSubmitVO.class);

        assertThat(plan.getDailyMinutes()).isEqualTo(60);
        assertThat(plan.getTasks()).hasSize(1);
        assertThat(plan.getTasks().get(0).getQuestionType()).isEqualTo("八股");
    }

    /**
     * 整份写成 JSON 字符串：也要能收进来。
     */
    @Test
    void shouldReadPlanGivenAsJsonString() throws Exception {
        String inner = "{\"days\":3,\"dailyMinutes\":60,\"tasks\":[{\"dayIndex\":1,\"topic\":\"A\","
                + "\"questionType\":\"八股\",\"difficulty\":3,\"durationMinutes\":30}]}";
        String json = objectMapper.writeValueAsString(inner);

        TrainingPlanSubmitVO plan = objectMapper.readValue(json, TrainingPlanSubmitVO.class);

        assertThat(plan.getDays()).isEqualTo(3);
        assertThat(plan.getTasks()).hasSize(1);
    }

    /**
     * snake_case、带单位的数字、字符串数字都要能解析。
     */
    @Test
    void shouldReadSnakeCaseAndLooseNumbers() throws Exception {
        String json = "{\"days\":\"7 天\",\"daily_minutes\":\"60\",\"adjustment_reason\":\"进度落后\","
                + "\"tasks\":[{\"day_index\":\"1\",\"topic\":\"Redis\",\"question_type\":\"八股\","
                + "\"difficulty\":\"中等\",\"duration_minutes\":\"30 分钟\",\"knowledge_point\":\"Redis 分布式锁\"}]}";

        TrainingPlanSubmitVO plan = objectMapper.readValue(json, TrainingPlanSubmitVO.class);

        assertThat(plan.getDays()).isEqualTo(7);
        assertThat(plan.getDailyMinutes()).isEqualTo(60);
        assertThat(plan.getAdjustmentReason()).isEqualTo("进度落后");
        TrainingTaskSubmitVO task = plan.getTasks().get(0);
        assertThat(task.getDayIndex()).isEqualTo(1);
        assertThat(task.getDurationMinutes()).isEqualTo(30);
        // 难度写了文字：留空，由服务端归一到默认难度，而不是让整轮失败。
        assertThat(task.getDifficulty()).isNull();
    }

    /**
     * 完全不成形的入参不抛异常：留空交给服务端校验，模型会收到可读原因。
     */
    @Test
    void shouldNotThrowOnUnexpectedShapes() throws Exception {
        assertThat(objectMapper.readValue("\"not json at all\"", TrainingPlanSubmitVO.class).getTasks()).isNull();
        assertThat(objectMapper.readValue("\"{\\\"days\\\":7}\"", TrainingPlanSubmitVO.class).getDays())
                .isEqualTo(7);
        assertThat(objectMapper.readValue("[]", TrainingPlanSubmitVO.class).getDays()).isNull();
        assertThat(objectMapper.readValue("{\"tasks\":[1,2]}", TrainingPlanSubmitVO.class).getTasks()).hasSize(2);
    }
}
