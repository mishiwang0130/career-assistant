package com.wxy.career.service;

import com.wxy.career.vo.TrainingPlanConfirmReqVO;
import com.wxy.career.vo.TrainingPlanGenerateReqVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 训练计划生成服务（两阶段 HITL）。
 *
 * <p>计划页不是会话，但生成与确认是两个请求：第一次请求跑计划 Agent 并可能在「提交计划」这一步停下等确认，
 * 第二次请求把确认结论送回同一次运行。两个阶段都用 SSE 推送过程与最终结果。
 *
 * @author wxy
 * @date 2026-10-01
 */
public interface TrainingPlanGenerationService {

    /**
     * 生成（或重新规划）训练计划。
     *
     * <p>首次生成会自动确认写入；已有生效计划时先下发确认请求并结束本次流，等用户确认后再继续。
     *
     * @param reqVO 生成参数（还有几天、每天多长时间）
     * @return SSE 响应对象
     */
    SseEmitter generate(TrainingPlanGenerateReqVO reqVO);

    /**
     * 回填「是否覆盖已有计划」的确认结论并继续生成。
     *
     * @param reqVO 确认结果
     * @return SSE 响应对象
     */
    SseEmitter confirm(TrainingPlanConfirmReqVO reqVO);
}
