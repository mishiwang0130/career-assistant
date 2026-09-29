package com.wxy.career.service.impl;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.mapper.ResumeMapper;
import com.wxy.career.po.Resume;
import com.wxy.career.vo.ResumeDiagnosisDimensionVO;
import com.wxy.career.vo.ResumeDiagnosisResultVO;
import com.wxy.career.vo.ResumeDiagnosisSubmitVO;
import com.wxy.career.vo.ResumeReadResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 简历诊断服务测试。
 *
 * <p>覆盖三件事：读简历的分段与目标定位（含 1201/1301/1302 三种不可用原因）、诊断结论的校验、
 * 结论缓冲的隔离与一次消费。不依赖 MySQL、Redis 与模型。
 *
 * @author wxy
 * @date 2026-09-29
 */
class ResumeDiagnosisServiceImplTest {

    /**
     * 单段最大字符数，与实现保持一致；用例用它构造跨段正文。
     */
    private static final int SEGMENT_LENGTH = 4000;

    /**
     * 简历 Mapper mock。
     */
    private ResumeMapper resumeMapper;

    /**
     * 被测服务。
     */
    private ResumeDiagnosisServiceImpl service;

    /**
     * 初始化被测服务与 Mapper 桩。
     */
    @BeforeEach
    void setUp() {
        resumeMapper = mock(ResumeMapper.class);
        service = new ResumeDiagnosisServiceImpl();
        ReflectionTestUtils.setField(service, "resumeMapper", resumeMapper);
    }

    /**
     * 验证长简历按段返回：第 1 段截断且提示还有下一段，最后一段 hasMore=false。
     */
    @Test
    void shouldReturnResumeInSegments() {
        Resume resume = buildResume(1L, "长简历", "x".repeat(SEGMENT_LENGTH + 10));
        when(resumeMapper.selectByIdAndUserId(1L, 7L)).thenReturn(resume);

        ResumeReadResultVO first = service.readResume(7L, 1L, null, null);
        ResumeReadResultVO second = service.readResume(7L, 1L, null, 2);
        ResumeReadResultVO overflow = service.readResume(7L, 1L, null, 9);

        assertThat(first.isFound()).isTrue();
        assertThat(first.getContent()).hasSize(SEGMENT_LENGTH);
        assertThat(first.getTotalSegments()).isEqualTo(2);
        assertThat(first.isHasMore()).isTrue();
        assertThat(second.getContent()).hasSize(10);
        assertThat(second.isHasMore()).isFalse();
        // 越界的段号钳到最后一段，避免模型算错段号读不到内容。
        assertThat(overflow.getSegment()).isEqualTo(2);
        assertThat(overflow.isHasMore()).isFalse();
    }

    /**
     * 验证不传参数时读默认简历。
     */
    @Test
    void shouldReadDefaultResumeWhenNoTargetGiven() {
        when(resumeMapper.selectByUserId(7L))
                .thenReturn(List.of(buildResume(2L, "普通简历", "内容"), buildDefaultResume(3L, "默认简历")));

        ResumeReadResultVO result = service.readResume(7L, null, null, null);

        assertThat(result.isFound()).isTrue();
        assertThat(result.getResumeId()).isEqualTo(3L);
        assertThat(result.getTitle()).isEqualTo("默认简历");
    }

    /**
     * 验证按标题精确匹配唯一简历。
     */
    @Test
    void shouldReadResumeByTitle() {
        when(resumeMapper.selectByUserId(7L))
                .thenReturn(List.of(buildResume(2L, "Java 开发简历", "内容A"), buildResume(3L, "产品简历", "内容B")));

        ResumeReadResultVO result = service.readResume(7L, null, "产品简历", null);

        assertThat(result.getResumeId()).isEqualTo(3L);
    }

    /**
     * 验证没有简历时返回不可用原因并带上空候选列表。
     */
    @Test
    void shouldReportUnavailableWhenNoResumeAtAll() {
        when(resumeMapper.selectByUserId(7L)).thenReturn(List.of());

        ResumeReadResultVO result = service.readResume(7L, null, null, null);

        assertThat(result.isFound()).isFalse();
        assertThat(result.getMessage())
                .contains(ErrorConstant.RESUME_DIAGNOSIS_TARGET_MISSING.getMsg())
                .contains("还没有可诊断的简历");
        assertThat(result.getCandidates()).isEmpty();
    }

