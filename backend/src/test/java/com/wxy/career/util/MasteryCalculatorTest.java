package com.wxy.career.util;

import com.wxy.career.common.enums.InterviewOutcomeEnum;
import com.wxy.career.common.enums.KnowledgeMasteryLevelEnum;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 掌握度计算口径单测。
 *
 * <p>口径是产品规则（写进 docs/技术约定.md 与 mastery-evaluation 技能），这里把最容易做错的四条固化下来：
 * 单题答错不会把掌握度打到最低、连续多次证据才平滑变化、90 天窗口边界、以及等级与薄弱点判定边界。
 * 纯函数计算，不依赖 MySQL、Redis 与模型。
 *
 * @author wxy
 * @date 2026-09-29
 */
class MasteryCalculatorTest {

    /**
     * 计算基准时间，固定值让「距今天数」可复现。
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 20, 0);

    /**
     * 单题答错：掌握度落到 40（先验 60 与 20 分证据各占一半），不会打到最低，但已经算薄弱点。
     */
    @Test
    void shouldNotFloorMasteryAfterSingleWrongAnswer() {
        MasteryCalculator.MasteryResult result = MasteryCalculator.calculate(
                List.of(evidence("Redis 分布式锁", 20, InterviewOutcomeEnum.WRONG, 0)), NOW);

        assertThat(result.score()).isEqualTo(40);
        assertThat(result.level()).isEqualTo(KnowledgeMasteryLevelEnum.NEEDS_WORK);
        assertThat(result.weak()).isTrue();
        assertThat(result.evidenceCount()).isEqualTo(1);
        assertThat(result.lastOutcome()).isEqualTo(InterviewOutcomeEnum.WRONG.getValue());
    }

    /**
     * 连续答错才平滑下沉：第三次答错仍在 30 分以上，第四次才落到「薄弱」。
     */
    @Test
    void shouldSinkSmoothlyOnlyWithRepeatedEvidence() {
        List<MasteryCalculator.Evidence> evidences = new ArrayList<>();
        evidences.add(evidence("线程池参数", 20, InterviewOutcomeEnum.WRONG, 0));
        assertThat(MasteryCalculator.calculate(evidences, NOW).score()).isEqualTo(40);

        evidences.add(evidence("线程池参数", 20, InterviewOutcomeEnum.WRONG, 0));
        assertThat(MasteryCalculator.calculate(evidences, NOW).score()).isEqualTo(33);

        evidences.add(evidence("线程池参数", 20, InterviewOutcomeEnum.WRONG, 0));
        MasteryCalculator.MasteryResult third = MasteryCalculator.calculate(evidences, NOW);
        assertThat(third.score()).isEqualTo(30);
        assertThat(third.level()).isEqualTo(KnowledgeMasteryLevelEnum.NEEDS_WORK);

        evidences.add(evidence("线程池参数", 20, InterviewOutcomeEnum.WRONG, 0));
        MasteryCalculator.MasteryResult fourth = MasteryCalculator.calculate(evidences, NOW);
        assertThat(fourth.score()).isEqualTo(28);
        assertThat(fourth.level()).isEqualTo(KnowledgeMasteryLevelEnum.WEAK);
    }

    /**
     * 答对后平滑回升：先三次答错再连续答对，掌握度一路往上走，不会因为历史错题卡死。
     */
    @Test
    void shouldRecoverSmoothlyWithCorrectEvidence() {
        List<MasteryCalculator.Evidence> evidences = new ArrayList<>();
        for (int index = 0; index < 3; index += 1) {
            evidences.add(evidence("MySQL 索引", 20, InterviewOutcomeEnum.WRONG, 0));
        }
        int lowest = MasteryCalculator.calculate(evidences, NOW).score();

        evidences.add(evidence("MySQL 索引", 90, InterviewOutcomeEnum.CORRECT, 0));
        int afterFirstCorrect = MasteryCalculator.calculate(evidences, NOW).score();
        evidences.add(evidence("MySQL 索引", 90, InterviewOutcomeEnum.CORRECT, 0));
        int afterSecondCorrect = MasteryCalculator.calculate(evidences, NOW).score();

        assertThat(lowest).isEqualTo(30);
        assertThat(afterFirstCorrect).isGreaterThan(lowest);
        assertThat(afterSecondCorrect).isGreaterThan(afterFirstCorrect);
        // 一直答对时掌握度能升回「基本掌握」以上：单条答对经先验平滑后是 75 分
        MasteryCalculator.MasteryResult singleCorrect = MasteryCalculator.calculate(
                List.of(evidence("MySQL 索引", 90, InterviewOutcomeEnum.CORRECT, 0)), NOW);
        assertThat(singleCorrect.score()).isEqualTo(75);
        assertThat(singleCorrect.level()).isEqualTo(KnowledgeMasteryLevelEnum.PROFICIENT);
    }

