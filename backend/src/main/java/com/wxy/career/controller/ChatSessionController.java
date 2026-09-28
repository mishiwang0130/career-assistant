package com.wxy.career.controller;

import com.wxy.career.common.result.Result;
import com.wxy.career.service.ChatSessionService;
import com.wxy.career.vo.AssistantChatReqVO;
import com.wxy.career.vo.ChatSessionCreateReqVO;
import com.wxy.career.vo.ChatSessionRenameReqVO;
import com.wxy.career.vo.ChatSessionRespVO;
import com.wxy.career.vo.PageRespVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话中心接口。
 *
 * <p>会话 ID 由后端生成并返回，前端不再自造；所有接口的用户身份都取自登录态。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Validated
@RestController
@RequestMapping("/api/sessions")
public class ChatSessionController {

    /**
     * 会话中心服务。
     */
    @Resource
    private ChatSessionService chatSessionService;

    /**
     * 新建会话。
     *
     * @param reqVO 新建请求
     * @return 新建的会话
     */
    @PostMapping
    public Result<ChatSessionRespVO> create(@Valid @RequestBody ChatSessionCreateReqVO reqVO) {
        return Result.success(chatSessionService.create(reqVO));
    }

    /**
     * 分页查询当前用户会话列表，最近消息时间倒序。
     *
     * @param pageNum 页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页会话列表
     */
    @GetMapping
    public Result<PageRespVO<ChatSessionRespVO>> list(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return Result.success(chatSessionService.list(pageNum, pageSize));
    }

    /**
     * 重命名当前用户的会话。
     *
     * @param sessionId 会话 ID
     * @param reqVO 重命名请求
     * @return 更新后的会话
     */
    @PatchMapping("/{sessionId}")
    public Result<ChatSessionRespVO> rename(
            @PathVariable
            @Pattern(regexp = AssistantChatReqVO.SESSION_ID_REGEXP,
                    message = AssistantChatReqVO.SESSION_ID_PATTERN_MESSAGE)
            String sessionId,
            @Valid @RequestBody ChatSessionRenameReqVO reqVO) {
        return Result.success(chatSessionService.rename(sessionId, reqVO));
    }

    /**
     * 删除当前用户的会话。
     *
     * @param sessionId 会话 ID
     * @return 成功响应
     */
    @DeleteMapping("/{sessionId}")
    public Result<Void> delete(
            @PathVariable
            @Pattern(regexp = AssistantChatReqVO.SESSION_ID_REGEXP,
                    message = AssistantChatReqVO.SESSION_ID_PATTERN_MESSAGE)
            String sessionId) {
        chatSessionService.delete(sessionId);
        return Result.success();
    }
}
