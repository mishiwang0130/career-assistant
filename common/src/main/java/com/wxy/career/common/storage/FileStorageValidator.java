package com.wxy.career.common.storage;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 上传文件安全校验器。
 *
 * <p>扩展名白名单、大小限制和文件头魔数在此统一校验，避免业务模块重复实现安全规则。
 *
 * @author wxy
 * @date 2026-09-28
 */
final class FileStorageValidator {

    /**
     * PDF 文件头。
     */
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    /**
     * OLE2 复合文档文件头，DOC 文件必须以此开头。
     */
    private static final byte[] OLE2_MAGIC = {
            (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1
    };

    /**
     * ZIP 本地文件头，DOCX 文件必须以此开头。
     */
    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};

    /**
     * 工具类禁止实例化。
     */
    private FileStorageValidator() {
    }

    /**
     * 校验文件内容与文件名。
     *
     * @param content 文件内容
     * @param originalFilename 原始文件名
     * @return 安全化文件名与小写扩展名
     */
    static ValidatedFile validate(byte[] content, String originalFilename) {
        if (content == null || content.length == 0) {
            throw new BizException(ErrorConstant.FILE_EMPTY);
        }
        if (content.length > StorageConstants.MAX_FILE_SIZE_BYTES) {
            throw new BizException(ErrorConstant.FILE_TOO_LARGE);
        }
        String fileName = sanitizeFilename(originalFilename);
        String extension = resolveExtension(fileName);
        validateMagic(content, extension);
        return new ValidatedFile(fileName, extension);
    }

    /**
     * 安全化原始文件名，只保留展示所需的最后一个路径片段。
     *
     * @param originalFilename 原始文件名
     * @return 安全化文件名
     */
    static String sanitizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "resume";
        }
        String normalized = originalFilename.replace('\\', '/');
        int lastSeparatorIndex = normalized.lastIndexOf('/');
        String fileName = lastSeparatorIndex >= 0 ? normalized.substring(lastSeparatorIndex + 1) : normalized;
        fileName = fileName.replaceAll("[\\p{Cntrl}]", "").trim();
        if (fileName.isEmpty()) {
            return "resume";
        }
        if (fileName.length() > 255) {
            return fileName.substring(0, 255);
        }
        return fileName;
    }

    /**
     * 从安全化文件名中解析扩展名并校验白名单。
     *
     * @param fileName 安全化文件名
     * @return 小写扩展名，不含点号
     */
    static String resolveExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == fileName.length() - 1) {
            throw new BizException(ErrorConstant.FILE_TYPE_UNSUPPORTED);
        }
        String extension = fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        if (!StorageConstants.SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new BizException(ErrorConstant.FILE_TYPE_UNSUPPORTED);
        }
        return extension;
    }

    /**
     * 按扩展名校验文件头魔数。
     *
     * @param content 文件内容
     * @param extension 小写扩展名
     */
    static void validateMagic(byte[] content, String extension) {
        boolean valid;
        switch (extension) {
            case "pdf" -> valid = startsWith(content, PDF_MAGIC);
            case "doc" -> valid = startsWith(content, OLE2_MAGIC);
            case "docx" -> valid = startsWith(content, ZIP_MAGIC);
            case "txt", "md" -> valid = isValidUtf8Text(content);
            default -> throw new BizException(ErrorConstant.FILE_TYPE_UNSUPPORTED);
        }
        if (!valid) {
            throw new BizException(ErrorConstant.FILE_CONTENT_INVALID);
        }
    }

    /**
     * 判断字节数组是否以指定魔数开头。
     *
     * @param content 文件内容
     * @param magic 文件头魔数
     * @return true 表示匹配
     */
    private static boolean startsWith(byte[] content, byte[] magic) {
        if (content.length < magic.length) {
            return false;
        }
        for (int index = 0; index < magic.length; index++) {
            if (content[index] != magic[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断文本文件是否为严格 UTF-8 且不包含 NUL 字符。
     *
     * @param content 文件内容
     * @return true 表示合法
     */
    private static boolean isValidUtf8Text(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }
}
