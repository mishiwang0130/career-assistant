package com.wxy.career.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.UserProfileRespVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 查询当前登录用户求职目标的只读工具。
 *
 * <p>命名遵循 Tool 规范：snake_case、动词开头。用户身份只从 {@link RuntimeContext} 读取
 * （SSE 在弹性线程池执行，请求线程的 {@code LoginUserHolder} 不可用），不接受模型传入的 userId；
 * 未填写返回明确的空状态而不是报错，让模型提示用户去补填。
 *
 * @author wxy
 * @date 2026-09-28
 */
public class GetUserProfileTool extends ToolBase {

    /**
     * 工具名，遵循 snake_case 动词开头规范。
     */
    public static final String TOOL_NAME = "get_user_profile";

    /**
     * 工具说明，供模型理解调用时机。
     */
    private static final String TOOL_DESCRIPTION =
            "查询当前登录用户的求职目标（目标岗位、当前工作年限）；未填写时返回 filled=false，不得编造";

    /**
     * 空入参 JSON Schema。
     */
    private static final Map<String, Object> EMPTY_INPUT_SCHEMA =
            Map.of("type", "object", "properties", Map.of(), "required", List.of());

    /**
     * 求职目标服务。
     */
    private final UserProfileService userProfileService;

    /**
     * JSON 序列化组件。
     */
    private final ObjectMapper objectMapper;

    /**
     * 构造只读工具并声明只读、并发安全属性。
     *
     * @param userProfileService 求职目标服务
     * @param objectMapper JSON 序列化组件
     */
    public GetUserProfileTool(UserProfileService userProfileService, ObjectMapper objectMapper) {
        super(ToolBase.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .inputSchema(EMPTY_INPUT_SCHEMA)
                .readOnly(true)
                .concurrencySafe(true));
        this.userProfileService = userProfileService;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行工具：按会话上下文中的用户 ID 查询求职目标。
     *
     * @param param 工具调用参数
     * @return 工具执行结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param));
    }

    /**
     * 执行查询并组装工具结果。
     *
     * @param param 工具调用参数
     * @return 工具执行结果
     */
    private ToolResultBlock execute(ToolCallParam param) {
        RuntimeContext runtimeContext = param.getRuntimeContext();
        if (runtimeContext == null || runtimeContext.getUserId() == null) {
            return ToolResultBlock.error("缺少用户上下文，无法查询求职目标");
        }
        Long userId;
        try {
            userId = Long.valueOf(runtimeContext.getUserId());
        } catch (NumberFormatException exception) {
            return ToolResultBlock.error("用户标识非法，无法查询求职目标");
        }
        UserProfileRespVO userProfile = userProfileService.getUserProfileByUserId(userId);
        UserProfileToolResult result = userProfile == null
                ? UserProfileToolResult.missing() : UserProfileToolResult.filled(userProfile);
        try {
            String content = objectMapper.writeValueAsString(result);
            return ToolResultBlock.of(
                    param.getToolUseBlock().getId(), TOOL_NAME, TextBlock.builder().text(content).build());
        } catch (Exception exception) {
            return ToolResultBlock.error("查询求职目标失败：" + exception.getMessage());
        }
    }
}
