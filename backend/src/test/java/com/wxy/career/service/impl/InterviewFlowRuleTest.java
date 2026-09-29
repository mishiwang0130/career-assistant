package com.wxy.career.service.impl;

import com.wxy.career.common.enums.InterviewActionEnum;
import com.wxy.career.common.enums.InterviewOutcomeEnum;
import com.wxy.career.common.enums.InterviewQuestionTypeEnum;
import com.wxy.career.po.InterviewQa;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 面试流程规则单测。
 *
 * <p>把三条写死的业务规则与难度阶梯固定下来：答到要点追问并上调、答得有遗漏只追问遗漏点、答错换题不再纠缠；
 * 外加「答错后难度不低于起始难度」「最后一题收口后结束」「题型按 4:2:2 铺排」的边界用例。
 * 规则全是纯函数，因此这里不需要 Spring、数据库与模型。
 *
 * @author wxy
 * @date 2026-09-29
 */
class InterviewFlowRuleTest {

    /**
     * 演示用题量。
     */
    private static final int QUESTION_COUNT = 8;

    /**
     * 答到要点：追问一层，难度上调一级。
     */
    @Test
    void shouldFollowUpAndRaiseDifficultyWhenAnswerIsCorrect() {
        InterviewActionEnum action = InterviewFlowServiceImpl.decideAction(
                1, InterviewQa.ROUND_MAIN, QUESTION_COUNT, InterviewOutcomeEnum.CORRECT, false);

        assertThat(action).isEqualTo(InterviewActionEnum.FOLLOW_UP);
        assertThat(InterviewFlowServiceImpl.nextDifficulty(3, 2, InterviewOutcomeEnum.CORRECT)).isEqualTo(4);
        // 上调有上限：已经到 5 级时不再上升。
        assertThat(InterviewFlowServiceImpl.nextDifficulty(5, 2, InterviewOutcomeEnum.CORRECT)).isEqualTo(5);
    }

    /**
     * 答得有遗漏：同样追问一层，但难度持平。
     */
    @Test
    void shouldFollowUpAndKeepDifficultyWhenAnswerIsPartial() {
        InterviewActionEnum action = InterviewFlowServiceImpl.decideAction(
                2, InterviewQa.ROUND_MAIN, QUESTION_COUNT, InterviewOutcomeEnum.PARTIAL, false);

        assertThat(action).isEqualTo(InterviewActionEnum.FOLLOW_UP);
        assertThat(InterviewFlowServiceImpl.nextDifficulty(3, 2, InterviewOutcomeEnum.PARTIAL)).isEqualTo(3);
    }

    /**
     * 完全不会或答错：记为错题、直接换新题，难度持平。
     */
    @Test
    void shouldSwitchQuestionAndKeepDifficultyWhenAnswerIsWrong() {
        InterviewActionEnum action = InterviewFlowServiceImpl.decideAction(
                2, InterviewQa.ROUND_MAIN, QUESTION_COUNT, InterviewOutcomeEnum.WRONG, false);

        assertThat(action).isEqualTo(InterviewActionEnum.NEXT_QUESTION);
        assertThat(InterviewFlowServiceImpl.nextDifficulty(4, 2, InterviewOutcomeEnum.WRONG)).isEqualTo(4);
    }

    /**
     * 边界：连续答错时难度不会掉到起始难度以下，也不会低于当前这题。
     */
    @Test
    void shouldNeverDropBelowStartDifficulty() {
        int difficulty = InterviewFlowServiceImpl.resolveStartDifficulty(4);
        assertThat(difficulty).isEqualTo(3);

        // 连续答错：一直停在起始难度。
        for (int round = 0; round < 5; round++) {
            difficulty = InterviewFlowServiceImpl.nextDifficulty(
                    difficulty, 3, InterviewOutcomeEnum.WRONG);
            assertThat(difficulty).isEqualTo(3);
        }
        // 档案被改成更高年限后，起始难度上移，当前难度也不会被拉到下限之下。
        assertThat(InterviewFlowServiceImpl.nextDifficulty(3, 5, InterviewOutcomeEnum.WRONG)).isEqualTo(5);
    }

