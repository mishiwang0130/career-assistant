package com.wxy.career.vo;

import com.wxy.career.po.Resume;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 简历列表响应。
 *
 * <p>列表不返回 rawText，避免简历正文导致响应体过大。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
public class ResumeListRespVO {

    /**
     * 简历 ID。
     */
    private Long id;

    /**
     * 简历标题。
     */
    private String title;

    /**
     * 来源类型：UPLOAD 或 MANUAL。
     */
    private String sourceType;

    /**
     * 安全化后的文件名，仅用于展示。
     */
    private String fileName;

    /**
     * 文件字节数。
     */
    private Long fileSize;

    /**
     * 小写扩展名。
     */
    private String fileExt;

    /**
     * 解析状态：PENDING、SUCCESS 或 FAILED。
     */
    private String parseStatus;

    /**
     * 解析失败原因。
     */
    private String parseError;

    /**
     * 是否默认简历。
     */
    private Boolean defaultFlag;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    private LocalDateTime updateTime;

    /**
     * 将数据库实体转换为列表响应。
     *
     * @param resume 简历实体
     * @return 列表响应
     */
    public static ResumeListRespVO from(Resume resume) {
        ResumeListRespVO respVO = new ResumeListRespVO();
        respVO.setId(resume.getId());
        respVO.setTitle(resume.getTitle());
        respVO.setSourceType(resume.getSourceType());
        respVO.setFileName(resume.getFileName());
        respVO.setFileSize(resume.getFileSize());
        respVO.setFileExt(resume.getFileExt());
        respVO.setParseStatus(resume.getParseStatus());
        respVO.setParseError(resume.getParseError());
        respVO.setDefaultFlag(Integer.valueOf(Resume.DEFAULT_FLAG_YES).equals(resume.getDefaultFlag()));
        respVO.setCreateTime(resume.getCreateTime());
        respVO.setUpdateTime(resume.getUpdateTime());
        return respVO;
    }
}
