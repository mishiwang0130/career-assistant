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
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_token")
public class SysToken extends BasePO {

    public static final int STATUS_ACTIVE = 0;

    public static final int STATUS_REVOKED = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String jti;

    private LocalDateTime expiresAt;

    private Integer revoked;

    private LocalDateTime revokedAt;
}
