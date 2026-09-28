package com.wxy.career.service;

import com.wxy.career.vo.AssistantChatReqVO;
import com.wxy.career.vo.AssistantMessageRespVO;
import com.wxy.career.vo.PageRespVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 通用助手服务。
 *
 * @author wxy
 * @date 2026-09-28
 */
public interface AssistantService {

    /**
     * 发送一条消息并流式返回 Agent 回复。
     *
     * @param reqVO 对话请求
     * @return SSE 响应对象
     */
    SseEmitter chat(AssistantChatReqVO reqVO);

    /**
     * 分页查询当前用户指定会话的历史消息。
     *
     * @param sessionId 会话 ID
     * @param pageNum 页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页消息
     */
    PageRespVO<AssistantMessageRespVO> listMessages(String sessionId, long pageNum, long pageSize);

    /**
     * 清空当前用户指定会话的历史消息与 Agent 上下文。
     *
     * @param sessionId 会话 ID
     */
    void clearSession(String sessionId);
}