    /**
     * 时间窗口边界：第 90 天的证据算数，第 91 天的不算；证据条数上限只取最近 8 条。
     */
    @Test
    void shouldApplyWindowAndEvidenceLimit() {
        MasteryCalculator.MasteryResult inWindow = MasteryCalculator.calculate(
                List.of(evidence("JVM 内存", 20, InterviewOutcomeEnum.WRONG, MasteryCalculator.WINDOW_DAYS)), NOW);
        MasteryCalculator.MasteryResult outOfWindow = MasteryCalculator.calculate(
                List.of(evidence("JVM 内存", 20, InterviewOutcomeEnum.WRONG,
                        MasteryCalculator.WINDOW_DAYS + 1)), NOW);

        assertThat(inWindow.evidenceCount()).isEqualTo(1);
        assertThat(inWindow.weak()).isTrue();
        // 窗口外没有证据，只剩中性先验：掌握度回到 60，且不再是薄弱点
        assertThat(outOfWindow.evidenceCount()).isZero();
        assertThat(outOfWindow.score()).isEqualTo(MasteryCalculator.PRIOR_SCORE);
        assertThat(outOfWindow.weak()).isFalse();

        List<MasteryCalculator.Evidence> many = new ArrayList<>();
        for (int index = 0; index < 12; index += 1) {
            many.add(evidence("JVM 内存", 20, InterviewOutcomeEnum.WRONG, index));
        }
        assertThat(MasteryCalculator.calculate(many, NOW).evidenceCount())
                .isEqualTo(MasteryCalculator.MAX_EVIDENCE_COUNT);
    }

    /**
     * 时间衰减：同样一条证据，越近的越算数——旧证据会被新证据摊薄。
     */
    @Test
    void shouldDecayOlderEvidenceByHalfLife() {
        int withFreshWrong = MasteryCalculator.calculate(
                List.of(evidence("分布式事务", 20, InterviewOutcomeEnum.WRONG, 0)), NOW).score();
        int withHalfLifeOldWrong = MasteryCalculator.calculate(
                List.of(evidence("分布式事务", 20, InterviewOutcomeEnum.WRONG,
                        (int) MasteryCalculator.HALF_LIFE_DAYS)), NOW).score();

        assertThat(withHalfLifeOldWrong).isGreaterThan(withFreshWrong);
    }

    /**
     * 等级边界与薄弱点判定：掌握度低于 60 算薄弱，最近一条证据是 WRONG 也算薄弱。
     */
    @Test
    void shouldJudgeLevelAndWeakAtBoundaries() {
        assertThat(KnowledgeMasteryLevelEnum.fromScore(29)).isEqualTo(KnowledgeMasteryLevelEnum.WEAK);
        assertThat(KnowledgeMasteryLevelEnum.fromScore(30)).isEqualTo(KnowledgeMasteryLevelEnum.NEEDS_WORK);
        assertThat(KnowledgeMasteryLevelEnum.fromScore(59)).isEqualTo(KnowledgeMasteryLevelEnum.NEEDS_WORK);
        assertThat(KnowledgeMasteryLevelEnum.fromScore(60)).isEqualTo(KnowledgeMasteryLevelEnum.BASIC);
        assertThat(KnowledgeMasteryLevelEnum.fromScore(75)).isEqualTo(KnowledgeMasteryLevelEnum.PROFICIENT);
        assertThat(KnowledgeMasteryLevelEnum.fromScore(90)).isEqualTo(KnowledgeMasteryLevelEnum.MASTERED);

        // 最近一条是答对，但历史错题太多导致掌握度低于 60：仍然算薄弱点
        List<MasteryCalculator.Evidence> evidences = List.of(
                evidence("Redis 持久化", 90, InterviewOutcomeEnum.CORRECT, 0),
                evidence("Redis 持久化", 20, InterviewOutcomeEnum.WRONG, 0),
                evidence("Redis 持久化", 20, InterviewOutcomeEnum.WRONG, 0),
                evidence("Redis 持久化", 20, InterviewOutcomeEnum.WRONG, 0));
        MasteryCalculator.MasteryResult result = MasteryCalculator.calculate(evidences, NOW);
        assertThat(result.score()).isLessThan(MasteryCalculator.WEAK_SCORE_THRESHOLD);
        assertThat(result.lastOutcome()).isEqualTo(InterviewOutcomeEnum.CORRECT.getValue());
        assertThat(result.weak()).isTrue();
    }

    /**
     * 没有参考分时按判定折算：答到要点 90、有遗漏 65、不会或答错 20。
     */
    @Test
    void shouldMapOutcomeWhenScoreMissing() {
        assertThat(MasteryCalculator.evidenceScore(null, InterviewOutcomeEnum.CORRECT.getValue())).isEqualTo(90);
        assertThat(MasteryCalculator.evidenceScore(null, InterviewOutcomeEnum.PARTIAL.getValue())).isEqualTo(65);
        assertThat(MasteryCalculator.evidenceScore(null, InterviewOutcomeEnum.WRONG.getValue())).isEqualTo(20);
        // 判定缺失时按最低一档折算，不给「可能答对了」的假设
        assertThat(MasteryCalculator.evidenceScore(null, null)).isEqualTo(20);
        // 有点评分时优先用评分，并收敛到 0-100
        assertThat(MasteryCalculator.evidenceScore(85, InterviewOutcomeEnum.WRONG.getValue())).isEqualTo(85);
        assertThat(MasteryCalculator.evidenceScore(130, null)).isEqualTo(100);
    }

    /**
     * 构造一条证据。
     *
     * @param knowledgePoint 知识点
     * @param score 参考分
     * @param outcome 判定结果
     * @param ageDays 距基准时间的天数
     * @return 证据
     */
    private MasteryCalculator.Evidence evidence(
            String knowledgePoint, Integer score, InterviewOutcomeEnum outcome, int ageDays) {
        return new MasteryCalculator.Evidence(
                knowledgePoint, score, outcome.getValue(), NOW.minusDays(ageDays));
    }
}
