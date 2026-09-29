package com.wxy.career.tool;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.service.ResumeDiagnosisService;
import com.wxy.career.vo.ResumeReadResultVO;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 读简历工具测试。
 *
 * <p>固定两件事：用户身份只从运行时上下文取（模型无法指定 userId），以及上下文缺失时按未登录处理、
 * 不返回任何用户数据。
 *
 * @author wxy
 * @date 2026-09-29
 */
class ReadResumeToolTest {

    /**
     * 简历诊断服务 mock。
     */
    private ResumeDiagnosisService resumeDiagnosisService;

    /**
     * 被测工具。
     */
    private ReadResumeTool readResumeTool;

    /**
     * 初始化被测工具。
     */
    @BeforeEach
    void setUp() {
        resumeDiagnosisService = mock(ResumeDiagnosisService.class);
        readResumeTool = new ReadResumeTool();
        ReflectionTestUtils.setField(readResumeTool, "resumeDiagnosisService", resumeDiagnosisService);
    }

    /**
     * 验证按运行时上下文里的用户身份读取简历。
     */
    @Test
    void shouldReadResumeWithRuntimeUserId() {
        ResumeReadResultVO expected = new ResumeReadResultVO();
        expected.setFound(true);
        expected.setResumeId(5L);
        when(resumeDiagnosisService.readResume(7L, 5L, null, 2)).thenReturn(expected);

        ResumeReadResultVO result = readResumeTool.readResume(
                5L, null, 2, RuntimeContext.builder().userId("7").sessionId("100").build());

        assertThat(result.isFound()).isTrue();
        verify(resumeDiagnosisService).readResume(7L, 5L, null, 2);
    }

    /**
     * 验证缺少运行时用户身份时按未登录处理，且不访问任何简历数据。
     */
    @Test
    void shouldRejectMissingRuntimeUserId() {
        assertThatThrownBy(() -> readResumeTool.readResume(
                null, null, null, RuntimeContext.builder().sessionId("100").build()))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(
                        ((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(ErrorConstant.UNAUTHORIZED.getCode()));
        assertThatThrownBy(() -> readResumeTool.readResume(null, null, null, null))
                .isInstanceOf(BizException.class);

        verify(resumeDiagnosisService, never()).readResume(any(), any(), any(), any());
    }
}
