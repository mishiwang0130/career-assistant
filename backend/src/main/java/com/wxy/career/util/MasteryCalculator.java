package com.wxy.career.util;

import com.wxy.career.common.enums.InterviewOutcomeEnum;
import com.wxy.career.common.enums.KnowledgeMasteryLevelEnum;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 知识点掌握度计算器（纯函数）。
 *
 * <p>口径写死在 {@code docs/技术约定.md} 的「面试点评与报告（F6）」章节与 {@code mastery-evaluation} 技能里，
 * 这里实现同一套数值：只取 90 天窗口内最近的若干条证据，按半衰期 30 天做指数衰减加权，再叠一条分数 60、
 * 权重 1.0 的中性先验。先验的作用是「还没形成判断」——答错一两道题不会把掌握度直接打到最低，只有连续多次
 * 证据才会平滑地把分数压下去。
 *
 * @author wxy
 * @date 2026-09-29
 */
public final class MasteryCalculator {

    /**
     * 证据窗口天数：只看最近 90 天内的证据。
     */
    public static final int WINDOW_DAYS = 90;

    /**
     * 单个知识点参与计算的证据条数上限，取最近的若干条。
     */
    public static final int MAX_EVIDENCE_COUNT = 8;

    /**
     * 时间衰减半衰期天数：权重 = 0.5 ^ (距今天数 / 30)。
     */
    public static final double HALF_LIFE_DAYS = 30D;

    /**
     * 中性先验的分数。
     */
    public static final int PRIOR_SCORE = 60;

    /**
     * 中性先验的权重。
     */
    public static final double PRIOR_WEIGHT = 1D;

    /**
     * 薄弱点的掌握度阈值：低于该值即算薄弱点。
     */
    public static final int WEAK_SCORE_THRESHOLD = 60;

    /**
     * 没有参考分时，「答到要点」折算的证据分。
     */
    private static final int SCORE_CORRECT = 90;

    /**
     * 没有参考分时，「答得有遗漏」折算的证据分。
     */
    private static final int SCORE_PARTIAL = 65;

    /**
     * 没有参考分时，「完全不会或答错」折算的证据分。
     */
    private static final int SCORE_WRONG = 20;

    /**
     * 证据分下限。
     */
    private static final int SCORE_MIN = 0;

    /**
     * 证据分上限。
     */
    private static final int SCORE_MAX = 100;

    /**
     * 工具类禁止实例化。
     */
    private MasteryCalculator() {
    }

    /**
     * 一条掌握度证据：某次面试里对某个知识点的点评结论。
     *
     * @param knowledgePoint 知识点名称
     * @param score 点评给出的 0-100 参考分，没有时为 null
     * @param outcome 判定结果，取值见 InterviewOutcomeEnum
     * @param time 证据时间（问答记录的创建时间）
     *
     * @author wxy
     * @date 2026-09-29
     */
    public record Evidence(String knowledgePoint, Integer score, String outcome, LocalDateTime time) {
    }

    /**
     * 一次掌握度计算的结果。
     *
     * @param score 掌握度分数 0-100
     * @param level 掌握度等级
     * @param weak 是否薄弱点
     * @param evidenceCount 参与计算的证据条数（不含中性先验）
     * @param lastOutcome 最近一条证据的判定，没有证据时为 null
     * @param lastEvidenceTime 最近一条证据的时间，没有证据时为 null
     *
     * @author wxy
     * @date 2026-09-29
     */
    public record MasteryResult(
            int score,
            KnowledgeMasteryLevelEnum level,
            boolean weak,
            int evidenceCount,
            String lastOutcome,
            LocalDateTime lastEvidenceTime) {
    }

    /**
     * 计算某个知识点的掌握度。
     *
     * <p>调用方保证传入的证据都属于同一个知识点；本方法会按时间窗口过滤、按时间倒序取最近若干条，
     * 因此窗口边界与取舍规则只在这一处实现，便于单测直接覆盖。
     *
     * @param evidences 该知识点的证据列表，可为 null 或空
     * @param now 计算基准时间，用于换算证据年龄
     * @return 掌握度结果
     */
    public static MasteryResult calculate(List<Evidence> evidences, LocalDateTime now) {
        LocalDateTime baseTime = now == null ? LocalDateTime.now() : now;
        List<Evidence> windowEvidences = (evidences == null ? List.<Evidence>of() : evidences).stream()
                .filter(evidence -> evidence != null && evidence.time() != null)
                .filter(evidence -> !evidence.time().isBefore(baseTime.minusDays(WINDOW_DAYS)))
                .sorted(Comparator.comparing(Evidence::time).reversed())
                .limit(MAX_EVIDENCE_COUNT)
                .toList();
        double weightedScore = PRIOR_SCORE * PRIOR_WEIGHT;
        double weightSum = PRIOR_WEIGHT;
        for (Evidence evidence : windowEvidences) {
            double weight = decayWeight(evidence.time(), baseTime);
            weightedScore += weight * evidenceScore(evidence.score(), evidence.outcome());
            weightSum += weight;
        }
        int score = weightSum <= 0D ? PRIOR_SCORE
                : (int) Math.round(Math.min(Math.max(weightedScore / weightSum, SCORE_MIN), SCORE_MAX));
        String lastOutcome = windowEvidences.isEmpty() ? null : windowEvidences.get(0).outcome();
        LocalDateTime lastEvidenceTime = windowEvidences.isEmpty() ? null : windowEvidences.get(0).time();
        boolean weak = InterviewOutcomeEnum.WRONG.getValue().equals(lastOutcome) || score < WEAK_SCORE_THRESHOLD;
        return new MasteryResult(
                score, KnowledgeMasteryLevelEnum.fromScore(score), weak, windowEvidences.size(),
                lastOutcome, lastEvidenceTime);
    }

    /**
     * 计算单条证据的分数：有点评分就用评分，没有就按判定折算。
     *
     * @param score 参考分，可为 null
     * @param outcome 判定结果
     * @return 0-100 的证据分
     */
    public static int evidenceScore(Integer score, String outcome) {
        if (score != null) {
            return Math.min(Math.max(score, SCORE_MIN), SCORE_MAX);
        }
        InterviewOutcomeEnum outcomeEnum = InterviewOutcomeEnum.find(outcome);
        if (outcomeEnum == InterviewOutcomeEnum.CORRECT) {
            return SCORE_CORRECT;
        }
        if (outcomeEnum == InterviewOutcomeEnum.PARTIAL) {
            return SCORE_PARTIAL;
        }
        // 判定缺失或为 WRONG 时按最低一档折算：缺少评分结论时宁可保守，也不给「可能答对了」的假设。
        return SCORE_WRONG;
    }

    /**
     * 计算时间衰减权重：半衰期 30 天，越近的证据越算数。
     *
     * @param evidenceTime 证据时间
     * @param baseTime 计算基准时间
     * @return 权重，取值 (0, 1]
     */
    private static double decayWeight(LocalDateTime evidenceTime, LocalDateTime baseTime) {
        long ageMinutes = Math.max(Duration.between(evidenceTime, baseTime).toMinutes(), 0L);
        double ageDays = ageMinutes / (60D * 24D);
        return Math.pow(0.5D, ageDays / HALF_LIFE_DAYS);
    }
}
