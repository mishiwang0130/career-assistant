package com.wxy.career.service.impl;

import com.wxy.career.common.auth.LoginUser;
import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.storage.FileStorage;
import com.wxy.career.common.storage.StoredFile;
import com.wxy.career.mapper.ResumeMapper;
import com.wxy.career.po.Resume;
import com.wxy.career.util.ResumeParseException;
import com.wxy.career.util.ResumeParser;
import com.wxy.career.vo.ResumeDetailRespVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 简历服务测试。
 *
 * @author wxy
 * @date 2026-09-28
 */
@ExtendWith(MockitoExtension.class)
class ResumeServiceImplTest {

    /**
     * 简历 Mapper。
     */
    @Mock
    private ResumeMapper resumeMapper;

    /**
     * 文件存储。
     */
    @Mock
    private FileStorage fileStorage;

    /**
     * 简历解析器。
     */
    @Mock
    private ResumeParser resumeParser;

    /**
     * 被测简历服务。
     */
    @InjectMocks
    private ResumeServiceImpl resumeService;

    /**
     * 初始化登录用户上下文。
     */
    @BeforeEach
    void setUp() {
        LoginUserHolder.set(new LoginUser(1L, "alice", "jti-1"));
    }

    /**
     * 清理登录用户上下文。
     */
    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    /**
     * 验证详情查询按 id 与 userId 双条件执行，跨账号表现为不存在。
     */
    @Test
    void shouldTreatCrossAccountResumeAsNotFound() {
        when(resumeMapper.selectByIdAndUserId(9L, 1L)).thenReturn(null);

        assertThatThrownBy(() -> resumeService.detail(9L))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> {
                    BizException bizException = (BizException) exception;
                    assertThat(bizException.getErrorCode().getCode()).isEqualTo(1201);
                });
        verify(resumeMapper).selectByIdAndUserId(9L, 1L);
    }

    /**
     * 验证解析失败仍返回成功响应并写入 FAILED 状态。
     */
    @Test
    void shouldReturnFailedStatusWhenParserFails() {
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "resume.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.UTF_8));
        when(fileStorage.store(any(byte[].class), eq("resume.pdf"), eq("resume")))
                .thenReturn(new StoredFile("resume/1/202609/uuid.pdf", "resume.pdf", 8L, "pdf"));
        when(resumeParser.parse(any(byte[].class), eq("pdf")))
                .thenThrow(new ResumeParseException("解析器内部错误", new RuntimeException("boom")));
        doAnswer(invocation -> {
            Resume resume = invocation.getArgument(0);
            resume.setId(7L);
            return 1;
        }).when(resumeMapper).insert(any(Resume.class));

        ResumeDetailRespVO response = resumeService.upload(multipartFile, null);

        ArgumentCaptor<Resume> captor = ArgumentCaptor.forClass(Resume.class);
        verify(resumeMapper).insert(captor.capture());
        assertThat(captor.getValue().getParseStatus()).isEqualTo(Resume.PARSE_STATUS_FAILED);
        assertThat(captor.getValue().getParseError()).isEqualTo("解析器内部错误");
        assertThat(response.getId()).isEqualTo(7L);
        assertThat(response.getParseStatus()).isEqualTo(Resume.PARSE_STATUS_FAILED);
    }

    /**
     * 验证设为默认先清空旧默认再设置新默认。
     */
    @Test
    void shouldClearOldDefaultBeforeSettingNewDefault() {
        when(resumeMapper.selectByIdAndUserId(5L, 1L)).thenReturn(existingResume(5L));
        when(resumeMapper.update(any(Resume.class), any())).thenReturn(1);

        resumeService.setDefault(5L);

        verify(resumeMapper).clearDefault(1L);
        ArgumentCaptor<Resume> captor = ArgumentCaptor.forClass(Resume.class);
        verify(resumeMapper).update(captor.capture(), any());
        assertThat(captor.getValue().getDefaultFlag()).isEqualTo(Resume.DEFAULT_FLAG_YES);
    }

    /**
     * 验证列表只查询当前用户。
     */
    @Test
    void shouldListOnlyCurrentUserResumes() {
        resumeService.list();

        verify(resumeMapper).selectByUserId(1L);
    }

    /**
     * 构建已有简历。
     *
     * @param id 简历 ID
     * @return 简历实体
     */
    private Resume existingResume(Long id) {
        Resume resume = new Resume();
        resume.setId(id);
        resume.setUserId(1L);
        resume.setTitle("我的简历");
        resume.setSourceType(Resume.SOURCE_TYPE_MANUAL);
        resume.setParseStatus(Resume.PARSE_STATUS_SUCCESS);
        resume.setDefaultFlag(Resume.DEFAULT_FLAG_NO);
        return resume;
    }
}
