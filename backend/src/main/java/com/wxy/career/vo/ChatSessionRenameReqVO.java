package com.wxy.career.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 会话重命名请求。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
public class ChatSessionRenameReqVO {

    /**
     * 新标题，服务层会去掉首尾空白并折叠连续空白，最长 100 个字符。
     */
    @NotBlank(message = "会话标题不能为空")
    @Size(max = 100, message = "会话标题长度不能超过100位")
    private String title;
}
