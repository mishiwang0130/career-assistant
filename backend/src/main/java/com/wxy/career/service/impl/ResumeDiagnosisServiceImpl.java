package com.wxy.career.service.impl;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.mapper.ResumeMapper;
import com.wxy.career.po.Resume;
import com.wxy.career.service.ResumeDiagnosisService;
import com.wxy.career.vo.ResumeCandidateVO;
import com.wxy.career.vo.ResumeDiagnosisDimensionVO;
import com.wxy.career.vo.ResumeDiagnosisResultVO;
import com.wxy.career.vo.ResumeDiagnosisSubmitVO;
import com.wxy.career.vo.ResumeReadResultVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简历诊断服务实现。
 *
 * <p>分段与缓冲都在本层完成：正文按固定长度切段，避免长简历一次撑爆模型上下文；
 * 结构化结论按 {@code userId + sessionId} 暂存，同用户不同会话互不串号。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
@Service
public class ResumeDiagnosisServiceImpl implements ResumeDiagnosisService {

    /**
     * 单次返回的正文最大长度（字符）。长简历由模型自己决定是否继续读下一段。
     */
    private static final int SEGMENT_MAX_LENGTH = 4000;

    /**
     * 暂存诊断结论的存活时间，单位毫秒。
     *
     * <p>结论只在一次流式对话内使用，正常路径下流一结束就被取走；这里的过期时间只是兜底，
     * 避免异常路径（连接中断、异常未清理）把结论长期留在内存里。
     */
    private static final long BUFFER_TTL_MILLIS = 30 * 60 * 1000L;

    /**
     * 候选简历列表最多返回条数，避免把用户全部简历都塞进模型上下文。
     */
    private static final int MAX_CANDIDATES = 20;

    /**
     * 维度评分最少条数，低于该数量视为结构不完整。
     */
    private static final int MIN_DIMENSION_COUNT = 4;

    /**
     * 可能被追问的项目点最少条数。
     */
    private static final int MIN_FOLLOW_UP_COUNT = 3;

    /**
     * 可能被追问的项目点最多条数。
     */
    private static final int MAX_FOLLOW_UP_COUNT = 5;

    /**
     * 分数下限。
     */
    private static final int SCORE_MIN = 0;

    /**
     * 分数上限。
     */
    private static final int SCORE_MAX = 100;

    /**
     * 简历 Mapper。
     */
    @Resource
    private ResumeMapper resumeMapper;

    /**
     * 诊断结论运行态缓冲：键为 {@code userId/sessionId}，值为结论与写入时间。
     */
    private final Map<String, BufferedDiagnosis> diagnosisBuffer = new ConcurrentHashMap<>();

