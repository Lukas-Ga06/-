package com.travel.agent.service.ocr;

/**
 * 发票 OCR 客户端契约。
 *
 * <p>只做「一张图片 → 若干字段」这一件事，不接触数据库、不决定业务动作：
 * 失败一律抛 {@link OcrException}（消息里不得出现任何密钥），由 {@link OcrInvoiceService} 转成
 * 「可用 / 不可用 + 原因」的用户可见结论。
 *
 * <p>实现必须是同步阻塞的单次调用：本条链路在对话线程里执行，异步队列不在能力范围内。
 */
public interface OcrClient {

    /** 是否可用（配置齐全且未被关闭）；false 时调用方必须报「未配置」，不得发起识别 */
    boolean available();

    /**
     * 识别一张发票图片。
     *
     * @param imageBytes 图片原始字节（调用方已按大小上限校验）
     * @return 识别出的字段；缺失字段为 {@code null}
     * @throws OcrException 网络/鉴权/服务端错误，或响应里带非 0 错误码
     */
    InvoiceFields recognize(byte[] imageBytes);
}