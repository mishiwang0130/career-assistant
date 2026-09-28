package com.wxy.career.common.storage;

/**
 * 通过安全校验的文件描述。
 *
 * @param fileName 安全化后的展示文件名
 * @param extension 小写扩展名，不含点号
 * @author wxy
 * @date 2026-09-28
 */
record ValidatedFile(String fileName, String extension) {
}
