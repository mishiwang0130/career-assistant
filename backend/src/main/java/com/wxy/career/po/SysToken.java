package com.wxy.career.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wxy.career.common.mybatis.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Access Token 记录。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_token")
public class SysToken extends BasePO {

    /**
     * 令牌有效状态。
     */
    public static final int STATUS_ACTIVE = 0;

    /**
     * 令牌已撤销状态。
     */
    public static final int STATUS_REVOKED = 1;

    /**
     * 主键 ID，对应 sys_token.id。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID，对应 sys_token.user_id。
     */
    private Long userId;

    /**
     * JWT 唯一标识，对应 sys_token.jti。
     */
    private String jti;

    /**
     * Access Token 过期时间，对应 sys_token.expires_at。
     */
    private LocalDateTime expiresAt;

    /**
     * 是否撤销，对应 sys_token.revoked，0-否，1-是。
     */
    private Integer revoked;

    /**
     * 撤销时间，对应 sys_token.revoked_at。
     */
    private LocalDateTime revokedAt;
}
