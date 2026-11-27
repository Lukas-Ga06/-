package com.travel.agent.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

/**
 * JWT 签发与校验。登录 JWT 只从 Authorization 头或 httpOnly Cookie 读取——
 * <b>不再接受查询参数</b>（72 小时凭证进 URL 等于写进代理日志、浏览器历史与 Referer）。
 * EventSource / {@code <img>} 这类无法自定义请求头的场景改用短期只读票据（见 {@link AccessTicketService}）。
 *
 * 密钥来源：环境变量 JWT_SECRET（Base64，解码后 >= 32 字节）。
 * 未配置时生成一次性随机密钥，便于本地开箱即用，但重启后已签发的 token 全部失效。
 */
@Slf4j
@Component
public class JwtService {

    /** HS256 要求的最小密钥长度（字节） */
    private static final int MIN_KEY_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKey key;
    private final long expireHours;

    public JwtService(@Value("${travel.jwt.secret:}") String secret,
                      @Value("${travel.jwt.expire-hours:72}") long expireHours) {
        this.key = resolveKey(secret);
        this.expireHours = expireHours;
    }

    private static SecretKey resolveKey(String secret) {
        if (secret == null || secret.isBlank()) {
            byte[] ephemeral = new byte[64];
            SECURE_RANDOM.nextBytes(ephemeral);
            log.warn("未配置环境变量 JWT_SECRET，已生成一次性随机密钥：服务重启后所有已签发 token 将失效。"
                    + "生产环境请注入固定密钥（Base64，>= 32 字节，可用 openssl rand -base64 48 生成）。");
            return Keys.hmacShaKeyFor(ephemeral);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secret.trim().getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT_SECRET 必须是 Base64 编码的字符串，可用 openssl rand -base64 48 生成", e);
        }
        if (decoded.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("JWT_SECRET 解码后长度不足 " + MIN_KEY_BYTES
                    + " 字节（当前 " + decoded.length + "），请重新生成：openssl rand -base64 48");
        }
        return Keys.hmacShaKeyFor(decoded);
    }

    /**
     * 登录 token 携带的身份。
     *
     * <p>{@code uid} 是用户主键：注销后同名重新注册会拿到新的主键，旧 token 不会串到新账号。
     * {@code tokenVersion} 与 {@code users.token_version} 对应，版本 +1 即吊销该用户全部 token。
     */
    public record TokenIdentity(String username, Long userId, long tokenVersion) { }

    /**
     * 签发登录 token。
     *
     * @param username     用户名（subject，保留给日志与兼容展示；鉴权以 uid 为准）
     * @param userId       用户主键（uid）
     * @param tokenVersion 用户当前 token 版本（ver）
     */
    public String generate(String username, Long userId, long tokenVersion) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("ver", tokenVersion)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireHours * 3600_000L))
                .signWith(key)
                .compact();
    }

    /** token 有效期（秒）：httpOnly Cookie 的 maxAge 与之对齐，避免「token 还没过期 Cookie 先没了」 */
    public long expireSeconds() {
        return expireHours * 3600L;
    }

    /**
     * 校验签名与有效期并返回身份；非法、过期或缺少 {@code uid/ver} 的旧 token 返回 null。
     *
     * <p>旧 token 没有 uid/ver 时一律拒绝：部署 V24 后所有在线会话需要重新登录一次，
     * 以换取「注销即失效、同名重注册不串号」的确定性（见 data-deletion.md §3.1）。
     */
    public TokenIdentity validate(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            String username = claims.getSubject();
            Number uid = claims.get("uid", Number.class);
            Number ver = claims.get("ver", Number.class);
            if (username == null || uid == null || ver == null) {
                return null;
            }
            return new TokenIdentity(username, uid.longValue(), ver.longValue());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从签名密钥派生用途隔离的子密钥（例如短期只读票据）。
     * 分离后，票据无法被当作登录凭证使用，登录态泄露也不会连带影响票据。
     */
    public SecretKey deriveKey(String purpose) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(key.getEncoded());
            digest.update(purpose.getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法派生票据密钥", e);
        }
    }
}