    /**
     * 验证指定简历不存在时附上候选简历，让模型改口径重试。
     */
    @Test
    void shouldReportCandidatesWhenTargetMissing() {
        when(resumeMapper.selectByIdAndUserId(99L, 7L)).thenReturn(null);
        when(resumeMapper.selectByUserId(7L)).thenReturn(List.of(buildResume(2L, "Java 开发简历", "内容A")));

        ResumeReadResultVO result = service.readResume(7L, 99L, null, null);

        assertThat(result.isFound()).isFalse();
        assertThat(result.getMessage()).contains(ErrorConstant.RESUME_NOT_FOUND.getMsg());
        assertThat(result.getCandidates()).hasSize(1);
        assertThat(result.getCandidates().get(0).getTitle()).isEqualTo("Java 开发简历");
    }

    /**
     * 验证正文为空或还在解析中的简历不能作为诊断输入。
     */
    @Test
    void shouldReportUnavailableWhenContentNotReady() {
        Resume pending = buildResume(2L, "解析中简历", null);
        pending.setParseStatus(Resume.PARSE_STATUS_PENDING);
        when(resumeMapper.selectByIdAndUserId(2L, 7L)).thenReturn(pending);

        ResumeReadResultVO result = service.readResume(7L, 2L, null, null);

        assertThat(result.isFound()).isFalse();
        assertThat(result.getMessage()).contains(ErrorConstant.RESUME_CONTENT_UNAVAILABLE.getMsg());
    }

