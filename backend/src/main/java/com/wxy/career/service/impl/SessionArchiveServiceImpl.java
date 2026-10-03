package com.wxy.career.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.enums.ChatSceneEnum;
import com.wxy.career.common.enums.MessageRoleEnum;
import com.wxy.career.config.MemoryProperties;
import com.wxy.career.mapper.AssistantMessageMapper;
import com.wxy.career.mapper.ChatSessionMapper;
import com.wxy.career.po.AssistantMessage;
import com.wxy.career.po.ChatSession;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.SessionArchiveService;
import com.wxy.career.service.UserMemoryService;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.UserProfileRespVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话归档总结实现。
 *
 * <p>为什么由 Java 而不是模型编排：模型只负责「提炼」这一步，扫描哪些会话、写什么类型的记忆、
 * 写失败怎么重试都由服务端决定，因此归档总结 Agent 没有任何工具，模型始终接触不到记忆库。
 *
 * <p>状态流转固定为：{@code PENDING} → 成功（含提炼出 0 条）置 {@code DONE}；失败累计尝试次数，
 * 未达上限保持 {@code PENDING} 下轮重试，达到上限置 {@code FAILED} 并打 error 日志，不再反复调用模型。
 * 记忆写入失败同样算失败：文档要求「写入失败留待补偿」，重试就是这里的补偿手段。
 *
 * @author wxy
 * @date 2026-10-03
 */
@Slf4j
@Service
public class SessionArchiveServiceImpl implements SessionArchiveService {

    /**
     * 归档运行时上下文的会话标识前缀。
     *
     * <p>归档不是用户会话，给它一个不会与真实会话 ID 冲突的运行标识，便于日志与埋点按会话追溯。
     */
    private static final String ARCHIVE_RUNTIME_SESSION_PREFIX = "session-archive-";

    /**
     * 发给归档 Agent 的用户消息发送者名。
     */
    private static final String USER_MESSAGE_NAME = "user";

    /**
     * 对话记录小节标题，与 {@code prompts/session-archiver.md} 的输入约定对应。
     */
    private static final String TRANSCRIPT_HEADER = "【对话记录】";

    /**
     * 对话记录被截断时的提示行：归档关心「讲到哪里」，超长只保留最近的部分。
     */
    private static final String TRUNCATED_MARKER = "（更早的消息已省略）";

    /**
     * 用户消息在输入里的角色前缀。
     */
    private static final String USER_ROLE_PREFIX = "用户：";

    /**
     * 助手消息在输入里的角色前缀。
     */
    private static final String ASSISTANT_ROLE_PREFIX = "助手：";

    /**
     * 长期记忆配置（含归档开关、阈值与重试上限）。
     */
    @Resource
    private MemoryProperties memoryProperties;

    /**
     * 会话元数据 Mapper，负责扫描安静会话与回写归档状态。
     */
    @Resource
    private ChatSessionMapper chatSessionMapper;

    /**
     * 会话消息 Mapper，提供归档所需的对话记录。
     */
    @Resource
    private AssistantMessageMapper assistantMessageMapper;

    /**
     * 求职目标服务，给归档 Agent 提供目标岗位与工作年限。
     */
    @Resource
    private UserProfileService userProfileService;

    /**
     * 长期记忆业务入口，模型产出的结论经它写入。
     */
    @Resource
    private UserMemoryService userMemoryService;

    /**
     * Agent 工厂，提供无工具的归档总结 Agent。
     */
    @Resource
    private AgentFactory agentFactory;

