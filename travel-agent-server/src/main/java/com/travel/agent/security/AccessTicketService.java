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

/**
 * 短期只读访问票据。
 *
 * 背景：EventSource 与 &lt;img&gt; 无法自定义请求头，SSE 与照片原图只能把凭证放进查询串。
 * 若直接放登录 JWT（默认 72 小时），它会进入浏览器历史、代理与网关访问日志。
 * 这里改为：单独签发 10 分钟有效、只读用途的票据，容量有限且可在服务端随时改密钥作废。
 *
 * 票据不落库：内容为 scope|expiresAt|username，附 HMAC-SHA256 签名。
 */
@Service
public class AccessTicketService {

    /** 票据用途，写入票据内容并参与校验，防止票据被挪用到其它场景 */
    static final String SCOPE = "media-read";

    private final SecretKey key;
    private final long ttlSeconds;

    public AccessTicketService(JwtService jwtService,
                               @Value("${travel.jwt.ticket-ttl-seconds:600}") long ttlSeconds) {
        this.key = jwtService.deriveKey(SCOPE);
        this.ttlSeconds = ttlSeconds;
    }

    public record Ticket(String ticket, long expiresInSeconds) {
    }

    /** 为当前登录用户签发一张只读票据 */
    public Ticket issue(String username) {
        long expiresAt = Instant.now().getEpochSecond() + ttlSeconds;
        String payload = SCOPE + "|" + expiresAt + "|" + username;
        String encoded = encode(payload.getBytes(StandardCharsets.UTF_8));
        return new Ticket(encoded + "." + encode(sign(encoded)), ttlSeconds);
    }

    /** 校验票据并返回用户名；非法、过期或用途不符返回 null */
    public String validate(String ticket) {
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
        // 用户名放在最后一段：它本身可能包含 '|'，用前两个分隔符切分即可
        int firstSep = payload.indexOf('|');
        int secondSep = firstSep < 0 ? -1 : payload.indexOf('|', firstSep + 1);
        if (firstSep <= 0 || secondSep <= firstSep || secondSep == payload.length() - 1) {
            return null;
        }
        if (!SCOPE.equals(payload.substring(0, firstSep))) {
            return null;
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(payload.substring(firstSep + 1, secondSep));
        } catch (NumberFormatException e) {
            return null;
        }
        if (expiresAt < Instant.now().getEpochSecond()) {
            return null;
        }
        return payload.substring(secondSep + 1);
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
