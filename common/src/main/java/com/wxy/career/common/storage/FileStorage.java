package com.wxy.career.common.storage;

/**
 * 文件存储抽象。
 *
 * <p>业务模块只依赖该接口保存、读取和删除文件，不直接感知具体对象存储实现。
 *
 * @author wxy
 * @date 2026-09-28
 */
public interface FileStorage {

    /**
     * 保存文件。
     *
     * @param content 文件字节内容
     * @param originalFilename 原始文件名，仅用于生成安全化展示名和扩展名
     * @param bizType 业务类型，只允许字母、数字、下划线和短横线
     * @return 存储文件元数据
     */
    StoredFile store(byte[] content, String originalFilename, String bizType);

    /**
     * 按对象 key 读取文件内容。
     *
     * @param objectKey 对象 key
     * @return 文件字节内容
     */
    byte[] load(String objectKey);

    /**
     * 按对象 key 删除文件。
     *
     * @param objectKey 对象 key
     */
    void delete(String objectKey);
}
