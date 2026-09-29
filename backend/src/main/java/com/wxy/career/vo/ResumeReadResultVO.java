package com.wxy.career.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 读简历工具返回结构。
 *
 * <p>独立于 PO：不暴露 object_key、用户 ID 等内部字段。正文按固定长度分段返回，一次只回当前一段，
 * 让模型自己决定是否继续读下一段（本项目不启用框架的大结果卸载）。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class ResumeReadResultVO {

    /**
     * 是否成功取到简历正文。
     */
    private boolean found;

    /**
     * 简历 ID，取到正文时非空。
     */
    private Long resumeId;

    /**
     * 简历标题，取到正文时非空。
     */
    private String title;

    /**
     * 解析状态，取到正文时非空。
     */
    private String parseStatus;

    /**
     * 简历正文总长度，单位为字符。
     */
    private int totalLength;

    /**
     * 当前返回第几段，从 1 开始。
     */
    private int segment;

    /**
     * 正文总段数。
     */
    private int totalSegments;

    /**
     * 是否还有下一段未读取。
     */
    private boolean hasMore;

    /**
     * 当前段的正文内容。
     */
    private String content;

    /**
     * 结果说明：取到正文时说明还有几段，未取到正文时说明原因与下一步怎么做。
     */
    private String message;

    /**
     * 可选简历候选，仅在无法确定目标或目标不可用时返回。
     */
    private List<ResumeCandidateVO> candidates = new ArrayList<>();
}