    /**
     * 验证目标定位的三类失败分别抛出对应错误码，供接口与工具统一复用。
     */
    @Test
    void shouldThrowBusinessCodesWhenTargetUnusable() {
        when(resumeMapper.selectByIdAndUserId(1L, 7L)).thenReturn(null);
        assertThatThrownBy(() -> service.resolveDiagnosisTarget(7L, 1L, null))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(
                        ((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(ErrorConstant.RESUME_NOT_FOUND.getCode()));

        Resume pending = buildResume(2L, "解析中简历", null);
        pending.setParseStatus(Resume.PARSE_STATUS_PENDING);
        when(resumeMapper.selectByIdAndUserId(2L, 7L)).thenReturn(pending);
        assertThatThrownBy(() -> service.resolveDiagnosisTarget(7L, 2L, null))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(
                        ((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(ErrorConstant.RESUME_CONTENT_UNAVAILABLE.getCode()));

        when(resumeMapper.selectByUserId(7L)).thenReturn(List.of(buildResume(3L, "普通简历", "内容")));
        assertThatThrownBy(() -> service.resolveDiagnosisTarget(7L, null, null))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(
                        ((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(ErrorConstant.RESUME_DIAGNOSIS_TARGET_MISSING.getCode()));
    }

    /**
     * 验证标题匹配到多份时不替用户挑一份。
     */
    @Test
    void shouldRejectAmbiguousTitle() {
        when(resumeMapper.selectByUserId(7L)).thenReturn(List.of(
                buildResume(2L, "Java 开发简历", "内容A"),
                buildResume(3L, "Java 开发简历（副本）", "内容B")));

        assertThatThrownBy(() -> service.resolveDiagnosisTarget(7L, null, "Java"))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(
                        ((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(ErrorConstant.RESUME_DIAGNOSIS_TARGET_MISSING.getCode()));
    }

    /**
     * 验证合法结论被暂存，并且只消费一次。
     */
    @Test
    void shouldStoreDiagnosisAndConsumeOnce() {
        when(resumeMapper.selectByIdAndUserId(5L, 7L)).thenReturn(buildResume(5L, "Java 开发简历", "内容"));

        service.submitDiagnosis(7L, "100", buildValidDiagnosis(5L));
        ResumeDiagnosisResultVO result = service.consumeDiagnosis(7L, "100");
        ResumeDiagnosisResultVO second = service.consumeDiagnosis(7L, "100");

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(ResumeDiagnosisResultVO.TYPE_RESUME_DIAGNOSIS);
        assertThat(result.getResumeId()).isEqualTo(5L);
        assertThat(result.getResumeTitle()).isEqualTo("Java 开发简历");
        assertThat(result.getDimensions()).hasSize(4);
        assertThat(second).isNull();
    }

    /**
     * 验证结论按用户与会话隔离，不会被别的会话或别的用户取走。
     */
    @Test
    void shouldIsolateDiagnosisByUserAndSession() {
        when(resumeMapper.selectByIdAndUserId(5L, 7L)).thenReturn(buildResume(5L, "Java 开发简历", "内容"));

        service.submitDiagnosis(7L, "100", buildValidDiagnosis(5L));

        assertThat(service.consumeDiagnosis(7L, "101")).isNull();
        assertThat(service.consumeDiagnosis(8L, "100")).isNull();
        assertThat(service.consumeDiagnosis(7L, "100")).isNotNull();
    }

    /**
     * 验证结构不完整的结论被拒绝，不能推半成品卡片给用户。
     */
    @Test
    void shouldRejectIncompleteDiagnosis() {
        ResumeDiagnosisSubmitVO submitVO = buildValidDiagnosis(5L);
        submitVO.setDimensions(submitVO.getDimensions().subList(0, 3));
        assertThatThrownBy(() -> service.submitDiagnosis(7L, "100", submitVO))
                .isInstanceOf(BizException.class);

        ResumeDiagnosisSubmitVO scoreOutOfRange = buildValidDiagnosis(5L);
        scoreOutOfRange.setOverallScore(120);
        assertThatThrownBy(() -> service.submitDiagnosis(7L, "100", scoreOutOfRange))
                .isInstanceOf(BizException.class);

        ResumeDiagnosisSubmitVO noFollowUp = buildValidDiagnosis(5L);
        noFollowUp.setInterviewFollowUps(List.of("只有一个追问点"));
        assertThatThrownBy(() -> service.submitDiagnosis(7L, "100", noFollowUp))
                .isInstanceOf(BizException.class);
    }

    /**
     * 验证结论指向不属于当前用户的简历时按「简历不存在」处理。
     */
    @Test
    void shouldRejectDiagnosisOfOtherUsersResume() {
        when(resumeMapper.selectByIdAndUserId(5L, 7L)).thenReturn(null);

        assertThatThrownBy(() -> service.submitDiagnosis(7L, "100", buildValidDiagnosis(5L)))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(
                        ((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(ErrorConstant.RESUME_NOT_FOUND.getCode()));
    }

    /**
     * 构造简历实体。
     *
     * @param id 简历 ID
     * @param title 标题
     * @param rawText 正文，null 表示未解析出正文
     * @return 简历实体
     */
    private Resume buildResume(Long id, String title, String rawText) {
        Resume resume = new Resume();
        resume.setId(id);
        resume.setUserId(7L);
        resume.setTitle(title);
        resume.setRawText(rawText);
        resume.setParseStatus(Resume.PARSE_STATUS_SUCCESS);
        resume.setDefaultFlag(Resume.DEFAULT_FLAG_NO);
        return resume;
    }

    /**
     * 构造默认简历。
     *
     * @param id 简历 ID
     * @param title 标题
     * @return 默认简历
     */
    private Resume buildDefaultResume(Long id, String title) {
        Resume resume = buildResume(id, title, "默认简历内容");
        resume.setDefaultFlag(Resume.DEFAULT_FLAG_YES);
        return resume;
    }

    /**
     * 构造一份通过校验的诊断结论。
     *
     * @param resumeId 简历 ID
     * @return 诊断结论
     */
    private ResumeDiagnosisSubmitVO buildValidDiagnosis(Long resumeId) {
        ResumeDiagnosisSubmitVO submitVO = new ResumeDiagnosisSubmitVO();
        submitVO.setResumeId(resumeId);
        submitVO.setOverallScore(72);
        submitVO.setScoreSummary("扣分主要来自项目成果缺少量化。");
        List<ResumeDiagnosisDimensionVO> dimensions = new ArrayList<>();
        for (String name : List.of("结构与排版", "完整度", "成果量化", "表达专业性")) {
            ResumeDiagnosisDimensionVO dimension = new ResumeDiagnosisDimensionVO();
            dimension.setName(name);
            dimension.setScore(70);
            dimension.setComment("说明");
            dimensions.add(dimension);
        }
        submitVO.setDimensions(dimensions);
        submitVO.setProblems(List.of());
        submitVO.setHighlights(List.of());
        submitVO.setSuggestions(List.of());
        submitVO.setOptimizedResume("优化后的正文");
        submitVO.setInterviewFollowUps(List.of("点一", "点二", "点三"));
        return submitVO;
    }
}
