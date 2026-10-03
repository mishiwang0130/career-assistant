package com.wxy.career.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wxy.career.common.mybatis.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 会话元数据。
 *
 * <p>会话标识就是主键 id，消息表 {@code assistant_message.session_id} 直接引用该值，
 * Agent 的会话状态仍保存在 Redis，本表只承担列表展示与归属校验。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_session")
public class ChatSession extends BasePO {

    /**
     * 会话状态：正常。
     */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /**
     * 新建会话的默认标题，标题仍是该值时才会被首条用户消息改写。
     */
    public static final String DEFAULT_TITLE = "新会话";

    /**
     * 归档状态：待归档（新建会话的默认值）。
     */
    public static final String ARCHIVE_STATUS_PENDING = "PENDING";

    /**
     * 归档状态：已归档（归档成功，含「提炼出 0 条记忆」这种正常结果）。
     */
    public static final String ARCHIVE_STATUS_DONE = "DONE";

    /**
     * 归档状态：归档失败（重试达到上限，需要人工介入，不再自动重试）。
     */
    public static final String ARCHIVE_STATUS_FAILED = "FAILED";

    /**
     * 会话 ID，对应 chat_session.id，同时是消息表的关联值。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID，对应 chat_session.user_id，所有查询都必须带该条件。
     */
    private Long userId;

    /**
     * 会话场景，对应 chat_session.scene，取值见 ChatSceneEnum，当前为 ASSISTANT/INTERVIEW。
     */
    private String scene;

    /**
     * 会话标题，对应 chat_session.title，最长 100 个字符。
     */
    private String title;

    /**
     * 最近一条用户消息时间，对应 chat_session.last_message_at，用于会话列表倒序排列。
     */
    private LocalDateTime lastMessageAt;

    /**
     * 会话状态，对应 chat_session.status，本期固定为 ACTIVE，为后续归档预留。
     */
    private String status;

    /**
     * 记忆归档状态，对应 chat_session.archive_status，取值 PENDING / DONE / FAILED。
     *
     * <p>与 status 分开存：status 描述会话本身是否正常，归档状态只描述「这场会话有没有被提炼成记忆」，
     * 归档后会话仍然正常可回访。
     */
    private String archiveStatus;

    /**
     * 归档完成时间，对应 chat_session.archive_time，NULL 表示还没有归档成功。
     */
    private LocalDateTime archiveTime;

    /**
     * 归档尝试次数，对应 chat_session.archive_attempts；达到配置上限时状态置 FAILED。
     */
    private Integer archiveAttempts;
}
