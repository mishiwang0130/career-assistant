package com.wxy.career.service;

import com.wxy.career.po.ChatSession;
import com.wxy.career.vo.ChatSessionCreateReqVO;
import com.wxy.career.vo.ChatSessionRenameReqVO;
import com.wxy.career.vo.ChatSessionRespVO;
import com.wxy.career.vo.PageRespVO;

/**
 * 会话中心服务。
 *
 * <p>负责会话元数据的增删改查：会话 ID 由后端生成，列表按最近消息时间倒序分页，
 * 删除会话时同时清理消息与会话状态。用户身份只从登录态获取，绝不接受前端传入 userId。
 *
 * @author wxy
 * @date 2026-09-28
 */
public interface ChatSessionService {

    /**
     * 新建会话，返回后端生成的会话 ID。
     *
     * @param reqVO 新建请求
     * @return 新建的会话
     */
    ChatSessionRespVO create(ChatSessionCreateReqVO reqVO);

    /**
     * 分页查询当前用户会话列表，最近消息时间倒序。
     *
     * @param pageNum 页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页会话列表
     */
    PageRespVO<ChatSessionRespVO> list(long pageNum, long pageSize);

    /**
     * 重命名当前用户的会话。
     *
     * @param sessionId 会话 ID
     * @param reqVO 重命名请求
     * @return 更新后的会话
     */
    ChatSessionRespVO rename(String sessionId, ChatSessionRenameReqVO reqVO);

    /**
     * 删除当前用户的会话：逻辑删除会话、清空消息并清理 Agent 会话状态。
     *
     * @param sessionId 会话 ID
     */
    void delete(String sessionId);

    /**
     * 校验会话属于指定用户，不存在、已删除或跨账号统一抛「会话不存在」。
     *
     * <p>供消息接口复用，使「打开别人的会话」「打开已删除的会话」都表现为资源不存在。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 会话实体
     */
    ChatSession requireOwnedSession(Long userId, String sessionId);

    /**
     * 保存用户消息后回写会话元数据：默认标题改写为首条消息前若干字，并刷新活跃时间。
     *
     * <p>会话不存在时只记日志直接返回，保证对话主流程不被元数据异常打断。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param content 用户消息内容
     */
    void recordUserMessage(Long userId, String sessionId, String content);
}