    /**
     * JSON 组件，解析归档结论。
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 扫描一次待归档的安静会话并逐场归档。
     *
     * @return 本次归档成功的会话数
     */
    @Override
    public int archiveQuietSessions() {
        MemoryProperties.Archive archive = memoryProperties.getArchive();
        if (!memoryProperties.isEnabled()) {
            log.info("长期记忆已关闭，跳过会话归档扫描");
            return 0;
        }
        if (!archive.isEnabled()) {
            log.debug("会话归档已关闭，跳过本次扫描");
            return 0;
        }
        LocalDateTime quietBefore = LocalDateTime.now().minusMinutes(archive.getQuietMinutes());
        List<ChatSession> sessions = chatSessionMapper.selectQuietSessionsForArchive(
                ChatSceneEnum.ASSISTANT.getValue(), quietBefore, archive.getMaxAttempts(), archive.getBatchSize());
        if (sessions == null || sessions.isEmpty()) {
            log.debug("会话归档扫描完成：没有待归档的安静会话");
            return 0;
        }
        int archived = 0;
        for (ChatSession session : sessions) {
            if (archiveSession(session, archive)) {
                archived++;
            }
        }
        log.info("会话归档扫描完成，候选 {} 场，归档成功 {} 场", sessions.size(), archived);
        return archived;
    }

    /**
     * 归档单场会话：读记录 → 模型提炼 → 写记忆 → 回写状态。
     *
     * <p>任何一种失败都不向外抛：定时任务要继续处理后续会话。失败时按尝试次数决定保持待归档还是置失败。
     *
     * @param session 待归档会话
     * @param archive 归档配置
     * @return 归档成功返回 true
     */
    private boolean archiveSession(ChatSession session, MemoryProperties.Archive archive) {
        int attempts = (session.getArchiveAttempts() == null ? 0 : session.getArchiveAttempts()) + 1;
        long startedAt = System.nanoTime();
        try {
            List<AssistantMessage> messages = listSessionMessages(session);
            List<String> memories = summarizeSession(session, messages, archive);
            int written = writeMemories(session, memories);
            chatSessionMapper.updateArchiveState(
                    session.getId(), ChatSession.ARCHIVE_STATUS_DONE, LocalDateTime.now(), attempts);
            log.info("会话归档成功，sessionId={}，userId={}，messages={}，memories={}，costMs={}",
                    session.getId(), session.getUserId(), messages.size(), written, costMillis(startedAt));
            return true;
        } catch (Exception exception) {
            boolean giveUp = attempts >= archive.getMaxAttempts();
            chatSessionMapper.updateArchiveState(session.getId(),
                    giveUp ? ChatSession.ARCHIVE_STATUS_FAILED : ChatSession.ARCHIVE_STATUS_PENDING, null, attempts);
            if (giveUp) {
                // 达到上限说明不是偶发抖动：打 error 并置 FAILED，等人工确认后再决定是否重置状态重跑。
                log.error("会话归档失败且达到重试上限，不再自动重试，sessionId={}，userId={}，attempts={}",
                        session.getId(), session.getUserId(), attempts, exception);
            } else {
                log.warn("会话归档失败，留待下轮重试，sessionId={}，userId={}，attempts={}，cause={}",
                        session.getId(), session.getUserId(), attempts, exception.getClass().getSimpleName());
            }
            return false;
        }
    }

    /**
     * 读取会话的全部消息，按写入顺序正序排列。
     *
     * <p>同时带用户 ID 条件：会话归属已经由扫描阶段确定，这里再对齐一次，避免异常数据把别人的消息带进来。
     *
     * @param session 会话
     * @return 消息列表，没有时返回空列表
     */
    private List<AssistantMessage> listSessionMessages(ChatSession session) {
        List<AssistantMessage> messages = assistantMessageMapper.selectList(
                new LambdaQueryWrapper<AssistantMessage>()
                        .eq(AssistantMessage::getUserId, session.getUserId())
                        .eq(AssistantMessage::getSessionId, session.getId())
                        .orderByAsc(AssistantMessage::getId));
        return messages == null ? List.of() : messages;
    }

