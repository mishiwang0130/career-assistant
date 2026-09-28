package com.wxy.career.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.enums.ChatSceneEnum;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.mapper.ChatSessionMapper;
import com.wxy.career.po.ChatSession;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.AssistantMessageService;
import com.wxy.career.service.ChatSessionService;
import com.wxy.career.vo.ChatSessionCreateReqVO;
import com.wxy.career.vo.ChatSessionRenameReqVO;
import com.wxy.career.vo.ChatSessionRespVO;
import com.wxy.career.vo.PageRespVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话中心服务实现。
 *
 * <p>会话 ID 就是 chat_session.id，所有读写都必须带 user_id 条件；删除会话属于跨表写入，
 * 事务范围覆盖会话逻辑删除与消息清理，Agent 会话状态清理放在事务内调用，失败只记日志。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Slf4j
@Service
public class ChatSessionServiceImpl implements ChatSessionService {

    /**
     * 默认每页条数。
     */
    private static final long DEFAULT_PAGE_SIZE = 20L;

    /**
     * 单页最大条数，避免一次拉取过多会话。
     */
    private static final long MAX_PAGE_SIZE = 100L;

    /**
     * 自动标题截取长度，单位为 Unicode 码点。
     */
    private static final int AUTO_TITLE_MAX_LENGTH = 20;

    /**
     * 会话元数据 Mapper。
     */
    @Resource
    private ChatSessionMapper chatSessionMapper;

    /**
     * 消息服务，删除会话时同步清理消息。
     */
    @Resource
    private AssistantMessageService assistantMessageService;

    /**
     * Agent 工厂，删除会话时同步清理会话状态。
     */
    @Resource
    private AgentFactory agentFactory;

