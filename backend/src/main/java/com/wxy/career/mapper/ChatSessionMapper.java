package com.wxy.career.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wxy.career.po.ChatSession;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

/**
 * 会话元数据 Mapper。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    /**
     * 按会话 ID 与用户 ID 查询会话。
     *
     * <p>所有详情、重命名与删除都必须同时带用户 ID，跨账号访问与已删除会话统一表现为不存在。
     *
     * @param id 会话 ID
     * @param userId 用户 ID
     * @return 会话实体，不存在时返回 null
     */
    default ChatSession selectByIdAndUserId(Long id, Long userId) {
        return selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getId, id)
                .eq(ChatSession::getUserId, userId));
    }

    /**
     * 分页查询用户会话列表。
     *
     * <p>排序固定为最近消息时间倒序、ID 倒序，保证第 1 页永远是最新的会话。
     *
     * @param page 分页对象
     * @param userId 用户 ID
     * @return 分页结果，含总数与当前页记录
     */
    default Page<ChatSession> selectPageByUserId(Page<ChatSession> page, Long userId) {
        return selectPage(page, new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .orderByDesc(ChatSession::getLastMessageAt)
                .orderByDesc(ChatSession::getId));
    }

    /**
     * 更新会话标题，不改动活跃时间（重命名不应把会话顶到列表最前面）。
     *
     * @param id 会话 ID
     * @param userId 用户 ID
     * @param title 归一化后的标题
     * @return 更新行数
     */
    default int updateTitle(Long id, Long userId, String title) {
        ChatSession session = new ChatSession();
        session.setTitle(title);
        session.setUpdateBy(userId);
        return update(session, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, id)
                .eq(ChatSession::getUserId, userId));
    }

    /**
     * 更新会话标题与活跃时间，供保存用户消息后回写自动标题使用。
     *
     * @param id 会话 ID
     * @param userId 用户 ID
     * @param title 归一化后的标题
     * @param lastMessageAt 最近消息时间
     * @return 更新行数
     */
    default int updateTitleAndLastMessageAt(Long id, Long userId, String title, LocalDateTime lastMessageAt) {
        ChatSession session = new ChatSession();
        session.setTitle(title);
        session.setLastMessageAt(lastMessageAt);
        session.setUpdateBy(userId);
        return update(session, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, id)
                .eq(ChatSession::getUserId, userId));
    }
}
