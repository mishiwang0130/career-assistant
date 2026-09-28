package com.wxy.career.util;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 简历文件解析器。
 *
 * <p>该类放在 util 包，是因为它是无状态、纯技术性的文件格式读取工具，不属于业务 Service，
 * 也不参与事务或数据库操作；放入 util 便于后续报告解析等场景按同一约定复用。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Component
public class ResumeParser {

    /**
     * 换行符正则，统一兼容 CRLF、LF 和 CR。
     */
    private static final String LINE_SEPARATOR_REGEX = "\\R";

    /**
     * 解析简历文件并返回清洗后的纯文本。
     *
     * @param content 文件字节内容
     * @param extension 小写扩展名
     * @return 清洗后的简历正文
     */
    public String parse(byte[] content, String extension) {
        if (content == null || content.length == 0 || extension == null || extension.isBlank()) {
            throw new BizException(ErrorConstant.FILE_EMPTY);
        }
        String normalizedExtension = extension.toLowerCase(Locale.ROOT);
        String rawText = switch (normalizedExtension) {
            case "pdf" -> parsePdf(content);
            case "doc" -> parseDoc(content);
            case "docx" -> parseDocx(content);
            case "txt", "md" -> parsePlainText(content);
            default -> throw new BizException(ErrorConstant.FILE_TYPE_UNSUPPORTED);
        };
        return cleanText(rawText);
    }

    /**
     * 清洗文本内容。
     *
     * <p>清洗规则固定为：去 BOM 与控制字符、换行归一、去行尾空格、连续空行压缩为一行、整体 trim。
     *
     * @param text 原始文本
     * @return 清洗后的文本
     */
    public String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = text.replace("\uFEFF", "")
                .replaceAll(LINE_SEPARATOR_REGEX, "\n")
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        String[] lines = normalized.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        boolean previousBlank = false;
        for (String line : lines) {
            String trimmedLine = line.stripTrailing();
            boolean blankLine = trimmedLine.isBlank();
            if (blankLine && previousBlank) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(trimmedLine);
            previousBlank = blankLine;
        }
        return builder.toString().strip();
    }

    /**
     * 使用 PDFBox 3 解析 PDF。
     *
     * @param content 文件字节内容
     * @return PDF 文本
     */
    private String parsePdf(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException exception) {
            throw new ResumeParseException("PDF 解析失败", exception);
        }
    }

    /**
     * 使用 POI 解析 DOC。
     *
     * @param content 文件字节内容
     * @return DOC 文本
     */
    private String parseDoc(byte[] content) {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(content))) {
            WordExtractor extractor = new WordExtractor(document);
            return extractor.getText();
        } catch (IOException exception) {
            throw new ResumeParseException("DOC 解析失败", exception);
        }
    }

    /**
     * 使用 POI 解析 DOCX。
     *
     * @param content 文件字节内容
     * @return DOCX 文本
     */
    private String parseDocx(byte[] content) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (IOException exception) {
            throw new ResumeParseException("DOCX 解析失败", exception);
        }
    }

    /**
     * 按 UTF-8 读取 TXT 或 Markdown。
     *
     * @param content 文件字节内容
     * @return 文本内容
     */
    private String parsePlainText(byte[] content) {
        try {
            return new String(content, StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            throw new ResumeParseException("文本解析失败", exception);
        }
    }
}
