package com.wxy.career.vo;

import com.wxy.career.po.Resume;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 简历详情响应。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ResumeDetailRespVO extends ResumeListRespVO {

    /**
     * 解析或填写后的简历正文。
     */
    private String rawText;

    /**
     * 将数据库实体转换为详情响应。
     *
     * @param resume 简历实体
     * @return 详情响应
     */
    public static ResumeDetailRespVO from(Resume resume) {
        ResumeDetailRespVO respVO = new ResumeDetailRespVO();
        respVO.setId(resume.getId());
        respVO.setTitle(resume.getTitle());
        respVO.setSourceType(resume.getSourceType());
        respVO.setFileName(resume.getFileName());
        respVO.setFileSize(resume.getFileSize());
        respVO.setFileExt(resume.getFileExt());
        respVO.setRawText(resume.getRawText());
        respVO.setParseStatus(resume.getParseStatus());
        respVO.setParseError(resume.getParseError());
        respVO.setDefaultFlag(Integer.valueOf(Resume.DEFAULT_FLAG_YES).equals(resume.getDefaultFlag()));
        respVO.setCreateTime(resume.getCreateTime());
        respVO.setUpdateTime(resume.getUpdateTime());
        return respVO;
    }
}
