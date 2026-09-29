package com.wxy.career.tool;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.service.ResumeDiagnosisService;
import com.wxy.career.vo.ResumeDiagnosisSubmitVO;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 提交简历诊断结论工具测试。
 *
 * <p>固定三件事：用户与会话身份来自运行时上下文；提交成功时给出确认文案；校验失败时返回可读提示
 * 而不是把异常抛进对话流（模型要能自我修正后重试）。
 *
 * @author wxy
 * @date 2026-09-29
 */
class SubmitResumeDiagnosisToolTest {

    /**
     * 简历诊断服务 mock。
     */
    private ResumeDiagnosisService resumeDiagnosisService;

    /**
     * 被测工具。
     */
    private SubmitResumeDiagnosisTool submitResumeDiagnosisTool;

    /**
     * 初始化被测工具。
     */
    @BeforeEach
    void setUp() {
        resumeDiagnosisService = mock(ResumeDiagnosisService.class);
        submitResumeDiagnosisTool = new SubmitResumeDiagnosisTool();
        ReflectionTestUtils.setField(
                submitResumeDiagnosisTool, "resumeDiagnosisService", resumeDiagnosisService);
    }

    /**
     * 验证提交成功时返回确认文案，并把运行时身份传给服务。
     */
    @Test
    void shouldSubmitDiagnosisWithRuntimeIdentity() {
        ResumeDiagnosisSubmitVO diagnosis = new ResumeDiagnosisSubmitVO();
        diagnosis.setResumeId(5L);

        String message = submitResumeDiagnosisTool.submitResumeDiagnosis(
                diagnosis, RuntimeContext.builder().userId("7").sessionId("100").build());

        assertThat(message).contains("已提交");
        verify(resumeDiagnosisService).submitDiagnosis(7L, "100", diagnosis);
    }

    /**
     * 验证服务校验失败时返回可读提示，异常不冒泡。
     */
    @Test
    void shouldReturnReadableMessageWhenValidationFails() {
        doThrow(new BizException(ErrorConstant.PARAM_ERROR))
                .when(resumeDiagnosisService)
                .submitDiagnosis(eq(7L), anyString(), any());

        String message = submitResumeDiagnosisTool.submitResumeDiagnosis(
                new ResumeDiagnosisSubmitVO(), RuntimeContext.builder().userId("7").sessionId("100").build());

        assertThat(message).contains("提交失败").contains("0-100").contains("至少 4 项");
    }

    /**
     * 验证缺少运行时用户身份时按未登录处理。
     */
    @Test
    void shouldRejectMissingRuntimeUserId() {
        assertThatThrownBy(() -> submitResumeDiagnosisTool.submitResumeDiagnosis(
                new ResumeDiagnosisSubmitVO(), RuntimeContext.builder().sessionId("100").build()))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(
                        ((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(ErrorConstant.UNAUTHORIZED.getCode()));
    }
}
