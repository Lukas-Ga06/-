package com.travel.agent.service.ocr;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 发票 OCR 配置（{@code travel.ocr.*}）。
 *
 * <p>默认 {@code provider=none}：不配置任何 Key 时 OCR 明确报「未配置」，<b>不返回模拟识别结果</b>。
 * 目前只有一个真实实现（百度云增值税发票识别），Key 只从环境变量注入（R7），不入库、不进日志。
 *
 * <p>字段默认值即安全值，因此 {@code application.yml} 只暴露 provider 与两个 Key；
 * 超时、大小上限等调优项走代码默认值，需要时用配置文件或 {@code SPRING_APPLICATION_JSON} 覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.ocr")
public class OcrProperties {

    /** OCR 提供方：{@code none}（默认，关闭）/ {@code baidu}（百度云发票识别） */
    private String provider = "none";

    private Baidu baidu = new Baidu();

    /** 建连超时（毫秒）；默认 3000，与天气服务同一量级：宁可快速失败也不要挂住对话 */
    private int connectTimeoutMs = 3000;

    /** 读超时（毫秒）；发票识别是单次同步上传，5 秒足够，超过即按失败如实告知 */
    private int readTimeoutMs = 5000;

    /** 单张发票图片的识别上限（字节，默认 8MB）：超过则不发送、直接报失败，避免把配额与带宽耗在大图上 */
    private long maxImageBytes = 8L * 1024 * 1024;

    /** 百度云发票识别配置（{@code travel.ocr.baidu.*}） */
    @Data
    public static class Baidu {

        /** 百度云应用的 API Key（环境变量 {@code OCR_BAIDU_API_KEY}）；空串表示未配置 */
        private String apiKey = "";

        /** 百度云应用的 Secret Key（环境变量 {@code OCR_BAIDU_SECRET_KEY}）；空串表示未配置 */
        private String secretKey = "";

        /** 服务根地址（不带结尾斜杠）；可指向私有代理，测试据此不触网 */
        private String endpoint = "https://aip.baidubce.com";

        /** 取 access_token 的路径 */
        private String tokenPath = "/oauth/2.0/token";

        /** 增值税发票识别路径 */
        private String invoicePath = "/rest/2.0/ocr/v1/vat_invoice";

        /** 两个 Key 都非空才算可用（缺任意一个都只能报「未配置」） */
        public boolean usable() {
            return apiKey != null && !apiKey.isBlank() && secretKey != null && !secretKey.isBlank();
        }
    }

    /**
     * OCR 是否可用：{@code provider=baidu} 且两个 Key 都在。
     *
     * <p>这是「Key 判断唯一入口」（不让 {@code BaiduOcrClient} 与 {@code OcrInvoiceService} 各判一次），
     * 与 {@code AmapProperties#usable()} 同一口径。
     */
    public boolean usable() {
        return "baidu".equalsIgnoreCase(provider == null ? "" : provider.trim())
                && baidu != null && baidu.usable();
    }
}