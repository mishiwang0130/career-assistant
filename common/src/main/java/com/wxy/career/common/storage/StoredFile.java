package com.wxy.career.common.storage;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 文件存储结果。
 *
 * <p>对象 key 是业务表需要持久化的唯一物理定位信息，安全化文件名只用于展示。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Getter
@AllArgsConstructor
public class StoredFile {

    /**
     * 对象 key。
     */
    private final String objectKey;

    /**
     * 安全化后的原始文件名，仅用于展示。
     */
    private final String fileName;

    /**
     * 文件字节数。
     */
    private final long size;

    /**
     * 小写扩展名，不含点号。
     */
    private final String extension;
}
