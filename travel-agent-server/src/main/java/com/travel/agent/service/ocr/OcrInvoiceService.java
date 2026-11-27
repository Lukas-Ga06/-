package com.travel.agent.service.ocr;

import com.travel.agent.entity.TripPhoto;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.service.PhotoService;
import com.travel.agent.util.ExpenseCategories;
import com.travel.agent.util.ExternalDegradationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * 「取该用户最新一张发票照片并识别」的唯一实现。
 *
 * <p>对外只返回 {@link InvoiceLookup} 这一种结果：可用（带字段）或不可用（带原因）。
 * <b>没有第三种「猜一份识别结果」</b>——未配置、识别失败、照片读不出来都必须如实说，
 * 否则用户会在确认门里为一笔凭空的账目点「确认」（能力边界 §2 的同一口径）。
 *
 * <p>不落库、不改照片：识别是只读行为；真正的记账在用户确认后由
 * {@code ExecutorAgent.commit} 单事务完成（R1/R2）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OcrInvoiceService {

    /**
     * 识别结果。
     *
     * @param available 是否拿到了可用的识别结果（false 时 fields 为空）
     * @param reason    不可用/失败的原因（用户可读，已脱敏）；可用时为 null
     * @param photoId   被识别的照片 id；不可用时为 null
     * @param fields    识别字段；不可用时为 null
     */
    public record InvoiceLookup(boolean available, String reason, Long photoId, InvoiceFields fields) {

        /** 该用户还没有上传过发票照片 */
        public static final String REASON_NOT_FOUND = "没有已上传的发票照片";
        /** 没有配置 OCR（provider 不是 baidu，或缺 Key） */
        public static final String REASON_NOT_CONFIGURED = "未配置 OCR（travel.ocr.provider/密钥）";

        static InvoiceLookup unavailable(String reason) {
            return new InvoiceLookup(false, reason, null, null);
        }

        static InvoiceLookup notFound() {
            return new InvoiceLookup(false, REASON_NOT_FOUND, null, null);
        }

        static InvoiceLookup failed(String reason) {
            return new InvoiceLookup(false, reason, null, null);
        }

        static InvoiceLookup ok(Long photoId, InvoiceFields fields) {
            return new InvoiceLookup(true, null, photoId, fields);
        }
    }

    private final OcrClient client;
    private final PhotoService photoService;
    private final OcrProperties properties;
    /** 外部依赖降级计数（provider=ocr）；指标失败不影响识别结果的返回 */
    private final MeterRegistry registry;

    /**
     * 识别该用户最新一张发票照片。
     *
     * <p>判定顺序即用户能看到的顺序：没上传过（去传）→ 没配置（去配）→ 图太大/读不出/识别失败/没金额（重拍或手动记）。
     * 每种情况都不生成方案。
     */
    public InvoiceLookup recognizeLatest(Long userId) {
        if (userId == null) {
            return InvoiceLookup.notFound();
        }
        TripPhoto photo = photoService.latestInvoice(userId).orElse(null);
        if (photo == null) {
            return InvoiceLookup.notFound();
        }
        if (!client.available()) {
            return InvoiceLookup.unavailable(InvoiceLookup.REASON_NOT_CONFIGURED);
        }
        long maxBytes = Math.max(properties.getMaxImageBytes(), 1L);
        if (photo.getSizeBytes() != null && photo.getSizeBytes() > maxBytes) {
            return InvoiceLookup.failed(tooLargeReason(maxBytes));
        }
        byte[] bytes;
        try {
            bytes = photoService.fileFor(photo.getId(), userId).resource().getContentAsByteArray();
        } catch (BusinessException e) {
            // 照片元数据在、文件被清理：如实说文件没了，不让用户以为是识别失败
            return InvoiceLookup.failed(e.getMessage());
        } catch (IOException e) {
            return InvoiceLookup.failed("读取发票照片失败");
        }
        if (bytes.length == 0) {
            return InvoiceLookup.failed("发票照片是空文件");
        }
        if (bytes.length > maxBytes) {
            return InvoiceLookup.failed(tooLargeReason(maxBytes));
        }
        InvoiceFields fields;
        try {
            fields = client.recognize(bytes);
        } catch (OcrException e) {
            ExternalDegradationMetrics.record(registry, ExternalDegradationMetrics.PROVIDER_OCR, e.reason());
            log.warn("发票识别失败: photoId={} reason={} message={}", photo.getId(), e.reason(), e.getMessage());
            return InvoiceLookup.failed(e.getMessage());
        } catch (RuntimeException e) {
            // 客户端契约只允许抛 OcrException；实现（含测试替身）越界时同样转成失败，不让对话 500
            String reason = ExternalDegradationMetrics.reasonOf(e);
            ExternalDegradationMetrics.record(registry, ExternalDegradationMetrics.PROVIDER_OCR, reason);
            log.warn("发票识别异常: photoId={} reason={} type={}", photo.getId(), reason,
                    e.getClass().getSimpleName());
            return InvoiceLookup.failed("发票识别出错，请稍后重试或手动记账");
        }
        if (fields == null || fields.amount() == null || fields.amount().signum() <= 0) {
            // 识别到了内容但没有可用金额：不能让用户在确认门里确认一笔 0 元的账
            return InvoiceLookup.failed("没有识别出金额");
        }
        // 类目由平台按「商户 + 识别摘要」归类，与对话记账共用同一张词表（util.ExpenseCategories）
        String category = ExpenseCategories.of(fields.merchant() + " " + fields.rawText());
        return InvoiceLookup.ok(photo.getId(),
                new InvoiceFields(fields.amount(), fields.merchant(), fields.invoiceDate(), category, fields.rawText()));
    }

    private static String tooLargeReason(long maxBytes) {
        return "发票照片超过识别大小上限（" + Math.max(maxBytes / 1024 / 1024, 1) + "MB）";
    }
}