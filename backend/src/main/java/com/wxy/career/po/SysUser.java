package com.wxy.career.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wxy.career.common.mybatis.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统用户。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BasePO {

    /**
     * 用户禁用状态。
     */
    public static final int STATUS_DISABLED = 0;

    /**
     * 用户启用状态。
     */
    public static final int STATUS_ENABLED = 1;

    /**
     * 用户 ID，对应 sys_user.id。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户名，对应 sys_user.username，全局唯一且不区分大小写。
     */
    private String username;

    /**
     * BCrypt 密码密文，对应 sys_user.password。
     */
    private String password;

    /**
     * 用户昵称，对应 sys_user.nickname。
     */
    private String nickname;

    /**
     * 用户状态，对应 sys_user.status，0-禁用，1-启用。
     */
    private Integer status;
}
