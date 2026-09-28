package com.wxy.career.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wxy.career.common.mybatis.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 求职目标实体。
 *
 * <p>一人一份，供模拟面试（F5）出题与训练计划（F7）读取；公共字段由 {@link BasePO} 承接，
 * 逻辑删除由 {@code @TableLogic} 承接，因此删除后的记录查不到，等价于「未填写」。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_profile")
public class UserProfile extends BasePO {

    /**
     * 主键 ID，对应 user_profile.id。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID，对应 user_profile.user_id，一人一份。
     */
    private Long userId;

    /**
     * 目标岗位，对应 user_profile.target_position，落库前已去除首尾空白。
     */
    private String targetPosition;

    /**
     * 当前工作年限（年），对应 user_profile.work_years，0 表示应届或不足一年。
     */
    private Integer workYears;
}
