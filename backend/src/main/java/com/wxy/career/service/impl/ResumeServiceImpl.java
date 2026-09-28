package com.wxy.career.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.common.storage.FileStorage;
import com.wxy.career.common.storage.StorageConstants;
import com.wxy.career.common.storage.StoredFile;
import com.wxy.career.mapper.ResumeMapper;
import com.wxy.career.po.Resume;
import com.wxy.career.service.ResumeService;
import com.wxy.career.util.ResumeParser;
import com.wxy.career.vo.ResumeDetailRespVO;
import com.wxy.career.vo.ResumeListRespVO;
import com.wxy.career.vo.ResumeManualReqVO;
import com.wxy.career.vo.ResumeUpdateReqVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 简历服务实现。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Slf4j
@Service
public class ResumeServiceImpl implements ResumeService {

    /**
     * 简历文件在存储层的业务类型。
     */
    private static final String BIZ_TYPE_RESUME = "resume";

    /**
     * 无法从文件名推导标题时使用的默认标题。
     */
    private static final String DEFAULT_TITLE = "未命名简历";

    /**
     * parse_error 字段最大长度，避免异常信息过长撑满日志与数据库字段。
     */
    private static final int PARSE_ERROR_MAX_LENGTH = 500;

    /**
     * 简历 Mapper。
     */
    @Resource
    private ResumeMapper resumeMapper;

    /**
     * 文件存储。
     */
    @Resource
    private FileStorage fileStorage;

    /**
     * 简历解析器。
     */
    @Resource
    private ResumeParser resumeParser;

    /**
     * 上传文件并同步解析。
     *
     * @param file 上传文件
     * @param title 简历标题
     * @return 简历详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResumeDetailRespVO upload(MultipartFile file, String title) {
        Long userId = requireUserId();
        validateUploadSize(file);
        byte[] content = readFileBytes(file);
        StoredFile storedFile = fileStorage.store(content, file.getOriginalFilename(), BIZ_TYPE_RESUME);

        String rawText = null;
        String parseStatus = Resume.PARSE_STATUS_SUCCESS;
        String parseError = null;
        try {
            rawText = resumeParser.parse(content, storedFile.getExtension());
        } catch (Exception exception) {
            // 解析失败仍保留文件和简历记录，只把状态与错误原因返回给前端。
            parseStatus = Resume.PARSE_STATUS_FAILED;
            parseError = truncateParseError(exception);
            log.warn("简历解析失败, fileName={}, fileExt={}", storedFile.getFileName(),
                    storedFile.getExtension(), exception);
        }

        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setTitle(resolveUploadTitle(title, storedFile.getFileName()));
        resume.setSourceType(Resume.SOURCE_TYPE_UPLOAD);
        resume.setFileName(storedFile.getFileName());
        resume.setObjectKey(storedFile.getObjectKey());
        resume.setFileSize(storedFile.getSize());
        resume.setFileExt(storedFile.getExtension());
        resume.setRawText(rawText);
        resume.setParseStatus(parseStatus);
        resume.setParseError(parseError);
        resume.setDefaultFlag(Resume.DEFAULT_FLAG_NO);
        try {
            resumeMapper.insert(resume);
        } catch (RuntimeException exception) {
            // 数据库写入失败时清理刚上传的对象，避免产生不可追踪的孤儿文件。
            safeDeleteFile(storedFile);
            throw exception;
        }
        return ResumeDetailRespVO.from(resume);
    }

    /**
     * 在线创建简历。
     *
     * @param reqVO 创建请求
     * @return 简历详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResumeDetailRespVO createManual(ResumeManualReqVO reqVO) {
        Resume resume = new Resume();
        resume.setUserId(requireUserId());
        resume.setTitle(reqVO.getTitle().trim());
        resume.setSourceType(Resume.SOURCE_TYPE_MANUAL);
        resume.setRawText(resumeParser.cleanText(reqVO.getRawText()));
        resume.setParseStatus(Resume.PARSE_STATUS_SUCCESS);
        resume.setDefaultFlag(Resume.DEFAULT_FLAG_NO);
        resumeMapper.insert(resume);
        return ResumeDetailRespVO.from(resume);
    }

    /**
     * 查询当前用户简历列表。
     *
     * @return 简历列表
     */
    @Override
    public List<ResumeListRespVO> list() {
        List<Resume> resumes = resumeMapper.selectByUserId(requireUserId());
        List<ResumeListRespVO> result = new ArrayList<>(resumes.size());
        for (Resume resume : resumes) {
            result.add(ResumeListRespVO.from(resume));
        }
        return result;
    }

    /**
     * 查询当前用户指定简历详情。
     *
     * @param id 简历 ID
     * @return 简历详情
     */
    @Override
    public ResumeDetailRespVO detail(Long id) {
        return ResumeDetailRespVO.from(getOwnedResume(id));
    }

