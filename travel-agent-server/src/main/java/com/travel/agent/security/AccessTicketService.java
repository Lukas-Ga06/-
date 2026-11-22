package com.travel.agent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

/**
 * 短期只读访问票据。
 *
 * <p>背景：EventSource 与 &lt;img&gt; 无法自定义请求头，SSE 与照片原图只能把凭证放进查询串。
 * 若直接放登录 JWT（默认 72 小时），它会进入浏览器历史、代理与网关访问日志。
 * 这里改为：单独签发 5 分钟有效（travel.jwt.ticket-ttl-seconds 可覆盖）、按用途收窄的票据，
 * 容量有限且可在服务端随时改密钥作废。
 *
 * <p><b>aud（用途）</b>：票据内容带用途段，签发时由调用方选择，校验时与请求路径严格匹配——
 * {@link #PURPOSE_MEDIA_READ}（{@code media-read}，只给 {@code GET /api/photos/{id}/raw}）与
 * {@link #PURPOSE_AGENT_STREAM}（{@code agent-stream}，只给 {@code GET /api/agent/stream}）。
 * 照片票据不能拿去开 SSE，SSE 票据也不能拿去读照片，进一步压缩"一条泄漏 URL"的可用面。
 * 没有用途段的旧票据（本次改动前签发、最多再活 5 分钟）一律按 {@code agent-stream} 处理以保持兼容。
 *
 * <p><b>只读语义</b>：票据只对上面两个 GET 路径生效（见 {@link JwtAuthenticationFilter}），
 * 因此即使票据泄漏，也无法用于写接口、任意业务路径或 POST /api/access-ticket 自我续期。
 * 另外，仅凭票据认证（没有 Cookie/Authorization）的请求会被标记为
 * {@link AuthAttributes#TICKET_ONLY}，Agent 侧据此拒绝记账/备忘/日程这类直接写入操作。
 *
 * <p>票据不落库：内容为 {@code v2|aud|expiresAt|username}，附 HMAC-SHA256 签名。
 */
@Service
public class AccessTicketService {

    /**
     * 旧票据的 scope 标记（v1 内容格式为 {@code media-read|expiresAt|username}）。
     *
     * <p>同时作为派生票据密钥的标签：必须保持字面量不变，否则存量票据与登录态密钥的隔离关系会变，
     * 且所有已签发票据立即失效。
     */
    static final String LEGACY_SCOPE = "media-read";

    /** 用途：照片原图（{@code GET /api/photos/{id}/raw}） */
    public static final String PURPOSE_MEDIA_READ = "media-read";

    /** 用途：SSE 对话流（{@code GET /api/agent/stream}） */
    public static final String PURPOSE_AGENT_STREAM = "agent-stream";

    /** 允许签发/校验的用途白名单；白名单外一律视为非法（签发 400，校验失败） */
    public static final Set<String> PURPOSES = Set.of(PURPOSE_MEDIA_READ, PURPOSE_AGENT_STREAM);

    /** 票据内容版本标记；旧票据没有这一段 */
    private static final String VERSION = "v2";

    /** 默认有效期（秒）：票据会进 URL，宁短勿长 */
    static final long DEFAULT_TTL_SECONDS = 300;

    private final SecretKey key;
    private final long ttlSeconds;

    public AccessTicketService(JwtService jwtService,
                               @Value("${travel.jwt.ticket-ttl-seconds:300}") long ttlSeconds) {
        this.key = jwtService.deriveKey(LEGACY_SCOPE);
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
    }

    /** 票据实体：票据串 + 有效期（秒）+ 用途（客户端可据此确认自己拿到的是哪种票据） */
    public record Ticket(String ticket, long expiresInSeconds, String purpose) {
    }

    /** 规范化用途：null / 空白 → 默认 {@code agent-stream}；不在白名单内返回 {@code null} */
    public static String normalizePurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            return PURPOSE_AGENT_STREAM;
        }
        String trimmed = purpose.trim();
        return PURPOSES.contains(trimmed) ? trimmed : null;
    }

    /** 为当前登录用户签发一张默认用途（SSE）的只读票据 */
    public Ticket issue(String username) {
        return issue(username, PURPOSE_AGENT_STREAM);
    }

    /** 为当前登录用户签发指定用途的只读票据；用途非法时抛 {@link IllegalArgumentException} */
    public Ticket issue(String username, String purpose) {
        String aud = normalizePurpose(purpose);
        if (aud == null) {
            throw new IllegalArgumentException("未知的票据用途: " + purpose);
        }
        long expiresAt = Instant.now().getEpochSecond() + ttlSeconds;
        String payload = VERSION + "|" + aud + "|" + expiresAt + "|" + username;
        String encoded = encode(payload.getBytes(StandardCharsets.UTF_8));
        return new Ticket(encoded + "." + encode(sign(encoded)), ttlSeconds, aud);
    }

    /**
     * 校验票据并返回用户名；非法、过期或用途与 {@code expectedPurpose} 不匹配返回 {@code null}。
     *
     * @param expectedPurpose 期望用途（{@link #PURPOSE_MEDIA_READ} / {@link #PURPOSE_AGENT_STREAM}）；
     *                        传 {@code null} 时不校验用途（仅用于诊断/内部场景）
     */
    public String validate(String ticket, String expectedPurpose) {
        Parsed parsed = parse(ticket);
        if (parsed == null) {
            return null;
        }
        if (expectedPurpose != null && !expectedPurpose.equals(parsed.aud())) {
            return null;
        }
        return parsed.username();
    }

    /** 解析并验签票据；任何不合法都返回 {@code null} */
    private Parsed parse(String ticket) {
        if (ticket == null) {
            return null;
        }
        int dot = ticket.indexOf('.');
        if (dot <= 0 || dot == ticket.length() - 1) {
            return null;
        }
        String encoded = ticket.substring(0, dot);
        byte[] provided;
        try {
            provided = Base64.getUrlDecoder().decode(ticket.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!MessageDigest.isEqual(sign(encoded), provided)) {
            return null;
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        // 用户名放在最后一段：它本身可能包含 '|'，所以只按固定段数切分前面的部分
        int firstSep = payload.indexOf('|');
        if (firstSep <= 0) {
            return null;
        }
        String head = payload.substring(0, firstSep);
        String rest = payload.substring(firstSep + 1);
        String aud;
        if (VERSION.equals(head)) {
            int audSep = rest.indexOf('|');
            if (audSep <= 0) {
                return null;
            }
            aud = rest.substring(0, audSep);
            if (!PURPOSES.contains(aud)) {
                return null;
            }
            rest = rest.substring(audSep + 1);
        } else if (LEGACY_SCOPE.equals(head)) {
            // 旧票据没有用途段：按 SSE 用途处理（兼容窗口仅有 5 分钟）
            aud = PURPOSE_AGENT_STREAM;
        } else {
            return null;
        }
        int expSep = rest.indexOf('|');
        if (expSep <= 0 || expSep == rest.length() - 1) {
            return null;
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(rest.substring(0, expSep));
        } catch (NumberFormatException e) {
            return null;
        }
        String username = rest.substring(expSep + 1);
        if (username.isBlank()) {
            return null;
        }
        if (expiresAt < Instant.now().getEpochSecond()) {
            return null;
        }
        return new Parsed(aud, username);
    }

    private record Parsed(String aud, String username) {
    }

    private byte[] sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("票据签名失败", e);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}