    /**
     * 调用归档总结 Agent 提炼记忆。
     *
     * <p>没有消息时直接返回空结果，不调用模型：空会话归档成 0 条是正常结果，不必浪费一次模型调用。
     *
     * @param session 会话
     * @param messages 会话消息
     * @param archive 归档配置
     * @return 记忆正文列表，可能是空列表
     */
    private List<String> summarizeSession(
            ChatSession session, List<AssistantMessage> messages, MemoryProperties.Archive archive) {
        if (messages.isEmpty()) {
            return List.of();
        }
        Msg message = Msg.builder()
                .name(USER_MESSAGE_NAME)
                .role(MsgRole.USER)
                .textContent(buildArchiverInput(session, messages, archive.getMaxTranscriptChars()))
                .build();
        String runtimeSessionId = ARCHIVE_RUNTIME_SESSION_PREFIX + session.getId();
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(String.valueOf(session.getUserId()))
                .sessionId(runtimeSessionId)
                .build();
        HarnessAgent archiver = agentFactory.getAgent(AgentFactory.SESSION_ARCHIVER_AGENT_NAME);
        try {
            // 归档是后台任务，阻塞等待一次模型产出即可，超时按失败处理并留待下轮重试。
            Msg reply = archiver.call(message, runtimeContext)
                    .block(Duration.ofSeconds(archive.getTimeoutSeconds()));
            if (reply == null) {
                throw new IllegalStateException("归档总结 Agent 未返回结果");
            }
            return parseMemories(reply.getTextContent(), archive.getMaxMemories());
        } finally {
            // 归档只做一次提炼、不需要记忆：调用后清掉本次运行状态，避免上下文与重试历史在进程内累积。
            clearArchiveContext(archiver, session, runtimeSessionId);
        }
    }

    /**
     * 清理归档 Agent 本次运行的状态。
     *
     * <p>清理失败不影响归档结果（记忆已经解析完成），因此只记 debug 日志。归档 Agent 是按 Agent 名缓存的，
     * 不清理会让每个归档过的会话在进程里留一份上下文。
     *
     * @param archiver 归档总结 Agent
     * @param session 会话
     * @param runtimeSessionId 归档运行时会话标识
     */
    private void clearArchiveContext(HarnessAgent archiver, ChatSession session, String runtimeSessionId) {
        try {
            archiver.clearContext(String.valueOf(session.getUserId()), runtimeSessionId);
        } catch (Exception exception) {
            log.debug("清理归档运行时上下文失败，已忽略，sessionId={}，cause={}",
                    session.getId(), exception.getClass().getSimpleName());
        }
    }

    /**
     * 把模型产出的记忆逐条写入记忆库。
     *
     * <p>只要有一条写入失败就抛异常：调用方据此保持待归档，下轮重试。已经写成功的那几条由 Mem0 自身
     * 的「同用户同类型内容合并」语义去重，不会越积越多。
     *
     * @param session 会话
     * @param memories 记忆正文列表
     * @return 写入成功的条数
     */
    private int writeMemories(ChatSession session, List<String> memories) {
        int written = 0;
        int failed = 0;
        for (String memory : memories) {
            boolean success = userMemoryService.rememberFact(
                    session.getUserId(), String.valueOf(session.getId()), memory);
            if (success) {
                written++;
            } else {
                failed++;
            }
        }
        if (failed > 0) {
            throw new IllegalStateException("有 " + failed + " 条记忆写入失败，会话保持待归档以便下轮重试");
        }
        return written;
    }

