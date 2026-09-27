package com.wxy.career.vo;

import com.wxy.career.po.SysUser;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户信息响应。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoRespVO {

    /**
     * 用户 ID。
     */
    private Long id;

    /**
     * 用户名。
     */
    private String username;

    /**
     * 用户昵称。
     */
    private String nickname;

    /**
     * 从用户实体构建响应对象。
     *
     * @param user 用户实体
     * @return 用户信息响应
     */
    public static UserInfoRespVO from(SysUser user) {
        return new UserInfoRespVO(user.getId(), user.getUsername(), user.getNickname());
    }
}
