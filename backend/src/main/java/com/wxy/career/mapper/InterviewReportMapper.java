package com.wxy.career.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wxy.career.po.InterviewReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 面试报告 Mapper。
 *
 * <p>查询一律同时带 user_id 与 session_id：会话 ID 相同也不能读到别人的报告。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Mapper
public interface InterviewReportMapper extends BaseMapper<InterviewReport> {

    /**
     * 按用户与会话查询报告。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @return 报告记录，不存在时返回 null
     */
    default InterviewReport selectByUserAndSession(Long userId, Long sessionId) {
        return selectOne(new LambdaQueryWrapper<InterviewReport>()
                .eq(InterviewReport::getUserId, userId)
                .eq(InterviewReport::getSessionId, sessionId));
    }

    /**
     * 幂等地把报告置为「生成中」：没有记录就插入，已有记录就重置为生成中并递增尝试次数。
     *
     * <p>用一条 INSERT ... ON DUPLICATE KEY UPDATE 完成，避免「先查再写」的事务与并发窗口；
     * 重置摘要与失败原因，保证重试后的状态与内容不会残留上一次的结果。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @return 受影响行数
     */
    @Update("INSERT INTO interview_report (user_id, session_id, status, attempt, summary, report_json,"
            + " error_message, finish_time, create_time, create_by, update_time, update_by, is_delete)"
            + " VALUES (#{userId}, #{sessionId}, 'GENERATING', 1, NULL, NULL, NULL, NULL,"
            + " NOW(), 0, NOW(), 0, 0)"
            + " ON DUPLICATE KEY UPDATE status = 'GENERATING', attempt = attempt + 1, summary = NULL,"
            + " report_json = NULL, error_message = NULL, finish_time = NULL, update_time = NOW()")
    int upsertGenerating(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    /**
     * 把报告置为「生成失败」并记录原因。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @param errorMessage 失败原因，超长截断
     * @return 受影响行数
     */
    @Update("UPDATE interview_report SET status = 'FAILED', error_message = #{errorMessage},"
            + " update_time = NOW()"
            + " WHERE user_id = #{userId} AND session_id = #{sessionId} AND is_delete = 0")
    int markFailed(
            @Param("userId") Long userId,
            @Param("sessionId") Long sessionId,
            @Param("errorMessage") String errorMessage);

    /**
     * 回写报告结论并置为「已完成」。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @param summary 面试总结正文
     * @param reportJson 报告结构化结论 JSON
     * @return 受影响行数
     */
    @Update("UPDATE interview_report SET status = 'SUCCEEDED', summary = #{summary},"
            + " report_json = #{reportJson}, error_message = NULL, finish_time = NOW(), update_time = NOW()"
            + " WHERE user_id = #{userId} AND session_id = #{sessionId} AND is_delete = 0")
    int markSucceeded(
            @Param("userId") Long userId,
            @Param("sessionId") Long sessionId,
            @Param("summary") String summary,
            @Param("reportJson") String reportJson);
}
