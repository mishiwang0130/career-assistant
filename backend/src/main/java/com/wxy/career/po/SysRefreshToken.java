package com.wxy.career.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wxy.career.common.mybatis.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Refresh Token 记录。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_refresh_token")
public class SysRefreshToken extends BasePO {

    /**
     * 令牌有效状态。
     */
    public static final int STATUS_ACTIVE = 0;

    /**
     * 令牌已撤销状态。
     */
    public static final int STATUS_REVOKED = 1;

    /**
     * 主键 ID，对应 sys_refresh_token.id。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID，对应 sys_refresh_token.user_id。
     */
    private Long userId;

    /**
     * 关联的 Access Token jti，对应 sys_refresh_token.access_jti。
     */
    private String accessJti;

    /**
     * Refresh Token SHA-256 摘要，对应 sys_refresh_token.token_hash。
     */
    private String tokenHash;

    /**
     * Refresh Token 过期时间，对应 sys_refresh_token.expires_at。
     */
    private LocalDateTime expiresAt;

    /**
     * 是否撤销，对应 sys_refresh_token.revoked，0-否，1-是。
     */
    private Integer revoked;

    /**
     * 撤销时间，对应 sys_refresh_token.revoked_at。
     */
    private LocalDateTime revokedAt;
}
