package com.wxy.career.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wxy.career.po.ChatSession;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 查询待归档的「安静会话」。
     *
     * <p>筛选条件固定为：指定场景（只归档助手会话，面试会话的结论已有结构化表）、归档状态 PENDING、
     * 尝试次数未达上限、最近一条用户消息早于安静阈值。排序按会话最旧优先，保证积压时先补老的会话；
     * 条数上限在这里直接截断，避免一次运行拉出过多会话。
     *
     * @param scene 会话场景，取值见 {@code ChatSceneEnum}
     * @param quietBefore 安静阈值时间点，最后消息早于该值的会话才算安静
     * @param maxAttempts 归档重试上限，达到上限的会话不再返回
     * @param batchSize 单次最多返回的会话数
     * @return 待归档会话列表，没有时返回空列表
     */
    default List<ChatSession> selectQuietSessionsForArchive(
            String scene, LocalDateTime quietBefore, int maxAttempts, int batchSize) {
        Page<ChatSession> page = new Page<>(1L, batchSize);
        Page<ChatSession> result = selectPage(page, new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getScene, scene)
                .eq(ChatSession::getArchiveStatus, ChatSession.ARCHIVE_STATUS_PENDING)
                .lt(ChatSession::getArchiveAttempts, maxAttempts)
                .isNotNull(ChatSession::getLastMessageAt)
                .le(ChatSession::getLastMessageAt, quietBefore)
                .orderByAsc(ChatSession::getLastMessageAt)
                .orderByAsc(ChatSession::getId));
        return result.getRecords();
    }

    /**
     * 回写会话的归档状态、归档时间与尝试次数。
     *
     * <p>只按会话 ID 更新，不带用户条件：调用方是系统定时任务，没有登录上下文；会话归属已经在扫描阶段
     * 从库里读出，不再二次校验。归档成功时写入完成时间，失败重试时时间传 null（未归档成功）。
     *
     * @param id 会话 ID
     * @param archiveStatus 归档状态，取值 PENDING / DONE / FAILED
     * @param archiveTime 归档完成时间，未成功时传 null
     * @param archiveAttempts 累计尝试次数
     * @return 更新行数
     */
    default int updateArchiveState(Long id, String archiveStatus, LocalDateTime archiveTime, int archiveAttempts) {
        ChatSession session = new ChatSession();
        session.setArchiveStatus(archiveStatus);
        session.setArchiveTime(archiveTime);
        session.setArchiveAttempts(archiveAttempts);
        return update(session, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, id));
    }
}
