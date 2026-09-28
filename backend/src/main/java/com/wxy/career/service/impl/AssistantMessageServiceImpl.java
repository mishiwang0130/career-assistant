package com.wxy.career.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wxy.career.common.enums.MessageRoleEnum;
import com.wxy.career.mapper.AssistantMessageMapper;
import com.wxy.career.po.AssistantMessage;
import com.wxy.career.service.AssistantMessageService;
import com.wxy.career.vo.AssistantMessageRespVO;
import com.wxy.career.vo.PageRespVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 通用助手消息服务实现。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Service
public class AssistantMessageServiceImpl implements AssistantMessageService {

    /**
     * 默认每页条数。
     */
    private static final long DEFAULT_PAGE_SIZE = 20L;

    /**
     * 单页最大条数，避免一次拉取过多历史消息。
     */
    private static final long MAX_PAGE_SIZE = 100L;

    /**
     * 消息 Mapper。
     */
    @Resource
    private AssistantMessageMapper assistantMessageMapper;

    /**
     * 保存一条会话消息。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID，取值是 chat_session.id
     * @param role 消息角色
     * @param content 消息内容
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveMessage(Long userId, Long sessionId, MessageRoleEnum role, String content) {
        AssistantMessage message = new AssistantMessage();
        message.setUserId(userId);
        message.setSessionId(sessionId);
        message.setRole(role.getValue());
        message.setContent(content);
        // 流式回复在异步线程落库，此时没有登录上下文，审计字段必须显式写入。
        message.setCreateBy(userId);
        message.setUpdateBy(userId);
        assistantMessageMapper.insert(message);
    }

    /**
     * 分页查询会话消息。
     *
     * @param userId 用户 ID
     * <p>排序按消息 ID 倒序：第 1 页就是最新的若干条消息，前端据此把最新内容放在最下方，
     * 向上滚动时继续请求更早的分页。
     *
     * @param sessionId 会话 ID，取值是 chat_session.id
     * @param pageNum 页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页消息
     */
    @Override
    @Transactional(readOnly = true)
    public PageRespVO<AssistantMessageRespVO> listMessages(
            Long userId, Long sessionId, long pageNum, long pageSize) {
        long normalizedPageNum = pageNum <= 0 ? 1L : pageNum;
        long normalizedPageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        Page<AssistantMessage> page = new Page<>(normalizedPageNum, normalizedPageSize);
        LambdaQueryWrapper<AssistantMessage> wrapper = new LambdaQueryWrapper<AssistantMessage>()
                .eq(AssistantMessage::getUserId, userId)
                .eq(AssistantMessage::getSessionId, sessionId)
                .orderByDesc(AssistantMessage::getId);
        Page<AssistantMessage> result = assistantMessageMapper.selectPage(page, wrapper);
        List<AssistantMessageRespVO> records = result.getRecords().stream()
                .map(AssistantMessageRespVO::from)
                .toList();
        return PageRespVO.of(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    /**
     * 逻辑删除会话的全部消息。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID，取值是 chat_session.id
     * @return 删除条数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int clearSession(Long userId, Long sessionId) {
        return assistantMessageMapper.delete(new LambdaQueryWrapper<AssistantMessage>()
                .eq(AssistantMessage::getUserId, userId)
                .eq(AssistantMessage::getSessionId, sessionId));
    }
}
