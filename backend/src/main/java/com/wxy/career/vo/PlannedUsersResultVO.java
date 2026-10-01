package com.wxy.career.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 需要提醒的用户列表（提醒 Agent 的只读工具返回）。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class PlannedUsersResultVO {

    /**
     * 是否需要生成提醒，false 表示当前没有任何用户需要提醒。
     */
    private boolean hasData;

    /**
     * 本次返回的用户数。
     */
    private int count;

    /**
     * 说明文字，空集时给出可转述的原因（例如「今天没有需要提醒的用户」）。
     */
    private String message;

    /**
     * 用户简报列表。
     */
    private List<PlannedUserBriefingVO> users = new ArrayList<>();
}
