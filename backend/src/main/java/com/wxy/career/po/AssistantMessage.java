package com.wxy.career.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wxy.career.common.mybatis.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通用助手消息。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("assistant_message")
public class AssistantMessage extends BasePO {

    /**
     * 主键 ID，对应 assistant_message.id。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID，对应 assistant_message.user_id。
     */
    private Long userId;

    /**
     * 会话 ID，对应 assistant_message.session_id，由前端生成并保证同一用户内唯一。
     */
    private String sessionId;

    /**
     * 消息角色，对应 assistant_message.role，取值 USER / ASSISTANT / SYSTEM。
     */
    private String role;

    /**
     * 消息内容，对应 assistant_message.content。
     */
    private String content;
}
