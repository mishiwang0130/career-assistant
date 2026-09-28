package com.wxy.career.service;

import com.wxy.career.common.enums.MessageRoleEnum;
import com.wxy.career.vo.AssistantMessageRespVO;
import com.wxy.career.vo.PageRespVO;

/**
 * 通用助手消息服务。
 *
 * <p>只负责消息表的读写与分页，独立于 Agent 调用链，便于在异步线程中安全落库。
 *
 * @author wxy
 * @date 2026-09-28
 */
public interface AssistantMessageService {

    /**
     * 保存一条会话消息。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID，取值是 chat_session.id
     * @param role 消息角色
     * @param content 消息内容
     */
    void saveMessage(Long userId, Long sessionId, MessageRoleEnum role, String content);

    /**
     * 分页查询会话消息。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID，取值是 chat_session.id
     * @param pageNum 页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页消息，按消息 ID 倒序（第 1 页即最新消息）
     */
    PageRespVO<AssistantMessageRespVO> listMessages(Long userId, Long sessionId, long pageNum, long pageSize);

    /**
     * 逻辑删除会话的全部消息。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID，取值是 chat_session.id
     * @return 删除条数
     */
    int clearSession(Long userId, Long sessionId);
}
