package com.wxy.career.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.UserProfileRespVO;
import com.wxy.career.vo.UserProfileSaveReqVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 保存当前登录用户求职目标的写工具。
 *
 * <p>用户身份只从 {@link RuntimeContext} 读取，不接受模型传入的 userId；入参校验与接口层保持一致
 * （目标岗位非空且不超过 100 字符、工作年限 0–60），校验不通过返回工具错误结果而不是抛异常，
 * 便于模型自行纠正后重试。
 *
 * @author wxy
 * @date 2026-09-28
 */
public class UpdateUserProfileTool extends ToolBase {

    /**
     * 工具名，遵循 snake_case 动词开头规范。
     */
    public static final String TOOL_NAME = "update_user_profile";

    /**
     * 工具说明，供模型理解调用时机。
     */
    private static final String TOOL_DESCRIPTION =
            "保存当前登录用户的求职目标：目标岗位与当前工作年限，两个字段都是必填";

    /**
     * 目标岗位最大长度，与 user_profile.target_position 和接口校验保持一致。
     */
    private static final int TARGET_POSITION_MAX_LENGTH = 100;

    /**
     * 工作年限上限，与接口校验保持一致。
     */
    private static final int WORK_YEARS_MAX = 60;

    /**
     * 入参 JSON Schema。
     */
    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "targetPosition", Map.of(
                            "type", "string",
                            "description", "目标岗位，非空且不超过 100 个字符"),
                    "workYears", Map.of(
                            "type", "integer",
                            "description", "当前工作年限（年），0–60，0 表示应届或不足一年")),
            "required", List.of("targetPosition", "workYears"));

    /**
     * 求职目标服务。
     */
    private final UserProfileService userProfileService;

    /**
     * JSON 序列化组件。
     */
    private final ObjectMapper objectMapper;

    /**
     * 构造写工具并声明非只读、非并发安全属性。
     *
     * @param userProfileService 求职目标服务
     * @param objectMapper JSON 序列化组件
     */
    public UpdateUserProfileTool(UserProfileService userProfileService, ObjectMapper objectMapper) {
        super(ToolBase.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .inputSchema(INPUT_SCHEMA)
                .readOnly(false)
                .concurrencySafe(false));
        this.userProfileService = userProfileService;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行工具：按会话上下文中的用户 ID 保存求职目标。
     *
     * @param param 工具调用参数
     * @return 工具执行结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param));
    }

    /**
     * 校验入参并保存求职目标。
     *
     * @param param 工具调用参数
     * @return 工具执行结果
     */
    private ToolResultBlock execute(ToolCallParam param) {
        RuntimeContext runtimeContext = param.getRuntimeContext();
        if (runtimeContext == null || runtimeContext.getUserId() == null) {
            return ToolResultBlock.error("缺少用户上下文，无法保存求职目标");
        }
        Long userId;
        try {
            userId = Long.valueOf(runtimeContext.getUserId());
        } catch (NumberFormatException exception) {
            return ToolResultBlock.error("用户标识非法，无法保存求职目标");
        }
        Map<String, Object> input = param.getInput();
        if (input == null) {
            return ToolResultBlock.error("缺少入参，无法保存求职目标");
        }
        String targetPosition = resolveTargetPosition(input.get("targetPosition"));
        if (targetPosition == null) {
            return ToolResultBlock.error("targetPosition 不能为空且不能超过 100 个字符，请补充后重试");
        }
        Integer workYears = resolveWorkYears(input.get("workYears"));
        if (workYears == null) {
            return ToolResultBlock.error("workYears 必须是 0 到 60 之间的整数，请确认后重试");
        }
        UserProfileSaveReqVO reqVO = new UserProfileSaveReqVO();
        reqVO.setTargetPosition(targetPosition);
        reqVO.setWorkYears(workYears);
        try {
            UserProfileRespVO saved = userProfileService.saveUserProfileByUserId(userId, reqVO);
            String content = objectMapper.writeValueAsString(UserProfileToolResult.filled(saved));
            return ToolResultBlock.of(
                    param.getToolUseBlock().getId(), TOOL_NAME, TextBlock.builder().text(content).build());
        } catch (Exception exception) {
            return ToolResultBlock.error("保存求职目标失败：" + exception.getMessage());
        }
    }

    /**
     * 解析并校验目标岗位。
     *
     * @param value 模型传入的原始值
     * @return 去除首尾空白后的目标岗位，非法时返回 null
     */
    private String resolveTargetPosition(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > TARGET_POSITION_MAX_LENGTH) {
            return null;
        }
        return trimmed;
    }

    /**
     * 解析并校验当前工作年限。
     *
     * <p>模型偶尔会把数字写成字符串，这里对「数字」与「数字字符串」都做一次宽松解析，
     * 但仍要求整数且落在 0–60，避免把 3.5 年这类取值写进整数列。
     *
     * @param value 模型传入的原始值
     * @return 工作年限，非法时返回 null
     */
    private Integer resolveWorkYears(Object value) {
        if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            if (asDouble != Math.rint(asDouble)) {
                return null;
            }
            int asInt = number.intValue();
            return asInt >= 0 && asInt <= WORK_YEARS_MAX ? asInt : null;
        }
        if (value instanceof String text) {
            try {
                return resolveWorkYears(Integer.valueOf(text.trim()));
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }
}
