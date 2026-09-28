package com.wxy.career.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.mapper.SysUserMapper;
import com.wxy.career.po.SysUser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.AllArgsConstructor;
import lombok.Getter;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 查询当前登录用户的只读工具。
 *
 * <p>命名遵循 Tool 规范：snake_case、动词开头。用户身份只从 {@link RuntimeContext} 读取，
 * 不接受模型传入的 userId；返回独立 DTO 的 JSON，不直接暴露数据库实体。
 *
 * @author wxy
 * @date 2026-09-28
 */
public class GetCurrentUserTool extends ToolBase {

    /**
     * 工具名，遵循 snake_case 动词开头规范。
     */
    public static final String TOOL_NAME = "get_current_user";

    /**
     * 工具说明，供模型理解调用时机。
     */
    private static final String TOOL_DESCRIPTION = "查询当前登录用户的基本信息，不得编造";

    /**
     * 空入参 JSON Schema。
     */
    private static final Map<String, Object> EMPTY_INPUT_SCHEMA =
            Map.of("type", "object", "properties", Map.of(), "required", List.of());

    /**
     * 用户 Mapper。
     */
    private final SysUserMapper sysUserMapper;

    /**
     * JSON 序列化组件。
     */
    private final ObjectMapper objectMapper;

    /**
     * 构造只读工具并声明只读、并发安全属性。
     *
     * @param sysUserMapper 用户 Mapper
     * @param objectMapper JSON 序列化组件
     */
    public GetCurrentUserTool(SysUserMapper sysUserMapper, ObjectMapper objectMapper) {
        super(ToolBase.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .inputSchema(EMPTY_INPUT_SCHEMA)
                .readOnly(true)
                .concurrencySafe(true));
        this.sysUserMapper = sysUserMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行工具：按会话上下文中的用户 ID 查询昵称。
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
            return ToolResultBlock.error("缺少用户上下文，无法查询当前用户");
        }
        Long userId;
        try {
            userId = Long.valueOf(runtimeContext.getUserId());
        } catch (NumberFormatException exception) {
            return ToolResultBlock.error("用户标识非法，无法查询当前用户");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            return ToolResultBlock.error("当前登录用户不存在");
        }
        try {
            String content = objectMapper.writeValueAsString(
                    new CurrentUserResult(user.getId(), user.getNickname()));
            return ToolResultBlock.of(param.getToolUseBlock().getId(), TOOL_NAME, TextBlock.builder().text(content).build());
        } catch (Exception exception) {
            return ToolResultBlock.error("查询当前用户失败：" + exception.getMessage());
        }
    }

    /**
     * 工具返回的用户信息，只暴露必要字段。
     *
     * @author wxy
     * @date 2026-09-28
     */
    @Getter
    @AllArgsConstructor
    private static class CurrentUserResult {

        /**
         * 用户 ID。
         */
        private final Long userId;

        /**
         * 用户昵称。
         */
        private final String nickname;
    }
}