    /**
     * 更新当前用户指定简历。
     *
     * @param id 简历 ID
     * @param reqVO 更新请求
     * @return 更新后的简历详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResumeDetailRespVO update(Long id, ResumeUpdateReqVO reqVO) {
        Long userId = requireUserId();
        getOwnedResume(id, userId);

        Resume update = new Resume();
        if (hasText(reqVO.getTitle())) {
            update.setTitle(reqVO.getTitle().trim());
        }
        if (hasText(reqVO.getRawText())) {
            update.setRawText(resumeParser.cleanText(reqVO.getRawText()));
            update.setParseStatus(Resume.PARSE_STATUS_SUCCESS);
        }
        LambdaUpdateWrapper<Resume> updateWrapper = new LambdaUpdateWrapper<Resume>()
                .eq(Resume::getId, id)
                .eq(Resume::getUserId, userId);
        if (hasText(reqVO.getRawText())) {
            updateWrapper.set(Resume::getParseError, null);
        }
        int updatedRows = resumeMapper.update(update, updateWrapper);
        if (updatedRows == 0) {
            throw new BizException(ErrorConstant.RESUME_NOT_FOUND);
        }
        return ResumeDetailRespVO.from(getOwnedResume(id, userId));
    }

    /**
     * 逻辑删除当前用户指定简历。
     *
     * @param id 简历 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Long userId = requireUserId();
        getOwnedResume(id, userId);
        resumeMapper.delete(new LambdaQueryWrapper<Resume>()
                .eq(Resume::getId, id)
                .eq(Resume::getUserId, userId));
    }

    /**
     * 将当前用户指定简历设为默认。
     *
     * @param id 简历 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long id) {
        Long userId = requireUserId();
        getOwnedResume(id, userId);
        resumeMapper.clearDefault(userId);
        Resume update = new Resume();
        update.setDefaultFlag(Resume.DEFAULT_FLAG_YES);
        int updatedRows = resumeMapper.update(update, new LambdaUpdateWrapper<Resume>()
                .eq(Resume::getId, id)
                .eq(Resume::getUserId, userId));
        if (updatedRows == 0) {
            throw new BizException(ErrorConstant.RESUME_NOT_FOUND);
        }
    }

    /**
     * 获取当前登录用户 ID。
     *
     * @return 当前用户 ID
     */
    private Long requireUserId() {
        Long userId = LoginUserHolder.getUserId();
        if (userId == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * 查询当前用户拥有的简历，跨账号统一表现为不存在。
     *
     * @param id 简历 ID
     * @return 简历实体
     */
    private Resume getOwnedResume(Long id) {
        return getOwnedResume(id, requireUserId());
    }

    /**
     * 按简历 ID 与用户 ID 查询简历。
     *
     * @param id 简历 ID
     * @param userId 用户 ID
     * @return 简历实体
     */
    private Resume getOwnedResume(Long id, Long userId) {
        Resume resume = resumeMapper.selectByIdAndUserId(id, userId);
        if (resume == null) {
            throw new BizException(ErrorConstant.RESUME_NOT_FOUND);
        }
        return resume;
    }

    /**
     * 校验上传文件是否为空或超过大小限制。
     *
     * @param file 上传文件
     */
    private void validateUploadSize(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() == 0) {
            throw new BizException(ErrorConstant.FILE_EMPTY);
        }
        if (file.getSize() > StorageConstants.MAX_FILE_SIZE_BYTES) {
            throw new BizException(ErrorConstant.FILE_TOO_LARGE);
        }
    }

    /**
     * 读取上传文件字节。
     *
     * @param file 上传文件
     * @return 文件字节
     */
    private byte[] readFileBytes(MultipartFile file) {
        try {
            byte[] content = file.getBytes();
            if (content.length > StorageConstants.MAX_FILE_SIZE_BYTES) {
                throw new BizException(ErrorConstant.FILE_TOO_LARGE);
            }
            return content;
        } catch (IOException exception) {
            log.error("读取上传文件失败, fileName={}", file.getOriginalFilename(), exception);
            throw new BizException(ErrorConstant.FILE_STORAGE_ERROR);
        }
    }

    /**
     * 根据请求标题或文件名生成简历标题。
     *
     * @param title 请求标题
     * @param fileName 安全化文件名
     * @return 简历标题
     */
    private String resolveUploadTitle(String title, String fileName) {
        String resolvedTitle = hasText(title) ? title.trim() : stripExtension(fileName);
        if (!hasText(resolvedTitle)) {
            resolvedTitle = DEFAULT_TITLE;
        }
        return resolvedTitle.length() > 100 ? resolvedTitle.substring(0, 100) : resolvedTitle;
    }

    /**
     * 去掉文件名扩展名。
     *
     * @param fileName 文件名
     * @return 不含扩展名的文件名
     */
    private String stripExtension(String fileName) {
        if (!hasText(fileName)) {
            return DEFAULT_TITLE;
        }
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    /**
     * 截断解析异常信息，避免写入过长错误内容。
     *
     * @param exception 解析异常
     * @return 截断后的错误信息
     */
    private String truncateParseError(Exception exception) {
        String message = exception.getMessage();
        if (!hasText(message)) {
            message = exception.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() > PARSE_ERROR_MAX_LENGTH
                ? normalized.substring(0, PARSE_ERROR_MAX_LENGTH) : normalized;
    }

    /**
     * 判断字符串是否为非空白内容。
     *
     * @param value 待判断字符串
     * @return true 表示包含非空白内容
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 尽力删除 MinIO 对象，失败只记录日志，不覆盖原始数据库异常。
     *
     * @param storedFile 已上传文件
     */
    private void safeDeleteFile(StoredFile storedFile) {
        try {
            fileStorage.delete(storedFile.getObjectKey());
        } catch (RuntimeException exception) {
            log.warn("清理 MinIO 对象失败, objectKey={}", storedFile.getObjectKey(), exception);
        }
    }
}
