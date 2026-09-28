package com.wxy.career.common.storage;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文件存储安全约束常量。
 *
 * @author wxy
 * @date 2026-09-28
 */
public final class StorageConstants {

    /**
     * 单文件最大字节数，固定为 10MB。
     */
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    /**
     * 允许上传的扩展名白名单。
     */
    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "doc", "docx", "txt", "md");

    /**
     * 业务类型合法格式，防止拼接到对象 key 时产生路径穿越。
     */
    public static final Pattern BIZ_TYPE_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * 工具类禁止实例化。
     */
    private StorageConstants() {
    }
}
