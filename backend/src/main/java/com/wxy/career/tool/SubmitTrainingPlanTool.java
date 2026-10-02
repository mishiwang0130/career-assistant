package com.wxy.career.tool;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.service.TrainingPlanService;
import com.wxy.career.util.RuntimeContextUserUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 提交训练计划正文（计划 Agent 唯一的写工具）。
 *
 * <p>**参数只有一个正文**（+ 可选的调整原因）：起止日期与每天时长都来自用户在计划页填写的表单，模型只需要写
 * 「第 1 天：今天练什么知识点」这种一天一行的短正文。工具调用的参数越少越稳定——之前让模型吐整份嵌套 JSON、
 * 或逐条报任务，都因为参数太多/太长而反复失败。
 *
 * <p>工具标注为非只读，因此受框架权限管控：调用前会触发人工确认（HITL），**用户确认之后才会落库**，
 * 已有计划也不会被未确认地覆盖。
 *
 * <p>**入参声明成 {@link Object} 而不是 {@code String}**：框架的入参校验是按 JSON Schema 做的，声明成 String 时
 * 模型只要把正文写成数组、对象或漏掉字段名（例如 snake_case），调用就会在**进入本方法之前**被判为
 * {@code state=ERROR}，业务日志里什么都没有，模型只能反复重试同一条坏入参。声明成 Object 后 schema 不带类型约束，
 * 任何写法都能进来，由这里归一化成正文文本——模型写得不规范时拿到的是可读提示，而不是死循环。
 *
 * <p>校验失败时返回可读提示而不是抛异常，让模型能立刻修正后重新提交。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Slf4j
@Component
public class SubmitTrainingPlanTool {

    /**
     * 校验失败时补充的口径提示。
     */
    private static final String FIELD_HINT =
            "计划正文请一天一行，写成「第 N 天：今天练什么知识点」，一句话概括即可，不要写题型、难度或时长。"
                    + "修正后请**立即重新调用 submit_training_plan** 提交一次。";

    /**
     * 可能的正文键名（比较时统一去掉下划线/连字符并转小写）。
     */
    private static final List<String> CONTENT_KEYS = List.of(
            "plancontent", "plantext", "content", "plan", "text", "markdown", "body", "summary",
            "topic", "task", "knowledgepoint", "detail", "正文");

    /**
     * 可能的「按天列表」键名：模型把计划写成一天一条的数组时，从这里把每天拼回正文。
     */
    private static final List<String> DAY_LIST_KEYS = List.of(
            "days", "dailyplan", "planitems", "tasks", "items", "schedule", "list");

    /**
     * 归一化递归的最大层数，防止模型给出深层嵌套结构时无限展开。
     */
    private static final int MAX_DEPTH = 3;

    /**
     * 识别「整份写成 JSON 字符串」的入参时用的解析器。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 训练计划服务。
     */
    @Resource
    private TrainingPlanService trainingPlanService;

    /**
     * 提交计划正文。
     *
     * @param planContent 计划正文：一天一行「第 N 天：今天练什么知识点」（模型写法不规范时由本方法归一化）
     * @param adjustmentReason 调整原因，重新规划时写清依据；首次生成留空
     * @param runtimeContext 运行时上下文，由框架注入
     * @return 提交结果说明，失败时给出可读原因
     */
    @Tool(name = "submit_training_plan",
            description = "提交训练计划正文：一天一行「第 N 天：今天练什么知识点」，一句话概括，正文尽量短。"
                    + "起止日期与每天时长由用户在计划页填写，不需要你填。只提交一次，提交成功后只简要说明取舍。",
            readOnly = false)
    public String submitTrainingPlan(
            @ToolParam(name = "planContent", required = false,
                    description = "计划正文（字符串，直接把整段正文作为这个参数的值，不要拆成数组或嵌套对象、"
                            + "不要写成 JSON）：Markdown，一天一行，例如「第 1 天：Redis 分布式锁——能讲清加锁、"
                            + "续期、释放三步」；不要写题型、难度分档与时长分钟数")
            Object planContent,
            @ToolParam(name = "adjustmentReason", required = false,
                    description = "调整原因（字符串）：重新规划时写清依据（新增薄弱点/进度落后/时间变化），首次生成留空")
            Object adjustmentReason,
            RuntimeContext runtimeContext) {
        Long userId = RuntimeContextUserUtil.requireUserId(runtimeContext);
        String content = normalizePlanContent(planContent);
        String reason = normalizeText(adjustmentReason);
        log.info("收到训练计划提交，userId={}，rawType={}，contentLength={}，hasAdjustmentReason={}",
                userId, planContent == null ? "null" : planContent.getClass().getSimpleName(),
                content == null ? 0 : content.length(), reason != null);
        try {
            trainingPlanService.submitPlan(
                    userId, runtimeContext.getSessionId(), content, reason);
            return "训练计划已保存，请用一两句话说明本次的取舍。";
        } catch (BizException exception) {
            log.info("提交训练计划失败，userId={}，code={}", userId, exception.getErrorCode().getCode());
            return "提交失败：" + exception.getErrorCode().getMsg() + "。" + FIELD_HINT;
        } catch (Exception exception) {
            // 兜底：数据库异常、空指针一类非业务异常不能让整轮直接崩（框架会把工具异常标记成 ERROR 并中断这一轮），
            // 这里转成可读提示让模型改正或重提，同时把堆栈打进日志便于定位。
            log.error("提交训练计划出现非业务异常，userId={}", userId, exception);
            return "提交失败：服务端处理计划时出错（" + exception.getClass().getSimpleName()
                    + "）。请稍后重试。" + FIELD_HINT;
        }
    }

