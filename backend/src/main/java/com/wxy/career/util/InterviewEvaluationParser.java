package com.wxy.career.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.vo.AnswerEvaluationSubmitVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 逐题点评结论（{@code interview_qa.evaluation_json}）的解析工具。
 *
 * <p>F6 的点评下发、掌握度沉淀与报告生成都要读这份结论，解析口径集中在这一处：解析失败或内容为空时
 * 返回 null，由调用方按「评分不可用」处理，不抛异常、不影响面试主流程。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
public final class InterviewEvaluationParser {

    /**
     * 工具类禁止实例化。
     */
    private InterviewEvaluationParser() {
    }

    /**
     * 解析评分结论 JSON。
     *
     * @param objectMapper JSON 组件
     * @param evaluationJson 评分结论 JSON，可为空
     * @return 评分结论，解析失败或为空时返回 null
     */
    public static AnswerEvaluationSubmitVO parse(ObjectMapper objectMapper, String evaluationJson) {
        if (objectMapper == null || !StringUtils.hasText(evaluationJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(evaluationJson, AnswerEvaluationSubmitVO.class);
        } catch (Exception exception) {
            log.warn("解析逐题点评结论失败，按评分不可用处理", exception);
            return null;
        }
    }

    /**
     * 取结论里的知识点清单，空清单归一化为空列表。
     *
     * @param evaluation 评分结论，可为 null
     * @return 知识点清单
     */
    public static List<String> knowledgePoints(AnswerEvaluationSubmitVO evaluation) {
        if (evaluation == null || evaluation.getKnowledgePoints() == null) {
            return List.of();
        }
        return evaluation.getKnowledgePoints().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }
}
