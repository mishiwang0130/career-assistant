package com.wxy.career.vo;

import com.wxy.career.po.UserProfile;
import lombok.Data;

/**
 * 求职目标响应。
 *
 * <p>只返回业务字段，不暴露主键、用户 ID 与审计字段；未填写时接口返回 data 为 null，不返回本对象。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
public class UserProfileRespVO {

    /**
     * 目标岗位。
     */
    private String targetPosition;

    /**
     * 当前工作年限（年），0 表示应届或不足一年。
     */
    private Integer workYears;

    /**
     * 将数据库实体转换为响应对象。
     *
     * @param userProfile 求职目标实体
     * @return 求职目标响应
     */
    public static UserProfileRespVO from(UserProfile userProfile) {
        UserProfileRespVO respVO = new UserProfileRespVO();
        respVO.setTargetPosition(userProfile.getTargetPosition());
        respVO.setWorkYears(userProfile.getWorkYears());
        return respVO;
    }
}
