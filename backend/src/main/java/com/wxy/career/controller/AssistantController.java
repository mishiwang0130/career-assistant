package com.wxy.career.controller;

import com.wxy.career.common.result.Result;
import com.wxy.career.service.AssistantService;
import com.wxy.career.vo.AssistantChatReqVO;
import com.wxy.career.vo.AssistantMessageRespVO;
import com.wxy.career.vo.PageRespVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 通用助手接口。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Validated
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    /**
     * 通用助手服务。
     */
    @Resource
    private AssistantService assistantService;

    /**
     * 流式对话接口。
     *
     * <p>该接口不返回统一响应包装：进入流之前（未登录、参数非法）仍由全局异常处理返回 Result，
     * 进入流之后只用 error 事件表达失败。
     *
     * @param reqVO 对话请求
     * @return SSE 响应对象
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody AssistantChatReqVO reqVO) {
        return assistantService.chat(reqVO);
    }

    /**
     * 分页查询历史消息。
     *
     * @param sessionId 会话 ID
     * @param pageNum 页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页消息
     */
    @GetMapping("/messages")
    public Result<PageRespVO<AssistantMessageRespVO>> messages(
            @Pattern(regexp = AssistantChatReqVO.SESSION_ID_REGEXP,
                    message = AssistantChatReqVO.SESSION_ID_PATTERN_MESSAGE)
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return Result.success(assistantService.listMessages(sessionId, pageNum, pageSize));
    }
}
