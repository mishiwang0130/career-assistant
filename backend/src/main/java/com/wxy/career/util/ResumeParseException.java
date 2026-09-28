package com.wxy.career.util;

/**
 * 简历解析异常。
 *
 * <p>该异常只表示文件已经通过类型校验但解析过程失败，由业务流程转换为 FAILED 状态。
 *
 * @author wxy
 * @date 2026-09-28
 */
public class ResumeParseException extends RuntimeException {

    /**
     * 创建简历解析异常。
     *
     * @param message 异常说明
     * @param cause 原始异常
     */
    public ResumeParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
