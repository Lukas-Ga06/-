package com.travel.agent.service.ocr;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 百度发票识别的**真实验收**（人工按需运行，不进 CI）：默认 skipped，不触网（R6）。
 *
 * <p>补的是 {@code BaiduOcrClientTest} 证明不了的那一段：真实 access_token 换取、
 * 真实请求组装、真实错误码 → {@link OcrException} 的映射。Key 只从环境变量读，
 * 不读 {@code .env.local}、不硬编码、不回显任何 Key / token（R7）。
 *
 * <pre>
 * OCR_PROVIDER=baidu OCR_BAIDU_API_KEY=… OCR_BAIDU_SECRET_KEY=… OCR_REAL=true \
 *   mvn -B -Dtest=OcrInvoiceLiveTest -DfailIfNoTests=false test
 * # 可选：OCR_SAMPLE_INVOICE=/path/to/real-invoice.png（真实增值税发票照片）
 * # 可选：OCR_EXPECT_AMOUNT=128.50（断言识别金额）
 * </pre>
 *
 * <p><b>两张图的验收口径不同</b>：合成版式图只能验证「鉴权 / 请求 / 错误映射」——
 * 它不是真实发票，百度 {@code vat_invoice} 按票面模板匹配，会返回 282103（模板不匹配），
 * 这正是测试 A 的预期行为；真实字段识别（金额/商户/日期）必须用真实发票照片（测试 B）。
 *
 * <p>不启动 Spring：直接构造 {@link OcrProperties} + {@link BaiduOcrClient}。
 */
@EnabledIfEnvironmentVariable(named = "OCR_REAL", matches = "true")
class OcrInvoiceLiveTest {

    /** 仓库内合成发票版式图（gitignored 的 data/，由离线样例脚本生成；缺失时跳过） */
    private static final Path SYNTHETIC_INVOICE = Path.of("data", "ocr-acceptance", "invoice.png");

    @Test
    @DisplayName("真实链路打通：合成版式图被显式拒绝（服务错误码），不返回空字段冒充识别结果")
    void realApiRejectsNonInvoiceImageExplicitly() throws IOException {
        BaiduOcrClient client = newClient();
        Assumptions.assumeTrue(Files.isRegularFile(SYNTHETIC_INVOICE),
                "合成样例图不存在：" + SYNTHETIC_INVOICE.toAbsolutePath());
        byte[] bytes = Files.readAllBytes(SYNTHETIC_INVOICE);

        Throwable thrown = catchThrowable(() -> client.recognize(bytes));
        assertThat(thrown)
                .as("合成图不是真实发票：预期 282103（票面模板不匹配）被映射成带服务错误码的 OcrException")
                .isInstanceOf(OcrException.class)
                .hasMessageContaining("服务错误码");
        // 消息由 OcrException 统一脱敏（无 Key / token / URL），可直接作为验收证据打印
        System.out.printf("[ocr-real] 合成图被显式拒绝：%s%n", thrown.getMessage());
    }

    @Test
    @DisplayName("提供真实发票照片时识别出金额/商户/开票日期，并按需校验 OCR_EXPECT_AMOUNT")
    void recognizesRealInvoiceWhenSampleProvided() throws IOException {
        BaiduOcrClient client = newClient();
        String sample = env("OCR_SAMPLE_INVOICE");
        Assumptions.assumeTrue(!sample.isEmpty(), "未提供 OCR_SAMPLE_INVOICE（真实发票照片），跳过字段识别验收");
        Path samplePath = Path.of(sample);
        Assumptions.assumeTrue(Files.isRegularFile(samplePath),
                "OCR_SAMPLE_INVOICE 指向的文件不存在：" + samplePath.toAbsolutePath());

        InvoiceFields fields = client.recognize(Files.readAllBytes(samplePath));

        assertThat(fields).as("真实发票必须返回字段对象").isNotNull();
        assertThat(fields.amount()).as("金额（价税合计）不能为空").isNotNull();
        assertThat(fields.merchant()).as("商户名称不能为空").isNotBlank();
        assertThat(fields.invoiceDate()).as("开票日期必须解析出来").isNotNull();
        System.out.printf("[ocr-real] 金额=%s 商户=%s 日期=%s 类目=%s%n",
                fields.amount(), fields.merchant(), fields.invoiceDate(), fields.category());

        String expected = env("OCR_EXPECT_AMOUNT");
        if (!expected.isEmpty()) {
            assertThat(fields.amount())
                    .as("OCR_EXPECT_AMOUNT=%s 应与票面金额一致", expected)
                    .isEqualByComparingTo(new BigDecimal(expected));
        }
    }

    /** 只用环境变量构造配置；缺任一必需项即跳过（即使误开 OCR_REAL 也不会拿空 Key 发请求） */
    private static BaiduOcrClient newClient() {
        OcrProperties properties = new OcrProperties();
        properties.setProvider(env("OCR_PROVIDER"));
        properties.getBaidu().setApiKey(env("OCR_BAIDU_API_KEY"));
        properties.getBaidu().setSecretKey(env("OCR_BAIDU_SECRET_KEY"));
        Assumptions.assumeTrue(properties.usable(),
                "需要 OCR_PROVIDER=baidu 且 OCR_BAIDU_API_KEY / OCR_BAIDU_SECRET_KEY 非空（只读环境变量）");
        return new BaiduOcrClient(properties);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }
}