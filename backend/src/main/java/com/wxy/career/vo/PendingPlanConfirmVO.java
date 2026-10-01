package com.wxy.career.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 待确认的「覆盖已有计划」快照。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class PendingPlanConfirmVO {

    /**
     * 框架给出的确认回复 ID，恢复时带回。
     */
    private String replyId;

    /**
     * 待确认的工具调用快照。
     */
    private List<PendingPlanToolCallVO> toolCalls = new ArrayList<>();
}
