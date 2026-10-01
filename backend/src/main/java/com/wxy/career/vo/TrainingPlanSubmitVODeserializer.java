package com.wxy.career.vo;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 计划结论的宽松反序列化：模型怎么填都能收进 VO，收不进来的部分按缺省处理。
 *
 * <p>为什么需要：工具入参由框架用 Jackson 转换，模型有时会把整个 {@code plan} 写成一个 JSON 字符串，
 * 或者把数字写成带单位的文本（「30 分钟」）。默认转换会直接抛异常，框架把这次工具调用标记为 ERROR 并中断这一轮，
 * 用户看到的是「计划生成失败」，而模型只收到一段技术报错、很难自我修正。
 *
 * <p>这里的口径：对象与 JSON 字符串两种形态都接受；字段名同时兼容 camelCase 与 snake_case；
 * 数字字段能取到数字或纯数字文本就取，取不到就留空交给服务端校验（校验失败会给出可读原因）。
 * 因此本类**不会抛异常**。
 *
 * @author wxy
 * @date 2026-10-01
 */
public class TrainingPlanSubmitVODeserializer extends JsonDeserializer<TrainingPlanSubmitVO> {

    /**
     * 反序列化入口。
     *
     * @param parser JSON 解析器
     * @param context 反序列化上下文
     * @return 计划结论，任何形态异常时返回尽可能填好的对象
     * @throws IOException 读取 JSON 失败（仅在 JSON 本身损坏时）
     */
    @Override
    public TrainingPlanSubmitVO deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        JsonNode node = mapper.readTree(parser);
        return fromNode(mapper, node);
    }

    /**
     * 把 JSON 节点转成计划结论（可单测直接调用）。
     *
     * @param mapper JSON 组件
     * @param node 节点，可为 null
     * @return 计划结论
     */
    static TrainingPlanSubmitVO fromNode(ObjectMapper mapper, JsonNode node) {
        TrainingPlanSubmitVO plan = new TrainingPlanSubmitVO();
        JsonNode object = unwrap(mapper, node);
        if (object == null || !object.isObject()) {
            return plan;
        }
        plan.setDays(intOrNull(object, "days", "days_count"));
        plan.setDailyMinutes(intOrNull(object, "dailyMinutes", "daily_minutes"));
        plan.setSummary(textOrNull(object, "summary"));
        plan.setAdjustmentReason(textOrNull(object, "adjustmentReason", "adjustment_reason"));
        JsonNode tasks = object.get("tasks");
        if (tasks != null && tasks.isArray()) {
            List<TrainingTaskSubmitVO> parsed = new ArrayList<>(tasks.size());
            for (JsonNode taskNode : tasks) {
                parsed.add(taskFromNode(taskNode));
            }
            plan.setTasks(parsed);
        }
        return plan;
    }

    /**
     * 兼容「plan 是 JSON 字符串」的写法。
     *
     * @param mapper JSON 组件
     * @param node 原节点
     * @return 对象节点，解析不出来时返回 null
     */
    private static JsonNode unwrap(ObjectMapper mapper, JsonNode node) {
        if (node == null) {
            return null;
        }
        if (!node.isTextual()) {
            return node;
        }
        try {
            return mapper.readTree(node.asText());
        } catch (Exception exception) {
            // 字符串不是合法 JSON：当成空计划，让服务端给出「计划里没有任何任务」这类可读原因。
            return null;
        }
    }

    /**
     * 解析单条任务。
     *
     * @param node 任务节点
     * @return 任务，节点不是对象时返回只填了空字段的对象
     */
    private static TrainingTaskSubmitVO taskFromNode(JsonNode node) {
        TrainingTaskSubmitVO task = new TrainingTaskSubmitVO();
        if (node == null || !node.isObject()) {
            return task;
        }
        task.setDayIndex(intOrNull(node, "dayIndex", "day_index"));
        task.setTopic(textOrNull(node, "topic"));
        task.setQuestionType(textOrNull(node, "questionType", "question_type"));
        task.setDifficulty(intOrNull(node, "difficulty"));
        task.setDurationMinutes(intOrNull(node, "durationMinutes", "duration_minutes", "minutes"));
        task.setKnowledgePoint(textOrNull(node, "knowledgePoint", "knowledge_point"));
        return task;
    }

    /**
     * 取整数字段：数字直接取，纯数字文本（可带「分钟」等后缀）取前导数字，其余返回 null。
     *
     * @param node 对象节点
     * @param names 允许的字段名（并列按优先级）
     * @return 整数值，取不到时返回 null
     */
    private static Integer intOrNull(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                String matched = leadingDigits(value.asText());
                if (matched != null) {
                    try {
                        return Integer.valueOf(matched);
                    } catch (NumberFormatException exception) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 取文本字段。
     *
     * @param node 对象节点
     * @param names 允许的字段名（并列按优先级）
     * @return 文本值，取不到时返回 null
     */
    private static String textOrNull(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.isTextual() ? value.asText() : value.toString();
            if (text != null && !text.isBlank()) {
                return text.trim();
            }
        }
        return null;
    }

    /**
     * 取字符串开头的连续数字。
     *
     * @param text 原文本
     * @return 数字串，没有数字时返回 null
     */
    private static String leadingDigits(String text) {
        StringBuilder digits = new StringBuilder();
        for (char character : text.trim().toCharArray()) {
            if (digits.length() == 0 && !Character.isDigit(character) && character != '-') {
                continue;
            }
            if (Character.isDigit(character)) {
                digits.append(character);
                continue;
            }
            break;
        }
        return digits.length() == 0 ? null : digits.toString();
    }
}
