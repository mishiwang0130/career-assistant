package com.wxy.career.service;

import com.wxy.career.vo.ResumeDiagnosisResultVO;
import com.wxy.career.vo.ResumeDiagnosisSubmitVO;
import com.wxy.career.vo.ResumeReadResultVO;

/**
 * 简历诊断服务。
 *
 * <p>职责有三：按标识、标题或默认简历定位诊断目标并分段返回正文；校验并暂存模型提交的结构化诊断结论；
 * 在流结束时把暂存的结论交给对话通道作为 {@code result} 事件下发。
 *
 * <p>诊断结论只暂存在进程内的运行态缓冲里（按 userId + sessionId 隔离、带过期时间），**不落库**：
 * F2 是分析产物而不是简历数据，简历表结构不变，用户看到的完整诊断正文仍随助手消息正常落库。
 *
 * @author wxy
 * @date 2026-09-29
 */
public interface ResumeDiagnosisService {

    /**
     * 读取简历正文（分段）。
     *
     * @param userId 用户 ID，只接受运行时上下文中的身份
     * @param resumeId 简历 ID，可为空
     * @param title 简历标题，可为空
     * @param segment 分段序号，从 1 开始，为空按第 1 段处理
     * @return 读简历结果，未取到正文时返回原因与可选候选
     */
    ResumeReadResultVO readResume(Long userId, Long resumeId, String title, Integer segment);

    /**
     * 校验并暂存简历诊断结论。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param submitVO 诊断结论
     */
    void submitDiagnosis(Long userId, String sessionId, ResumeDiagnosisSubmitVO submitVO);

    /**
     * 取出并清空暂存的诊断结论，由对话通道在流结束时调用。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 诊断结论，没有或已过期时返回 null
     */
    ResumeDiagnosisResultVO consumeDiagnosis(Long userId, String sessionId);
}
