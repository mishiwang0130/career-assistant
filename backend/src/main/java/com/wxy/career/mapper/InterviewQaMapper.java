package com.wxy.career.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wxy.career.po.InterviewQa;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 面试问答 Mapper。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Mapper
public interface InterviewQaMapper extends BaseMapper<InterviewQa> {

    /**
     * 按用户与会话查询问答记录，按主键升序（即面试进行顺序）。
     *
     * <p>必须同时带 user_id 条件：会话 ID 相同也不能读到别人的面试记录，两个账号的面试互不可见。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID，取值是 chat_session.id
     * @return 问答记录列表，按提问顺序排列
     */
    default List<InterviewQa> selectBySession(Long userId, Long sessionId) {
        return selectList(new LambdaQueryWrapper<InterviewQa>()
                .eq(InterviewQa::getUserId, userId)
                .eq(InterviewQa::getSessionId, sessionId)
                .orderByAsc(InterviewQa::getId));
    }
}
