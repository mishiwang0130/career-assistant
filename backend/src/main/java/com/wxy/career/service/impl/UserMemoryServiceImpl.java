package com.wxy.career.service.impl;

import com.wxy.career.middleware.UserLongTermMemoryAdapter;
import com.wxy.career.service.UserMemoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 长期记忆业务入口实现。
 *
 * <p>只做三件事：把业务语义拼成记忆正文、按用户与会话委托给 {@link UserLongTermMemoryAdapter}、
 * 把异常挡在业务之外。真正的用户隔离、降级、超时、截断都在适配层里统一实现。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
@Service
public class UserMemoryServiceImpl implements UserMemoryService {

    /**
     * 薄弱点记忆的正文前缀，让召回结果自带知识点，模型不用猜是哪一块薄弱。
     */
    private static final String WEAKNESS_PREFIX = "薄弱知识点「";

    /**
     * 薄弱点记忆的正文分隔符。
     */
    private static final String WEAKNESS_SEPARATOR = "」：";

    /**
     * 长期记忆适配层。
     */
    @Resource
    private UserLongTermMemoryAdapter userLongTermMemoryAdapter;

    /**
     * 记录薄弱知识点。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param knowledgePoint 知识点名称
     * @param summary 一句话说明
     */
    @Override
    public void rememberWeakness(Long userId, String sessionId, String knowledgePoint, String summary) {
        if (!StringUtils.hasText(knowledgePoint)) {
            return;
        }
        String content = WEAKNESS_PREFIX + knowledgePoint.trim() + WEAKNESS_SEPARATOR
                + (StringUtils.hasText(summary) ? summary.trim() : "本场面试没有答到要点，需要补强");
        write(userId, sessionId, knowledgePoint, content, UserLongTermMemoryAdapter.TYPE_WEAKNESS);
    }

    /**
     * 记录用户画像记忆。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param content 画像内容
     */
    @Override
    public void rememberProfile(Long userId, String sessionId, String content) {
        write(userId, sessionId, null, content, UserLongTermMemoryAdapter.TYPE_PROFILE);
    }

    /**
     * 记录事实记忆。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param content 事实内容
     */
    @Override
    public void rememberFact(Long userId, String sessionId, String content) {
        write(userId, sessionId, null, content, UserLongTermMemoryAdapter.TYPE_FACT);
    }

    /**
     * 按当前问题召回长期记忆。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param query 召回依据的问题文本
     * @return 注入文本；没有命中或 Mem0 不可用时返回空串
     */
    @Override
    public String recall(Long userId, String sessionId, String query) {
        if (userId == null || !StringUtils.hasText(query)) {
            return "";
        }
        try {
            return userLongTermMemoryAdapter.recallForUser(userId, sessionId, query);
        } catch (Exception exception) {
            // 召回的异常已经在适配层兜过一层，这里再兜一次：业务侧永远按「没有长期记忆」继续。
            log.warn("长期记忆召回异常，已按无长期记忆处理，userId={}，cause={}",
                    userId, exception.getClass().getSimpleName());
            return "";
        }
    }

    /**
     * 统一写入入口：非空校验 + 异常兜底，写入失败不影响调用方。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param knowledgePoint 知识点名称，画像与事实记忆为 null
     * @param content 记忆正文
     * @param memoryType 记忆类型
     */
    private void write(
            Long userId, String sessionId, String knowledgePoint, String content, String memoryType) {
        if (userId == null || !StringUtils.hasText(content)) {
            return;
        }
        try {
            userLongTermMemoryAdapter.recordForUser(userId, sessionId, memoryType, content);
        } catch (Exception exception) {
            log.warn("长期记忆写入异常，只记日志、留待补偿，userId={}，type={}，knowledgePoint={}，cause={}",
                    userId, memoryType, knowledgePoint, exception.getClass().getSimpleName());
        }
    }
}
