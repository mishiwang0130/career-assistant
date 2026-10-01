package com.wxy.career.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wxy.career.po.KnowledgeMastery;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 知识点掌握度 Mapper。
 *
 * <p>所有查询都带 user_id：掌握度是用户私有数据，跨账号必须读不到。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Mapper
public interface KnowledgeMasteryMapper extends BaseMapper<KnowledgeMastery> {

    /**
     * 查询指定用户某个知识点的掌握度。
     *
     * @param userId 用户 ID
     * @param knowledgePoint 知识点名称
     * @return 掌握度记录，不存在时返回 null
     */
    default KnowledgeMastery selectByUserAndPoint(Long userId, String knowledgePoint) {
        return selectOne(new LambdaQueryWrapper<KnowledgeMastery>()
                .eq(KnowledgeMastery::getUserId, userId)
                .eq(KnowledgeMastery::getKnowledgePoint, knowledgePoint));
    }

    /**
     * 查询指定用户的全部掌握度，薄弱点在前、分数低的在前。
     *
     * <p>F7 的训练计划通过这条查询（经 KnowledgeMasteryService）读取薄弱点，不需要新开对外接口。
     *
     * @param userId 用户 ID
     * @return 掌握度记录列表
     */
    default List<KnowledgeMastery> selectByUser(Long userId) {
        return selectList(new LambdaQueryWrapper<KnowledgeMastery>()
                .eq(KnowledgeMastery::getUserId, userId)
                .orderByDesc(KnowledgeMastery::getWeak)
                .orderByAsc(KnowledgeMastery::getMasteryScore)
                .orderByAsc(KnowledgeMastery::getId));
    }

    /**
     * 按知识点集合查询指定用户的掌握度，用于报告只取本场涉及的知识点。
     *
     * @param userId 用户 ID
     * @param knowledgePoints 知识点集合，为空时返回空列表
     * @return 掌握度记录列表
     */
    default List<KnowledgeMastery> selectByUserAndPoints(Long userId, Collection<String> knowledgePoints) {
        if (knowledgePoints == null || knowledgePoints.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapper<KnowledgeMastery>()
                .eq(KnowledgeMastery::getUserId, userId)
                .in(KnowledgeMastery::getKnowledgePoint, knowledgePoints)
                .orderByAsc(KnowledgeMastery::getMasteryScore));
    }
}
