package com.wxy.career.vo;

import lombok.Data;

/**
 * 待确认计划里的工具调用快照。
 *
 * <p>HITL 的确认请求与恢复是两个请求：确认请求里要带回框架要求的工具调用（含 id 与入参），才能让同一个
 * 待确认调用继续执行，因此把快照存进 Redis，恢复时按快照重建。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class PendingPlanToolCallVO {

    /**
     * 工具调用 ID，框架按它匹配待确认的调用。
     */
    private String id;

    /**
     * 工具名，本项目固定为 submit_training_plan。
     */
    private String name;

    /**
     * 工具入参 JSON 字符串，恢复时反序列化成 Map。
     */
    private String inputJson;
}
