package com.wxy.career.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 在线创建简历请求。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
public class ResumeManualReqVO {

    /**
     * 简历标题，最长 100 个字符。
     */
    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题长度不能超过100")
    private String title;

    /**
     * 简历正文，由用户在线填写或粘贴。
     */
    @NotBlank(message = "正文不能为空")
    private String rawText;
}
