package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.support.IntegrationTestSupport;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 发票文件上传：PDF 文本抽取入库；图片在未配置 OCR 时给出明确指引 */
class InvoiceUploadTest extends IntegrationTestSupport {

    private static byte[] samplePdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("INVOICE");
                content.newLineAtOffset(0, -20);
                content.showText("Total: 120.00");
                content.newLineAtOffset(0, -20);
                content.showText("Date: 2026-10-10");
                content.newLineAtOffset(0, -20);
                content.showText("Taxi transport");
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private MvcResult upload(Account account, MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/api/office/invoice/upload")
                        .file(file)
                        .header("Authorization", account.bearer()))
                .andReturn();
    }

    @Test
    @DisplayName("上传 PDF 电子发票：抽取文字并入库")
    void uploadsPdfInvoice() throws Exception {
        Account account = registerNew();
        MockMultipartFile file = new MockMultipartFile(
                "file", "invoice.pdf", "application/pdf", samplePdf());

        MvcResult result = upload(account, file);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = body(result).path("data");
        assertThat(data.path("amount").decimalValue()).isEqualByComparingTo("120");
        assertThat(data.path("category").asText()).isEqualTo("交通");
        assertThat(data.path("invoiceDate").asText()).isEqualTo("2026-10-10");
        assertThat(data.path("sourceType").asText()).isEqualTo("PDF");
        assertThat(data.path("sourceFile").asText()).isNotBlank();
    }

    @Test
    @DisplayName("未配置 OCR 时上传图片返回 422 并说明如何配置")
    void imageWithoutOcrIsRejectedWithHint() throws Exception {
        Account account = registerNew();
        MockMultipartFile file = new MockMultipartFile(
                "file", "invoice.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

        MvcResult result = upload(account, file);

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(result).path("message").asText()).contains("OCR");
    }

    @Test
    @DisplayName("不支持的文件类型返回 400")
    void unsupportedFileTypeIsRejected() throws Exception {
        Account account = registerNew();
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        MvcResult result = upload(account, file);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }
}
