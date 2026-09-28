package com.wxy.career.common.storage;

import com.wxy.career.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件存储安全校验测试。
 *
 * @author wxy
 * @date 2026-09-28
 */
class FileStorageSecurityTest {

    /**
     * 验证路径穿越文件名不会进入对象 key。
     */
    @Test
    void shouldKeepPathTraversalFilenameInsideResumePrefix() {
        String safeFilename = MinioFileStorage.sanitizeFilename("../../etc/passwd.txt");
        String objectKey = MinioFileStorage.buildObjectKey("resume", 42L,
                FileStorageValidator.resolveExtension(safeFilename));

        assertThat(safeFilename).isEqualTo("passwd.txt");
        assertThat(objectKey).startsWith("resume/42/").endsWith(".txt");
        assertThat(objectKey).doesNotContain("..").doesNotContain("passwd");
    }

    /**
     * 验证 Windows 路径分隔符同样只保留最后一段。
     */
    @Test
    void shouldSanitizeBackslashPathFilename() {
        String safeFilename = MinioFileStorage.sanitizeFilename("..\\..\\tmp\\resume.pdf");

        assertThat(safeFilename).isEqualTo("resume.pdf");
    }

    /**
     * 验证不支持扩展名返回 1202。
     */
    @Test
    void shouldRejectUnsupportedExtension() {
        assertThatThrownBy(() -> FileStorageValidator.validate(new byte[]{0x4D, 0x5A}, "../../etc/passwd.exe"))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> {
                    BizException bizException = (BizException) exception;
                    assertThat(bizException.getErrorCode().getCode()).isEqualTo(1202);
                });
    }

    /**
     * 验证文件头与扩展名不匹配返回 1205。
     */
    @Test
    void shouldRejectMismatchedMagic() {
        assertThatThrownBy(() -> FileStorageValidator.validate("not-a-pdf".getBytes(), "resume.pdf"))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> {
                    BizException bizException = (BizException) exception;
                    assertThat(bizException.getErrorCode().getCode()).isEqualTo(1205);
                });
    }

    /**
     * 验证 bizType 不接受路径穿越字符。
     */
    @Test
    void shouldRejectUnsafeBizType() {
        assertThatThrownBy(() -> MinioFileStorage.buildObjectKey("../resume", 42L, "pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