    /**
     * 拼装归档总结 Agent 的输入。
     *
     * <p>输入只包含目标岗位、工作年限与对话记录：目标岗位是用户自填的权威值；对话记录是这场会话的全部
     * 用户与助手消息（超长保留最近部分）。归档 Agent 没有工具，因此需要的信息必须在这一次输入里给全。
     *
     * @param session 会话
     * @param messages 会话消息
     * @param maxTranscriptChars 对话记录长度上限（字符）
     * @return 模型输入正文
     */
    String buildArchiverInput(ChatSession session, List<AssistantMessage> messages, int maxTranscriptChars) {
        UserProfileRespVO profile = userProfileService.getUserProfileByUserId(session.getUserId());
        String targetPosition = profile == null || !StringUtils.hasText(profile.getTargetPosition())
                ? "未填写" : profile.getTargetPosition();
        int workYears = profile == null || profile.getWorkYears() == null ? 0 : profile.getWorkYears();
        String workYearsText = workYears == 0 ? "0 年（应届或不足一年）" : workYears + " 年";
        String lineSeparator = System.lineSeparator();
        return "请把下面这场已经结束的对话整理成 0~3 条长期记忆，只返回 JSON，不要输出其它文字。"
                + lineSeparator
                + "【目标岗位】" + targetPosition + "（工作年限 " + workYearsText + "）"
                + lineSeparator
                + TRANSCRIPT_HEADER
                + lineSeparator
                + buildTranscript(messages, maxTranscriptChars);
    }

    /**
     * 把消息列表拼成对话记录文本。
     *
     * <p>只保留 USER / ASSISTANT 两类角色（SYSTEM 消息是平台内部提示，不属于对话内容）；超过长度上限时
     * 只保留最近的对话——归档要判断「讲到哪里、卡在哪里」，越新的轮次信息量越大。
     *
     * @param messages 会话消息
     * @param maxChars 长度上限（字符）
     * @return 对话记录文本
     */
    private String buildTranscript(List<AssistantMessage> messages, int maxChars) {
        StringBuilder builder = new StringBuilder();
        for (AssistantMessage message : messages) {
            if (message == null || !StringUtils.hasText(message.getContent())) {
                continue;
            }
            String role = message.getRole();
            if (MessageRoleEnum.USER.getValue().equals(role)) {
                builder.append(USER_ROLE_PREFIX);
            } else if (MessageRoleEnum.ASSISTANT.getValue().equals(role)) {
                builder.append(ASSISTANT_ROLE_PREFIX);
            } else {
                continue;
            }
            builder.append(message.getContent().trim()).append(System.lineSeparator());
        }
        String transcript = builder.toString().trim();
        if (transcript.length() <= maxChars) {
            return transcript;
        }
        return TRUNCATED_MARKER + System.lineSeparator()
                + transcript.substring(transcript.length() - maxChars);
    }

    /**
     * 解析归档总结 Agent 的产出。
     *
     * <p>模型可能把 JSON 包在 Markdown 代码块里或前后带一句说明，因此先截取第一个 <code>{</code> 到最后一个
     * <code>}</code> 之间的内容再解析；解析失败按失败处理（留待重试），不静默当成「0 条记忆」，
     * 否则一次模型格式抖动就会把这场会话标记成已归档、记忆永久丢失。
     *
     * @param raw 模型输出原文
     * @param maxMemories 最多保留的记忆条数
     * @return 记忆正文列表，可能是空列表
     */
    List<String> parseMemories(String raw, int maxMemories) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        String json = extractJsonObject(raw);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("归档结论不是合法 JSON", exception);
        }
        JsonNode memories = root == null ? null : root.path("memories");
        if (memories == null || !memories.isArray()) {
            throw new IllegalStateException("归档结论缺少 memories 数组");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode node : memories) {
            if (node == null || !StringUtils.hasText(node.asText())) {
                continue;
            }
            unique.add(node.asText().trim());
            if (unique.size() >= maxMemories) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    /**
     * 从模型输出里截取 JSON 对象。
     *
     * @param raw 模型输出原文
     * @return JSON 对象文本
     * @throws IllegalStateException 输出里没有完整的 JSON 对象
     */
    private String extractJsonObject(String raw) {
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("归档结论里没有 JSON 对象");
        }
        return text.substring(start, end + 1);
    }

    /**
     * 计算耗时毫秒数。
     *
     * @param startedAt 开始时间，纳秒
     * @return 耗时毫秒
     */
    private long costMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