    /**
     * 新建会话，返回后端生成的会话 ID。
     *
     * @param reqVO 新建请求
     * @return 新建的会话
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatSessionRespVO create(ChatSessionCreateReqVO reqVO) {
        Long userId = currentUserId();
        ChatSceneEnum scene = ChatSceneEnum.find(reqVO.getScene());
        // 未登记或尚未开放（M7/M8/M14 认领）的场景一律拒绝，避免建出前端无法渲染的会话。
        if (scene == null || !scene.isAvailable()) {
            throw new BizException(ErrorConstant.CHAT_SCENE_UNSUPPORTED);
        }

        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setScene(scene.getValue());
        session.setTitle(ChatSession.DEFAULT_TITLE);
        session.setStatus(ChatSession.STATUS_ACTIVE);
        // 活跃时间在创建时就落一个值，保证列表排序有确定依据。
        session.setLastMessageAt(LocalDateTime.now());
        session.setCreateBy(userId);
        session.setUpdateBy(userId);
        chatSessionMapper.insert(session);
        return ChatSessionRespVO.from(session);
    }

    /**
     * 分页查询当前用户会话列表，最近消息时间倒序。
     *
     * @param pageNum 页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页会话列表
     */
    @Override
    @Transactional(readOnly = true)
    public PageRespVO<ChatSessionRespVO> list(long pageNum, long pageSize) {
        Long userId = currentUserId();
        long normalizedPageNum = pageNum <= 0 ? 1L : pageNum;
        long normalizedPageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        Page<ChatSession> page = new Page<>(normalizedPageNum, normalizedPageSize);
        Page<ChatSession> result = chatSessionMapper.selectPageByUserId(page, userId);
        List<ChatSessionRespVO> records = result.getRecords().stream()
                .map(ChatSessionRespVO::from)
                .toList();
        return PageRespVO.of(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    /**
     * 重命名当前用户的会话。
     *
     * @param sessionId 会话 ID
     * @param reqVO 重命名请求
     * @return 更新后的会话
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatSessionRespVO rename(String sessionId, ChatSessionRenameReqVO reqVO) {
        Long userId = currentUserId();
        ChatSession session = requireOwnedSession(userId, sessionId);
        String title = normalizeTitle(reqVO.getTitle());
        if (!StringUtils.hasText(title)) {
            // @NotBlank 已经拦掉全空白输入，这里只是防御：宁可报参数错误也不写入空标题。
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        // 重命名不改活跃时间，因此不会把会话顶到列表最前面。
        chatSessionMapper.updateTitle(session.getId(), userId, title);
        session.setTitle(title);
        return ChatSessionRespVO.from(session);
    }

    /**
     * 删除当前用户的会话：逻辑删除会话、清空消息并清理 Agent 会话状态。
     *
     * @param sessionId 会话 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String sessionId) {
        Long userId = currentUserId();
        ChatSession session = requireOwnedSession(userId, sessionId);
        chatSessionMapper.deleteById(session.getId());
        // 只删会话不动消息会留下孤儿数据，重新访问时还会看到已经"删除"的历史。
        assistantMessageService.clearSession(userId, session.getId());
        // Agent 里的上下文同样要清掉，否则重建同 ID 会话时会带着旧记忆继续回答。
        agentFactory.clearSession(userId, String.valueOf(session.getId()));
    }

    /**
     * 校验会话属于指定用户，不存在、已删除或跨账号统一抛「会话不存在」。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 会话实体
     */
    @Override
    @Transactional(readOnly = true)
    public ChatSession requireOwnedSession(Long userId, String sessionId) {
        Long sessionIdValue = parseSessionId(sessionId);
        if (userId == null || sessionIdValue == null) {
            throw new BizException(ErrorConstant.CHAT_SESSION_NOT_FOUND);
        }
        ChatSession session = chatSessionMapper.selectByIdAndUserId(sessionIdValue, userId);
        if (session == null) {
            // 跨账号访问与已删除会话一律表现为不存在，不暴露资源是否存在。
            throw new BizException(ErrorConstant.CHAT_SESSION_NOT_FOUND);
        }
        return session;
    }

    /**
     * 保存用户消息后回写会话元数据。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param content 用户消息内容
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordUserMessage(Long userId, String sessionId, String content) {
        Long sessionIdValue = parseSessionId(sessionId);
        if (userId == null || sessionIdValue == null) {
            return;
        }
        ChatSession session = chatSessionMapper.selectByIdAndUserId(sessionIdValue, userId);
        if (session == null) {
            // 会话元数据缺失不该让对话失败，只记录日志便于排查（历史 ID、已删除会话等）。
            log.warn("会话元数据不存在，跳过标题与活跃时间更新，userId={}，sessionId={}", userId, sessionId);
            return;
        }
        String title = ChatSession.DEFAULT_TITLE.equals(session.getTitle())
                ? buildAutoTitle(content)
                : session.getTitle();
        chatSessionMapper.updateTitleAndLastMessageAt(session.getId(), userId, title, LocalDateTime.now());
    }

    /**
     * 获取当前登录用户 ID。
     *
     * @return 用户 ID
     */
    private Long currentUserId() {
        Long userId = LoginUserHolder.getUserId();
        if (userId == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * 解析会话 ID，非法取值返回 null 由调用方决定语义。
     *
     * @param sessionId 会话 ID 字符串
     * @return 会话 ID，非法时返回 null
     */
    private Long parseSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        try {
            return Long.valueOf(sessionId.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * 生成自动标题：取首条消息的前 20 个 Unicode 码点。
     *
     * @param content 用户消息内容
     * @return 自动标题
     */
    private String buildAutoTitle(String content) {
        String normalized = normalizeTitle(content);
        if (!StringUtils.hasText(normalized)) {
            return ChatSession.DEFAULT_TITLE;
        }
        int codePointCount = normalized.codePointCount(0, normalized.length());
        if (codePointCount <= AUTO_TITLE_MAX_LENGTH) {
            return normalized;
        }
        // 按码点截断，避免把 emoji 等增补字符切成半个字符。
        return normalized.substring(0, normalized.offsetByCodePoints(0, AUTO_TITLE_MAX_LENGTH));
    }

    /**
     * 归一化标题：去掉首尾空白并把连续空白（含换行）折叠为单个空格。
     *
     * @param raw 原始文本
     * @return 归一化后的文本，入参为空时返回空串
     */
    private String normalizeTitle(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.strip().replaceAll("\\s+", " ");
    }
}
