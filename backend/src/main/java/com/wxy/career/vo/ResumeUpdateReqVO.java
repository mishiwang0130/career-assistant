package com.wxy.career.vo;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新简历请求。
 *
 * <p>标题和正文均为可选字段，但至少需要提供一个非空内容；更新正文会自动把解析状态修正为成功。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
public class ResumeUpdateReqVO {

    /**
     * 简历标题，最长 100 个字符。
     */
    @Size(max = 100, message = "标题长度不能超过100")
    private String title;

    /**
     * 简历正文，填写后会覆盖原解析文本。
     */
    private String rawText;

    /**
     * 校验标题与正文至少填写一项。
     *
     * @return true 表示至少有一个非空字段
     */
    @AssertTrue(message = "标题和正文至少填写一项")
    public boolean isAtLeastOneFieldProvided() {
        return hasText(title) || hasText(rawText);
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
}