    /**
     * 把模型给的各种写法归一化成计划正文。
     *
     * <p>覆盖实测里出现过的写法：字符串正文、正文写在数组里（一天一条）、包一层对象（planContent/content/plan…）、
     * 按天列表（days/tasks 里每项是字符串或带 dayIndex/topic 的对象）。归一化不出来时返回 null，由调用方给出可读提示。
     *
     * @param raw 模型给的原始入参
     * @return 计划正文，取不到时返回 null
     */
    private String normalizePlanContent(Object raw) {
        String content = extractContent(raw, 0);
        if (!StringUtils.hasText(content)) {
            return null;
        }
        return content.strip();
    }

    /**
     * 递归提取文本。
     *
     * @param raw 原始值
     * @param depth 当前递归层数
     * @return 提取到的文本，取不到时返回 null
     */
    private String extractContent(Object raw, int depth) {
        if (raw == null || depth > MAX_DEPTH) {
            return null;
        }
        if (raw instanceof String text) {
            if (looksLikeJson(text)) {
                // 模型把整份入参当成 JSON 字符串塞进来时，先解一层再继续归一化。
                try {
                    String nested = extractContent(JSON.readValue(text, Object.class), depth + 1);
                    if (StringUtils.hasText(nested)) {
                        return nested;
                    }
                } catch (Exception ignored) {
                    // 不是合法 JSON：按普通正文处理。
                }
            }
            return StringUtils.hasText(text) ? text : null;
        }
        if (raw instanceof List<?> list) {
            return joinLines(list);
        }
        if (raw instanceof Map<?, ?> map) {
            Object byContentKey = findValue(map, CONTENT_KEYS);
            String extracted = extractContent(byContentKey, depth + 1);
            if (StringUtils.hasText(extracted)) {
                return extracted;
            }
            return extractDayList(map, depth);
        }
        return null;
    }

    /**
     * 把数组里的每一项抽成一行。
     *
     * @param list 数组
     * @return 合并后的文本，没有可用内容时返回 null
     */
    private String joinLines(List<?> list) {
        List<String> lines = new ArrayList<>();
        for (Object item : list) {
            String line = extractContent(item, 1);
            if (StringUtils.hasText(line)) {
                lines.add(line.strip());
            }
        }
        return lines.isEmpty() ? null : String.join(System.lineSeparator(), lines);
    }

    /**
     * 从「按天列表」里拼出正文（第 N 天：今天练什么）。
     *
     * @param map 原始对象
     * @param depth 当前递归层数
     * @return 拼出来的正文，取不到时返回 null
     */
    private String extractDayList(Map<?, ?> map, int depth) {
        Object listValue = findValue(map, DAY_LIST_KEYS);
        if (!(listValue instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            Object item = list.get(index);
            String text = item instanceof Map<?, ?> itemMap
                    ? extractContent(itemMap, depth + 1) : extractContent(item, depth + 1);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            String stripped = text.strip();
            // 每项本身没写「第 N 天」时补上，保证落到库里的正文仍然是一天一行。
            lines.add(stripped.startsWith("第") ? stripped : "第 " + (index + 1) + " 天：" + stripped);
        }
        return lines.isEmpty() ? null : String.join(System.lineSeparator(), lines);
    }

    /**
     * 按下标名列表在对象里找值（键名比较时忽略大小写、下划线与连字符）。
     *
     * @param map 原始对象
     * @param keys 候选键名
     * @return 命中的值，没有命中时返回 null
     */
    private Object findValue(Map<?, ?> map, List<String> keys) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                continue;
            }
            if (keys.contains(normalizeKey(key))) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 归一化键名：去掉下划线、连字符与空格，转小写。
     *
     * @param key 原始键名
     * @return 归一化后的键名
     */
    private String normalizeKey(String key) {
        return key.replace("_", "").replace("-", "").replace(" ", "").toLowerCase();
    }

    /**
     * 判断一段文本是不是 JSON 对象/数组（用于识别「整份写成 JSON 字符串」的入参）。
     *
     * @param text 文本
     * @return 形如 JSON 时返回 true
     */
    private boolean looksLikeJson(String text) {
        String trimmed = text.strip();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    /**
     * 归一化可选的短文本参数。
     *
     * @param raw 原始入参
     * @return 文本，取不到时返回 null
     */
    private String normalizeText(Object raw) {
        if (raw instanceof String text && StringUtils.hasText(text)) {
            return text.strip();
        }
        if (raw == null) {
            return null;
        }
        String fallback = extractContent(raw, 0);
        return StringUtils.hasText(fallback) ? fallback.strip() : null;
    }
}
