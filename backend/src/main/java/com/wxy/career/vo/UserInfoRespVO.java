package com.wxy.career.vo;

import com.wxy.career.po.SysUser;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户信息响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoRespVO {

    private Long id;

    private String username;

    private String nickname;

    public static UserInfoRespVO from(SysUser user) {
        return new UserInfoRespVO(user.getId(), user.getUsername(), user.getNickname());
    }
}
