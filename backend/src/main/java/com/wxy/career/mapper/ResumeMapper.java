package com.wxy.career.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wxy.career.po.Resume;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 简历 Mapper。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Mapper
public interface ResumeMapper extends BaseMapper<Resume> {

    /**
     * 按简历 ID 与用户 ID 查询简历。
     *
     * <p>所有详情、更新、删除和默认设置都必须同时带用户 ID，跨账号访问统一表现为不存在。
     *
     * @param id 简历 ID
     * @param userId 用户 ID
     * @return 简历实体，不存在时返回 null
     */
    default Resume selectByIdAndUserId(Long id, Long userId) {
        return selectOne(new LambdaQueryWrapper<Resume>()
                .eq(Resume::getId, id)
                .eq(Resume::getUserId, userId));
    }

    /**
     * 查询用户简历列表。
     *
     * @param userId 用户 ID
     * @return 默认优先、创建时间倒序的简历列表
     */
    default List<Resume> selectByUserId(Long userId) {
        return selectList(new LambdaQueryWrapper<Resume>()
                .eq(Resume::getUserId, userId)
                .orderByDesc(Resume::getDefaultFlag)
                .orderByDesc(Resume::getCreateTime)
                .orderByDesc(Resume::getId));
    }

    /**
     * 清空用户旧默认标记。
     *
     * @param userId 用户 ID
     * @return 更新行数
     */
    default int clearDefault(Long userId) {
        return update(null, new LambdaUpdateWrapper<Resume>()
                .eq(Resume::getUserId, userId)
                .set(Resume::getDefaultFlag, Resume.DEFAULT_FLAG_NO));
    }
}
