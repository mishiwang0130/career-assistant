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
}
