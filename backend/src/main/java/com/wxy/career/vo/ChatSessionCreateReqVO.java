package com.wxy.career.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建会话请求。
 *
 * <p>会话 ID 由后端生成，请求里只允许指定场景，避免前端自造会话标识。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
public class ChatSessionCreateReqVO {

    /**
     * 会话场景，取值见 {@code ChatSceneEnum}，本期只开放 ASSISTANT。
     */
    @NotBlank(message = "会话场景不能为空")
    @Size(max = 32, message = "会话场景长度不能超过32位")
    private String scene;
}