    /**
     * 读取简历正文（分段）。
     *
     * @param userId 用户 ID
     * @param resumeId 简历 ID，可为空
     * @param title 简历标题，可为空
     * @param segment 分段序号，从 1 开始
     * @return 读简历结果
     */
    @Override
    public ResumeReadResultVO readResume(Long userId, Long resumeId, String title, Integer segment) {
        if (userId == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
        try {
            Resume target = resolveDiagnosisTarget(userId, resumeId, title);
            return buildSegmentResult(target, normalizeSegment(segment));
        } catch (BizException exception) {
            // 目标不可用时把原因说清楚，并附上可选简历，让模型能改口径重试而不是反问用户。
            log.info("读取简历未取到正文，userId={}，code={}，msg={}",
                    userId, exception.getErrorCode().getCode(), exception.getErrorCode().getMsg());
            return buildUnavailableResult(exception.getErrorCode().getMsg(), listCandidates(userId));
        }
    }

    /**
     * 定位诊断目标：简历 ID → 标题 → 默认简历，并要求正文可用。
     *
     * @param userId 用户 ID
     * @param resumeId 简历 ID，可为空
     * @param title 简历标题，可为空
     * @return 可用于诊断的简历
     */
    public Resume resolveDiagnosisTarget(Long userId, Long resumeId, String title) {
        if (resumeId != null) {
            Resume resume = resumeMapper.selectByIdAndUserId(resumeId, userId);
            if (resume == null) {
                // 跨账号访问与不存在表现一致，不暴露资源是否存在。
                throw new BizException(ErrorConstant.RESUME_NOT_FOUND);
            }
            return requireUsableContent(resume);
        }
        List<Resume> resumes = resumeMapper.selectByUserId(userId);
        if (StringUtils.hasText(title)) {
            return requireUsableContent(matchByTitle(resumes, title.trim()));
        }
        for (Resume resume : resumes) {
            if (Resume.DEFAULT_FLAG_YES == (resume.getDefaultFlag() == null
                    ? Resume.DEFAULT_FLAG_NO : resume.getDefaultFlag())) {
                return requireUsableContent(resume);
            }
        }
        // 既没指定也没有默认简历时不能替用户猜，交由模型向用户确认。
        throw new BizException(ErrorConstant.RESUME_DIAGNOSIS_TARGET_MISSING);
    }

    /**
     * 校验并暂存诊断结论。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param submitVO 诊断结论
     */
    @Override
    public void submitDiagnosis(Long userId, String sessionId, ResumeDiagnosisSubmitVO submitVO) {
        if (userId == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
        if (!StringUtils.hasText(sessionId) || submitVO == null) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        validateDiagnosis(submitVO);
        Resume resume = resumeMapper.selectByIdAndUserId(submitVO.getResumeId(), userId);
        if (resume == null) {
            throw new BizException(ErrorConstant.RESUME_NOT_FOUND);
        }
        long now = System.currentTimeMillis();
        purgeExpired(now);
        diagnosisBuffer.put(bufferKey(userId, sessionId), new BufferedDiagnosis(toResult(resume, submitVO), now));
    }

    /**
     * 取出并清空暂存的诊断结论。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 诊断结论，没有或已过期时返回 null
     */
    @Override
    public ResumeDiagnosisResultVO consumeDiagnosis(Long userId, String sessionId) {
        if (userId == null || !StringUtils.hasText(sessionId)) {
            return null;
        }
        BufferedDiagnosis buffered = diagnosisBuffer.remove(bufferKey(userId, sessionId));
        if (buffered == null) {
            return null;
        }
        if (System.currentTimeMillis() - buffered.createdAt() > BUFFER_TTL_MILLIS) {
            log.info("暂存的诊断结论已过期，直接丢弃，userId={}，sessionId={}", userId, sessionId);
            return null;
        }
        return buffered.payload();
    }

    /**
     * 校验诊断结论的结构与取值范围。
     *
     * <p>校验失败按参数错误抛出，由工具转成可读提示让模型重试：结论不完整时宁可要模型重填一次，
     * 也不能把半成品卡片推给用户。
     *
     * @param submitVO 诊断结论
     */
    private void validateDiagnosis(ResumeDiagnosisSubmitVO submitVO) {
        if (submitVO.getResumeId() == null) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        requireScore(submitVO.getOverallScore());
        if (!StringUtils.hasText(submitVO.getScoreSummary())) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        if (submitVO.getDimensions() == null || submitVO.getDimensions().size() < MIN_DIMENSION_COUNT) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        for (ResumeDiagnosisDimensionVO dimension : submitVO.getDimensions()) {
            if (dimension == null || !StringUtils.hasText(dimension.getName())) {
                throw new BizException(ErrorConstant.PARAM_ERROR);
            }
            requireScore(dimension.getScore());
        }
        if (!StringUtils.hasText(submitVO.getOptimizedResume())) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        List<String> followUps = submitVO.getInterviewFollowUps();
        if (followUps == null
                || followUps.size() < MIN_FOLLOW_UP_COUNT
                || followUps.size() > MAX_FOLLOW_UP_COUNT) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
    }

    /**
     * 校验单个分数落在 0-100 之间。
     *
     * @param score 分数
     */
    private void requireScore(Integer score) {
        if (score == null || score < SCORE_MIN || score > SCORE_MAX) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
    }

    /**
     * 按标题匹配唯一简历。
     *
     * @param resumes 用户全部简历
     * @param keyword 标题关键字
     * @return 唯一匹配的简历
     */
    private Resume matchByTitle(List<Resume> resumes, String keyword) {
        List<Resume> exact = new ArrayList<>(1);
        List<Resume> fuzzy = new ArrayList<>(1);
        for (Resume resume : resumes) {
            String resumeTitle = resume.getTitle();
            if (!StringUtils.hasText(resumeTitle)) {
                continue;
            }
            if (resumeTitle.trim().equalsIgnoreCase(keyword)) {
                exact.add(resume);
            } else if (resumeTitle.contains(keyword)) {
                fuzzy.add(resume);
            }
        }
        List<Resume> matched = exact.isEmpty() ? fuzzy : exact;
        if (matched.size() != 1) {
            // 匹配不到或多份命中时都不能替用户挑一份。
            throw new BizException(matched.isEmpty()
                    ? ErrorConstant.RESUME_NOT_FOUND : ErrorConstant.RESUME_DIAGNOSIS_TARGET_MISSING);
        }
        return matched.get(0);
    }

    /**
     * 校验简历正文是否可用于诊断。
     *
     * @param resume 简历
     * @return 原样返回可用的简历
     */
    private Resume requireUsableContent(Resume resume) {
        if (!Resume.PARSE_STATUS_SUCCESS.equals(resume.getParseStatus())
                || !StringUtils.hasText(resume.getRawText())) {
            throw new BizException(ErrorConstant.RESUME_CONTENT_UNAVAILABLE);
        }
        return resume;
    }

    /**
     * 归一化分段序号：非正数或空按第 1 段处理，超出总段数时钳到最后一段。
     *
     * @param segment 分段序号
     * @return 归一化后的分段序号
     */
    private int normalizeSegment(Integer segment) {
        return segment == null || segment < 1 ? 1 : segment;
    }

    /**
     * 按固定长度切出指定分段的正文。
     *
     * @param resume 简历
     * @param segment 分段序号
     * @return 读简历结果
     */
    private ResumeReadResultVO buildSegmentResult(Resume resume, int segment) {
        String text = resume.getRawText();
        int totalLength = text.length();
        int totalSegments = Math.max(1, (totalLength + SEGMENT_MAX_LENGTH - 1) / SEGMENT_MAX_LENGTH);
        int currentSegment = Math.min(segment, totalSegments);
        int fromIndex = (currentSegment - 1) * SEGMENT_MAX_LENGTH;
        int toIndex = Math.min(fromIndex + SEGMENT_MAX_LENGTH, totalLength);
        boolean hasMore = currentSegment < totalSegments;

        ResumeReadResultVO result = new ResumeReadResultVO();
        result.setFound(true);
        result.setResumeId(resume.getId());
        result.setTitle(resume.getTitle());
        result.setParseStatus(resume.getParseStatus());
        result.setTotalLength(totalLength);
        result.setSegment(currentSegment);
        result.setTotalSegments(totalSegments);
        result.setHasMore(hasMore);
        result.setContent(text.substring(fromIndex, toIndex));
        result.setMessage(hasMore
                ? "已返回第 " + currentSegment + "/" + totalSegments + " 段，正文未读完，请继续以 segment="
                        + (currentSegment + 1) + " 读取下一段"
                : "已返回第 " + currentSegment + "/" + totalSegments + " 段，正文已读完");
        return result;
    }

    /**
     * 构造未取到正文时的结果，附上可选简历，让模型能改口径重试。
     *
     * @param message 未取到的原因
     * @param candidates 可选简历
     * @return 读简历结果
     */
    private ResumeReadResultVO buildUnavailableResult(String message, List<ResumeCandidateVO> candidates) {
        ResumeReadResultVO result = new ResumeReadResultVO();
        result.setFound(false);
        result.setMessage(candidates.isEmpty()
                ? message + "；当前账号还没有可诊断的简历"
                : message + "；可选简历见 candidates，请用 resume_id 指定其中一份");
        result.setCandidates(candidates);
        return result;
    }

    /**
     * 列出当前用户的可选简历。
     *
     * @param userId 用户 ID
     * @return 可选简历列表，最多 {@link #MAX_CANDIDATES} 条
     */
    private List<ResumeCandidateVO> listCandidates(Long userId) {
        List<Resume> resumes = resumeMapper.selectByUserId(userId);
        List<ResumeCandidateVO> candidates = new ArrayList<>(resumes.size());
        for (Resume resume : resumes) {
            if (candidates.size() >= MAX_CANDIDATES) {
                break;
            }
            ResumeCandidateVO candidate = new ResumeCandidateVO();
            candidate.setId(resume.getId());
            candidate.setTitle(resume.getTitle());
            candidate.setDefaultFlag(Resume.DEFAULT_FLAG_YES == (resume.getDefaultFlag() == null
                    ? Resume.DEFAULT_FLAG_NO : resume.getDefaultFlag()));
            candidate.setParseStatus(resume.getParseStatus());
            candidates.add(candidate);
        }
        return candidates;
    }

    /**
     * 把提交的结论组装成下发结构，并补齐简历标识与标题。
     *
     * @param resume 被诊断的简历
     * @param submitVO 提交的结论
     * @return 下发结构
     */
    private ResumeDiagnosisResultVO toResult(Resume resume, ResumeDiagnosisSubmitVO submitVO) {
        ResumeDiagnosisResultVO result = new ResumeDiagnosisResultVO();
        result.setResumeId(resume.getId());
        result.setResumeTitle(resume.getTitle());
        result.setOverallScore(submitVO.getOverallScore());
        result.setScoreSummary(submitVO.getScoreSummary());
        result.setDimensions(submitVO.getDimensions());
        result.setProblems(nullToEmpty(submitVO.getProblems()));
        result.setHighlights(nullToEmpty(submitVO.getHighlights()));
        result.setSuggestions(nullToEmpty(submitVO.getSuggestions()));
        result.setOptimizedResume(submitVO.getOptimizedResume());
        result.setInterviewFollowUps(submitVO.getInterviewFollowUps());
        return result;
    }

    /**
     * 空清单归一化为空列表，避免前端为 null 做额外判空。
     *
     * @param source 原清单
     * @param <T> 元素类型
     * @return 非空清单
     */
    private <T> List<T> nullToEmpty(List<T> source) {
        return source == null ? List.of() : source;
    }

    /**
     * 清理已过期的暂存结论。
     *
     * @param now 当前时间戳
     */
    private void purgeExpired(long now) {
        Collection<String> keys = diagnosisBuffer.keySet();
        Iterator<String> iterator = keys.iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            BufferedDiagnosis buffered = diagnosisBuffer.get(key);
            if (buffered != null && now - buffered.createdAt() > BUFFER_TTL_MILLIS) {
                diagnosisBuffer.remove(key, buffered);
            }
        }
    }

    /**
     * 构造缓冲键：用户与会话共同决定，避免同用户的不同会话互相覆盖。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 缓冲键
     */
    private String bufferKey(Long userId, String sessionId) {
        return userId + "/" + sessionId;
    }

    /**
     * 暂存的诊断结论。
     *
     * @param payload 下发的结构化结论
     * @param createdAt 写入时间戳，用于过期兜底清理
     * @author wxy
     * @date 2026-09-29
     */
    private record BufferedDiagnosis(ResumeDiagnosisResultVO payload, long createdAt) {
    }
}