    /**
     * 结束条件：题量走满后不再出新题，最后一题也允许一次追问。
     */
    @Test
    void shouldFinishAfterLastQuestion() {
        // 最后一题答到要点：仍追问一层（追问属于这道题，不额外占题量）。
        assertThat(InterviewFlowServiceImpl.decideAction(
                QUESTION_COUNT, InterviewQa.ROUND_MAIN, QUESTION_COUNT,
                InterviewOutcomeEnum.CORRECT, false))
                .isEqualTo(InterviewActionEnum.FOLLOW_UP);
        // 最后一题答错：直接收尾。
        assertThat(InterviewFlowServiceImpl.decideAction(
                QUESTION_COUNT, InterviewQa.ROUND_MAIN, QUESTION_COUNT,
                InterviewOutcomeEnum.WRONG, false))
                .isEqualTo(InterviewActionEnum.FINISHED);
        // 追问答完：收尾。
        assertThat(InterviewFlowServiceImpl.decideAction(
                QUESTION_COUNT, InterviewQa.ROUND_FOLLOW_UP, QUESTION_COUNT,
                InterviewOutcomeEnum.CORRECT, false))
                .isEqualTo(InterviewActionEnum.FINISHED);
    }

    /**
     * 用户主动要求结束时直接收尾。
     */
    @Test
    void shouldFinishWhenUserAsksToEnd() {
        assertThat(InterviewFlowServiceImpl.decideAction(
                2, InterviewQa.ROUND_MAIN, QUESTION_COUNT, InterviewOutcomeEnum.CORRECT, true))
                .isEqualTo(InterviewActionEnum.FINISHED);
    }

    /**
     * 起始难度按工作年限映射，最高 5 级。
     */
    @Test
    void shouldResolveStartDifficultyByWorkYears() {
        assertThat(InterviewFlowServiceImpl.resolveStartDifficulty(0)).isEqualTo(1);
        assertThat(InterviewFlowServiceImpl.resolveStartDifficulty(1)).isEqualTo(1);
        assertThat(InterviewFlowServiceImpl.resolveStartDifficulty(2)).isEqualTo(2);
        assertThat(InterviewFlowServiceImpl.resolveStartDifficulty(3)).isEqualTo(2);
        assertThat(InterviewFlowServiceImpl.resolveStartDifficulty(5)).isEqualTo(3);
        assertThat(InterviewFlowServiceImpl.resolveStartDifficulty(8)).isEqualTo(4);
        assertThat(InterviewFlowServiceImpl.resolveStartDifficulty(9)).isEqualTo(5);
        assertThat(InterviewFlowServiceImpl.resolveStartDifficulty(30)).isEqualTo(5);
    }

    /**
     * 题型按 4:2:2 的参考配比铺排，且不出现三道同型连排。
     */
    @Test
    void shouldRecommendQuestionTypesByRatio() {
        List<InterviewQuestionTypeEnum> types = new ArrayList<>(QUESTION_COUNT);
        for (int index = 1; index <= QUESTION_COUNT; index++) {
            types.add(InterviewFlowServiceImpl.recommendQuestionType(index, QUESTION_COUNT));
        }

        assertThat(types.stream().filter(type -> type == InterviewQuestionTypeEnum.BASIC).count()).isEqualTo(4);
        assertThat(types.stream().filter(type -> type == InterviewQuestionTypeEnum.PROJECT).count()).isEqualTo(2);
        assertThat(types.stream()
                .filter(type -> type == InterviewQuestionTypeEnum.COMPREHENSIVE).count()).isEqualTo(2);
        for (int index = 2; index < types.size(); index++) {
            boolean triple = types.get(index).equals(types.get(index - 1))
                    && types.get(index).equals(types.get(index - 2));
            assertThat(triple).isFalse();
        }

        // 非 8 题时按比例折算，三类题数量之和始终等于题量。
        List<InterviewQuestionTypeEnum> five = new ArrayList<>(5);
        for (int index = 1; index <= 5; index++) {
            five.add(InterviewFlowServiceImpl.recommendQuestionType(index, 5));
        }
        assertThat(five).hasSize(5);
        assertThat(five.stream().filter(type -> type == InterviewQuestionTypeEnum.BASIC).count()).isEqualTo(3);
    }
}
