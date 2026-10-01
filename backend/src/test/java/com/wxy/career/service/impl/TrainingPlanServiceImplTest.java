package com.wxy.career.service.impl;

import com.wxy.career.common.enums.TrainingPlanStatusEnum;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.mapper.TrainingPlanMapper;
import com.wxy.career.mapper.TrainingReminderMapper;
import com.wxy.career.po.TrainingPlan;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.TrainingPlanRespVO;
import com.wxy.career.vo.UserProfileRespVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 训练计划服务测试。
 *
 * <p>固定三条口径：一份计划就是一条记录（起止日期与每天时长来自用户表单输入）、重规划是覆盖生成且旧计划标记
 * 结束（不物理删除）、提交失败要给得出具体原因。不连数据库、不依赖模型。
 *
 * @author wxy
 * @date 2026-10-01
 */
class TrainingPlanServiceImplTest {

    /**
     * 被测服务。
     */
    private TrainingPlanServiceImpl trainingPlanService;

    /**
     * 计划 Mapper 桩。
     */
    private TrainingPlanMapper trainingPlanMapper;

    /**
     * 组装被测服务。
     */
    @BeforeEach
    void setUp() {
        trainingPlanMapper = mock(TrainingPlanMapper.class);
        TrainingReminderMapper trainingReminderMapper = mock(TrainingReminderMapper.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        UserProfileRespVO profile = new UserProfileRespVO();
        profile.setTargetPosition("Java 后端开发");
        profile.setWorkYears(3);
        when(userProfileService.getRequiredUserProfile(anyLong())).thenReturn(profile);

        trainingPlanService = new TrainingPlanServiceImpl();
        ReflectionTestUtils.setField(trainingPlanService, "trainingPlanMapper", trainingPlanMapper);
        ReflectionTestUtils.setField(trainingPlanService, "trainingReminderMapper", trainingReminderMapper);
        ReflectionTestUtils.setField(trainingPlanService, "userProfileService", userProfileService);
    }

    /**
     * 首次生成：天数与起止日期来自用户表单输入，正文原样落库，目标岗位取生成时的快照。
     */
    @Test
    void shouldCreatePlanWithFormDatesAndContent() {
        List<TrainingPlan> inserted = new ArrayList<>();
        when(trainingPlanMapper.insert(any(TrainingPlan.class))).thenAnswer(invocation -> {
            TrainingPlan plan = invocation.getArgument(0);
            plan.setId(77L);
            inserted.add(plan);
            return 1;
        });

        trainingPlanService.recordGenerationInput(1L, "training-plan-1", 7, 60);
        trainingPlanService.submitPlan(1L, "training-plan-1", "第 1 天：Redis 分布式锁——能讲清加锁、续期、释放三步", null);

        verify(trainingPlanMapper).endActiveByUser(1L);
        assertThat(inserted).hasSize(1);
        TrainingPlan plan = inserted.get(0);
        assertThat(plan.getStatus()).isEqualTo(TrainingPlanStatusEnum.ACTIVE.getValue());
        assertThat(plan.getTotalDays()).isEqualTo(7);
        assertThat(plan.getDailyMinutes()).isEqualTo(60);
        assertThat(plan.getStartDate()).isEqualTo(LocalDate.now());
        assertThat(plan.getEndDate()).isEqualTo(LocalDate.now().plusDays(6));
        assertThat(plan.getPlanContent()).contains("Redis 分布式锁");
        assertThat(plan.getTargetPosition()).isEqualTo("Java 后端开发");
    }

    /**
     * 重新规划：仍写入新计划（旧计划由 Mapper 标记结束），调整原因被保留。
     */
    @Test
    void shouldKeepAdjustmentReasonOnRegeneration() {
        List<TrainingPlan> inserted = new ArrayList<>();
        when(trainingPlanMapper.insert(any(TrainingPlan.class))).thenAnswer(invocation -> {
            TrainingPlan plan = invocation.getArgument(0);
            plan.setId(88L);
            inserted.add(plan);
            return 1;
        });

        trainingPlanService.recordGenerationInput(2L, "training-plan-2", 3, 45);
        trainingPlanService.submitPlan(2L, "training-plan-2", "第 1 天：JVM 内存模型——能画出堆栈与方法区", "新增薄弱点：JVM 内存模型");

        assertThat(inserted).hasSize(1);
        assertThat(inserted.get(0).getAdjustmentReason()).isEqualTo("新增薄弱点：JVM 内存模型");
        assertThat(inserted.get(0).getTotalDays()).isEqualTo(3);
        assertThat(inserted.get(0).getDailyMinutes()).isEqualTo(45);
    }

    /**
     * 正文太短：拒绝并给出可读原因，且原因留在本次运行上供生成流提示。
     */
    @Test
    void shouldRejectTooShortContent() {
        trainingPlanService.recordGenerationInput(1L, "training-plan-1", 7, 60);

        assertThatThrownBy(() -> trainingPlanService.submitPlan(1L, "training-plan-1", "太短", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("计划正文太短");
        verify(trainingPlanMapper, never()).insert(any(TrainingPlan.class));
        assertThat(trainingPlanService.consumeSubmitFailure(1L, "training-plan-1"))
                .contains("计划正文太短");
        assertThat(trainingPlanService.consumeSubmitFailure(1L, "training-plan-1")).isNull();
    }

    /**
     * 没有登记输入（例如直接调用服务层）时拒绝，而不是抛空指针或写出一份没有起止日期的计划。
     */
    @Test
    void shouldRejectSubmitWithoutGenerationInput() {
        assertThatThrownBy(() -> trainingPlanService.submitPlan(
                1L, "training-plan-1", "第 1 天：Redis 分布式锁——能讲清加锁、续期、释放三步", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("输入已失效");
        verify(trainingPlanMapper, never()).insert(any(TrainingPlan.class));
    }

    /**
     * 取走本次生成落库的计划：包含正文与实时剩余天数；取走后再取为空。
     */
    @Test
    void shouldConsumeSubmittedPlanOnce() {
        when(trainingPlanMapper.insert(any(TrainingPlan.class))).thenAnswer(invocation -> {
            TrainingPlan plan = invocation.getArgument(0);
            plan.setId(99L);
            return 1;
        });
        when(trainingPlanMapper.selectByIdAndUser(99L, 1L)).thenAnswer(invocation -> {
            TrainingPlan plan = new TrainingPlan();
            plan.setId(99L);
            plan.setUserId(1L);
            plan.setStatus(TrainingPlanStatusEnum.ACTIVE.getValue());
            plan.setTotalDays(3);
            plan.setDailyMinutes(60);
            plan.setStartDate(LocalDate.now());
            plan.setEndDate(LocalDate.now().plusDays(2));
            plan.setPlanContent("第 1 天：Redis 分布式锁——能讲清加锁、续期、释放三步");
            return plan;
        });
        trainingPlanService.recordGenerationInput(1L, "training-plan-1", 3, 60);
        trainingPlanService.submitPlan(1L, "training-plan-1", "第 1 天：Redis 分布式锁——能讲清加锁、续期、释放三步", null);

        TrainingPlanRespVO plan = trainingPlanService.consumeSubmittedPlan(1L, "training-plan-1");

        assertThat(plan).isNotNull();
        assertThat(plan.getHasPlan()).isTrue();
        assertThat(plan.getRemainingDays()).isEqualTo(3);
        assertThat(plan.getPlanContent()).contains("Redis 分布式锁");
        assertThat(trainingPlanService.consumeSubmittedPlan(1L, "training-plan-1")).isNull();
    }

    /**
     * 没有生效计划时返回空状态而不是抛异常。
     */
    @Test
    void shouldReturnEmptyStateWhenNoActivePlan() {
        when(trainingPlanMapper.selectActiveByUser(1L)).thenReturn(null);

        TrainingPlanRespVO plan = trainingPlanService.getCurrentPlan(1L);

        assertThat(plan.getHasPlan()).isFalse();
        assertThat(plan.getPlanContent()).isNull();
        assertThat(trainingPlanService.hasActivePlan(1L)).isFalse();
    }
}
