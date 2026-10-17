package com.travel.agent.service;

import com.travel.agent.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 发票文件 → 文本。
 *
 * - PDF：PDFBox 直接抽取文字（电子发票通常可提取；扫描件会明确提示需要 OCR）
 * - 图片：调用可配置的 OCR 引擎（Tesseract）；未配置时给出明确的配置指引
 */
@Component
@Slf4j
public class InvoiceTextExtractor {

    private static final Set<String> IMAGE_EXT = Set.of(
            ".jpg", ".jpeg", ".png", ".webp", ".bmp", ".heic", ".heif");

    private final String tesseractCmd;
    private final String tessdataDir;

    public InvoiceTextExtractor(@Value("${travel.ocr.tesseract:${TESSERACT_CMD:}}") String tesseractCmd,
                                @Value("${travel.ocr.tessdata:${TESSDATA_PREFIX:}}") String tessdataDir) {
        this.tesseractCmd = tesseractCmd == null ? "" : tesseractCmd.trim();
        this.tessdataDir = tessdataDir == null ? "" : tessdataDir.trim();
    }

    public boolean ocrConfigured() {
        return !tesseractCmd.isBlank();
    }

    public String extract(byte[] bytes, String filename, String contentType) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);

        if (name.endsWith(".pdf") || type.contains("pdf")) {
            return extractPdf(bytes);
        }
        boolean image = type.startsWith("image/") || IMAGE_EXT.stream().anyMatch(name::endsWith);
        if (image) {
            return extractImage(bytes, name);
        }
        throw new BusinessException(400, "只支持 PDF 发票或图片（jpg/png/webp）");
    }

    private String extractPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            if (text == null || text.trim().length() < 10) {
                throw new BusinessException(422,
                        "这份 PDF 没有可提取的文字（可能是扫描件），请上传电子发票或配置 OCR 后再试");
            }
            return text;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.warn("PDF 解析失败: {}", e.getMessage());
            throw new BusinessException(400, "PDF 解析失败，请确认文件未损坏");
        }
    }

    private String extractImage(byte[] bytes, String filename) {
        if (!ocrConfigured()) {
            throw new BusinessException(422,
                    "图片发票需要 OCR 引擎：安装 Tesseract 并设置环境变量 TESSERACT_CMD，"
                            + "或改用 PDF 电子发票 / 粘贴文本");
        }
        Path temp = null;
        try {
            String ext = filename.endsWith(".png") ? ".png" : ".jpg";
            temp = Files.createTempFile("invoice-ocr-", ext);
            Files.write(temp, bytes);
            List<String> command = new ArrayList<>();
            command.add(tesseractCmd);
            command.add(temp.toString());
            command.add("stdout");
            if (!tessdataDir.isBlank()) {
                command.add("--tessdata-dir");
                command.add(tessdataDir);
            }
            command.add("-l");
            command.add("chi_sim+eng");
            command.add("--psm");
            command.add("6");
            ProcessBuilder builder = new ProcessBuilder(command);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BusinessException(422, "OCR 识别超时，请换一张更清晰的图片");
            }
            if (process.exitValue() != 0 || output.trim().length() < 5) {
                throw new BusinessException(422, "OCR 未能识别出文字，请换一张更清晰的图片或改用 PDF");
            }
            return output;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OCR 调用失败: {}", e.getMessage());
            throw new BusinessException(422,
                    "OCR 调用失败，请检查 TESSERACT_CMD 是否正确、TESSDATA_PREFIX 是否包含 chi_sim/eng 语言包");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响结果
                }
            }
        }
    }
}
