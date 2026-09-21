package com.travel.agent.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

/**
 * JWT 签发与校验。支持从 Authorization 头或 token 查询参数读取
 * （查询参数用于 EventSource SSE 连接，因为 EventSource 无法设置请求头）。
 *
 * 密钥来源：环境变量 JWT_SECRET（Base64，解码后 >= 32 字节）。
 * 未配置时生成一次性随机密钥，便于本地开箱即用，但重启后已签发的 token 全部失效。
 */
@Slf4j
@Component
public class JwtService {

    /** HS256 要求的最小密钥长度（字节） */
    private static final int MIN_KEY_BYTES = 32;

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
            new SecureRandom().nextBytes(ephemeral);
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

    public String generate(String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireHours * 3600_000L))
                .signWith(key)
                .compact();
    }

    /** 校验并返回用户名；非法或过期返回 null */
    public String validate(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
