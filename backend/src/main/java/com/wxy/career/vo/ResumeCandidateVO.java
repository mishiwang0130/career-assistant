package com.wxy.career.vo;

import lombok.Data;

/**
 * 可诊断简历候选。
 *
 * <p>读简历工具无法唯一确定目标时（标题匹配到多份、没有默认简历）随结果返回，供模型改口径重试；
 * 只含标识与展示信息，不含简历正文，避免把用户全部简历正文塞进上下文。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class ResumeCandidateVO {

    /**
     * 简历 ID，模型下次调用 read_resume 时用 resume_id 传入。
     */
    private Long id;

    /**
     * 简历标题，用于让模型向用户确认是哪一份。
     */
    private String title;

    /**
     * 是否是默认简历。
     */
    private boolean defaultFlag;

    /**
     * 解析状态，取值为 PENDING、SUCCESS 或 FAILED。
     */
    private String parseStatus;
}
