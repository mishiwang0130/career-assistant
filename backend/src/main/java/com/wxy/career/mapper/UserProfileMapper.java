package com.wxy.career.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wxy.career.po.UserProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 求职目标 Mapper。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {

    /**
     * 按用户 ID 查询求职目标。
     *
     * <p>查询与更新都必须带用户 ID，跨账号访问统一表现为未填写；逻辑删除的记录由 MyBatis-Plus
     * 自动过滤，因此返回值 null 表示该用户还没有档案。
     *
     * @param userId 用户 ID
     * @return 求职目标实体，未填写时返回 null
     */
    default UserProfile selectByUserId(Long userId) {
        return selectOne(new LambdaQueryWrapper<UserProfile>()
                .eq(UserProfile::getUserId, userId));
    }
}
