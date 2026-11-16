package com.travel.agent.rag;

/**
 * 知识库文档业务键（{@code rag_document.doc_key}）的唯一算法（batch10 / C）。
 *
 * <p>取值与迁移脚本 V11 的回填表达式逐字对应：
 * <pre>
 * LEFT(CONCAT(CASE WHEN user_id IS NULL THEN 'public' ELSE CONCAT('', user_id) END,
 *              '|', COALESCE(title, ''), '|', COALESCE(city, '')), 190)
 * （用 CASE 而非 COALESCE：H2 会把 COALESCE(BIGINT, 'public') 推断成 BIGINT 并转换失败，见 V11 文件头）
 * </pre>
 *
 * <p>为什么必须有这个键：{@code knowledge/add} 与启动同步都是「先查再插」，两个并发请求
 * 会同时查不到、同时插入，产生两条同标题同城市的文档（知识块也会重复进向量库）。
 * doc_key 上的唯一索引（{@code uk_rag_document_key}）让后插入的一方撞键，
 * 应用层把 {@code DataIntegrityViolationException} 当正常路径回读（见 {@link RagDocumentUpsertService}）。
 *
 * <p>注意与 SQL 的细微差异：{@code LEFT()} 在 MySQL 里按「字符」计，而 Java 的
 * {@code String} 按 UTF-16 code unit 计。本类按 code point 截断（不劈开代理对），
 * 只有在「标题恰好跨 190 字符边界且含 4 字节 emoji」这类极端情况下才会与 SQL 结果不同；
 * 那种情况下 {@link RagDocumentUpsertService} 还有按「标题 + 城市」回找的兜底，
 * 不会因此重复建文档。
 */
public final class RagDocumentKeys {

    /** 与 V11 的列宽一致：doc_key VARCHAR(190) */
    public static final int MAX_LENGTH = 190;

    /** 公共知识（内置 knowledge.json）的归属前缀；个人知识用 userId 十进制串 */
    private static final String PUBLIC_OWNER = "public";

    private RagDocumentKeys() {
    }

    /**
     * 计算 doc_key。
     *
     * @param userId 归属用户；{@code null} = 公共知识（前缀 "public"）
     * @param title  标题（null 按空串）
     * @param city   城市（null 按空串）
     * @return 长度不超过 {@value #MAX_LENGTH} 的业务键
     */
    public static String of(Long userId, String title, String city) {
        String owner = userId == null ? PUBLIC_OWNER : Long.toString(userId);
        String raw = owner + '|' + nullToEmpty(title) + '|' + nullToEmpty(city);
        return leftByCodePoints(raw, MAX_LENGTH);
    }

    /** 等价于 SQL 的 LEFT(s, max)，但不会劈开代理对（emoji 等） */
    static String leftByCodePoints(String value, int max) {
        if (value == null) {
            return null;
        }
        // 快速路径：UTF-16 长度不超过上限时，code point 数也只可能更少
        if (value.length() <= max) {
            return value;
        }
        if (value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}