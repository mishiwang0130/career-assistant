package com.wxy.career.util;

import com.wxy.career.common.exception.BizException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 简历解析器测试。
 *
 * @author wxy
 * @date 2026-09-28
 */
class ResumeParserTest {

    /**
     * 被测解析器。
     */
    private final ResumeParser resumeParser = new ResumeParser();

    /**
     * 验证文本清洗规则。
     */
    @Test
    void shouldCleanText() {
        String cleaned = resumeParser.cleanText("\uFEFF张三  \r\n\r\n\r\nJava 工程师\u0007");

        assertThat(cleaned).isEqualTo("张三\n\nJava 工程师");
    }

    /**
     * 验证 TXT 按 UTF-8 解析。
     */
    @Test
    void shouldParseTxt() {
        String result = resumeParser.parse("姓名：张三\n技能：Java".getBytes(StandardCharsets.UTF_8), "txt");

        assertThat(result).isEqualTo("姓名：张三\n技能：Java");
    }

    /**
     * 验证 Markdown 按 UTF-8 解析。
     */
    @Test
    void shouldParseMarkdown() {
        String result = resumeParser.parse("# 张三\n\n- Java".getBytes(StandardCharsets.UTF_8), "md");

        assertThat(result).isEqualTo("# 张三\n\n- Java");
    }

    /**
     * 验证 DOCX 解析路由。
     *
     * @throws Exception 生成测试文件失败
     */
    @Test
    void shouldParseDocx() throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("DOCX 简历");
            document.write(outputStream);
        }

        String result = resumeParser.parse(outputStream.toByteArray(), "docx");

        assertThat(result).contains("DOCX 简历");
    }

    /**
     * 验证 PDF 解析路由。
     *
     * @throws Exception 生成测试文件失败
     */
    @Test
    void shouldParsePdf() throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(72, 720);
                contentStream.showText("PDF Resume");
                contentStream.endText();
            }
            document.save(outputStream);
        }

        String result = resumeParser.parse(outputStream.toByteArray(), "pdf");

        assertThat(result).contains("PDF Resume");
    }

    /**
     * 验证不支持扩展名返回 1202。
     */
    @Test
    void shouldRejectUnsupportedExtension() {
        assertThatThrownBy(() -> resumeParser.parse("MZ".getBytes(StandardCharsets.UTF_8), "exe"))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> {
                    BizException bizException = (BizException) exception;
                    assertThat(bizException.getErrorCode().getCode()).isEqualTo(1202);
                });
    }

    /**
     * 验证损坏 PDF 转换为解析异常。
     */
    @Test
    void shouldWrapCorruptPdfAsParseException() {
        assertThatThrownBy(() -> resumeParser.parse("not-a-pdf".getBytes(StandardCharsets.UTF_8), "pdf"))
                .isInstanceOf(ResumeParseException.class);
    }
}
